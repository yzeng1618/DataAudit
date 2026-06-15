# data-audit 功能验证文档

本文档用于回答两个问题：

- 当前已经实现了哪些功能。
- 现阶段应该如何验证这些功能，并判断验证是否通过。

本文以当前代码实现为准，覆盖确定性审计 CLI、连接器、报告、状态存储、AI Copilot Alpha 和本地验证脚本。

## 1. 验证结论摘要

当前项目已经实现一个单机 CLI 形态的任务后一致性审计工具，主命令为：

```powershell
java -jar data-audit-cli/target/data-audit.jar plan  -f task.yaml
java -jar data-audit-cli/target/data-audit.jar check -f task.yaml
java -jar data-audit-cli/target/data-audit.jar diff  -f task.yaml --slice dt=2026-03-10
java -jar data-audit-cli/target/data-audit.jar report show reports/task/report.json
java -jar data-audit-cli/target/data-audit.jar version
```

确定性审计结果以 `report.json` 为准。AI Copilot Alpha 只生成辅助画像、计划、根因分析、修复建议和 Markdown 报告，不直接决定数据一致性。

推荐验证顺序：

1. 跑 Java 17 环境初始化。
2. 跑一层本地 SQLite 主链路验证。
3. 跑二层本地连接器验证。
4. 有 Docker 时补跑 PostgreSQL Testcontainers E2E。
5. 针对实际业务库，按 `plan -> check -> report show -> diff` 验证。
6. 如需 AI 能力，再验证 `ai profile/plan/explain/report/repair/ask` 和 `check --ai-report`。

## 2. 已实现功能清单

### 2.1 确定性 CLI 命令

| 功能 | 命令 | 当前状态 | 验证重点 |
| --- | --- | --- | --- |
| 生成执行计划 | `plan -f task.yaml` | 已实现 | 输出 `scale_class`、`signal_strategy`、`localization_strategy`、`decision_trace`；边界不稳定时退出码为 `5` |
| 执行审计 | `check -f task.yaml` | 已实现 | 生成报告产物；按状态返回退出码 |
| 可疑切片下钻 | `diff -f task.yaml --slice ...` | 已实现 | 指定 slice 后做 `EXACT_DIFF` |
| 报告摘要展示 | `report show report.json` | 已实现 | 从 `report.json` 打印状态、根因、证明方式、置信度、可疑切片 |
| 构建元数据 | `version` | 已实现 | 打印 version、build_time、commit_id 和 Java runtime；缺失元数据为 `unknown` |
| 确定性 check 后生成 AI sidecar | `check --ai-report` | 已实现 | 不改变确定性 `check` 退出码；在输出目录写 AI 旁路文件 |

退出码语义：

| 退出码 | 含义 |
| --- | --- |
| `0` | `CONSISTENT`，一致 |
| `1` | `DIFF_FOUND`，发现差异 |
| `2` | 配置错误或未知状态 |
| `4` | `EXECUTION_FAILED`，执行失败 |
| `5` | `UNSTABLE_BOUNDARY`，边界不稳定 |
| `6` | AI profile 需要人工确认，暂不生成 plan |

### 2.2 Scale-driven 审计路径

| 规模 | 判定方式 | 已实现路径 | 预期证明 |
| --- | --- | --- | --- |
| `SMALL` | `estimated_rows <= 100000` 且 `estimated_bytes <= 300MB`，或 `planner.scale_override: small` | `global row_count + checksum`；不一致再 `exact diff` | 一致时 `GLOBAL_CHECKSUM/HIGH`，差异时 `EXACT_DIFF/EXACT` |
| `LARGE` | 默认档位，或 `planner.scale_override: large` | `global row_count + grouped checksum -> localization -> exact diff` | 有切片时 `GROUPED_CHECKSUM/HIGH`，下钻后 `EXACT_DIFF/EXACT` |
| `XLARGE` | `estimated_rows > 100000000` 或 `estimated_bytes > 30GB`，或 `planner.scale_override: xlarge` | 优先 `routing_digest`，否则 key bucket；无 key 时采样 | `ROUTING_DIGEST/HIGH`、`GROUPED_CHECKSUM/HIGH` 或 `SAMPLING/LOW` |

当前 root cause 固定为：

- `row_count_mismatch`
- `duplicate_or_missing`
- `value_mismatch`
- 边界漂移类拒绝执行表现为 `UNSTABLE_BOUNDARY`，不产出数据类 root cause

