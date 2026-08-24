# data-audit

> 一个面向大数据与湖仓场景的任务后一致性审计 CLI。

`data-audit` 不参与同步链路，不依赖 SeaTunnel / DataX / Flink CDC 等同步框架，也不要求中心 repository 或 Web 平台。它只在任务完成后的稳定边界上执行校验：

- 批任务：在 `job_finish` 后校验结果
- 实时任务：只在 `snapshot` / `version` / `instant` / `time_window` 等已提交边界后校验

它解决的问题不是“两个表 hash 是否一致”，而是：

- 任务跑完后，结果到底对不对
- 问题落在哪个分区、哪个 snapshot、哪个时间窗口
- 是漏数、重复、删除未生效，还是 DDL / schema evolution 引发的误报
- 下次复查时，能不能只重查受影响范围

## v1 Scale-Driven Pipeline

当前 v1 配置已经切换到 `task / boundary / query_connector / source / target / object / planner / normalize / semantics / output`。

- `type: trino` 是推荐写法，表示对象通过 Trino 查询平面访问
- `type: sql` 仍可用，但在 v1 中只作为 `type: trino` 的兼容别名
- `type: jdbc` 作为直连 fallback
- `type: iceberg` 保留 snapshot/native metadata 真值路径
- 默认执行策略已经变成 `small / large / xlarge` 的 scale-driven 流程
- 报告会显式输出 `proof_mode / confidence / no_key_mode / fallback_reason`

`type: trino` 适用于所有能通过 Trino catalog 暴露的对象，例如 MySQL / PostgreSQL / Oracle / Hive / Iceberg / Hudi / Delta / Paimon 的结果面访问。
如果需要 `snapshot` 真值、manifest/schema 元数据或 snapshot-pinned 读取，仍应使用 `type: iceberg` 走原生路径。

迁移说明见 [docs/migration-v1.md](docs/migration-v1.md)。
新模板见 `templates/small-table-trino.yaml`、`templates/partitioned-table-trino.yaml`、`templates/jdbc-fallback.yaml`、`templates/iceberg-snapshot-native.yaml`、`templates/large-table-nokey.yaml`、`templates/xlarge-sampling-fallback.yaml`。

## 核心原则

1. 不做同步中校验，只做边界后的结果审计。
2. 不做第二个 dataCompare，而是做大数据 / 湖仓场景的 snapshot-aware、DDL-aware CLI。
3. 默认路径不是全表 hash，而是由 planner 在 `global signal -> localization -> exact/sample diff` 中自动选择最小必要路径。
4. `exact diff` 是最强证明；`grouped checksum / routing digest / XOR checksum / sampling` 负责缩圈和给出分级置信度。

## 产品定位

`data-audit` 的定位不是字段规则校验器，也不是同步平台插件，而是一个统一的任务后一致性审计 CLI，用于在数据库、湖仓表、查询结果、文件集等异构对象之间，对某个稳定边界上的结果状态做分层校验，并输出可定位、可复查的差异证据。

MVP 阶段它是单进程、单命令、单次任务运行的 CLI。未来可以扩展可选控制面，用于报告汇聚、模板中心和任务目录，但这不改变 CLI-first 的产品边界。

## 5 分钟开始

需要 Java 17+；仓库内置 Maven 3.9.9 Wrapper，不要求预装 Maven：

```bash
./mvnw -pl data-audit-cli -am clean package
java -jar data-audit-cli/target/data-audit.jar config init -o task.yaml
java -jar data-audit-cli/target/data-audit.jar config validate -f task.yaml
java -jar data-audit-cli/target/data-audit.jar doctor -f task.yaml
```

编辑 `task.yaml` 后再执行：

```bash
java -jar data-audit-cli/target/data-audit.jar plan -f task.yaml
java -jar data-audit-cli/target/data-audit.jar check -f task.yaml
```

`config validate` 默认是离线检查，不访问 source/target。只有显式增加
`--test-connection` 才会打开连接器并探测 schema。Windows 下将 `./mvnw`
替换为 `.\mvnw.cmd`。

## 适用场景

`data-audit` 统一支持三类规模档位，覆盖小表、大表和超大表对象：

| 规模档位 | 典型 source / target | 典型边界 | 默认路径 | 主要证据 |
| --- | --- | --- | --- | --- |
| `small` | JDBC 表、查询结果、Trino 小结果集 | `job_finish` | `global row_count + global checksum -> exact diff(on mismatch)` | row diff、sample diff |
| `large` | 大表、分区表、按时间或业务键切片的大对象 | `job_finish` / `partition` / `time_window` | `global row_count + grouped checksum -> localization -> exact diff` | suspect slices、grouped signal |
| `xlarge` | 超大表、湖仓对象、超大结果集 | `snapshot` / `version` / `instant` / `time_window` | `metadata / routing digest -> localization -> exact diff or sampling` | snapshot info、routing digest、suspect slices |

