# 部署到 101.201.208.223（产物模式）

和 `S:\videonest` 一致：**本机编译 jar + dist，服务器只 COPY 产物**。服务器不装 Maven/Node，
不下载依赖；镜像重建通常 10~30 秒，整轮部署 ≈ 本机编译时间 + 约 80MB 上传。

产物型 Dockerfile 是本仓库的 `Dockerfile.deploy`（后端 `COPY target/*.jar`，前端 `COPY dist`）；
本地开发用的 `docker-compose.yml` 仍走多阶段 `Dockerfile`，两条路互不影响。

## 一次部署做了什么

1. 本机 `mvn clean package`（默认 `-DskipTests`）→ `heart-pilot-backend/target/*.jar`
2. 本机 `npm run build` → `heart-pilot-frontend/dist`
3. 打成 `C:\Users\Public\heart-pilot-deploy.tar.gz`（jar + dist + Dockerfile.deploy + compose + redeploy.sh + knowledge，约 80MB）
4. `scp` 到服务器 `/tmp`，解包到 `/opt/apps/heart-pilot`（不会碰 `.env.prod`）
5. 服务器执行 `deploy/server/redeploy.sh`：`docker compose build`（只 COPY）→ `up -d` → 等后端 healthy → `docker image prune -f` 清理悬空旧镜像

## 首次准备

服务器需要：

- Docker Engine + Compose v2
- 共享中间件 `lp-infra`（Postgres/Redis/MinIO）和 `lp-shared` 网络已就绪
- `/opt/apps/heart-pilot/.env.prod`：`cp .env.prod.example .env.prod && chmod 600 .env.prod`，填好密码和 Key（部署脚本从不上传这个文件）
- 腾讯云安全组放行 TCP 8081；5432/6379/9000/9001 不对公网开放

本机需要 Maven（或 `heart-pilot-backend\mvnw.cmd`）与 Node/npm。

## 共享中间件（lp-infra）

MySQL / PostgreSQL / Redis / MinIO 已独立到 `/opt/apps/lp-infra`，不再属于某一个应用仓库：

```bash
cd /opt/apps/lp-infra
./infra.sh status      # 容器 + 健康
./infra.sh up          # 启动并等 4 个容器 healthy
./infra.sh logs postgres
```

密码在 `/opt/apps/lp-infra/.env`，heart-pilot 侧的 `PG_PASSWORD`、`REDIS_PASSWORD`、`MINIO_ACCESS_KEY/SECRET_KEY` 必须与它一致。数据卷是 `lp-infra_*`，`./infra.sh down` 不会删数据。

## 一键部署 / 更新

```powershell
.\deploy.cmd
```

完成后访问 `http://101.201.208.223:8081`。数据在共享的 PostgreSQL/Redis/MinIO 里，重建容器不会丢。

常用参数：

```powershell
.\deploy.cmd -RunTests                 # 部署前跑后端测试（需要本机 Docker/Testcontainers，明显变慢）
.\deploy.cmd -SkipBuild                # 复用已有 jar/dist，只重新打包上传
.\deploy.cmd -SkipUpload               # 不传产物，只让服务器用现有产物重建
.\deploy.cmd -RemoteUser ubuntu -IdentityFile C:\keys\server.pem
```

## 只重新打包（不上传）

```powershell
.\scripts\package-deploy.ps1           # 生成 .\heart-pilot-deploy.tar.gz
```

## 服务器排查

```bash
cd /opt/apps/heart-pilot
bash deploy/server/redeploy.sh build     # 只构建镜像，不重启
docker compose -f docker-compose.server.yml --env-file .env.prod ps
docker compose -f docker-compose.server.yml --env-file .env.prod logs -f --tail=200
```

停止服务（保留数据）：`docker compose -f docker-compose.server.yml --env-file .env.prod down`，不要加 `-v`。

## 常见问题

- `COPY target/*.jar` 报匹配到多个文件：服务器 `target/` 残留了旧 jar。重新跑 `.\deploy.cmd`（脚本会先清 `target`/`dist`），或手动 `rm -rf /opt/apps/heart-pilot/heart-pilot-backend/target`。
- 后端 healthy 但接口 502：确认 `hp-backend`/`hp-frontend` 同属 `hp-app` 网络，且 `deploy/server/nginx.conf` 的 upstream 是 `hp-backend:8123`。
- 改了 `knowledge/`：重新部署即可，产物包会带上（容器只读挂载到 `/app/knowledge`）。

> `docker-compose.deploy.yml` 是早期 82.157.205.6 单机方案，已不再使用。