### 2.3 Connector 能力

| Connector | 当前状态 | 支持点 | 主要验证 |
| --- | --- | --- | --- |
| `jdbc` | 已实现 | PostgreSQL/MySQL/Hive/Doris 方言；table/query；列投影；query timeout；fetch size；读取进度日志；global/grouped signal | SQLite 模拟 JDBC、方言测试、真实 PostgreSQL E2E |
| `trino` / `sql` | 已实现 | 统一查询平面；Trino JDBC；global/grouped/routing signal；列投影；query/table | 使用 Trino 环境和 `templates/small-table-trino.yaml`、`templates/partitioned-table-trino.yaml` |
| `iceberg` | 已实现主链路 | snapshot boundary；metadata；routing signal；原生数据读取；`jdbc <-> iceberg` diff | `ReflectionIcebergMetadataReaderTest`、`IcebergMetadataCliIntegrationTest`、二层本地脚本 |

当前不应作为正式承诺的能力：

- Hudi/Delta/Paimon 原生 connector。
- 真正流式 diff。
- segment 并行调度。
- 统一内存上限治理。

Hudi/Delta/Paimon 如果被写成 native endpoint type，当前应以配置错误退出，
并提示这些能力仍是 design-reserved。需要走生产验收时，应使用 JDBC、Trino
或已实现的 Iceberg 路径。

### 2.4 报告与状态

`check` 和 `diff` 会在 `output.dir` 下写出：

| 文件 | 用途 |
| --- | --- |
| `report.json` | 主验收文件，包含 plan/result/evidence |
| `report.html` | HTML 可读报告 |
| `manifest.json` | 本次 plan 摘要 |
| `suspect_slices.csv` | 可疑切片列表 |
| `row_diff_sample.csv` | 差异样本 |
| `state.db` | SQLite 运行状态 |

`report.json` 重点字段：

```text
plan.task_name
plan.scale_class
plan.signal_strategy
plan.localization_strategy
plan.boundary
plan.reason
plan.decision_trace
result.status
result.root_cause
result.proof_mode
result.confidence
result.no_key_mode
result.fallback_reason
result.suspect_slices
result.diff
evidence.global_signal
evidence.localization
evidence.exact_diff
```

历史字段不应再出现：

```text
plan.object_class
plan.selected_path
plan.signal_backend
result.schema_issues
result.dml_audit
result.ddl_audit
```

### 2.5 AI Copilot Alpha

已实现命令：

```powershell
java -jar data-audit-cli/target/data-audit.jar ai profile --task task.yaml --output table_profile.json --review profile_review.md
java -jar data-audit-cli/target/data-audit.jar ai plan --task task.yaml --output audit_plan.json
java -jar data-audit-cli/target/data-audit.jar ai plan --task task.yaml --output audit_plan.json --accept-profile
java -jar data-audit-cli/target/data-audit.jar ai plan --profile table_profile.json --output audit_plan.json
java -jar data-audit-cli/target/data-audit.jar ai explain --plan audit_plan.json --result report.json --output root_cause_analysis.json
java -jar data-audit-cli/target/data-audit.jar ai report --plan audit_plan.json --result report.json --analysis root_cause_analysis.json --template technical --output audit_report.md
java -jar data-audit-cli/target/data-audit.jar ai repair --plan audit_plan.json --result report.json --analysis root_cause_analysis.json --output repair_plan.json
java -jar data-audit-cli/target/data-audit.jar ai ask --plan audit_plan.json --result report.json --analysis root_cause_analysis.json --question "这个差异是否阻塞验收？"
```

AI wrapper jar 也已实现：

```powershell
java -jar data-audit-cli/target/dataaudit-ai.jar plan --task task.yaml --output audit_plan.json
```

AI 默认 provider 为 `disabled`，会走本地规则和本地 RAG fallback。可选 provider：

- `disabled`
- `mock`
- `http-json`
- `openai-compatible`
- `openai-sdk`

可选环境变量：

```text
DATAAUDIT_AI_PROVIDER
DATAAUDIT_AI_ENDPOINT
DATAAUDIT_AI_API_KEY
DATAAUDIT_AI_MODEL
DATAAUDIT_AI_RAG_CORPUS
DATAAUDIT_AI_RAG_MODE
DATAAUDIT_PROFILE_MAX_SAMPLE_ROWS
DATAAUDIT_PROFILE_MAX_SAMPLE_FIELDS
DATAAUDIT_PROFILE_TIMEOUT_MS
```

