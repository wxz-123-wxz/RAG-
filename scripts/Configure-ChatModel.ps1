param([string]$BaseUrl = 'https://api.deepseek.com', [string]$Model = 'deepseek-flash')
$ErrorActionPreference = 'Stop'
if (-not $BaseUrl) { $BaseUrl = Read-Host 'Model base URL (usually ending in /v1)' }
if (-not $Model) { $Model = Read-Host 'Model identifier' }
$uri = $null
if (-not [Uri]::TryCreate($BaseUrl, [UriKind]::Absolute, [ref]$uri) -or $uri.Scheme -notin @('http','https')) {
    throw 'Provide an absolute http(s) base URL.'
}
if ($uri.Scheme -ne 'https' -and -not $uri.IsLoopback) { throw 'Use HTTPS for external model endpoints.' }
if ($uri.UserInfo -or $uri.Query -or $uri.Fragment) { throw 'Do not put credentials or query parameters in the base URL.' }
if ([string]::IsNullOrWhiteSpace($Model)) { throw 'A model identifier is required.' }
if ($uri.Host -eq 'api.deepseek.com' -and $Model -ieq 'DeepSeek-V4.1-Flash') {
    $Model = 'deepseek-flash'
    Write-Output 'Using the official API model identifier: deepseek-flash'
}
$base = $BaseUrl.TrimEnd('/')
if ($base.EndsWith('/chat/completions')) { $base = $base.Substring(0, $base.Length - '/chat/completions'.Length) }
$key = Read-Host 'API Key (hidden input; never printed)' -AsSecureString
if ($key.Length -eq 0) { throw 'API Key must not be empty.' }
$runtime = Join-Path (Split-Path -Parent $PSScriptRoot) '.runtime'
New-Item -ItemType Directory -Path $runtime -Force | Out-Null
$target = Join-Path $runtime 'model.config.xml'
[pscustomobject]@{ BaseUrl = $base; Model = $Model.Trim(); ApiKey = $key } | Export-Clixml -LiteralPath $target -Encoding utf8
Write-Output 'Model configuration saved on D drive. API Key is encrypted for this Windows user.'
Write-Output 'Restart the RAG service. The page keeps source-excerpt mode as default and enables the model option.'