兼容传统小表单次比对，指的是：

- 当边界稳定、数据规模可控时，planner 可以直接短路到 `exact diff`
- 小表仍然可以做传统“表对表精确比对”
- 但它仍然属于统一的任务后审计架构，而不是独立产品模型

## 和 dataCompare 的差异

| 维度 | dataCompare | data-audit |
| --- | --- | --- |
| 产品形态 | 服务 + repository + UI | 单 CLI |
| 默认部署 | 依赖 PostgreSQL repository | 本地状态即可运行，默认 SQLite / JSON |
| 默认对象 | 迁移/复制后的数据库表 | 数据库表、查询结果、湖仓表、文件集 |
| 默认边界 | 任务完成后表对表比对 | `job_finish` / `snapshot` / `version` / `instant` / `time_window` |
| 核心方法 | hash compare + 并行批处理 | planner 驱动的分层比较 |
| 大数据关注点 | 偏数据库迁移后比对 | 偏大表、分区、快照、实时任务结果校验 |
| DDL 处理 | 非核心卖点 | DDL evolution 是一等能力 |
| 输出 | 一致性结果 + repository 明细 | 根因分类 + suspect slice + diff sample + 报告文件 |

一句话概括：`dataCompare` 更像“迁移后两张表像不像”；`data-audit` 更像“任务跑完后，这个边界上的结果到底对不对，错在哪，为什么错”。

## 默认比较路径

默认路径由 planner 自动决定，而不是由用户手工拼算法：

- 小表：优先走 `global row_count + global checksum`，一致时直接结束
- 大表 / 分区表：优先走 `global row_count + grouped checksum -> localization -> exact diff`
- 超大表：优先走 `metadata / routing digest -> localization -> exact diff or sampling`
- 无稳定边界：直接拒绝执行

planner 的职责不是“选算法炫技”，而是根据对象能力、边界稳定性和预估成本，选择最小必要、可解释、可复查的审计路径。

## 架构摘要

- MVP 是单进程 CLI 执行面
- 运行过程分为 `Spec Load -> Capability Discovery -> Boundary Resolve -> Plan Build -> Layered Execute -> Report Persist`
- `source / target` 是被审计对象，不属于同步链路
- `state-store` 用于保存边界指纹、suspect slice 和恢复信息
- `report` 是产品接口的一部分，不是附属产物
- 未来可选控制面只做报告汇聚、模板中心、任务目录，不改变 CLI-only 结论

完整架构见 [docs/design.md](docs/design.md)。
配置样例、参数说明和当前实现限制见 [docs/config-examples.md](docs/config-examples.md)。
首次本地验证及真实数据的分阶段试用步骤见 [docs/trial-guide.md](docs/trial-guide.md)。

## 首版 Connector 策略

当前实现范围固定为三条主线：

- `connector-trino`：作为统一查询平面，承接所有可通过 Trino catalog 暴露的对象，并把 `signal / localization / drilldown` 尽量下推到 Trino
- `connector-jdbc`：作为通用 SQL 接入层，承接 PostgreSQL / MySQL / Hive JDBC / Doris JDBC 等可 SQL 化对象
- `connector-iceberg`：作为首个原生湖仓 connector，优先验证 `snapshot-aware + metadata-first` 路径，并补齐原生数据读取

这意味着：

- 如果对象已经能通过 Trino catalog 暴露，优先使用 `type: trino`，而不是为每个底层系统单独走直连 JDBC
- Hive 与 Doris 当前不做 native connector，而是统一通过 `type: jdbc` 接入
- 当需要利用 `snapshot / manifest / partition summary` 等原生元数据能力时，首版优先支持 Iceberg
- `jdbc <-> iceberg` 当前已可执行真实 `check`，不再默认返回 `PARTIAL`
- compare 核心逻辑只保留在 `core`，connector 只负责读数据、读元数据和暴露能力

JDBC 接入建议显式配置方言，首版支持：

- `postgres`
- `mysql`
- `hive`
- `doris`

示例：

```yaml
source:
  type: jdbc
  url: jdbc:hive2://hive-server:10000/dw
  username: hive
  password: ${HIVE_PASSWORD}
  query: |
    select order_id, amount, dt
    from dw.orders
  options:
    dialect: hive
```

## 命令设计

```bash
data-audit config init -o task.yaml
data-audit config validate -f task.yaml
data-audit doctor -f task.yaml
data-audit check -f task.yaml
data-audit plan  -f task.yaml
data-audit diff  -f task.yaml --slice dt=2026-03-10
data-audit report show ./reports/orders_reconcile/report.json
```

