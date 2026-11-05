# Build backend jar + frontend dist locally, then assemble the deploy archive
# (artifact mode). The server only COPYs these artifacts; it never runs Maven/npm.
#
# Archive layout:
#   heart-pilot-backend/target/app.jar         local mvn package output
#   heart-pilot-backend/Dockerfile.deploy      runtime image that only COPYs the jar
#   heart-pilot-frontend/dist                  local npm run build output
#   heart-pilot-frontend/Dockerfile.deploy     nginx image that only COPYs dist
#   heart-pilot-frontend/nginx.conf
#   deploy/server/nginx.conf                   server nginx override
#   deploy/server/redeploy.sh                  server-side build + up + health check
#   docker-compose.server.yml
#   knowledge/                                 mounted read-only at /app/knowledge
#
# Usually called by scripts\deploy.ps1 (.\deploy.cmd); it can also be run directly
# to produce .\heart-pilot-deploy.tar.gz without uploading.
param(
    [string]$OutputPath = ".\heart-pilot-deploy.tar.gz",
    [switch]$RunTests,
    [switch]$SkipBuild
)

$ErrorActionPreference = "Stop"

$ProjectRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$BackendDir = Join-Path $ProjectRoot "heart-pilot-backend"
$FrontendDir = Join-Path $ProjectRoot "heart-pilot-frontend"
$Stage = Join-Path $env:PUBLIC "heart-pilot-deploy-stage"

function Resolve-Executable {
    param([string[]]$Candidates, [string]$Fallback)
    foreach ($candidate in $Candidates) {
        if (Get-Command $candidate -ErrorAction SilentlyContinue) { return $candidate }
    }
    if ($Fallback -and (Test-Path -LiteralPath $Fallback)) { return $Fallback }
    return $null
}

function Invoke-Checked {
    param([string]$FilePath, [string[]]$Arguments, [string]$FailureMessage)
    & $FilePath @Arguments
    if ($LASTEXITCODE -ne 0) { throw "$FailureMessage (exit code $LASTEXITCODE)" }
}

# ---------------------------------------------------------------------
# 1) Local build: backend jar + frontend dist
# ---------------------------------------------------------------------
if (-not $SkipBuild) {
    Write-Host "[1/3] Building backend jar locally"

    $Maven = Resolve-Executable -Candidates @("mvn.cmd", "mvn") -Fallback (Join-Path $BackendDir "mvnw.cmd")
    if (-not $Maven) { throw "Maven not found. Install Maven, or keep heart-pilot-backend\mvnw.cmd." }

    $MavenArgs = @("-B", "-f", (Join-Path $BackendDir "pom.xml"), "clean", "package")
    if (-not $RunTests) { $MavenArgs += "-DskipTests" }

    $watch = [Diagnostics.Stopwatch]::StartNew()
    Invoke-Checked -FilePath $Maven -Arguments $MavenArgs -FailureMessage "Backend package failed"
    Write-Host ("      jar done in {0:N0}s" -f $watch.Elapsed.TotalSeconds)

    Write-Host "[2/3] Building frontend dist locally"
    $watch.Restart()

    $Npm = Resolve-Executable -Candidates @("npm.cmd", "npm")
    if (-not $Npm) { throw "npm not found." }

    if (-not (Test-Path -LiteralPath (Join-Path $FrontendDir "node_modules\.bin\vite.cmd"))) {
        Write-Host "      node_modules missing, running npm ci"
        $NpmCache = Join-Path $ProjectRoot "tmp\npm-cache"
        Invoke-Checked -FilePath $Npm -Arguments @("--prefix", $FrontendDir, "--cache", $NpmCache, "ci") -FailureMessage "Frontend dependency install failed"
    }
    Invoke-Checked -FilePath $Npm -Arguments @("--prefix", $FrontendDir, "run", "build") -FailureMessage "Frontend build failed"
    Write-Host ("      dist done in {0:N0}s" -f $watch.Elapsed.TotalSeconds)
} else {
    Write-Host "[1/3] -SkipBuild: reusing existing jar / dist"
    Write-Host "[2/3] -SkipBuild: reusing existing jar / dist"
}

