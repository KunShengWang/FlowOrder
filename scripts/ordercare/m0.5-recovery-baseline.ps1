param(
    [ValidateSet('Verify', 'Inject', 'Replay', 'Inspect', 'Cleanup', 'Scenario')]
    [string]$Action = 'Verify',
    [string]$DbHost = '127.0.0.1',
    [int]$DbPort = 3306,
    [string]$Database = 'floworder',
    [string]$DbUser = 'root',
    [string]$DbPassword = $env:FLOWORDER_MYSQL_PASSWORD,
    [string]$ResourceServiceUrl = 'http://127.0.0.1:8081'
)

$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$sqlRoot = Join-Path $PSScriptRoot 'sql'
$deadLetterId = 9000000000000505

function Invoke-BaselineTests {
    Push-Location $repoRoot
    try {
        & mvn -pl floworder-server/floworder-resource-service -am `
            '-Dtest=MqDeadLetterServiceTest,RecoveryServiceImplTest,DeadLetterRecoveryBaselineIntegrationTest' `
            '-Dsurefire.failIfNoSpecifiedTests=false' test
        if ($LASTEXITCODE -ne 0) {
            throw "M0.5 baseline tests failed with exit code $LASTEXITCODE"
        }
    }
    finally {
        Pop-Location
    }
}

function Assert-MySqlClient {
    if (-not (Get-Command mysql -ErrorAction SilentlyContinue)) {
        throw 'mysql client was not found. Install it or add it to PATH before using database fixture actions.'
    }
}

function Invoke-MySqlFile([string]$FileName) {
    Assert-MySqlClient
    $sqlFile = (Resolve-Path (Join-Path $sqlRoot $FileName)).Path.Replace('\', '/')
    $previousPassword = $env:MYSQL_PWD
    if ($DbPassword) {
        $env:MYSQL_PWD = $DbPassword
    }
    try {
        & mysql `
            "--host=$DbHost" `
            "--port=$DbPort" `
            "--user=$DbUser" `
            "--database=$Database" `
            '--default-character-set=utf8mb4' `
            "--execute=source $sqlFile"
        if ($LASTEXITCODE -ne 0) {
            throw "mysql failed while executing $FileName"
        }
    }
    finally {
        $env:MYSQL_PWD = $previousPassword
    }
}

function Inject-Fixture {
    Invoke-MySqlFile 'm0.5-cleanup.sql'
    Invoke-MySqlFile 'm0.5-inject-timeout-state-dead-letter.sql'
}

function Replay-Fixture {
    $uri = "$ResourceServiceUrl/internal/mq/dead-letter/$deadLetterId/replay?operator=ordercare-m0.5"
    Invoke-RestMethod -Method Post -Uri $uri | Out-Host
}

switch ($Action) {
    'Verify' {
        Invoke-BaselineTests
    }
    'Inject' {
        Inject-Fixture
        Invoke-MySqlFile 'm0.5-inspect.sql'
    }
    'Replay' {
        Replay-Fixture
    }
    'Inspect' {
        Invoke-MySqlFile 'm0.5-inspect.sql'
    }
    'Cleanup' {
        Invoke-MySqlFile 'm0.5-cleanup.sql'
    }
    'Scenario' {
        Inject-Fixture
        Replay-Fixture
        Start-Sleep -Seconds 5
        Invoke-MySqlFile 'm0.5-inspect.sql'
    }
}
