param(
    [ValidateRange(1, 65535)]
    [int]$Port = 8080,

    [string]$BackendUrl = 'http://127.0.0.1:8090'
)

$node = Get-Command node -ErrorAction SilentlyContinue
if (-not $node) {
    Write-Error 'Node.js was not found on PATH.'
    exit 1
}

$serverScript = Join-Path $PSScriptRoot 'local-dev-server.js'
& $node.Source $serverScript --port $Port --backend $BackendUrl
exit $LASTEXITCODE
