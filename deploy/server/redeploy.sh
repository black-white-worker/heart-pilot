#!/usr/bin/env bash
# =====================================================================
# heart-pilot 服务器端重建脚本（产物模式）
#
# 前提：本机已用 .\deploy.cmd（scripts\deploy.ps1）上传过
#   heart-pilot-backend/target/app.jar   （本机 mvn package 的产物）
#   heart-pilot-frontend/dist            （本机 npm run build 的产物）
#
# 这里做五件事：
#   0) 闸门：可用内存 / Docker 数据盘不够就直接拒绝部署
#   1) docker compose build hp-backend hp-frontend —— 只 COPY 产物，不装 Maven/npm
#   2) 部署前 pg_dump 备份 heart_pilot（保留最近 3 份，见下方 BACKUP_DIR）
#   3) up -d 重建两个容器
#   4) 等后端健康检查、确认前端在跑，并清掉悬空镜像
#
# 用法：
#   bash deploy/server/redeploy.sh          # 构建 + 备份 + 重启 + 等健康（演示档）
#   bash deploy/server/redeploy.sh build    # 只构建，不备份不重启
#   PROFILE=bench bash deploy/server/redeploy.sh   # 带压测叠层重建（测完不带 PROFILE 再跑一次还原）
#
# 备份/闸门相关：
#   SKIP_DB_BACKUP=1   跳过 pg_dump（明知库是空的、或刚还原过时用）
#   BACKUP_DIR         备份目录，默认 /var/backups/heart-pilot（放在仓库外，免得被上传脚本当产物带走）
#   MIN_FREE_MB        重启前要求的可用内存（默认 200，本栈优先级低于 videonest，门槛比它低）
#   MIN_FREE_DISK_MB   重启前要求的 Docker 数据盘可用空间（默认 1024）
# =====================================================================
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/../.."

ENV_FILE="${ENV_FILE:-.env.prod}"
PROFILE="${PROFILE:-idle}"
MODE="${1:-up}"

die() { echo "[x] $*" >&2; exit 1; }

COMPOSE=(docker compose -f docker-compose.server.yml --env-file "$ENV_FILE")
# 压测叠层只多改 hp-backend 一段，其他服务照旧
if [ "$PROFILE" = "bench" ]; then
  [ -f docker-compose.bench.yml ] || die "PROFILE=bench 但 docker-compose.bench.yml 不存在"
  COMPOSE=(docker compose -f docker-compose.server.yml -f docker-compose.bench.yml --env-file "$ENV_FILE")
fi