## 3. 验证前准备

### 3.1 本地环境

仓库提供 Java 17 本地切换脚本：

```powershell
. .\scripts\use-java17.ps1
```

预期：

- `java -version` 显示 Java 17。
- `mvn -v` 使用 Java 17。

### 3.2 构建 CLI

```powershell
mvn -q -pl data-audit-cli -am -DskipTests package
```

预期产物：

```text
data-audit-cli/target/data-audit.jar
data-audit-cli/target/dataaudit-ai.jar
```

### 3.3 查看帮助

```powershell
java -jar data-audit-cli/target/data-audit.jar --help
java -jar data-audit-cli/target/data-audit.jar ai --help
java -jar data-audit-cli/target/dataaudit-ai.jar --help
java -jar data-audit-cli/target/data-audit.jar version
```

预期：

- 主 CLI 展示 `plan/check/diff/report/ai`。
- AI 子命令展示 `profile/plan/explain/report/repair/ask`。
- `version` 至少展示 `version`、`build_time`、`commit_id`、`java_version`。
- 本地构建缺少 commit 或 build time 时，对应字段展示 `unknown`，命令退出码为 `0`。

## 4. 一层本地主链路验证

一层验证不依赖 Docker，会创建本地 SQLite 数据库模拟 source/target，然后执行真实 CLI。

### 4.1 执行命令

```powershell
. .\scripts\use-java17.ps1
.\scripts\verify-local-sqlite.ps1
```

### 4.2 覆盖场景和预期

| 场景 | 预期状态 | 预期路径/证明 | 预期重点 |
| --- | --- | --- | --- |
| `consistent_small` | `CONSISTENT` | `SMALL` + `GLOBAL_CHECKSUM` + `HIGH` | 小表一致短路，不做 exact diff |
| `small_diff` | `DIFF_FOUND` | `SMALL` + `EXACT_DIFF` + `EXACT` | 小表 checksum 不一致后进入精确 diff |
| `keyless_multiset` | `DIFF_FOUND` | `SMALL` + `EXACT_DIFF` + `EXACT` | 无 key 小表 multiset 差异识别为 `duplicate_or_missing` |
| `partition_mismatch` | `DIFF_FOUND` | `LARGE` + `partition_window` + `EXACT_DIFF` | 定位 `dt=2026-03-10` |
| `bucket_mismatch` | `DIFF_FOUND` | `LARGE` + `key_hash_bucket` + `EXACT_DIFF` | 按 key bucket 缩圈 |
| `keyless_large_consistent` | `CONSISTENT` | `XOR_CHECKSUM_PLUS_SAMPLE` + `MEDIUM` | 无 key 大表 fallback |
| `keyless_large_inconclusive` | `DIFF_FOUND` | `XOR_CHECKSUM_PLUS_SAMPLE` + `MEDIUM` | `no_key_mode=true`，`fallback_reason=no_key_xor_fallback` |
| `unstable_snapshot_jdbc` | `UNSTABLE_BOUNDARY` | 退出码 `5` | JDBC 不支持 snapshot 边界，拒绝执行 |
| `ddl_rename_compatible` | `CONSISTENT` | `GLOBAL_CHECKSUM` + `HIGH` | rename mapping、decimal scale、大小写归一化 |
| `delete_hard_delete_mismatch` | `DIFF_FOUND` | `EXACT_DIFF` + `EXACT` | 行数漂移归因为 `row_count_mismatch` |

### 4.3 产物路径

```text
.tmp/verify-local/<scenario>/reports/report.json
.tmp/verify-local/<scenario>/reports/report.html
.tmp/verify-local/<scenario>/reports/manifest.json
.tmp/verify-local/<scenario>/reports/suspect_slices.csv
.tmp/verify-local/<scenario>/reports/row_diff_sample.csv
.tmp/verify-local/<scenario>/reports/state.db
```

### 4.4 通过标准

脚本整体退出码为 `0`，并且每个场景满足：

- `report.json`、`report.html`、`manifest.json`、`suspect_slices.csv`、`row_diff_sample.csv`、`state.db` 都存在。
- `result.status` 符合场景预期。
- `plan.scale_class`、`plan.signal_strategy`、`plan.localization_strategy` 符合场景预期。
- `result.proof_mode`、`result.confidence`、`result.root_cause` 符合场景预期。
- 历史字段不出现在报告里。

