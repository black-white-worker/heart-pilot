# Local deployment / debugging helper for heart-pilot.
#
# Full local stack (build artifacts locally, Docker only copies them):
#   .\local-deploy.cmd                 # local Maven/npm build + docker compose up
#   .\local-deploy.cmd -SkipBuild      # reuse existing images
#   .\local-deploy.cmd -DockerBuild    # run Maven/npm inside Docker instead
#   .\local-deploy.cmd -Down           # stop containers (data volumes kept)
#   .\local-deploy.cmd -Clean          # stop AND delete local data volumes (asks YES)
#   .\local-deploy.cmd -Logs
#   .\local-deploy.cmd -Status
#
# Development mode (Docker only for middleware):
#   .\local-deploy.cmd -InfraOnly      # postgres + redis + minio only
#   .\local-deploy.cmd -Backend        # infra + spring-boot:run, loads .env keys
#   .\local-deploy.cmd -Frontend       # npm run dev on http://localhost:3001
#
# Local artifact mode follows deploy.cmd and avoids downloading dependencies
# inside Docker. For day-to-day debugging use -Backend / -Frontend.
param(
    [switch]$InfraOnly,
    [switch]$Backend,
    [switch]$Frontend,
    [switch]$SkipBuild,
    [switch]$DockerBuild,
    [switch]$Down,
    [switch]$Clean,
    [switch]$Logs,
    [switch]$Status
)

$ErrorActionPreference = "Stop"

$Root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$EnvFile = Join-Path $Root ".env"
$BackendDir = Join-Path $Root "heart-pilot-backend"
$FrontendDir = Join-Path $Root "heart-pilot-frontend"

function Invoke-Compose {
    param([string[]]$Arguments)
    & docker compose @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "docker compose failed (exit $LASTEXITCODE): $($Arguments -join ' ')"
    }
}

function Start-Middleware {
    Write-Host "[infra] starting postgres / redis / minio"
    Invoke-Compose @("up", "-d", "--wait", "postgres", "redis", "minio")
    Write-Host "  postgres  127.0.0.1:5432  (db heart_pilot)"
    Write-Host "  redis     127.0.0.1:6379"
    Write-Host "  minio     http://127.0.0.1:9000  console http://127.0.0.1:9001"
}

