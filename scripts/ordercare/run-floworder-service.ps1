param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('Order', 'Resource')]
    [string]$Service,
    [switch]$AdminEnabled,
    [switch]$Build
)

$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$serviceDefinition = if ($Service -eq 'Order') {
    @{
        Module = 'floworder-server/floworder-order-service'
        Artifact = 'floworder-order-service'
    }
} else {
    @{
        Module = 'floworder-server/floworder-resource-service'
        Artifact = 'floworder-resource-service'
    }
}
$module = $serviceDefinition.Module
$jar = Join-Path $repoRoot "$module/target/$($serviceDefinition.Artifact)-0.0.1-SNAPSHOT.jar"


Push-Location $repoRoot
try {
    if ($Build -or -not (Test-Path $jar)) {
        & mvn -pl $module -am -DskipTests package
        if ($LASTEXITCODE -ne 0) {
            throw "Failed to build $Service service"
        }
    }

    $arguments = @('-jar', $jar)
    if ($AdminEnabled) {
        $arguments += '--floworder.admin.enabled=true'
    }

    & java @arguments
    exit $LASTEXITCODE
}
finally {
    Pop-Location
}