# ---------------------------------------------------------------------
# 2) Validate artifacts
# ---------------------------------------------------------------------
$Jar = Get-ChildItem -LiteralPath (Join-Path $BackendDir "target") -Filter "*.jar" -File -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -notmatch '(-sources|-javadoc)$' } |
    Sort-Object LastWriteTime -Descending |
    Select-Object -First 1
if (-not $Jar) { throw "heart-pilot-backend\target\*.jar not found; rerun without -SkipBuild." }

$Dist = Join-Path $FrontendDir "dist"
if (-not (Test-Path -LiteralPath $Dist)) { throw "heart-pilot-frontend\dist not found; rerun without -SkipBuild." }

# ---------------------------------------------------------------------
# 3) Assemble the upload directory and archive it
# ---------------------------------------------------------------------
Write-Host ("[3/3] Staging artifacts (jar {0:N1} MB)" -f ($Jar.Length / 1MB))

if (Test-Path -LiteralPath $Stage) { Remove-Item -LiteralPath $Stage -Recurse -Force }
New-Item -ItemType Directory -Force -Path (Join-Path $Stage "heart-pilot-backend\target") | Out-Null
New-Item -ItemType Directory -Force -Path (Join-Path $Stage "heart-pilot-frontend") | Out-Null
New-Item -ItemType Directory -Force -Path (Join-Path $Stage "deploy\server") | Out-Null

# Rename to app.jar: Dockerfile.deploy uses COPY target/*.jar, so pom version bumps
# do not require Dockerfile changes.
Copy-Item -LiteralPath $Jar.FullName -Destination (Join-Path $Stage "heart-pilot-backend\target\app.jar") -Force
Copy-Item -LiteralPath $Dist -Destination (Join-Path $Stage "heart-pilot-frontend\dist") -Recurse -Force

$UploadFiles = @(
    "heart-pilot-backend\Dockerfile.deploy",
    "heart-pilot-backend\Dockerfile.deploy.dockerignore",
    "heart-pilot-frontend\Dockerfile.deploy",
    "heart-pilot-frontend\Dockerfile.deploy.dockerignore",
    "heart-pilot-frontend\nginx.conf",
    "deploy\server\nginx.conf",
    "deploy\server\redeploy.sh",
    "docker-compose.server.yml"
)
foreach ($relative in $UploadFiles) {
    $source = Join-Path $ProjectRoot $relative
    if (-not (Test-Path -LiteralPath $source)) { throw "Missing file: $relative" }
    $destination = Join-Path $Stage $relative
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $destination) | Out-Null
    Copy-Item -LiteralPath $source -Destination $destination -Force
}

$Knowledge = Join-Path $ProjectRoot "knowledge"
if (-not (Test-Path -LiteralPath $Knowledge)) {
    throw "knowledge/ is missing (backend mounts it read-only at /app/knowledge)."
}
Copy-Item -LiteralPath $Knowledge -Destination (Join-Path $Stage "knowledge") -Recurse -Force

$ResolvedOutputPath = if ([System.IO.Path]::IsPathRooted($OutputPath)) {
    [System.IO.Path]::GetFullPath($OutputPath)
} else {
    [System.IO.Path]::GetFullPath((Join-Path $ProjectRoot $OutputPath))
}
if (Test-Path -LiteralPath $ResolvedOutputPath) { Remove-Item -LiteralPath $ResolvedOutputPath -Force }

Push-Location $Stage
try {
    & tar -czf $ResolvedOutputPath .
    if ($LASTEXITCODE -ne 0) { throw "Packaging the deploy archive failed." }
} finally {
    Pop-Location
}

$ArchiveMb = [math]::Round((Get-Item -LiteralPath $ResolvedOutputPath).Length / 1MB, 1)
Write-Host "Archive: $ResolvedOutputPath ($ArchiveMb MB)"
