param([ValidateSet('mysql', 'rag', 'backend', 'frontend')][string]$Service = 'backend')
. "$PSScriptRoot\Set-LocalEnvironment.ps1"
switch ($Service) {
    'mysql' {
        Set-Location (Join-Path $RuntimeRoot 'mysql')
        & mysqld --defaults-file=my.ini --console
    }
    'rag' {
        Set-Location (Join-Path $ProjectRoot 'rag-service')
        & (Join-Path $RuntimeRoot 'venv\Scripts\python.exe') -B -m uvicorn app.main:app --host 127.0.0.1 --port 8000
    }
    'backend' {
        # Administrator was created on first startup; never bootstrap again.
        $env:APP_BOOTSTRAP_ADMIN_PASSWORD = ''
        Set-Location (Join-Path $ProjectRoot 'backend')
        $jar = Join-Path $ProjectRoot 'backend\target\rag-qa-backend-0.1.0-SNAPSHOT.jar'
        if (-not (Test-Path -LiteralPath $jar)) { throw 'Build the backend with mvn package first.' }
        & java '-XX:-UsePerfData' "-Djava.io.tmpdir=$env:TEMP" "-Duser.home=$(Join-Path $RuntimeRoot 'home')" -jar $jar
    }
    'frontend' {
        Set-Location (Join-Path $ProjectRoot 'frontend')
        & npm.cmd run dev -- --host 127.0.0.1
    }
}
exit $LASTEXITCODE
