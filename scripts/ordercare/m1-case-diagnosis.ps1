param(
    [ValidateSet('Verify', 'E2E', 'All', 'InspectApi')]
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
$baselineScript = Join-Path $PSScriptRoot 'm0.5-recovery-baseline.ps1'

function Invoke-MavenTests([string]$Tests) {
    Push-Location $repoRoot
    try {
        & mvn -pl floworder-server/floworder-resource-service -am `
            "-Dtest=$Tests" `
            '-Dsurefire.failIfNoSpecifiedTests=false' test
        if ($LASTEXITCODE -ne 0) {
            throw "M1 diagnosis tests failed with exit code $LASTEXITCODE"
        }
    }
    finally {
        Pop-Location
    }
}

function Invoke-DiagnosisTests {
    Invoke-MavenTests 'RecoveryCaseServiceImplTest'
}

function Invoke-CaseHttpE2E {
    & $baselineScript -Action Inject `
        -DbHost $DbHost `
        -DbPort $DbPort `
        -Database $Database `
        -DbUser $DbUser `
        -DbPassword $DbPassword

    $previousE2E = $env:FLOWORDER_E2E
    try {
        $env:FLOWORDER_E2E = 'true'
        Invoke-MavenTests 'RecoveryCaseHttpE2ETest'
    }
    finally {
        $env:FLOWORDER_E2E = $previousE2E
        & $baselineScript -Action Cleanup `
            -DbHost $DbHost `
            -DbPort $DbPort `
            -Database $Database `
            -DbUser $DbUser `
            -DbPassword $DbPassword
    }
}

function Invoke-LiveInspect {
    $uri = "$ResourceServiceUrl/internal/recovery/cases/inspect" `
        + '?identifierType=REQUEST_ID' `
        + '&identifierValue=ORDERCARE-M05-REQUEST'
    Invoke-RestMethod -Method Get -Uri $uri | ConvertTo-Json -Depth 12
}

switch ($Action) {
    'Verify' {
        Invoke-DiagnosisTests
    }
    'E2E' {
        Invoke-CaseHttpE2E
    }
    'All' {
        Invoke-DiagnosisTests
        Invoke-CaseHttpE2E
    }
    'InspectApi' {
        Invoke-LiveInspect
    }
}