命令分为执行面和运维面：

- `check`：标准执行
- `plan`：只生成比较计划，不执行
- `diff`：对指定 suspect segment 下钻
- `report`：查看或转换报告
- `config init/validate`：创建配置并做默认离线校验
- `doctor`：聚合检查 Java、连接器、SQLite 和输出目录；连接探测必须显式开启

## AI Copilot Alpha

AI Copilot Alpha 的目标是让 AI 参与核验策略规划、风险识别、根因假设和交付表达，但不让 AI 直接判断数据是否一致。最终一致性状态仍然只能来自确定性核验结果，例如 `row_count`、`checksum`、partition stats、bucket diff 和 exact diff。

当前可运行入口是：

```bash
data-audit ai profile --task task.yaml --output table_profile.json --review profile_review.md
data-audit ai plan --task task.yaml --output audit_plan.json
data-audit ai plan --task task.yaml --output audit_plan.json --accept-profile
data-audit ai plan --profile table_profile.json --output audit_plan.json
data-audit ai explain --plan audit_plan.json --result audit_result.json --output root_cause_analysis.json
data-audit ai report --plan audit_plan.json --result audit_result.json --analysis root_cause_analysis.json --template technical --output audit_report.md
data-audit ai repair --plan audit_plan.json --result report.json --analysis root_cause_analysis.json --output repair_plan.json
data-audit ai ask --plan audit_plan.json --result report.json --analysis root_cause_analysis.json --question "这个差异是否阻塞验收？"
data-audit check -f task.yaml --ai-report --ai-report-template technical
```

打包后也会生成独立 AI wrapper：`java -jar dataaudit-ai.jar plan/explain/report/repair/ask ...`，等价于 `java -jar data-audit.jar ai plan/explain/report/repair/ask ...`。本地构建命令和产物路径：

```bash
./mvnw -pl data-audit-cli -am clean package -DskipTests
java -jar data-audit-cli/target/data-audit.jar ai --help
java -jar data-audit-cli/target/dataaudit-ai.jar --help
```

- `data-audit-cli/target/data-audit.jar`：完整 CLI。
- `data-audit-cli/target/dataaudit-ai.jar`：只暴露 AI 子命令的 wrapper。

AI provider 默认关闭，命令会走确定性规则和本地 RAG fallback。需要接入外部结构化 JSON provider、OpenAI-compatible provider 或官方 OpenAI Java SDK provider 时，可以显式传入：

```bash
data-audit ai plan --task task.yaml --output audit_plan.json --ai-provider http-json --ai-endpoint http://localhost:8080/ai
data-audit ai explain --plan audit_plan.json --result audit_result.json --output root_cause_analysis.json --ai-provider http-json --ai-endpoint http://localhost:8080/ai
data-audit ai report --plan audit_plan.json --result audit_result.json --analysis root_cause_analysis.json --template technical --output audit_report.md --ai-provider http-json --ai-endpoint http://localhost:8080/ai
```

OpenAI-compatible provider 示例：

```bash
data-audit ai plan --task task.yaml --output audit_plan.json --ai-provider openai-compatible --ai-endpoint https://your-compatible-base-url --ai-model mimo-v2.5-pro
```

官方 OpenAI SDK provider 示例：

```bash
data-audit ai plan --task task.yaml --output audit_plan.json --ai-provider openai-sdk --ai-model gpt-5.2
```

也可以使用环境变量：`DATAAUDIT_AI_PROVIDER`、`DATAAUDIT_AI_ENDPOINT`、`DATAAUDIT_AI_API_KEY`、`DATAAUDIT_AI_MODEL`。provider request 会携带 compact response schema，provider 输出会先经过结构化校验、SQL 安全检查、必填字段护栏和确定性 baseline 补齐；失败时默认回退到规则路径。API key 不建议写入仓库文件；本地测试时请放在 shell 环境变量或被 `.gitignore` 忽略的 `.env` 文件中。

`check --ai-report` 会先运行确定性 `check`，再在 `output.dir` 下写出 AI sidecar：

- `table_profile.json`
- `profile_review.md`
- `ai_audit_plan.json`
- `root_cause_analysis.json`
- `ai_audit_report_<template>.md`

AI sidecar 生成失败不会改变确定性 check 的退出码或 `report.json` 内容。

默认产品流程是用户只提供现有 `task.yaml`：

```text
task.yaml
  -> 自动构建 table_profile.json
  -> Profile Quality Gate
  -> audit_plan.json / profile_review.md
```

