param(
    [string]$Mysql = "D:\MySql\mysql-8.0.34-winx64\bin\mysql.exe",
    [string]$MysqlUser = "root",
    [string]$MysqlPassword = "1234",
    [string]$RedisHost = "127.0.0.1",
    [int]$RedisPort = 6379,
    [int]$RedisDatabase = 1
)

$ErrorActionPreference = "Stop"

$ProjectRoot = Split-Path -Parent $PSScriptRoot
$SqlFile = Join-Path $ProjectRoot "sql\floworder_jmeter_data.sql"

if (!(Test-Path -LiteralPath $Mysql)) {
    throw "mysql executable not found: $Mysql"
}
if (!(Test-Path -LiteralPath $SqlFile)) {
    throw "sql file not found: $SqlFile"
}

Write-Host "Resetting MySQL data from $SqlFile"
Get-Content -LiteralPath $SqlFile -Encoding UTF8 |
    & $Mysql "-u$MysqlUser" "-p$MysqlPassword" --default-character-set=utf8mb4

function New-RedisCommandBytes {
    param([string[][]]$Commands)

    $builder = [System.Text.StringBuilder]::new()
    foreach ($parts in $Commands) {
        [void]$builder.Append("*" + $parts.Count + "`r`n")
        foreach ($part in $parts) {
            $byteCount = [System.Text.Encoding]::UTF8.GetByteCount($part)
            [void]$builder.Append('$' + $byteCount + "`r`n")
            [void]$builder.Append($part + "`r`n")
        }
    }
    return [System.Text.Encoding]::UTF8.GetBytes($builder.ToString())
}

$keys = @(
    "floworder:stock:1",
    "floworder:lock:reservation:create:v1:stock:1"
)

Write-Host "Clearing Redis DB $RedisDatabase keys: $($keys -join ', ')"
$client = [System.Net.Sockets.TcpClient]::new()
try {
    $client.Connect($RedisHost, $RedisPort)
    $stream = $client.GetStream()

    $commands = [System.Collections.Generic.List[string[]]]::new()
    $commands.Add([string[]]@("SELECT", [string]$RedisDatabase))
    $commands.Add([string[]](@("DEL") + $keys))

    $bytes = New-RedisCommandBytes -Commands $commands
    $stream.Write($bytes, 0, $bytes.Length)

    Start-Sleep -Milliseconds 100
    $buffer = New-Object byte[] 1024
    while ($stream.DataAvailable) {
        $read = $stream.Read($buffer, 0, $buffer.Length)
        if ($read -gt 0) {
            Write-Host ([System.Text.Encoding]::UTF8.GetString($buffer, 0, $read).Trim())
        }
    }
}
finally {
    $client.Close()
}

Write-Host "JMeter data reset finished."