### 4.5 资源治理 fixture

资源治理的本地 SQLite fixture 用 JUnit 直接驱动真实 CLI：

```powershell
. .\scripts\use-java17.ps1
mvn -q -pl data-audit-it -am -Dtest=ResourceGovernanceCliIntegrationTest test
```

通过标准：

- 命令退出码为 `0`。
- fixture 内部的 `data-audit check` 退出码为 `1`。
- `report.json.result.status=DIFF_FOUND`。
- `report.json.result.proof_mode=EXACT_DIFF`。
- `report.json.result.diff.resource_bounded=true`。
- `report.json.result.diff.limit_exceeded=false`。
- diff samples 不超过 `resources.max_diff_samples=2`。
- `report.json.evidence.progress_events` 包含 `exact_diff` 阶段事件。
- 本地 SQLite fixture 耗时小于 `15s`。

## 5. 二层连接器验证

二层验证覆盖 JDBC 方言、Iceberg metadata、`jdbc <-> iceberg` 真实对比。

### 5.1 本地二层验证

不依赖 Docker，推荐日常先跑：

```powershell
. .\scripts\use-java17.ps1
.\scripts\verify-second-layer-local.ps1
```

覆盖场景：

| 场景 | 预期状态 | 预期路径/证明 | 预期重点 |
| --- | --- | --- | --- |
| `postgres_simulated_jdbc` | `CONSISTENT` | `GLOBAL_CHECKSUM/HIGH` | PostgreSQL 方言路径 |
| `mysql_simulated_jdbc` | `CONSISTENT` | `GLOBAL_CHECKSUM/HIGH` | MySQL 方言路径 |
| `hive_jdbc_partitioned` | `DIFF_FOUND` | `partition_window -> EXACT_DIFF` | Hive JDBC 分区差异 |
| `doris_jdbc_result_diff` | `DIFF_FOUND` | `SMALL -> EXACT_DIFF` | Doris JDBC 结果差异 |
| `jdbc_to_iceberg_consistent` | `CONSISTENT` | `GROUPED_CHECKSUM/HIGH` | JDBC 到 Iceberg 一致 |
| `jdbc_to_iceberg_diff` | `DIFF_FOUND` | `partition_window -> EXACT_DIFF` | JDBC 到 Iceberg 差异 |
| `iceberg_to_jdbc_partitioned` | `DIFF_FOUND` | `partition_window -> EXACT_DIFF` | Iceberg 到 JDBC 差异 |

产物路径：

```text
.tmp/verify-second-layer/<scenario>/reports/report.json
.tmp/verify-second-layer/<scenario>/reports/report.html
.tmp/verify-second-layer/<scenario>/reports/manifest.json
.tmp/verify-second-layer/<scenario>/reports/suspect_slices.csv
.tmp/verify-second-layer/<scenario>/reports/row_diff_sample.csv
.tmp/verify-second-layer/<scenario>/reports/state.db
```

### 5.2 Maven 二层验证

```powershell
. .\scripts\use-java17.ps1
.\scripts\verify-second-layer.ps1
```

脚本会执行：

| 验证项 | 命令范围 | 说明 |
| --- | --- | --- |
| Hive/Doris JDBC adapter validation | `SqliteDialectCliIntegrationTest` | 用 SQLite 承载数据，验证 Hive/Doris 方言和 CLI 流程 |
| Iceberg metadata reader unit validation | `ReflectionIcebergMetadataReaderTest` | 验证 Iceberg snapshot/schema/manifest hints |
| Iceberg mixed JDBC CLI validation | `IcebergMetadataCliIntegrationTest` | 验证 `jdbc -> iceberg`、`iceberg -> jdbc` |
| PostgreSQL real JDBC E2E | `JdbcCliIntegrationTest` | 有 Docker 时通过 Testcontainers 跑真实 PostgreSQL |

如果当前环境没有 Docker，PostgreSQL E2E 会显示 `SKIPPED`。这不代表二层本地验证失败，但生产前仍建议在有 Docker 或真实 PostgreSQL 的环境补跑。

## 6. 单模块测试验证

需要定位问题或缩短反馈时间时，可以按模块执行。

### 6.1 Core 主逻辑

```powershell
mvn -q -pl data-audit-core -am test
```

重点覆盖：

- `ScaleClassifierTest`
- `PlanningServiceTest`
- `ExecutionServiceScalePipelineTest`
- `SignalEngineTest`
- `LocalizationEngineTest`
- `RootCauseEngineTest`
- `BoundaryGateTest`
- `SpecValidatorTest`