`table_profile.json` 是系统生成的中间画像，不要求用户手写。系统默认读取 task 中的 source、target、object、normalize、semantics 和 output 配置；connector schema/metadata/signal 是优先证据；stats/sample 是有限采集，带超时、最大行数和最大字段数限制。采集失败会降级为 `missing_information`，不会阻断普通 plan。采集限制可通过 `--profile-max-sample-rows`、`--profile-max-sample-fields`、`--profile-timeout-ms` 或 `DATAAUDIT_PROFILE_MAX_SAMPLE_ROWS`、`DATAAUDIT_PROFILE_MAX_SAMPLE_FIELDS`、`DATAAUDIT_PROFILE_TIMEOUT_MS` 调整。

本地 RAG 默认会在存在时加载 `examples/ai-copilot/cases/*.json`，也可以通过 `--rag-corpus` 或 `DATAAUDIT_AI_RAG_CORPUS` 指定历史案例目录。检索模式通过 `--rag-mode lexical|vector|hybrid` 或 `DATAAUDIT_AI_RAG_MODE` 选择，默认 `hybrid`。`vector` 模式当前使用离线确定性的 `HashingEmbeddingClient`，适合 CI 和 demo；后续可在 `EmbeddingClient` 边界后替换为真实 embedding 服务。

`data-audit ai repair` 会生成可 review 的 `repair_plan.json`，并可用 `--task task.yaml --patched-task patched-task.yaml` 输出配置级 task YAML copy。它只会写入 `normalize.timezone`、`semantics.ai.write_mode`、`semantics.ai.sync_mode` 这类安全配置补丁；source/target 数据不会被自动修改。`data-audit ai ask` 是单轮 Q&A，回答会区分 deterministic facts、AI hypotheses 和 recommended checks。

默认不落原始敏感样本，只写脱敏值、hash、模式或统计摘要。真实样本、明文主键、手机号、邮箱、证件号等不应进入 `table_profile.json`。

Profile Quality Gate 有三种状态：

- `CONFIRMED`：画像质量足够，直接生成 `audit_plan.json`。
- `REVIEW_REQUIRED`：只有 key、partition、write_mode、sync_mode、timezone 这些高影响项低置信或冲突时触发；系统写出 `profile_review.md` 和控制台摘要，并停止生成 plan。
- `INSUFFICIENT`：缺少基础 source、target、schema/table/query 或 columns 信息，停止并说明缺失项。

`REVIEW_REQUIRED` 时，用户可以更新 `task.yaml` 后重跑，也可以显式接受当前画像：

```bash
data-audit ai plan --task task.yaml --output audit_plan.json --accept-profile
```

如果需要明确声明 overwrite 写入模式，可以写入：

```yaml
semantics:
  ai:
    write_mode: overwrite
```

显式配置优先于 AI 推断；例如 `object.key`、`object.partition_by`、`normalize.timezone` 和 `semantics.ai.write_mode` 会作为强证据进入 plan。完整设计见 [docs/ai-design.md](docs/ai-design.md)，示例见 `examples/ai-copilot/`。

## 配置示例

下面是一个当前可运行的配置示例：

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

完整设计、全量配置样例和架构细节见 [docs/design.md](docs/design.md)。

## 本地 Java 17 环境

仓库内已经预留项目局部运行方式，不需要改全局 `JAVA_HOME`。

PowerShell 会话里执行：

```powershell
. .\scripts\use-java17.ps1
```

这会把当前会话切到仓库内的 `.tools\jdk-17`，然后打印 `java -version` 和 `mvn -v`。

## 本地真实验证

仓库内提供了不依赖 Docker 的真实验证脚本，会创建两个本地 SQLite 数据库来模拟 `source / target`，然后执行真实的 `plan / check / report show`：

```powershell
. .\scripts\use-java17.ps1
.\scripts\verify-local-sqlite.ps1
```

脚本会自动覆盖当前主链场景：

- `consistent_small`：小表一致，验证 `GLOBAL_CHECKSUM`
- `small_diff`：小表差异，验证 `EXACT_DIFF`
- `partition_mismatch`：大表分片异常，验证 `grouped checksum -> exact diff`
- `bucket_mismatch`：大表按 key hash bucket 缩圈
- `keyless_large_consistent`：无 key 大表一致，验证 `XOR_CHECKSUM_PLUS_SAMPLE`
- `keyless_large_inconclusive`：无 key 大表异常，验证 `XOR_CHECKSUM_PLUS_SAMPLE`
- `unstable_snapshot_jdbc`：边界不稳定时拒绝执行
- `ddl_rename_compatible`：rename mapping 后的一致性校验
- `delete_hard_delete_mismatch`：行数漂移归因为 `row_count_mismatch`

