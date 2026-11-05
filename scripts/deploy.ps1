# Deploy heart-pilot to the shared-infra server (artifact mode).
#
# Before: the whole source tree was uploaded and docker build ran mvn/npm on the
#         server (2 vCPU), which took many minutes and kept failing on network.
# Now: build the jar + dist locally, upload ~80MB of artifacts, and the server
#      only COPYs them into slim images (build usually 10-30 seconds).
#
# What it does:
#   1) local mvn package (skips tests by default; use -RunTests to run them)
#   2) local npm ci (only if node_modules is missing) + npm run build
#   3) pack jar / dist / Dockerfile.deploy / compose / redeploy.sh / knowledge
#   4) upload, extract, run deploy/server/redeploy.sh (build + up + health check)
#
# Usage:
#   .\deploy.cmd                     # normal deploy
#   .\deploy.cmd -RunTests           # run backend tests first (needs local Docker/Testcontainers)
#   .\deploy.cmd -SkipBuild          # reuse existing jar/dist, just repack and upload
#   .\deploy.cmd -SkipUpload         # rebuild on the server from artifacts already there
#   .\deploy.cmd -IdentityFile C:\keys\server.pem
param(
    [string]$Server = "101.201.208.223",
    [string]$RemoteUser = "root",
    [string]$RemoteDir = "/opt/apps/heart-pilot",
    [string]$IdentityFile,
    [switch]$RunTests,
    [switch]$SkipBuild,
    [switch]$SkipUpload
)

$ErrorActionPreference = "Stop"

$PackageScript = Join-Path $PSScriptRoot "package-deploy.ps1"
$Archive = Join-Path $env:PUBLIC "heart-pilot-deploy.tar.gz"
$Target = "$RemoteUser@$Server"
$RemoteArchive = "/tmp/heart-pilot-deploy.tar.gz"

$SshArgs = @('-o', 'ConnectTimeout=15')
if ($IdentityFile) {
    $SshArgs += @('-i', (Resolve-Path -LiteralPath $IdentityFile).Path)
}

function Invoke-OnServer {
    param([string]$Command)
    & ssh @SshArgs $Target $Command
    if ($LASTEXITCODE -ne 0) { throw "Remote command failed (exit $LASTEXITCODE): $Command" }
}

Write-Host "Target : $Target"
Write-Host "Remote : $RemoteDir"
Write-Host ""

# ---------------------------------------------------------------------
# 0) Connectivity / docker checks
# ---------------------------------------------------------------------
& ssh @SshArgs $Target "echo CONNECT_OK" | Out-Null
if ($LASTEXITCODE -ne 0) { throw "Cannot connect to $Target (check SSH key and security group)." }

$DockerOk = ((& ssh @SshArgs $Target "command -v docker >/dev/null 2>&1 && echo YES || echo NO") | Out-String).Trim()
if ($DockerOk -ne "YES") {
    throw "Docker is not installed on the server. Run first: bash /opt/apps/learningplatform/deploy/server/bootstrap.sh"
}

$Total = [Diagnostics.Stopwatch]::StartNew()

# Fail before touching the server if the production env file is missing.
$EnvOk = ((& ssh @SshArgs $Target "test -f '$RemoteDir/.env.prod' && echo YES || echo NO") | Out-String).Trim()
if ($EnvOk -ne "YES") {
    throw "$RemoteDir/.env.prod is missing on the server. Create it before deploying."
}

# ---------------------------------------------------------------------
# 1) Local build + package
# ---------------------------------------------------------------------
& $PackageScript -OutputPath $Archive -RunTests:$RunTests -SkipBuild:$SkipBuild
if ($LASTEXITCODE -ne 0) { throw "Local packaging failed." }

# ---------------------------------------------------------------------
# 2) Upload and extract
# ---------------------------------------------------------------------
if ($SkipUpload) {
    Write-Host "[upload] -SkipUpload: keeping artifacts already on the server"
} else {
    $ArchiveMb = [math]::Round((Get-Item -LiteralPath $Archive).Length / 1MB, 1)
    Write-Host "[upload] $ArchiveMb MB -> ${Target}:$RemoteArchive"
    $watch = [Diagnostics.Stopwatch]::StartNew()

    & scp @SshArgs $Archive "${Target}:$RemoteArchive"
    if ($LASTEXITCODE -ne 0) { throw "Upload failed." }

    # Remove stale target/dist first: COPY target/*.jar must match exactly one jar,
    # and dist must not keep old hashed assets. Never touches .env.prod.
    $RemoteCommand = "set -e; mkdir -p '$RemoteDir'; rm -rf '$RemoteDir/heart-pilot-backend/target' '$RemoteDir/heart-pilot-frontend/dist'; tar -xzf '$RemoteArchive' -C '$RemoteDir'; rm -f '$RemoteArchive'; sed -i 's/\r`$//' '$RemoteDir/deploy/server/redeploy.sh'; chmod +x '$RemoteDir/deploy/server/redeploy.sh'"
    Invoke-OnServer $RemoteCommand
    Write-Host ("       upload + extract done in {0:N0}s" -f $watch.Elapsed.TotalSeconds)
}

# ---------------------------------------------------------------------
# 3) Rebuild on the server (COPY only)
# ---------------------------------------------------------------------
Write-Host "[deploy] bash deploy/server/redeploy.sh"
Invoke-OnServer "bash '$RemoteDir/deploy/server/redeploy.sh'"

Write-Host ""
Write-Host ("Done in {0:N1} min: http://{1}:8081/" -f $Total.Elapsed.TotalMinutes, $Server)