### 6.2 JDBC Connector

```powershell
mvn -q -pl data-audit-connector-jdbc -am test
```

重点覆盖：

- SQL 方言解析。
- PostgreSQL/MySQL/Hive/Doris 方言行为。

### 6.3 Iceberg Connector

```powershell
mvn -q -pl data-audit-connector-iceberg -am test
```

重点覆盖：

- 本地 Iceberg metadata 读取。
- snapshot、schema、manifest hints。

### 6.4 AI Copilot

```powershell
mvn -q -pl data-audit-ai,data-audit-cli -am test
```

重点覆盖：

- profile 收集与质量门禁。
- AI plan 生成。
- explain/report/repair/ask。
- RAG 检索。
- SQL 安全与结构护栏。

## 7. 服务器或真实数据源验证

真实数据源建议固定按以下顺序执行。

### 7.1 构建并部署 jar

```powershell
mvn -q -DskipTests package
```

上传或复制：

```text
data-audit-cli/target/data-audit.jar
task.yaml
```

服务器建议目录：

```text
/opt/data-audit/bin
/opt/data-audit/tasks
/opt/data-audit/reports
/opt/data-audit/state
/opt/data-audit/logs
```

### 7.2 准备 task.yaml

优先从模板复制：

```text
templates/small-table-trino.yaml
templates/partitioned-table-trino.yaml
templates/jdbc-fallback.yaml
templates/iceberg-snapshot-native.yaml
templates/large-table-nokey.yaml
templates/server-mysql-to-doris.yaml
templates/server-hive-to-postgres.yaml
templates/server-jdbc-to-iceberg.yaml
```

注意：

- 当前 YAML 中的 `${PASSWORD}` 不会自动展开。正式运行前需要渲染成真实运行时 YAML，或者直接填入运行时密码文件。
- JDBC 场景建议显式配置 `query_timeout_seconds`、`fetch_size`、`progress_log_interval_rows`；也可以用 `resources.query_timeout_millis` 作为 JDBC/Trino 的统一查询超时默认值。
- 生产任务建议显式配置 `resources.max_in_memory_rows`、`resources.max_diff_samples`、`resources.global_timeout_millis`、`resources.segment_parallelism`。segment 并发默认是 `1`，不要在未评估数据库容量前调高。
- 小表优先写 `query:`，大表分区优先配置 `object.partition_by`。
- 不要把高基数字段如 `id/order_id/uuid` 当作分区切片字段。

### 7.3 先跑 plan

```bash
java -jar /opt/data-audit/bin/data-audit.jar plan -f /opt/data-audit/tasks/task.yaml
```

验收点：

- 退出码为 `0`，除非预期验证不稳定边界。
- `scale_class` 与数据规模预期一致。
- `signal_strategy` 与场景预期一致。
- `localization_strategy` 与切片/主键配置一致。
- `decision_trace` 能解释 planner 选择。

如果 `plan` 阶段失败，优先排查：

- connector type 是否支持。
- JDBC URL、账号、密码、网络是否可用。
- `query_connector` 是否为 Trino endpoint 配齐。
- Iceberg `location` 或 `catalog/warehouse/namespace/table` 是否正确。
- `snapshot` 边界是否被 connector 支持。

### 7.4 再跑 check

```bash
java -jar /opt/data-audit/bin/data-audit.jar check -f /opt/data-audit/tasks/task.yaml
```

后台执行示例：

```bash
nohup java -jar /opt/data-audit/bin/data-audit.jar check -f /opt/data-audit/tasks/task.yaml > /opt/data-audit/logs/check.log 2>&1 &
tail -f /opt/data-audit/logs/check.log
```

验收点：

- 一致场景返回 `0`，报告 `result.status=CONSISTENT`。
- 差异场景返回 `1`，报告 `result.status=DIFF_FOUND`。
- 边界不稳定场景返回 `5`，报告 `result.status=UNSTABLE_BOUNDARY`。
- 报告产物完整生成。

日志中可关注当前已实现的读取进度日志：

```text
JDBC read start [...]
JDBC read progress [...]: fetched N rows
JDBC read complete [...]: rows=N, elapsedMs=M
Trino read start [...]
Trino read complete [...]: rows=N, elapsedMs=M
```

### 7.5 查看报告

