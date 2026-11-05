# 贡献指南

感谢你对 HeartPilot 的关注。本项目目前为个人学习与研究项目，欢迎通过 Issue 与 PR 交流。

## 提交前检查

后端：

```powershell
cd heart-pilot-backend
.\mvnw.cmd clean verify
```

前端：

```powershell
cd heart-pilot-frontend
npm ci
npm run format:check
npm run build
```

以上命令全部通过后再提交。

## 提交信息格式

建议使用 Conventional Commits：

- `feat: 新增礼物候选价格校准`
- `fix: 修复 SSE 取消后连接未关闭`
- `docs: 更新部署说明`
- `refactor: 抽取需求校验器`
- `test: 补充并发取消竞态用例`
- `chore: 升级 Spring Boot 到 3.4.5`

一次提交只做一件事，不要把功能、格式化、依赖升级混在一起。

## 分支与 PR

- `main` 保持可构建、可启动。
- 功能开发从 `main` 切出分支，命名如 `feat/gift-price-calibration`。
- PR 描述里写清：改了什么、为什么改、如何验证、有无副作用。
- 涉及数据库变更时，新增 Flyway 迁移脚本（`V{n}__xxx.sql`），不要修改已发布的历史迁移。

## 代码规范

- 后端遵循现有包结构：`module/<领域>/{controller,service,repository,entity,dto}`，跨模块复用放 `common/` 或 `infrastructure/`。
- 业务接口入参出参用 DTO，Entity 不直接暴露给前端。
- 前端保持 TypeScript，组件放 `views/`，复用件放 `components/`。
- 不要把 `.env`、密钥、压测结果、构建产物提交进仓库。

## 安全

发现安全问题请直接邮件或私信维护者，不要公开开 Issue 暴露漏洞细节。
