#!/usr/bin/env bash
# =====================================================================
# heart-pilot 服务器端重建脚本（产物模式）
#
# 前提：本机已用 .\deploy.cmd（scripts\deploy.ps1）上传过
#   heart-pilot-backend/target/app.jar   （本机 mvn package 的产物）
#   heart-pilot-frontend/dist            （本机 npm run build 的产物）
#
# 这里只做三件事：
#   1) docker compose build hp-backend hp-frontend —— 只 COPY 产物，不装 Maven/npm
#   2) up -d 重建两个容器
#   3) 等后端健康检查、确认前端在跑
#
# 用法：
#   bash deploy/server/redeploy.sh          # 构建 + 重启 + 等健康
#   bash deploy/server/redeploy.sh build    # 只构建，不重启
# =====================================================================
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/../.."

ENV_FILE="${ENV_FILE:-.env.prod}"
COMPOSE=(docker compose -f docker-compose.server.yml --env-file "$ENV_FILE")
MODE="${1:-up}"

die() { echo "[x] $*" >&2; exit 1; }

[ -f "$ENV_FILE" ] || die "$ENV_FILE 不存在（在服务器上按 .env.prod.example 创建并填好）"
[ -f heart-pilot-backend/target/app.jar ] || die "heart-pilot-backend/target/app.jar 不存在，请在本机先跑 .\\deploy.cmd"
[ -d heart-pilot-frontend/dist ] || die "heart-pilot-frontend/dist 不存在，请在本机先跑 .\\deploy.cmd"
[ -d knowledge ] || die "knowledge/ 不存在（后端以只读卷挂载到 /app/knowledge）"

echo "==> 构建 hp-backend / hp-frontend（仅 COPY 本机产物）"
BUILD_START=$(date +%s)
"${COMPOSE[@]}" build hp-backend hp-frontend
BUILD_SECONDS=$(( $(date +%s) - BUILD_START ))
echo "==> 镜像构建完成，用时 ${BUILD_SECONDS}s"

if [ "$MODE" = "build" ]; then
  echo "==> 只构建模式，未重启容器"
  exit 0
fi

echo "==> 重建容器"
UP_START=$(date +%s)
"${COMPOSE[@]}" up -d hp-backend hp-frontend
echo "==> 容器已重建，用时 $(( $(date +%s) - UP_START ))s"

echo "==> 等待 hp-backend healthy"
HEALTH="unknown"
for _ in $(seq 1 40); do
  HEALTH=$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' hp-backend 2>/dev/null || echo missing)
  case "$HEALTH" in
    healthy)
      echo "    hp-backend healthy"
      break
      ;;
    unhealthy)
      echo "[!] hp-backend unhealthy，最近日志：" >&2
      docker logs --tail 80 hp-backend >&2 || true
      exit 1
      ;;
  esac
  sleep 3
done
[ "$HEALTH" = "healthy" ] || echo "[!] 等了 120 秒仍未 healthy（$HEALTH），请查看 docker logs hp-backend"

RUNNING=$(docker inspect -f '{{.State.Running}}' hp-frontend 2>/dev/null || echo false)
if [ "$RUNNING" != "true" ]; then
  echo "[!] hp-frontend 未运行" >&2
  docker logs --tail 40 hp-frontend >&2 || true
  exit 1
fi
echo "    hp-frontend running"

echo
"${COMPOSE[@]}" ps

# 旧镜像在 compose 重建后会变成悬空镜像（dangling），每次部署顺手清掉；
# 默认只删无 tag 的悬空层，不动正在运行或已打 tag 的镜像，失败也不影响部署结果。
echo
echo "==> 清理悬空镜像"
docker image prune -f || true

echo
echo "==> 完成：镜像构建 ${BUILD_SECONDS}s"