```bash
java -jar /opt/data-audit/bin/data-audit.jar report show /opt/data-audit/reports/<task>/report.json
```

验收点：

- 控制台能打印 `runId/status/scaleClass/signalStrategy/localizationStrategy/rootCause/proofMode/confidence`。
- `report show` 的退出码与 `report.json.result.status` 对齐。

### 7.6 下钻可疑 slice

如果 `report.json.result.suspect_slices` 非空，例如：

```json
[
  {
    "slice_key": "dt=2026-03-10"
  }
]
```

执行：

```bash
java -jar /opt/data-audit/bin/data-audit.jar diff -f /opt/data-audit/tasks/task.yaml --slice dt=2026-03-10
```

验收点：

- `result.proof_mode=EXACT_DIFF`。
- `result.confidence=EXACT`。
- `row_diff_sample.csv` 有差异样本，或报告说明 slice 已一致。

## 8. 典型业务场景验收口径

### 8.1 小表一致

推荐配置：

- `object.estimated_rows <= 100000`
- 配置 `object.key`
- 明确 `object.columns`
- source/target 尽量使用对称 `query:`

预期：

```text
result.status=CONSISTENT
plan.scale_class=SMALL
plan.signal_strategy=global_row_count_plus_checksum
plan.localization_strategy=none
result.proof_mode=GLOBAL_CHECKSUM
result.confidence=HIGH
result.root_cause=null
```

### 8.2 小表差异

预期：

```text
result.status=DIFF_FOUND
plan.scale_class=SMALL
result.proof_mode=EXACT_DIFF
result.confidence=EXACT
result.root_cause=value_mismatch 或 row_count_mismatch 或 duplicate_or_missing
row_diff_sample.csv 非空
```

### 8.3 大表分区差异

推荐配置：

- `object.estimated_rows` 设置为大表数量级。
- `object.partition_by` 设置低基数字段，如 `dt`。
- source/target 限定同一业务范围。

预期：

```text
plan.scale_class=LARGE
plan.signal_strategy=global_row_count_plus_grouped_checksum
plan.localization_strategy=partition_window
result.status=DIFF_FOUND
result.suspect_slices[0].slice_key=dt=<日期>
result.proof_mode=EXACT_DIFF
result.confidence=EXACT
```

### 8.4 大表有 key 但无分区

预期：

```text
plan.scale_class=LARGE
plan.localization_strategy=key_hash_bucket
result.suspect_slices[*].slice_key=bucket=<N>
```

### 8.5 大表无 key fallback

预期：

```text
plan.scale_class=LARGE
plan.localization_strategy=no_key_xor
result.no_key_mode=true
result.fallback_reason=no_key_xor_fallback
result.proof_mode=XOR_CHECKSUM_PLUS_SAMPLE
result.confidence=MEDIUM
```

### 8.6 超大表无 key fallback

预期：

```text
plan.scale_class=XLARGE
plan.localization_strategy=proportional_sampling
result.no_key_mode=true
result.fallback_reason=xlarge_sampling_fallback
result.proof_mode=SAMPLING
result.confidence=LOW
```

### 8.7 JDBC snapshot 边界拒绝

当 `boundary.type=snapshot` 且 endpoint 为 `jdbc` 或 `trino` 时，当前预期是拒绝：

```text
plan.refuse_reason=unstable_boundary
result.status=UNSTABLE_BOUNDARY
exit code=5
```

### 8.8 Iceberg snapshot

当 endpoint 为 `iceberg` 且配置了 `boundary.type=snapshot`：

预期：

- 可解析 snapshot 边界。
- 读取 schema/metadata/manifest hints。
- 可执行 `jdbc -> iceberg` 或 `iceberg -> jdbc` 真实 diff。
- 差异场景可定位 `dt=...` 等切片。

## 9. AI Copilot 验证

### 9.1 使用示例数据验证 AI 命令链路

可使用 `examples/ai-copilot/` 下的示例文件。

示例：

```powershell
$case = "examples\ai-copilot\small-table-with-key"

java -jar data-audit-cli/target/data-audit.jar ai profile `
  --task "$case\task.yaml" `
  --output "$case\table_profile.generated.json" `
  --review "$case\profile_review.generated.md"

java -jar data-audit-cli/target/data-audit.jar ai plan `
  --task "$case\task.yaml" `
  --output "$case\audit_plan.generated.json" `
  --accept-profile
```

如果已有 `audit_plan.json` 和 `audit_result.json`：