输出产物会写到 `.tmp\verify-local\` 下。

## 第二层测试矩阵

仓库内还提供了第二层验证脚本，用于覆盖 JDBC 方言适配、`jdbc <-> iceberg` 真实对比和 PostgreSQL E2E：

```powershell
. .\scripts\use-java17.ps1
.\scripts\verify-second-layer.ps1
```

如果当前环境没有 Docker，且你希望像第一层一样在本地固定目录落报告和状态文件，可以执行：

```powershell
. .\scripts\use-java17.ps1
.\scripts\verify-second-layer-local.ps1
```

本地第二层场景矩阵包括：

- `postgres_simulated_jdbc`
  - 结果：`CONSISTENT`
  - 路径：`global row_count + global checksum`
  - 说明：无 Docker 环境下用 `dialect: postgres` 走真实 CLI/JDBC 流程，底层由本地 SQLite 承载数据
- `hive_jdbc_partitioned`
  - 结果：`DIFF_FOUND`
  - 路径：`global row_count + grouped checksum -> exact diff`
  - 已定位：`dt=2026-03-10`
- `doris_jdbc_result_diff`
  - 结果：`DIFF_FOUND`
  - 路径：`global row_count + global checksum -> exact diff`
  - 根因：`value_mismatch`
- `jdbc_to_iceberg_consistent`
  - 结果：`CONSISTENT`
  - 路径：`global row_count + grouped checksum`
  - 说明：JDBC 源端与 Iceberg 目标端的真实一致性校验
- `jdbc_to_iceberg_diff`
  - 结果：`DIFF_FOUND`
  - 路径：`global row_count + grouped checksum -> exact diff`
  - 根因：`value_mismatch`
- `iceberg_to_jdbc_partitioned`
  - 结果：`DIFF_FOUND`
  - 路径：`global row_count + grouped checksum -> exact diff`
  - 根因：`value_mismatch`
  - 已定位：`dt=2026-03-10`

脚本执行成功后，验证产物会落到：

- 仓库根目录下的 `.tmp/verify-second-layer`

每个场景下都会生成：

- `report.json`
- `report.html`
- `manifest.json`
- `suspect_slices.csv`
- `row_diff_sample.csv`
- `state.db`

脚本会执行：

- `SqliteDialectCliIntegrationTest`
  - 用真实 `connector-jdbc` 分别验证 `dialect: hive` 和 `dialect: doris`
  - 目标是确认 planner 路径、分段逻辑和报告输出在通用 JDBC 模型下成立
- `ReflectionIcebergMetadataReaderTest`
  - 验证 `connector-iceberg` 能读取本地 Iceberg table 的 snapshot、schema 和 manifest hints
- `IcebergMetadataCliIntegrationTest`
  - 验证 `jdbc -> iceberg` 和 `iceberg -> jdbc` 会进入 `metadata-first` 路径并执行真实 diff
- `JdbcCliIntegrationTest`
  - 使用 Testcontainers 跑 PostgreSQL 真实 JDBC E2E
  - 如果当前环境没有 Docker，脚本会明确标记为 `SKIPPED`

## Quickstart 场景

### 1. 传统小表单次比对

适用于一次性表对表核验、迁移后小表验证、CI 中的小规模结果断言。

默认路径：

```text
global row_count + global checksum -> exact diff(on mismatch)
```

### 2. 大表 / 分区表结果校验

适用于离线任务完成后的分区比对、大表重跑复查、按 `dt` / `biz_date` 追查 suspect slice。

默认路径：

```text
global row_count + grouped checksum -> localization -> exact diff
```

### 3. 湖仓 snapshot / version / instant 校验

适用于 Iceberg / Hudi / Delta / Paimon 等对象在已提交边界后的结果核验。

默认路径：

```text
metadata / routing digest -> localization -> exact diff or sampling
```

推荐直接从 `templates/` 下的样例起步：

- `templates/small-table-once.yaml`
- `templates/big-table-partitioned.yaml`
- `templates/lakehouse-snapshot.yaml`
- `templates/hive-jdbc-partitioned.yaml`
- `templates/doris-jdbc-result.yaml`

### 4. 服务器验证 Quickstart

如果需要在服务器上做一次真实校验，推荐直接走 `单 jar` 模式；如果服务器已经有容器运行环境，也可以走 `单容器` 模式。

#### 4.1 单 jar 部署

1. 准备运行环境

- 服务器需要 `Java 17`
- 建议提前建好工作目录，例如：

```bash
mkdir -p /opt/data-audit/{bin,tasks,reports,state,logs}
```

2. 构建并上传产物

- 在构建机执行：

```bash
./mvnw -q -DskipTests package
```

- 上传以下文件到服务器：
  - `data-audit-cli/target/data-audit.jar`
  - 选定的 `task.yaml`

- `data-audit.jar` 是 fat jar，默认已打包 SQLite / PostgreSQL / MySQL / Trino 以及 Iceberg 所需运行时依赖。

3. 准备任务配置

- 可以直接从 `templates/` 拷贝一份再改：
  - 小表：`templates/small-table-once.yaml`
  - 分区表：`templates/big-table-partitioned.yaml`
  - Hive JDBC：`templates/hive-jdbc-partitioned.yaml`
  - Doris JDBC：`templates/doris-jdbc-result.yaml`
  - Iceberg：`templates/lakehouse-snapshot.yaml`
- 建议把密码放到环境变量里，不要写死在 YAML：

```bash
export SRC_PASSWORD='***'
export TGT_PASSWORD='***'
```

- JDBC 场景建议同时补上这些参数：
  - URL：`connectTimeout`、`socketTimeout`
  - `options.query_timeout_seconds`
  - `options.fetch_size`
  - `options.progress_log_interval_rows`

4. 先跑 `plan`

```bash
cd /opt/data-audit
java -jar ./bin/data-audit.jar plan -f ./tasks/task.yaml
```

这一步主要确认三件事：

- 配置能正常解析
- 边界是否稳定
- planner 选中的路径是否符合预期

5. 再跑 `check`

```bash
java -jar ./bin/data-audit.jar check -f ./tasks/task.yaml
```

当前 `check` 已经会输出阶段日志和 JDBC 读取进度，例如：

- `Stage 1/6: resolving boundary`
- `Stage 3/6: reading source rows for exact diff`
- `JDBC read progress [jdbc:orders]: fetched 5000 rows`
- `Stage 5/6 progress: diffing suspect segment 1/3 [dt=2026-03-10]`

如果需要后台执行，建议：

```bash
nohup java -jar ./bin/data-audit.jar check -f ./tasks/task.yaml > ./logs/check.log 2>&1 &
tail -f ./logs/check.log
```

执行完成后会在 `output.dir` 下生成：

- `report.json`
- `report.html`
- `manifest.json`
- `suspect_slices.csv`
- `row_diff_sample.csv`

运行状态会默认写到 `output.dir/state.db`。

6. 查看结果

```bash
java -jar ./bin/data-audit.jar report show ./reports/<task-name>/report.json
```

7. 对可疑 slice 继续下钻

如果 `report.json` 里有 `result.resume_hint`，可以直接执行类似命令：

```bash
java -jar ./bin/data-audit.jar diff -f ./tasks/task.yaml --slice dt=2026-03-10
```

#### 4.2 单容器部署

如果服务器有 Docker 或兼容运行时，可以先构建镜像：

```bash
docker build -t data-audit:local .
```

运行示例：

```bash
docker run --rm \
  -v /opt/data-audit/tasks:/tasks \
  -v /opt/data-audit/reports:/reports \
  -v /opt/data-audit/state:/state \
  -v /opt/data-audit/logs:/logs \
  -e SRC_PASSWORD='***' \
  -e TGT_PASSWORD='***' \
  data-audit:local \
  version