Push-Location $Root
try {
    if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
        throw "Docker not found. Install Docker Desktop and make sure 'docker' is on PATH."
    }
    & docker compose version | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "Docker Compose v2 is required (docker compose ...)." }

    if (-not (Test-Path -LiteralPath $EnvFile)) {
        Copy-Item (Join-Path $Root ".env.example") $EnvFile
        Write-Warning "Created .env from .env.example. Fill DASHSCOPE_API_KEY / AMAP_MAPS_API_KEY, then rerun."
    }

    $devModes = @()
    if ($InfraOnly.IsPresent) { $devModes += "InfraOnly" }
    if ($Backend.IsPresent) { $devModes += "Backend" }
    if ($Frontend.IsPresent) { $devModes += "Frontend" }
    if ($devModes.Count -gt 1) {
        throw "Choose only one of -InfraOnly / -Backend / -Frontend."
    }

    if ($Down) {
        Invoke-Compose @("down")
        Write-Host "Stopped. Data volumes are kept."
        return
    }

    if ($Clean) {
        $answer = Read-Host "This deletes local postgres/redis/minio data volumes. Type YES to continue"
        if ($answer -ne "YES") {
            Write-Host "Cancelled."
            return
        }
        Invoke-Compose @("down", "-v")
        Write-Host "Stopped and local data volumes removed."
        return
    }

    if ($Logs) {
        & docker compose logs -f --tail=200
        return
    }

    if ($Status) {
        Invoke-Compose @("ps")
        return
    }

    if (-not $DockerBuild -and -not $SkipBuild) {
        # Follow the artifact build used by deploy.cmd: compile once on this
        # machine, then let Docker only COPY the jar and frontend dist.
        $PackageScript = Join-Path $PSScriptRoot "package-deploy.ps1"
        & $PackageScript
        if ($LASTEXITCODE -ne 0) { throw "Local artifact build failed." }

        $composeArgs = @("-f", "docker-compose.yml", "-f", "docker-compose.deploy.yml", "up", "-d")
        $composeArgs += @("--wait", "--wait-timeout", "600")
        Write-Host "[docker] building local artifacts into runtime images and starting the stack."
        Invoke-Compose $composeArgs
        Invoke-Compose @("-f", "docker-compose.yml", "-f", "docker-compose.deploy.yml", "ps")
        Write-Host "App: http://localhost:8081/"
        return
    }

    if (-not $DockerBuild -and $SkipBuild) {
        Invoke-Compose @("-f", "docker-compose.yml", "-f", "docker-compose.deploy.yml", "up", "-d", "--wait", "--wait-timeout", "600")
        Invoke-Compose @("-f", "docker-compose.yml", "-f", "docker-compose.deploy.yml", "ps")
        return
    }

    if ($InfraOnly) {
        Start-Middleware
        Write-Host ""
        Write-Host "Next:"
        Write-Host "  backend  : .\local-deploy.cmd -Backend   (or run from IDE)"
        Write-Host "  frontend : .\local-deploy.cmd -Frontend  (http://localhost:3001)"
        return
    }

    if ($Backend) {
        Start-Middleware

        # Load .env into this process so the model keys are visible to Spring Boot.
        Get-Content -LiteralPath $EnvFile | ForEach-Object {
            if ($_ -match '^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)$') {
                $name = $matches[1]
                $value = $matches[2].Trim()
                if ($value.Length -ge 2 -and $value.StartsWith('"') -and $value.EndsWith('"')) {
                    $value = $value.Substring(1, $value.Length - 2)
                }
                [Environment]::SetEnvironmentVariable($name, $value, "Process")
            }
        }
        # The Vite dev server runs on 3001; keep it allowed even if .env has prod CORS.
        [Environment]::SetEnvironmentVariable(
            "CORS_ALLOWED_ORIGINS",
            "http://localhost:3001,http://127.0.0.1:3001",
            "Process"
        )

        $mvn = $null
        if (Get-Command mvn.cmd -ErrorAction SilentlyContinue) {
            $mvn = "mvn.cmd"
        } elseif (Test-Path -LiteralPath (Join-Path $BackendDir "mvnw.cmd")) {
            $mvn = Join-Path $BackendDir "mvnw.cmd"
        }
        if (-not $mvn) { throw "Maven not found. Install Maven or keep heart-pilot-backend\mvnw.cmd." }

        Push-Location $BackendDir
        try {
            Write-Host ""
            Write-Host "Starting backend: http://localhost:8123/api  (Ctrl+C to stop)"
            & $mvn spring-boot:run
        } finally {
            Pop-Location
        }
        return
    }

    if ($Frontend) {
        if (-not (Get-Command npm.cmd -ErrorAction SilentlyContinue)) {
            throw "npm not found. Install Node.js 20+."
        }
        if (-not (Test-Path -LiteralPath (Join-Path $FrontendDir "node_modules\.bin\vite.cmd"))) {
            Write-Host "[frontend] node_modules missing, running npm ci"
            & npm.cmd --prefix $FrontendDir ci
            if ($LASTEXITCODE -ne 0) { throw "npm ci failed (exit $LASTEXITCODE)." }
        }
        Write-Host ""
        Write-Host "Starting Vite: http://localhost:3001  (Ctrl+C to stop)"
        & npm.cmd --prefix $FrontendDir run dev
        return
    }

    $composeArgs = @("up", "-d")
    if (-not $SkipBuild) { $composeArgs += "--build" }
    $composeArgs += @("--wait", "--wait-timeout", "600")

    Write-Host "[docker] building images and starting the full local stack."
    Write-Host "         First build downloads Maven/npm dependencies and can take several minutes."
    Invoke-Compose $composeArgs

    Invoke-Compose @("ps")
    Write-Host ""
    Write-Host "App:     http://localhost:8081/"
    Write-Host "Health:  http://localhost:8081/api/health"
    Write-Host "Logs:    .\local-deploy.cmd -Logs"
    Write-Host "Stop:    .\local-deploy.cmd -Down"
} finally {
    Pop-Location
}