```powershell
java -jar data-audit-cli/target/data-audit.jar ai explain `
  --plan examples\ai-copilot\checksum-mismatch\audit_plan.json `
  --result examples\ai-copilot\checksum-mismatch\audit_result.json `
  --output .tmp\ai-root-cause.json

java -jar data-audit-cli/target/data-audit.jar ai report `
  --plan examples\ai-copilot\checksum-mismatch\audit_plan.json `
  --result examples\ai-copilot\checksum-mismatch\audit_result.json `
  --analysis .tmp\ai-root-cause.json `
  --template technical `
  --output .tmp\ai-report.md
```

预期：

- `profile` 生成 `table_profile.json` 和 `profile_review.md`。
- `plan` 正常时退出码 `0`；需要确认时退出码 `6`，并不生成 plan。
- `explain` 生成 `root_cause_analysis.json`。
- `report` 生成 Markdown。
- `repair` 生成 `repair_plan.json`，如传入 `--patched-task`，只写安全配置补丁。
- `ask` 在控制台输出回答；传入 `--output` 时额外写 JSON。

### 9.2 验证 check --ai-report

先准备一个能成功跑完确定性 `check` 的 task，然后执行：

```powershell
java -jar data-audit-cli/target/data-audit.jar check -f task.yaml --ai-report --ai-report-template technical
```

预期：

确定性报告仍生成：

```text
report.json
report.html
manifest.json
suspect_slices.csv
row_diff_sample.csv
state.db
```

AI sidecar 额外生成：

```text
table_profile.json
profile_review.md
ai_audit_plan.json
root_cause_analysis.json
ai_audit_report_technical.md
```

验收重点：

- AI sidecar 失败时，控制台应提示 AI 生成失败，但不改变确定性 check 的退出码和 `report.json`。
- AI 报告中的一致性结论必须引用确定性事实，例如 `status/proof_mode/confidence/suspect_slices`。

## 10. 生产验收 Checklist

每个业务任务建议按下面清单验收：

- `task.yaml` 中 source/target 查询范围对称。
- `object.columns` 明确，只包含需要比较的列。
- 小表配置 key；大表配置低基数 `partition_by` 或至少有 key。
- JDBC URL、账号、密码、网络连通已确认。
- `plan` 退出码符合预期。
- `plan.scale_class` 符合数据规模。
- `plan.signal_strategy` 和 `plan.localization_strategy` 符合预期。
- `check` 退出码符合预期。
- `report.json/result.status` 与退出码一致。
- 差异场景有 `root_cause`、`proof_mode`、`confidence`。
- 大表差异场景有 `suspect_slices`。
- `report.html`、`manifest.json`、`suspect_slices.csv`、`row_diff_sample.csv`、`state.db` 都生成。
- 对可疑切片执行过 `diff --slice`，并确认 `EXACT_DIFF/EXACT`。
- 报告中没有历史字段 `object_class/selected_path/signal_backend/schema_issues/dml_audit/ddl_audit`。
- 如启用 AI，只把 AI 输出作为解释和建议，不作为一致性裁决来源。

## 10.1 Artifact Contract 验证

产品化后的 artifact contract 以 `docs/artifact-contracts.md` 为准。

新增或修改报告相关代码后，需要确认：

- 新写出的 `report.json` 包含 `artifact_version`、`artifact_type`、
  `producer`、`schema_version`、`task_name`、`run_id` 和 `created_at`。
- `report.json` 仍保留原有 `plan/result/evidence` 结构。
- 旧版没有 artifact metadata 的 `report.json` 仍可被 `data-audit report show`
  读取。
- AI JSON sidecar 包含 `producer=data-audit-ai` 和对应 `artifact_type`。
- `check --ai-report` 不改变确定性 `report.json` 和 `check` 退出码。
- LangGraph Agent 或其他外部消费者只能读取 locked facts，不能改写
  `result.status`、`result.proof_mode`、`result.confidence`、
  `result.root_cause`、`result.suspect_slices` 或 `result.diff`。

最小契约测试命令：

```powershell
. .\scripts\use-java17.ps1
mvn -q -pl data-audit-report,data-audit-cli -am `
  -Dtest=ReportArtifactContractTest,DataAuditAiCommandTest `
  '-Dsurefire.failIfNoSpecifiedTests=false' test
```

## 10.2 Runtime Hardening 验证

产品化运行时硬化以 `openspec/changes/productize-cli-runtime-hardening` 为准。