```

计划验证：

```bash
docker run --rm \
  -v /opt/data-audit/tasks:/tasks \
  -v /opt/data-audit/reports:/reports \
  -v /opt/data-audit/state:/state \
  -v /opt/data-audit/logs:/logs \
  -e SRC_PASSWORD='***' \
  -e TGT_PASSWORD='***' \
  data-audit:local \
  plan -f /tasks/task.yaml
```

正式执行：

```bash
docker run --rm \
  -v /opt/data-audit/tasks:/tasks \
  -v /opt/data-audit/reports:/reports \
  -v /opt/data-audit/state:/state \
  -v /opt/data-audit/logs:/logs \
  -e SRC_PASSWORD='***' \
  -e TGT_PASSWORD='***' \
  data-audit:local \
  check -f /tasks/task.yaml
```

查看报告：

```bash
docker run --rm \
  -v /opt/data-audit/reports:/reports \
  data-audit:local \
  report show /reports/<task-name>/report.json
```

容器镜像声明了 `/tasks`、`/reports`、`/state`、`/logs` 四个运行目录。
任务 YAML 中的 JDBC URL、用户名、密码、Trino query connector URI/用户名/密码
以及 Iceberg URI/warehouse/location 等运行时字段支持 `${ENV_VAR}` 展开。
生产配置建议只在 YAML 中保留 `${SRC_PASSWORD}`、`${TGT_PASSWORD}` 这类引用，
真实值通过容器环境变量或调度器 secret 注入；缺失变量会在打开 connector 前以退出码 `2`
失败，输出只包含变量名和字段路径，不会打印展开后的 secret。

#### 4.3 推荐的服务器验证顺序

第一次上服务器，建议固定按这个顺序跑：

1. `plan`
2. `check`
3. `report show`
4. 必要时 `diff --slice`

推荐先从当前已经稳定的组合开始：

- `jdbc -> jdbc`：完整支持，适合首批生产验证
- `jdbc <-> iceberg`：已支持真实对比，适合继续验证 snapshot 边界与 metadata-first 路径

对性能的当前建议是：

- 小表优先走 `global row_count + global checksum`
- 大表 / 分区表优先走 `grouped checksum -> localization -> exact diff`
- 超大表优先走 `metadata / routing digest -> localization`
- 如果只比较少数列，显式配置 `object.columns` 或直接使用 `query:`

#### 4.4 服务器上的真实示例 `task.yaml`

推荐直接从下面 3 份服务器模板起步：

- `templates/server-mysql-to-doris.yaml`
- `templates/server-hive-to-postgres.yaml`
- `templates/server-jdbc-to-iceberg.yaml`

##### mysql -> doris

适用于 MySQL 明细表或结果表同步到 Doris 后的任务后一致性校验：

```yaml
task:
  name: mysql_to_doris_orders
  mode: post_check

