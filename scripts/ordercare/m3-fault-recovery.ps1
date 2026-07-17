param(
    [ValidateSet('Verify', 'E2E', 'All', 'Migrate')]
    [string]$Action = 'Verify',
    [string]$DbHost = '127.0.0.1',
    [int]$DbPort = 3306,
    [string]$Database = 'floworder',
    [string]$DbUser = 'root',
    [string]$DbPassword = $env:FLOWORDER_MYSQL_PASSWORD
)

$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$sqlRoot = Join-Path $PSScriptRoot 'sql'
$baselineScript = Join-Path $PSScriptRoot 'm0.5-recovery-baseline.ps1'

function Invoke-MySqlFile([string]$FileName) {
    if (-not (Get-Command mysql -ErrorAction SilentlyContinue)) {
        throw 'mysql client was not found.'
    }
    $sqlFile = (Resolve-Path (Join-Path $sqlRoot $FileName)).Path.Replace('\', '/')
    $previousPassword = $env:MYSQL_PWD
    if ($DbPassword) { $env:MYSQL_PWD = $DbPassword }
    try {
        & mysql "--host=$DbHost" "--port=$DbPort" "--user=$DbUser" `
            "--database=$Database" '--default-character-set=utf8mb4' `
            "--execute=source $sqlFile"
        if ($LASTEXITCODE -ne 0) { throw "mysql failed while executing $FileName" }
    }
    finally {
        $env:MYSQL_PWD = $previousPassword
    }
}

function Invoke-MavenTests([string]$Tests) {
    Push-Location $repoRoot
    try {
        & mvn -pl floworder-server/floworder-resource-service -am `
            "-Dtest=$Tests" '-Dsurefire.failIfNoSpecifiedTests=false' test
        if ($LASTEXITCODE -ne 0) { throw "M3 tests failed with exit code $LASTEXITCODE" }
    }
    finally {
        Pop-Location
    }
}

function Invoke-Migration {
    Invoke-MySqlFile 'm2-recovery-proposal-migration.sql'
    Invoke-MySqlFile 'm3-recovery-action-lease-migration.sql'
}

function Invoke-E2E {
    Invoke-Migration
    Invoke-MySqlFile 'm2-cleanup.sql'
    & $baselineScript -Action Inject `
        -DbHost $DbHost -DbPort $DbPort -Database $Database `
        -DbUser $DbUser -DbPassword $DbPassword
    $previousE2E = $env:FLOWORDER_E2E
    try {
        $env:FLOWORDER_E2E = 'true'
        Invoke-MavenTests 'RecoveryProposalHttpE2ETest'
    }
    finally {
        $env:FLOWORDER_E2E = $previousE2E
        Invoke-MySqlFile 'm2-cleanup.sql'
        & $baselineScript -Action Cleanup `
            -DbHost $DbHost -DbPort $DbPort -Database $Database `
            -DbUser $DbUser -DbPassword $DbPassword
    }
}

switch ($Action) {
    'Verify' {
        Invoke-MavenTests 'RecoveryActionLeaseServiceTests,RecoveryActionReconciliationServiceTests,RecoveryProposalServiceImplTest,RecoveryServiceImplTest'
    }
    'E2E' { Invoke-E2E }
    'All' {
        Invoke-MavenTests 'RecoveryActionLeaseServiceTests,RecoveryActionReconciliationServiceTests,RecoveryProposalServiceImplTest,RecoveryServiceImplTest'
        Invoke-E2E
    }
    'Migrate' { Invoke-Migration }
}