# 从 env 文件里取一个变量，去掉外层引号；不 source 整个文件，
# 因为 .env.prod 里可能有带 $ 的密码，source 会把它当变量展开。
env_from_file() {
  local key="$1" line val
  line=$(grep -E "^[[:space:]]*${key}=" "$ENV_FILE" | tail -n 1 || true)
  [ -n "$line" ] || return 0
  val="${line#*=}"
  val="${val%$'\r'}"
  case "$val" in
    \"*\") val="${val#\"}"; val="${val%\"}" ;;
    \'*\') val="${val#\'}"; val="${val%\'}" ;;
  esac
  printf '%s' "$val"
}

# 最近一次备份的路径 + 还原命令；没有备份时给出明确提示，不打印空字符串
latest_backup() {
  local latest
  latest=$(ls -1 "${BACKUP_DIR:-/var/backups/heart-pilot}"/heart_pilot-*.dump 2>/dev/null | tail -n 1)
  if [ -n "$latest" ]; then
    printf '%s\n    还原：docker exec -i -e PGPASSWORD=... infra-postgres \\\n' "$latest"
    printf '          pg_restore -U %s -d heart_pilot --clean --if-exists --no-owner < %s\n' \
      "${PG_USER_NAME:-heart_pilot}" "$latest"
  else
    printf '（本次没有备份，BACKUP_DIR=%s 下没有 dump 文件）\n' "${BACKUP_DIR:-/var/backups/heart-pilot}"
  fi
}

[ -f "$ENV_FILE" ] || die "$ENV_FILE 不存在（在服务器上按 .env.prod.example 创建并填好）"
[ -f heart-pilot-backend/target/app.jar ] || die "heart-pilot-backend/target/app.jar 不存在，请在本机先跑 .\\deploy.cmd"
[ -d heart-pilot-frontend/dist ] || die "heart-pilot-frontend/dist 不存在，请在本机先跑 .\\deploy.cmd"
[ -d knowledge ] || die "knowledge/ 不存在（后端以只读卷挂载到 /app/knowledge）"

# ---- 0) 闸门：内存与磁盘 ----
# 中间件是三栈共享的，本栈重建时如果内存已经见底，被 OOM kill 的往往是 infra-postgres：
# 那个库里有 pgvector 索引，重建索引比重启容器贵得多。所以先量一下再动手。
MIN_FREE_MB="${MIN_FREE_MB:-200}"
MIN_FREE_DISK_MB="${MIN_FREE_DISK_MB:-1024}"
if [ "$MODE" != "build" ]; then
  AVAIL_MB=$(awk '/^MemAvailable:/{print int($2/1024)}' /proc/meminfo 2>/dev/null || echo "")
  if [ -z "$AVAIL_MB" ]; then
    echo "[!] 读不到 /proc/meminfo，跳过内存闸门（只在这台机器不是 Linux 时才可能）" >&2
  else
    echo "==> 资源闸门：可用内存 ${AVAIL_MB}M（要求 >= ${MIN_FREE_MB}M）"
    if [ "$AVAIL_MB" -lt "$MIN_FREE_MB" ]; then
      echo
      echo "[x] 可用内存只有 ${AVAIL_MB}M，重建 hp-backend 有把 infra-postgres 挤进 OOM 的风险。" >&2
      echo "    先降载再来：docker stop lp-backend lp-frontend" >&2
      echo "    确认无风险后跳过：MIN_FREE_MB=0 bash $0" >&2
      exit 1
    fi
  fi

  DOCKER_ROOT=$(docker info -f '{{.DockerRootDir}}' 2>/dev/null || echo /var/lib/docker)
  FREE_DISK_MB=$(df -m --output=avail "$DOCKER_ROOT" 2>/dev/null | tail -n 1 | tr -d ' ')
  if [ -n "$FREE_DISK_MB" ]; then
    echo "==> ${DOCKER_ROOT} 可用磁盘 ${FREE_DISK_MB}M（要求 >= ${MIN_FREE_DISK_MB}M）"
    if [ "$FREE_DISK_MB" -lt "$MIN_FREE_DISK_MB" ]; then
      echo
      echo "[x] Docker 数据盘只剩 ${FREE_DISK_MB}M，先清理再部署：" >&2
      echo "      docker image prune -f && docker builder prune -f" >&2
      exit 1
    fi
  fi
fi


# ---- 1) 部署前备份业务库（只备份 heart_pilot，保留最近 3 份）----
# up -d 会带着 jar 里的 Flyway 迁移一起跑，迁移不可逆；库被写坏时旧代码也配不上新表结构。
# pg_dump --format=custom 是有损压缩的二进制格式，还原时配 --clean --if-exists 才不会被已存在对象打断。
if [ "$MODE" != "build" ]; then
  if [ "${SKIP_DB_BACKUP:-0}" = "1" ]; then
    echo "==> 已按要求跳过部署前数据库备份（SKIP_DB_BACKUP=1）"
  else
    PG_USER_NAME=$(env_from_file PG_USER)
    PG_USER_NAME="${PG_USER_NAME:-heart_pilot}"
    PG_PW=$(env_from_file PG_PASSWORD)
    BACKUP_DIR="${BACKUP_DIR:-/var/backups/heart-pilot}"
    if [ -z "$PG_PW" ]; then
      echo "[x] $ENV_FILE 里找不到 PG_PASSWORD，无法备份。" >&2
      echo "    确认可以跳过备份：SKIP_DB_BACKUP=1 bash $0" >&2
      exit 1
    elif ! docker ps --format '{{.Names}}' | grep -qx 'infra-postgres'; then
      echo "[!] infra-postgres 未在运行，跳过备份（继续部署，但本次没有可回滚的快照）" >&2
    else
      mkdir -p "$BACKUP_DIR" || die "备份目录 $BACKUP_DIR 建不出来（换 BACKUP_DIR 或先清盘）"
      STAMP=$(date +%Y%m%d-%H%M%S)
      DUMP_FILE="$BACKUP_DIR/heart_pilot-${STAMP}.dump"
      echo "==> 备份 heart_pilot -> ${DUMP_FILE}"
      # 密码经 PGPASSWORD 传入，不落 argv
      if docker exec -i -e PGPASSWORD="$PG_PW" infra-postgres \
          pg_dump -U "$PG_USER_NAME" --format=custom --no-owner --no-privileges \
                  -d heart_pilot 2>/tmp/hp-dump.err > "$DUMP_FILE"; then
        BYTES=$(wc -c < "$DUMP_FILE" | tr -d ' ')
        # custom 格式至少有几 KB 头信息；低于 1KB 判定为失败
        if [ "${BYTES:-0}" -lt 1024 ]; then
          rm -f "$DUMP_FILE"
          die "备份产物只有 ${BYTES:-0} 字节，判定为失败，不用于回滚"
        fi
        echo "    备份完成：${BYTES} 字节"
        mapfile -t DUMPS < <(ls -1 "$BACKUP_DIR"/heart_pilot-*.dump 2>/dev/null || true)
        if [ "${#DUMPS[@]}" -gt 3 ]; then
          for old in "${DUMPS[@]:0:${#DUMPS[@]}-3}"; do
            echo "    清理旧备份 $(basename "$old")"
            rm -f "$old"
          done
        fi
      else
        rm -f "$DUMP_FILE"
        echo "[x] pg_dump 失败：$(tail -n 3 /tmp/hp-dump.err 2>/dev/null)" >&2
        echo "    多半是密码不对或库不存在。修好再部署，或明确跳过：SKIP_DB_BACKUP=1 bash $0" >&2
        exit 1
      fi
    fi
  fi
fi

# ---- 2) 构建两个应用镜像 ----
echo "==> 构建 hp-backend / hp-frontend（仅 COPY 本机产物，档期：${PROFILE}）"
BUILD_START=$(date +%s)
"${COMPOSE[@]}" build hp-backend hp-frontend
BUILD_SECONDS=$(( $(date +%s) - BUILD_START ))
echo "==> 镜像构建完成，用时 ${BUILD_SECONDS}s"

if [ "$MODE" = "build" ]; then
  echo "==> 只构建模式，未重启容器"
  exit 0
fi

# ---- 3) 重启 + 健康检查 ----
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
      echo >&2
      echo "    本栈最常见的不健康原因：共享中间件没起（infra-postgres / infra-redis / infra-minio），" >&2
      echo "    以及压测档下 576M 配额不够（看 docker stats --no-stream hp-backend）。" >&2
      echo "    要退回上一版：$(latest_backup)" >&2
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
if [ "$PROFILE" = "bench" ]; then
  echo "==> 当前档位：压测（测完请不带 PROFILE 再跑一次 redeploy.sh，把演示档还原回来）"
else
  echo "==> 当前档位：演示"
fi
echo "==> 可回滚的最近备份：$(latest_backup)"