boundary:
  type: job_finish

source:
  type: jdbc
  url: jdbc:mysql://mysql-source.prod:3306/app
  username: app_read
  password: ${SRC_PASSWORD}
  query: |
    select order_id, user_id, status, amount, update_time, dt
    from orders
    where dt = '2026-03-10'
  options:
    dialect: mysql

target:
  type: jdbc
  url: jdbc:mysql://doris-fe.prod:9030/ads
  username: doris_read
  password: ${TGT_PASSWORD}
  table: ads.orders
  options:
    dialect: doris

object:
  key:
    - order_id
  partition_by:
    - dt
  estimated_rows: 5000000

planner:
  scale_override: large

resources:
  max_in_memory_rows: 100000

output:
  dir: /opt/data-audit/reports/mysql_to_doris_orders
```

##### hive -> postgres

适用于 Hive 数仓分区结果同步到 PostgreSQL 后的分区级校验：

```yaml
task:
  name: hive_to_postgres_orders_ads
  mode: post_check

boundary:
  type: partition
  reference: dt=2026-03-10

source:
  type: jdbc
  url: jdbc:hive2://hive-server.prod:10000/dw
  username: hive_read
  password: ${SRC_PASSWORD}
  query: |
    select order_id, shop_id, status, amount, dt
    from dw.orders_ads
    where dt = '2026-03-10'
  options:
    dialect: hive

target:
  type: jdbc
  url: jdbc:postgresql://postgres-ads.prod:5432/ads
  username: ads_read
  password: ${TGT_PASSWORD}
  table: public.orders_ads
  options:
    dialect: postgres

object:
  key:
    - order_id
  partition_by:
    - dt
  estimated_rows: 200000000

planner:
  scale_override: xlarge

resources:
  max_in_memory_rows: 100000

output:
  dir: /opt/data-audit/reports/hive_to_postgres_orders_ads
```

##### jdbc -> iceberg

适用于 JDBC 源端与 Iceberg 目标表之间的 snapshot 边界校验。当前已支持真实比对，planner 会先走 `metadata-first`，再在必要时进入统一的 summary / segment / diff：

```yaml
task:
  name: postgres_to_iceberg_orders
  mode: post_check

boundary:
  type: snapshot
  reference: latest

source:
  type: jdbc
  url: jdbc:postgresql://postgres-source.prod:5432/app
  username: app_read
  password: ${SRC_PASSWORD}
  query: |
    select order_id, status, amount, update_time, dt
    from public.orders
    where dt = '2026-03-10'
  options:
    dialect: postgres

target:
  type: iceberg
  catalog: prod
  catalog_type: hadoop
  warehouse: hdfs:///warehouse/iceberg
  namespace: dw
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
  partition_by:
    - dt
  estimated_rows: 5000000

output:
  dir: /opt/data-audit/reports/postgres_to_iceberg_orders
