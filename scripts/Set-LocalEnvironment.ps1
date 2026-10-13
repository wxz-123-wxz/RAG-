$ErrorActionPreference = 'Stop'
$ProjectRoot = Split-Path -Parent $PSScriptRoot
$RuntimeRoot = Join-Path $ProjectRoot '.runtime'
foreach ($name in @('tmp', 'logs', 'pip-cache', 'npm-cache', 'models', 'home')) {
    New-Item -ItemType Directory -Path (Join-Path $RuntimeRoot $name) -Force | Out-Null
}
$env:TEMP = Join-Path $RuntimeRoot 'tmp'
$env:TMP = $env:TEMP
$env:TMPDIR = $env:TEMP
$env:PYTHONDONTWRITEBYTECODE = '1'
$env:PYTHONUTF8 = '1'
$env:PIP_CACHE_DIR = Join-Path $RuntimeRoot 'pip-cache'
$env:PIP_DISABLE_PIP_VERSION_CHECK = '1'
$env:npm_config_cache = Join-Path $RuntimeRoot 'npm-cache'
$env:npm_config_update_notifier = 'false'
$env:HF_HOME = Join-Path $RuntimeRoot 'models\huggingface'
$env:TORCH_HOME = Join-Path $RuntimeRoot 'models\torch'
$env:XDG_CACHE_HOME = Join-Path $RuntimeRoot 'models\cache'
$env:MAVEN_OPTS = '-Duser.home="' + (Join-Path $RuntimeRoot 'home') + '" -Djava.io.tmpdir="' + $env:TEMP + '" -XX:-UsePerfData'
$LocalProperties = Join-Path $RuntimeRoot 'local.properties'
if (Test-Path -LiteralPath $LocalProperties) {
    Get-Content -LiteralPath $LocalProperties -Encoding utf8 | Where-Object { $_ -and -not $_.StartsWith('#') } | ForEach-Object {
        $entry = $_ -split '=', 2
        if ($entry.Length -eq 2) { [Environment]::SetEnvironmentVariable($entry[0], $entry[1], 'Process') }
    }
}
$EncryptedModelConfig = Join-Path $RuntimeRoot 'model.config.xml'
if (Test-Path -LiteralPath $EncryptedModelConfig) {
    $modelConfig = Import-Clixml -LiteralPath $EncryptedModelConfig
    $env:RAG_CHAT_PROVIDER = 'openai-compatible'
    $env:RAG_CHAT_BASE_URL = $modelConfig.BaseUrl
    $env:RAG_CHAT_MODEL = $modelConfig.Model
    $env:RAG_CHAT_API_KEY = [Net.NetworkCredential]::new('', $modelConfig.ApiKey).Password
    $env:RAG_ANSWER_MODE = 'generated'
}