新增或修改 CLI 配置加载、连接器校验、打包或部署文档后，需要确认：

- `task.yaml` 中受支持字段的 `${ENV_VAR}` 会在验证和执行前展开。
- 缺失环境变量时，`plan/check/diff` 在打开 connector 前失败，退出码为 `2`。
- 错误输出只包含变量名和字段路径，不打印展开后的 secret 值。
- `source.type` 或 `target.type` 配置为 native `hudi`、`delta`、`paimon`
  时，以配置错误退出并提示 design-reserved。
- JDBC、Trino、Iceberg 的有效配置仍通过 `SpecValidator`。
- `data-audit version` 打印 `version`、`build_time`、`commit_id`、
  `java_version`；缺失元数据展示 `unknown` 且退出码为 `0`。
- `hs_err_pid*.log` 和 `replay_pid*.log` 不再出现在 `git status --short`
  的未跟踪文件中。

最小 runtime hardening 测试命令：

```powershell
. .\scripts\use-java17.ps1
mvn -q -pl data-audit-cli,data-audit-core -am `
  -Dtest=DataAuditRuntimeHardeningTest,SpecValidatorTest `
  '-Dsurefire.failIfNoSpecifiedTests=false' test
```

单容器部署验收时，推荐固定挂载：

```text
/tasks
/reports
/state
/logs
```

并按 `version -> plan -> check -> report show` 顺序验证。生产 secret
应通过环境变量或调度器 secret 注入，任务文件中只保留 `${SRC_PASSWORD}`、
`${TGT_PASSWORD}` 这类受支持运行时字段引用；AI provider key 继续通过
`DATAAUDIT_AI_API_KEY` 或 CLI 参数注入。

## 11. 常见失败与排查

| 现象 | 常见原因 | 排查方式 |
| --- | --- | --- |
| `plan` 配置错误退出 | 缺少 `task.name`、`output.dir`、endpoint 必填字段 | 对照 `SpecValidator` 要求检查 YAML |
| `snapshot` 边界返回 `UNSTABLE_BOUNDARY` | JDBC/Trino endpoint 不支持 snapshot | Iceberg 使用 snapshot；JDBC/Trino 使用 `job_finish` |
| 小表误走大表路径 | `estimated_rows` 过大或未配置 | 配置正确估算，必要时用 `planner.scale_override: small` |
| 大表切片过多很慢 | 把唯一键当作分区字段 | `partition_by` 改为 `dt/biz_date/bucket_id` |
| `table:` 模式读取太慢 | 默认可能读取全列 | 改用 `query:` 或显式 `object.columns` |
| 连接器卡住 | DB 查询慢或 JDBC/Trino 没超时 | 配置 `resources.query_timeout_millis` 或 JDBC `query_timeout_seconds`、`fetch_size`，直接在 DB 执行同 SQL |
| report 出现 `limit_exceeded` | exact diff 超过内存或全局超时预算 | 查看 `evidence.progress_events.limit_type`，调大 `resources.max_in_memory_rows` / `resources.global_timeout_millis`，或改用更低基数的分区切片 |
| YAML 密码未生效 | 环境变量未注入或变量名拼错 | 确认 shell/容器/调度器中存在同名变量；缺失时 CLI 会以退出码 `2` 失败 |
| 配置 Hudi/Delta/Paimon native type 失败 | 这些 native connector 仍是 design-reserved | 改用 JDBC、Trino 或 Iceberg 路径 |
| AI plan 退出码 `6` | profile 质量门禁需要确认 | 补全 task 配置，或用 `--accept-profile` |

## 12. 最小验收命令集

日常开发最小集：

```powershell
. .\scripts\use-java17.ps1
mvn -q -pl data-audit-core,data-audit-it -am test
mvn -q -pl data-audit-cli -am -DskipTests package
.\scripts\verify-local-sqlite.ps1
.\scripts\verify-second-layer-local.ps1
```

合并前建议：

```powershell
. .\scripts\use-java17.ps1
mvn -q test
.\scripts\verify-local-sqlite.ps1
.\scripts\verify-second-layer-local.ps1
.\scripts\verify-second-layer.ps1
```

真实环境验收最小集：

```bash
java -jar data-audit.jar plan -f task.yaml
java -jar data-audit.jar check -f task.yaml
java -jar data-audit.jar report show reports/<task>/report.json
java -jar data-audit.jar diff -f task.yaml --slice <slice-from-report>
```