```

#### 4.5 调度器接入示例

如果要挂到 cron 或调度器后置步骤，建议保留退出码语义：

- `0`：一致
- `1`：发现差异
- `4`：部分完成
- `5`：边界不稳定，拒绝执行

cron 示例：

```bash
0 * * * * cd /opt/data-audit && /usr/bin/java -jar ./bin/data-audit.jar check -f ./tasks/task.yaml >> ./logs/task.log 2>&1
```

## 输出与复查

默认输出目录建议包含：

- `report.json`
- `report.html`
- `suspect_slices.csv`
- `row_diff_sample.csv`
- `manifest.json`

diff sample 的 `key/source_value/target_value` 默认在持久化前脱敏。通过
`output.value_mode` 可选择：

- `masked`：非空值写为 `***`，默认值
- `hash`：写入 SHA-256 摘要，便于跨报告关联；摘要不是加密
- `omit`：不持久化样本值
- `raw`：保留明文，只应在明确授权且访问受控的目录中使用

`slice_key` 和 `resume_hint` 是复查所需的操作值，不受该模式处理，仍可能包含
业务分区信息。HTML 动态内容会自动转义，CSV 单元格会中和电子表格公式前缀。
详细威胁边界见 [SECURITY.md](SECURITY.md)。

`report.json` 建议至少包含以下字段：

- `plan.scale_class`
- `plan.signal_strategy`
- `plan.localization_strategy`
- `plan.boundary`
- `plan.reason`
- `result.root_cause`
- `result.proof_mode`
- `result.confidence`
- `result.no_key_mode`
- `result.fallback_reason`
- `result.suspect_slices`
- `result.resume_hint`

`report show` 的目标不只是展示结果，还要回答两件事：

- 为什么这次会走这条比较路径
- 下次如何拿 suspect slice 继续复查

## 退出码

- `0`：一致
- `1`：发现差异
- `2`：配置错误
- `4`：执行失败
- `5`：边界不稳定

## 部署与接入

默认部署目标只有两类：

- 单 jar
- 单容器

典型接入方式：

- Shell / cron 手工或定时触发
- 调度器任务完成后的 post-hook
- CI / CD 或数据发布后的结果审计步骤

未来可选控制面只预留能力位，不作为当前依赖：

- 报告汇聚与检索
- 任务模板目录
- 策略模板中心
- 多次运行趋势对比

## MVP 路线图

### Milestone 1

- CLI 基础框架
- `task.yaml`
- JDBC / SQL source & target
- planner 自动路径选择
- `L1 schema + L2 summary + L3 segment`
- JSON / HTML / CSV 报告
- SQLite state

### Milestone 2

- `snapshot / version / instant / time_window`
- DML result auditor
- DDL evolution auditor
- suspect slice 精确 diff
- `rename / timezone / precision / null` normalization
- resume / report 复查闭环

### Milestone 3

- Iceberg metadata reader
- Hudi timeline / incremental / CDC evidence reader
- Delta CDF evidence reader
- Paimon snapshot / system table reader
- evidence 模式与根因增强
- 可选控制面能力接入

## 建议仓库结构

```text
data-audit/
├── README.md
├── docs/
│   └── design.md
├── templates/
│   ├── small-table-once.yaml
│   ├── big-table-partitioned.yaml
│   ├── lakehouse-snapshot.yaml
│   └── realtime-window-result.yaml
├── examples/
│   └── ...
├── src/
│   └── ...
└── tests/
    └── ...
```

## 参与贡献

项目使用 Apache License 2.0。提交代码前请阅读
[CONTRIBUTING.md](CONTRIBUTING.md) 和 [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md)，
安全问题请按 [SECURITY.md](SECURITY.md) 私下报告。

本地提交前的基线验证：

```bash
./mvnw verify
python -m pytest -q data-audit-agent
```

版本发布由 `v*` 标签触发，产物包含完整 CLI、AI wrapper、CycloneDX SBOM 和
SHA-256 校验文件。

## 总结

`data-audit` 不是新的同步平台，也不是另一个只会“全表 hash compare”的数据库工具。

它是一个统一的任务后一致性审计 CLI，具备以下特征：

- 兼容传统小表单次比对场景
- 支持大表、分区表和湖仓对象
- 只在稳定边界上校验
- 支持实时任务结果校验
- 理解 DML / DDL 和 schema 演进
- 使用摘要与切片逐层收敛，再由 `exact diff` 做最终裁决
- 保持轻量、可部署、可复查、可扩展

## 参考资料

- `dataCompare`: https://github.com/WJX20/dataCompare
- Apache Iceberg Evolution: https://iceberg.apache.org/docs/latest/evolution/
- Apache Iceberg Spec: https://iceberg.apache.org/spec/
- Flink CDC API: https://nightlies.apache.org/flink/flink-cdc-docs-release-3.5/zh/docs/developer-guide/understand-flink-cdc-api/
- Debezium tombstone / delete behavior: https://debezium.io/documentation/reference/stable/transformations/applying-transformations-selectively.html
- Delta Change Data Feed: https://docs.delta.io/delta-change-data-feed/
- Apache Hudi SQL Queries: https://hudi.apache.org/docs/sql_queries/
- Apache Paimon Append Table: https://paimon.apache.org/docs/0.8/append-table/append-table/
- Apache Paimon Snapshot Spec: https://paimon.apache.org/docs/1.3/concepts/spec/snapshot/
