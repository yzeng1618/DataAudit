# data-audit

[![CI](https://github.com/yzeng1618/DataAudit/actions/workflows/ci.yml/badge.svg)](https://github.com/yzeng1618/DataAudit/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)

> 一个面向大数据与湖仓场景的任务后一致性审计 CLI。

`data-audit` 不参与同步链路，不依赖 SeaTunnel / DataX / Flink CDC 等同步框架，也不要求中心 repository 或 Web 平台。它只在任务完成后的稳定边界上执行校验：批任务在 `job_finish` 后校验结果；实时任务只在 `snapshot` / `version` / `instant` / `time_window` 等已提交边界后校验。

它解决的问题不是"两个表 hash 是否一致"，而是：

- 任务跑完后，结果到底对不对
- 问题落在哪个分区、哪个 snapshot、哪个时间窗口
- 是漏数、重复、删除未生效，还是 DDL / schema evolution 引发的误报
- 下次复查时，能不能只重查受影响范围

一句话对比：`dataCompare` 这类工具回答"迁移后两张表像不像"；`data-audit` 回答"任务跑完后，这个边界上的结果到底对不对，错在哪，为什么错"。

## 目录

- [5 分钟开始](#5-分钟开始)
- [工作方式](#工作方式)
- [适用场景](#适用场景)
- [配置示例](#配置示例)
- [命令](#命令)
- [Connector 策略](#connector-策略)
- [AI Copilot（Alpha）](#ai-copilotalpha)
- [输出与复查](#输出与复查)
- [退出码](#退出码)
- [模块结构](#模块结构)
- [文档索引](#文档索引)
- [参与贡献](#参与贡献)

## 5 分钟开始

需要 Java 17+；仓库内置 Maven Wrapper，不要求预装 Maven（Windows 下将 `./mvnw` 替换为 `.\mvnw.cmd`）：

```bash
git clone https://github.com/yzeng1618/DataAudit.git
cd DataAudit
./mvnw -pl data-audit-cli -am clean package
java -jar data-audit-cli/target/data-audit.jar config init -o task.yaml
java -jar data-audit-cli/target/data-audit.jar config validate -f task.yaml
java -jar data-audit-cli/target/data-audit.jar doctor -f task.yaml
```

编辑 `task.yaml`（见下方[配置示例](#配置示例)）后再执行：

```bash
java -jar data-audit-cli/target/data-audit.jar plan -f task.yaml
java -jar data-audit-cli/target/data-audit.jar check -f task.yaml
```

`config validate` 默认是离线检查，不访问 source/target；只有显式增加 `--test-connection` 才会打开连接器并探测 schema。

> 本文其余命令中的 `data-audit` 均代表 `java -jar data-audit-cli/target/data-audit.jar`，可自行为它创建 alias 或 wrapper 脚本。

## 工作方式

1. 不做同步中校验，只做边界后的结果审计。
2. 不做第二个 dataCompare，而是做大数据 / 湖仓场景的 snapshot-aware、DDL-aware CLI。
3. 默认路径不是全表 hash，而是由 planner 在 `global signal -> localization -> exact/sample diff` 中自动选择最小必要路径：小表优先 `global row_count + global checksum`；大表 / 分区表优先 `grouped checksum -> localization -> exact diff`；超大表优先 `metadata / routing digest -> localization -> exact diff or sampling`；无稳定边界直接拒绝执行。
4. `exact diff` 是最强证明；`grouped checksum / routing digest / XOR checksum / sampling` 负责缩圈和给出分级置信度。报告显式输出 `proof_mode / confidence / no_key_mode / fallback_reason`。

运行过程分为 `Spec Load -> Capability Discovery -> Boundary Resolve -> Plan Build -> Layered Execute -> Report Persist`。比较逻辑只存在于 `data-audit-core`，connector 只负责读数据、读元数据和暴露能力。完整架构见 [docs/design.md](docs/design.md)。

## 适用场景

| 规模档位 | 典型 source / target | 典型边界 | 默认路径 | 主要证据 |
| --- | --- | --- | --- | --- |
| `small` | JDBC 表、查询结果、Trino 小结果集 | `job_finish` | `global row_count + global checksum -> exact diff(on mismatch)` | row diff、sample diff |
| `large` | 大表、分区表、按时间或业务键切片的大对象 | `job_finish` / `partition` / `time_window` | `global row_count + grouped checksum -> localization -> exact diff` | suspect slices、grouped signal |
| `xlarge` | 超大表、湖仓对象、超大结果集 | `snapshot` / `version` / `instant` / `time_window` | `metadata / routing digest -> localization -> exact diff or sampling` | snapshot info、routing digest、suspect slices |

边界稳定、规模可控时，planner 可以直接短路到 `exact diff`，因此传统小表"表对表精确比对"同样覆盖。推荐从 `templates/` 起步：`small-table-once.yaml`、`big-table-partitioned.yaml`、`lakehouse-snapshot.yaml`、`hive-jdbc-partitioned.yaml`、`doris-jdbc-result.yaml`，以及服务器场景的 `server-*.yaml`。

## 配置示例

```yaml
task:
  name: orders_reconcile
  mode: post_check

boundary:
  type: snapshot
  reference: latest
  grace_period: 5m

source:
  type: jdbc
  url: jdbc:postgresql://source.example:5432/app
  username: audit_reader
  password: ${SOURCE_PASSWORD}
  query: |
    select order_id, status, amount, update_time, dt
    from public.orders
    where dt = '2026-03-10'
  options:
    dialect: postgres

target:
  type: iceberg
  table: orders
  snapshot_id: latest

object:
  key:
    - order_id
  columns:
    - order_id
    - status
    - amount
    - update_time
    - dt
  estimated_rows: 5000000
  partition_by:
    - dt

planner:
  scale_override: large

normalize:
  decimal_scale:
    amount: 2

output:
  dir: ./reports/orders_reconcile
```

全量配置样例与参数说明见 [docs/config-examples.md](docs/config-examples.md)。

## 命令

```bash
data-audit demo
data-audit config init -o task.yaml
data-audit config validate -f task.yaml
data-audit doctor -f task.yaml
data-audit plan  -f task.yaml
data-audit check -f task.yaml
data-audit diff  -f task.yaml --slice dt=2026-03-10
data-audit report show ./reports/orders_reconcile/report.json
```

- `demo`：零依赖的完整演示——自动生成两份 SQLite 样例数据并跑一次真实核验，60 秒看到第一个有意义的差异
- `check`：标准执行
- `plan`：只生成比较计划，不执行
- `diff`：对指定 suspect segment 下钻
- `report`：查看或转换报告
- `config init/validate`：创建配置并做默认离线校验（`--test-connection` 才连库）
- `doctor`：聚合检查 Java、连接器、SQLite、输出目录，并**默认探测 source/target 连接**（`--offline` 跳过）

任何命令遇到未预期错误都会以一行 `[FAIL] <根因>` 说明问题并以退出码 4 结束；加 `--stacktrace` 查看完整堆栈。

## Connector 策略

v1 配置模型为 `task / boundary / query_connector / source / target / object / planner / normalize / semantics / output`，端点类型：

- `type: trino` — 推荐写法，作为统一查询平面，承接所有能通过 Trino catalog 暴露的对象（MySQL / PostgreSQL / Oracle / Hive / Iceberg / Hudi / Delta / Paimon 的结果面访问）；`type: sql` 是其兼容别名
- `type: jdbc` — 直连 fallback，承接 PostgreSQL / MySQL / Hive JDBC / Doris JDBC 等可 SQL 化对象，建议显式配置方言（首版支持 `postgres` / `mysql` / `hive` / `doris`）
- `type: iceberg` — 首个原生湖仓 connector，保留 snapshot/native metadata 真值路径；需要 `snapshot / manifest / partition summary` 等原生元数据能力时优先使用

`jdbc <-> iceberg` 已可执行真实 `check`。Hive 与 Doris 当前不做 native connector，统一通过 `type: jdbc` 接入。v0 配置迁移见 [docs/migration-v1.md](docs/migration-v1.md)。

**第三方 connector 无需修改本仓库**：实现 [SPI](data-audit-spi/src/main/java/io/github/dataaudit/spi/connector/ConnectorFactory.java) 并注册 `META-INF/services`，把 jar 放进环境变量 `DATAAUDIT_PLUGINS_DIR` 指向的目录即可被自动发现（内置 connector 优先，插件只能新增类型不能覆盖）。

## AI Copilot（Alpha）

AI 参与核验策略规划、风险识别、根因假设和交付表达，但**不判断数据是否一致**——一致性状态只来自确定性核验结果。AI provider 默认关闭，命令走确定性规则和本地 RAG fallback；API key 只从环境变量读取。

```bash
data-audit ai plan --task task.yaml --output audit_plan.json
data-audit check -f task.yaml --ai-report --ai-report-template technical
```

完整命令、provider 接入（http-json / openai-compatible / openai-sdk）、Profile Quality Gate 与本地 RAG 说明见 [docs/ai-copilot.md](docs/ai-copilot.md)。

## 输出与复查

`check` 在 `output.dir` 下生成 `report.json`、`report.html`、`manifest.json`、`suspect_slices.csv`、`row_diff_sample.csv`，运行状态写入 `state.db`。报告包含 `plan.scale_class / signal_strategy / boundary / reason` 与 `result.root_cause / proof_mode / confidence / suspect_slices / resume_hint`，既解释这次为什么走这条比较路径，也支持下次拿 suspect slice 继续复查。

diff sample 的值默认在持久化前脱敏，`output.value_mode` 可选：

- `masked`：非空样本值写为 `***`（默认）；**行 key 保持可读**——它通常是业务标识，也是排查的唯一线索
- `hash`：样本值写入 SHA-256 摘要（key 保持可读），便于跨报告关联；摘要不是加密
- `omit`：连同 key 一起不持久化——key 也敏感时使用
- `raw`：保留明文，只应在明确授权且访问受控的目录中使用

`slice_key` 和 `resume_hint` 是复查所需的操作值，不受该模式处理。详细威胁边界见 [SECURITY.md](SECURITY.md)。

## 退出码

| 退出码 | 含义 |
| --- | --- |
| `0` | 执行成功且数据一致 |
| `1` | 执行成功但发现差异 |
| `2` | 配置错误（任务文件不合法、缺失环境变量、非法参数） |
| `4` | 执行失败（连接、权限、驱动、诊断失败或未预期异常） |
| `5` | 边界不稳定，拒绝执行 |
| `6` | `ai plan` 画像质量门要求人工复核（`REVIEW_REQUIRED`） |

调度接入时把 `1` 视为业务差异告警，把 `2` / `4` / `5` 视为运行失败或需人工处理。

## 模块结构

```text
data-audit-spi               扩展点接口（connector / state / report SPI）
data-audit-core              比较引擎与 planner（唯一的比较逻辑所在地）
data-audit-connector-trino   Trino 查询平面 connector
data-audit-connector-jdbc    通用 JDBC connector（postgres/mysql/hive/doris 方言）
data-audit-connector-iceberg Iceberg 原生 metadata/数据 connector
data-audit-state-sqlite      本地运行状态存储
data-audit-report            JSON / HTML / CSV 报告生成
data-audit-ai                AI Copilot（策略规划 / 根因分析 / 报告生成）
data-audit-cli               命令行入口（产出 data-audit.jar 与 dataaudit-ai.jar）
data-audit-it                集成测试（Testcontainers，按需跳过）
data-audit-agent             可选 Python LangGraph sidecar（不在 Maven reactor 内）
```

## 文档索引

| 文档 | 内容 |
| --- | --- |
| [docs/design.md](docs/design.md) | 架构与设计 |
| [docs/config-examples.md](docs/config-examples.md) | 配置参考与全量样例 |
| [docs/trial-guide.md](docs/trial-guide.md) | 试用方案：本地冒烟 → 真实小表分阶段验证 |
| [docs/deployment.md](docs/deployment.md) | 服务器部署：单 jar / 单容器 / 调度器接入 |
| [docs/ai-copilot.md](docs/ai-copilot.md) | AI Copilot 使用指南 |
| [docs/development.md](docs/development.md) | 本地开发、验证脚本与测试矩阵 |
| [docs/migration-v1.md](docs/migration-v1.md) | v1 配置迁移说明 |
| [docs/artifact-contracts.md](docs/artifact-contracts.md) | `report.json` 产物契约 |
| [docs/roadmap.md](docs/roadmap.md) | 路线图 |
| [examples/README.md](examples/README.md) | 示例数据与复现说明 |
| [data-audit-agent/README.md](data-audit-agent/README.md) | Python agent sidecar |

## 参与贡献

项目使用 [Apache License 2.0](LICENSE)。提交代码前请阅读 [CONTRIBUTING.md](CONTRIBUTING.md) 和 [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md)，安全问题请按 [SECURITY.md](SECURITY.md) 私下报告。

本地提交前的基线验证：

```bash
./mvnw verify
python -m pytest -q data-audit-agent
```

`verify` 同时执行 SPDX license header 检查——新增源文件需带 `// SPDX-License-Identifier: Apache-2.0` 头，运行 `./mvnw spotless:apply` 可自动补齐；jacoco 覆盖率报告输出在各模块 `target/site/jacoco/`。

版本发布由 `v*` 标签触发：流水线将 POM 版本对齐到 tag、注入提交溯源并做可复现构建，产物包含完整 CLI、AI wrapper、CycloneDX SBOM 和 SHA-256 校验文件，见 [Releases](https://github.com/yzeng1618/DataAudit/releases)。

## 参考资料

- `dataCompare`: <https://github.com/WJX20/dataCompare>
- Apache Iceberg Evolution: <https://iceberg.apache.org/docs/latest/evolution/>
- Apache Iceberg Spec: <https://iceberg.apache.org/spec/>
- Flink CDC API: <https://nightlies.apache.org/flink/flink-cdc-docs-release-3.5/zh/docs/developer-guide/understand-flink-cdc-api/>
- Debezium delete behavior: <https://debezium.io/documentation/reference/stable/transformations/applying-transformations-selectively.html>
- Delta Change Data Feed: <https://docs.delta.io/delta-change-data-feed/>
- Apache Hudi SQL Queries: <https://hudi.apache.org/docs/sql_queries/>
- Apache Paimon Snapshot Spec: <https://paimon.apache.org/docs/1.3/concepts/spec/snapshot/>
