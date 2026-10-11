# HeartPilot 压测与评测资产

本目录收纳 HeartPilot 关系成长 AI 系统的在线压测脚本与实验报告，供复现与审计使用。

被测服务：`http://82.157.205.6:8081`（context-path `/api`）
测试日期：2026-10-09　|　测试机：Windows（Python 3.13，脚本仅标准库，零第三方依赖）

## 目录结构

```
stress-test/
├── heartpilot_stress_test.py   # 压测脚本（四类场景：持续负载 / 突发限流 / SSE TTFT / 解除限流容量）
├── HeartPilot压测与评测实验报告.docx  # 实验报告（含容量测试章节与清理 SQL）
├── README.md
└── results/                    # 压测运行产物（JSON 汇总 + CSV 逐请求），已 gitignore 不入库
```

## 脚本用法

常规三场景（登录态持续负载 + 单用户突发限流 + SSE 首字延迟）：

```bash
python heartpilot_stress_test.py --base-url http://82.157.205.6:8081 --users 8 --duration 60 --ramp 15 --ttft-samples 10 --burst-total 150
```

解除限流容量测试（只读接口、不修改线上配置）：

```bash
python heartpilot_stress_test.py --capacity-only --capacity-users 40 --capacity-duration 60 --capacity-ramp 15
python heartpilot_stress_test.py --capacity-only --capacity-users 20 --capacity-duration 60 --capacity-ramp 5
```

结果写入 `results/stress-summary-<时间戳>.json` 与 `results/stress-records-<时间戳>.csv`。

## 容量测试口径（重要）

- 线上限流：认证接口每 IP 20 次/分钟，其余接口按每用户 120 次/分钟（Redis 分布式窗口，`RateLimitFilter`）。
- 「解除限流」采用**多用户等效**实现：注册 `rl_` 前缀临时用户，每用户以 119 次/分钟打满自身配额窗口，聚合吞吐逼近「用户数 × 2 QPS」的配额聚合上限，等效测量配额模型内系统可支撑的业务容量。
- **不污染数据库**：只压只读接口 `GET /conversations`、`GET /agent-tasks`（分页 SELECT，业务零写入），不创建会话、不调用 AI 对话；注册仅写入 `app_user` 表。
- 容量测试用户清理（在 PostgreSQL 侧执行，先 SELECT 核对再 DELETE）：

```sql
SELECT count(*) FROM app_user WHERE username LIKE 'rl\_%' ESCAPE '\';
DELETE FROM app_user WHERE username LIKE 'rl\_%' ESCAPE '\';
```

## 正式采用的数据轮次

| 轮次（results/） | 用途 | 采用 |
|---|---|---|
| `stress-*220247` | 常规三场景（8 用户持续负载 / 突发限流 / SSE TTFT） | ✅ 报告引用 |
| `stress-*225843` | 容量测试 40 用户（60s，爬坡 15s，理论上限 80 QPS） | ✅ 报告引用 |
| `stress-*230338` | 容量测试 20 用户（60s，爬坡 5s，理论上限 40 QPS） | ✅ 报告引用 |
| `health-100conc.json` | 健康检查 100 并发参考（链路并发上限） | ✅ 报告引用 |
| `stress-*215214` | 容量测试首次尝试（注册阶段连接超时，中断轮） | 参考，不采用 |
| `stress-*215434` | 早期常规尝试轮 | 参考，不采用 |

## 结论摘要（2 核 2G 部署）

- 常规三场景数据正常：8 用户并发 QPS 7.31、P50 47.9ms、P99 191ms；突发超限 46 次被 Redis 限流 429 拒接；SSE 首字延迟 P50 672ms。
- 容量测试：40 用户轮 QPS 26.42、20 用户轮 QPS 20.44，均未触 429，但均显著低于配额聚合理论上限，且复现约 21 秒连接层无响应窗口 → **瓶颈不在限流层，为 2 核 2G 部署下周期性进程级停顿（疑似 Full GC 长暂停）**。
- 该结论是部署配置瓶颈而非产品缺陷，**不写入简历**；可作为「限流兼具容量保护」的工程判断依据。

完整分析见实验报告第 5 章。
