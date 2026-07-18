param(
    [ValidateSet('Inject', 'Cleanup', 'Verify', 'Scenario')]
    [string]$Action = 'Inject',
    [ValidateSet('HappyConsistent', 'Conflict12610093', 'MqTimeoutPartial')]
    [string]$Scenario = 'HappyConsistent',
    [string]$DbHost = '127.0.0.1',
    [int]$DbPort = 3306,
    [string]$Database = 'floworder',
    [string]$DbUser = 'root',
    [string]$DbPassword = $env:FLOWORDER_MYSQL_PASSWORD,
    [string]$EnterpriseAgentUrl = 'http://127.0.0.1:8083',
    [string]$RabbitManagementUrl = 'http://127.0.0.1:15672/api',
    [string]$RabbitUser = 'guest',
    [string]$RabbitPassword = 'guest',
    [int]$WaitSeconds = 180
)

$ErrorActionPreference = 'Stop'
$sqlRoot = Join-Path $PSScriptRoot 'sql'
$fixtureQueue = 'floworder.incident.e2e.dlq'

function Get-RabbitHeaders {
    $token = [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes("${RabbitUser}:${RabbitPassword}"))
    return @{ Authorization = "Basic $token" }
}

function Remove-FixtureQueue {
    $queue = [Uri]::EscapeDataString($fixtureQueue)
    try {
        Invoke-RestMethod -Method Delete -Uri "$RabbitManagementUrl/queues/%2F/$queue" `
            -Headers (Get-RabbitHeaders) | Out-Null
    }
    catch {
        if ($_.Exception.Response -and [int]$_.Exception.Response.StatusCode -eq 404) { return }
        throw
    }
}

function Initialize-FixtureQueue {
    Remove-FixtureQueue
    $queue = [Uri]::EscapeDataString($fixtureQueue)
    $body = @{ durable = $false; auto_delete = $false; arguments = @{} } | ConvertTo-Json
    Invoke-RestMethod -Method Put -Uri "$RabbitManagementUrl/queues/%2F/$queue" `
        -Headers (Get-RabbitHeaders) -ContentType 'application/json' -Body $body | Out-Null
}

function Publish-FixtureMessages {
    $count = if ($Scenario -eq 'Conflict12610093') { 126 } else { 3 }
    1..$count | ForEach-Object {
        $payload = @{
            fixture = $Scenario
            ordinal = $_
            requestId = (Get-RequestIds)[($_ - 1) % (Get-RequestIds).Count]
        } | ConvertTo-Json -Compress
        $body = @{
            properties = @{ content_type = 'application/json'; delivery_mode = 1 }
            routing_key = $fixtureQueue
            payload = $payload
            payload_encoding = 'string'
        } | ConvertTo-Json -Depth 5
        $published = Invoke-RestMethod -Method Post -Uri "$RabbitManagementUrl/exchanges/%2F/amq.default/publish" `
            -Headers (Get-RabbitHeaders) -ContentType 'application/json' -Body $body
        if (-not $published.routed) { throw "RabbitMQ did not route fixture message $_" }
    }
}

function Verify-FixtureQueue {
    $queue = [Uri]::EscapeDataString($fixtureQueue)
    $expected = if ($Scenario -eq 'Conflict12610093') { 126 } else { 3 }
    $state = $null
    for ($attempt = 1; $attempt -le 20; $attempt++) {
        $state = Invoke-RestMethod -Method Get -Uri "$RabbitManagementUrl/queues/%2F/$queue" `
            -Headers (Get-RabbitHeaders)
        if ($null -ne $state.messages -and $state.messages -eq $expected) { break }
        Start-Sleep -Milliseconds 250
    }
    if ($state.messages -ne $expected) {
        throw "fixture queue count mismatch: expected=$expected actual=$($state.messages)"
    }
    Write-Host "Rabbit fixture verified: queue=$fixtureQueue messages=$($state.messages)"
}

function Assert-MySqlClient {
    if (-not (Get-Command mysql -ErrorAction SilentlyContinue)) {
        throw 'mysql client was not found in PATH.'
    }
}

function Invoke-MySqlFile([string]$FileName) {
    Assert-MySqlClient
    $sqlFile = (Resolve-Path (Join-Path $sqlRoot $FileName)).Path.Replace('\', '/')
    $previousPassword = $env:MYSQL_PWD
    if ($DbPassword) { $env:MYSQL_PWD = $DbPassword }
    try {
        & mysql "--host=$DbHost" "--port=$DbPort" "--user=$DbUser" "--database=$Database" `
            '--default-character-set=utf8mb4' "--execute=source $sqlFile"
        if ($LASTEXITCODE -ne 0) { throw "mysql failed while executing $FileName" }
    }
    finally {
        $env:MYSQL_PWD = $previousPassword
    }
}

function Get-FixtureFile {
    switch ($Scenario) {
        'HappyConsistent' { return 'happy-consistent.sql' }
        'Conflict12610093' { return 'conflict-126-100-93.sql' }
        'MqTimeoutPartial' { return 'mq-timeout-partial.sql' }
    }
}

function Get-RequestIds {
    $prefix = switch ($Scenario) {
        'HappyConsistent' { 'IC-HAPPY-REQ-' }
        'Conflict12610093' { 'IC-CONFLICT-REQ-' }
        'MqTimeoutPartial' { 'IC-MQTIMEOUT-REQ-' }
    }
    $count = if ($Scenario -eq 'Conflict12610093') { 100 } else { 3 }
    return 1..$count | ForEach-Object { $prefix + $_.ToString('000') }
}

function Inject-Fixture {
    Initialize-FixtureQueue
    Invoke-MySqlFile 'cleanup.sql'
    Invoke-MySqlFile (Get-FixtureFile)
    Publish-FixtureMessages
    Invoke-MySqlFile 'verify.sql'
    Verify-FixtureQueue
}

function Invoke-Incident {
    if ($Scenario -eq 'MqTimeoutPartial') {
        Write-Host 'MQ timeout profile requires enterprise-agent to be started with:' -ForegroundColor Yellow
        Write-Host '  $env:RABBITMQ_MANAGEMENT_BASE_URL="http://127.0.0.1:1"' -ForegroundColor Yellow
        Write-Host 'This faults only RabbitMQ Management; persisted FlowOrder dead-letter facts remain readable.' -ForegroundColor Yellow
    }
    $body = @{
        alertBatchId = "IC-$Scenario-$(Get-Date -Format yyyyMMddHHmmss)"
        alertType = if ($Scenario -eq 'MqTimeoutPartial') { 'MQ_MANAGEMENT_TIMEOUT' } else { 'ORDER_STATE_INCONSISTENCY' }
        detectedAt = (Get-Date).ToUniversalTime().ToString('o')
        symptom = "Deterministic Incident Command E2E: $Scenario"
        candidateRequestIds = @(Get-RequestIds)
        queueNames = @($fixtureQueue)
    } | ConvertTo-Json -Depth 6
    $started = Invoke-RestMethod -Method Post -Uri "$EnterpriseAgentUrl/api/incidents/investigate" `
        -ContentType 'application/json; charset=utf-8' -Body $body
    if (-not $started.success) { throw $started.message }
    $incidentId = $started.data.incidentId
    Write-Host "Incident started: $incidentId"
    $deadline = (Get-Date).AddSeconds($WaitSeconds)
    do {
        Start-Sleep -Seconds 2
        $result = Invoke-RestMethod -Method Get -Uri "$EnterpriseAgentUrl/api/incidents/$incidentId?eventLimit=1000"
        $status = $result.data.incident.status
        Write-Host "[$(Get-Date -Format HH:mm:ss)] status=$status tasks=$($result.data.tasks.Count) evidence=$($result.data.evidence.Count)"
    } while ($status -notin @('ASSESSED', 'PARTIAL', 'MANUAL_REVIEW', 'FAILED', 'CANCELLED') -and (Get-Date) -lt $deadline)
    if ($status -notin @('ASSESSED', 'PARTIAL', 'MANUAL_REVIEW', 'FAILED', 'CANCELLED')) {
        throw "Incident did not reach a terminal state within $WaitSeconds seconds"
    }
    $result.data | ConvertTo-Json -Depth 12
}

switch ($Action) {
    'Cleanup' { Invoke-MySqlFile 'cleanup.sql'; Remove-FixtureQueue }
    'Verify' { Invoke-MySqlFile 'verify.sql'; Verify-FixtureQueue }
    'Inject' { Inject-Fixture }
    'Scenario' { Inject-Fixture; Invoke-Incident }
}
