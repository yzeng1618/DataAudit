# data-audit 配置参考

本文只描述当前代码能够严格解析和执行的 v1 配置。完整样例位于
[`templates/`](../templates/)，新用户建议先让 CLI 生成一份最小配置：

```bash
java -jar data-audit.jar config init -o task.yaml
java -jar data-audit.jar config validate -f task.yaml
```

`config validate` 默认只解析 YAML、展开环境变量并做语义校验，不会连接数据源。
只有显式增加 `--test-connection` 才会打开 source/target connector 并读取 schema：

```bash
java -jar data-audit.jar config validate -f task.yaml --test-connection
java -jar data-audit.jar doctor -f task.yaml --format json
```

## 1. 最小 JDBC 示例

```yaml
task:
  name: orders_reconcile
  description: Compare source and target orders after the job completes.
  mode: post_check

boundary:
  type: job_finish
  reference: latest

source:
  type: jdbc
  url: jdbc:postgresql://source.example:5432/app
  username: audit_reader
  password: ${SOURCE_PASSWORD}
  table: public.orders
  options:
    dialect: postgres

target:
  type: jdbc
  url: jdbc:postgresql://target.example:5432/warehouse
  username: audit_reader
  password: ${TARGET_PASSWORD}
  table: public.orders
  options:
    dialect: postgres

object:
  key:
    - order_id
  columns:
    - order_id
    - status
    - amount
    - updated_at
  estimated_rows: 100000

normalize:
  timezone: UTC
  trim_string: false
  empty_as_null: false
  decimal_scale:
    amount: 2

resources:
  max_in_memory_rows: 100000
  max_diff_samples: 100
  query_timeout_millis: 30000
  segment_parallelism: 1

output:
  dir: ./reports/orders_reconcile
  format:
    - json
    - html
    - csv
  value_mode: masked
```

## 2. 顶层字段

当前支持以下顶层字段：

| 字段 | 作用 |
| --- | --- |
| `task` | 任务名称、描述和运行模式 |
| `boundary` | 本次核验使用的稳定数据边界 |
| `query_connector` | `sql`/`trino` endpoint 使用的 Trino 查询平面 |
| `source` / `target` | 被核验的两个 endpoint |
| `object` | key、比较列、切片列、规模估计和路由提示 |
| `planner` | 可选的规模档位覆盖 |
| `normalize` | 时区、字符串、空值、大小写和小数精度规则 |
| `semantics` | DML、DDL 和 AI 语义提示 |
| `resources` | 内存、样本、超时和并行度边界 |
| `output` | 产物目录、格式和证据值保护方式 |

未知字段会导致严格解析失败。不要再使用旧配置中的
`planner.mode`、`planner.hints`、`compare`、`state`、`normalization` 或
`object.columns.include`；当前字段分别是 `planner.scale_override`、
`resources`、`normalize` 和 `object.columns`。

## 3. Endpoint

### 3.1 JDBC

`jdbc` 必须配置 `url`，并在 `table` 与 `query` 中至少配置一个：

```yaml
source:
  type: jdbc
  url: jdbc:mysql://mysql.example:3306/app
  username: audit_reader
  password: ${SOURCE_PASSWORD}
  query: |
    select order_id, status, amount, dt
    from orders
    where dt = '2026-07-27'
  options:
    dialect: mysql
    fetch_size: 1000
    query_timeout_seconds: 30
    progress_log_interval_rows: 10000
```

内置 JDBC 方言包括 `postgres`、`mysql`、`hive` 和 `doris`。当 endpoint
使用 `table` 时，`object.columns` 会作为比较列和读取投影；复杂过滤、函数或
底层系统兼容性要求较高时，优先使用显式 `query`。

### 3.2 Trino 查询平面

`type: trino` 是推荐写法，`type: sql` 是兼容别名。两者都需要
`query_connector`：

```yaml
query_connector:
  type: trino
  uri: http://trino.example:8080
  user: data_audit
  password: ${TRINO_PASSWORD}
  catalog: hive
  schema: analytics

source:
  type: trino
  catalog: mysql
  schema: app
  table: orders

target:
  type: trino
  catalog: iceberg
  schema: warehouse
  table: orders
```

`query_connector.type` 当前只能是 `trino`。每个 Trino endpoint 同样需要
`table` 或 `query`。

### 3.3 Iceberg

Iceberg 原生 endpoint 用于 snapshot、schema 和 metadata-first 路径：

```yaml
target:
  type: iceberg
  catalog: prod
  catalog_type: hadoop
  warehouse: hdfs:///warehouse/iceberg
  namespace: dw
  table: orders
  snapshot_id: latest
```

也可以通过 `location` 指向表位置。`table` 与 `location` 至少配置一个。
Hudi、Delta 和 Paimon 的原生 connector 仍是设计预留；如果对象已由 Trino
catalog 暴露，可通过 Trino 查询平面核验结果数据。

## 4. 对象、规划与资源边界

```yaml
object:
  key:
    - order_id
  columns:
    - order_id
    - status
    - amount
    - dt
  partition_by:
    - dt
  group_by:
    - region
  estimated_rows: 5000000
  estimated_bytes: 2147483648
  routing_strategy: hash_bucket

planner:
  scale_override: large

resources:
  max_in_memory_rows: 100000
  max_diff_samples: 100
  global_timeout_millis: 600000
  query_timeout_millis: 30000
  segment_parallelism: 2
```

- `key` 是 exact diff 的稳定行键；没有稳定 key 时会进入受限证明或采样路径。
- `columns` 是参与读取、归一化和比较的列。
- `partition_by` 与 `group_by` 用于缩小异常范围，优先选择低基数、可裁剪的列。
- `estimated_rows` / `estimated_bytes` 帮助 planner 判断规模。
- `scale_override` 只能是 `small`、`large` 或 `xlarge`，通常应让 planner 自动判断。
- `max_in_memory_rows`、`max_diff_samples` 必须为正数。
- 两类超时使用毫秒，`0` 表示不设置该资源超时。
- `segment_parallelism` 至少为 `1`。

## 5. 归一化与语义

```yaml
normalize:
  timezone: UTC
  trim_string: true
  empty_as_null: false
  case_insensitive_columns:
    - status
  decimal_scale:
    amount: 2

semantics:
  dml:
    insert: completeness
    update: latest_state
    delete:
      mode: hard_delete
    merge: latest_state
  ddl:
    mode: compatible
    rename_mapping:
      old_status: status
    partition_evolution: allow
  ai:
    sync_mode: batch
    write_mode: upsert
    metric_fields:
      - amount
    enum_fields:
      - status
    time_fields:
      - updated_at
```

`normalize` 控制确定性比较前的规范化。`semantics` 为 DML/DDL 根因分类和 AI
sidecar 提供上下文，不会授权工具修改 source 或 target。

## 6. 证据值保护

`output.value_mode` 控制持久化到 JSON、HTML、CSV、SQLite 状态以及 AI sidecar
之前的 diff sample key/source/target 值：

| 模式 | 行为 | 建议 |
| --- | --- | --- |
| `masked` | 非空值写为 `***` | 默认，适合大多数环境 |
| `hash` | 写为带 `sha256:` 前缀的摘要 | 需要跨报告关联同一值时使用 |
| `omit` | 不持久化这些样本值 | 高敏感环境 |
| `raw` | 保留明文 | 仅在受控目录、明确授权时使用 |

`hash` 不是加密，低基数值仍可能被猜测。`slice_key` 和 `resume_hint` 是下钻所需
的操作值，不受 `value_mode` 处理，可能包含业务分区值。报告目录仍需配置访问
控制、保留期限和安全删除策略。

HTML 报告会自动转义动态内容；CSV 会中和以 `=`, `+`, `-`, `@`、制表符或回车
开头的单元格，降低电子表格公式注入风险。

## 7. 环境变量

支持 `${NAME}` 占位符的字段包括：

- `query_connector.uri/user/password`
- `source` 与 `target` 的 `url/username/password/uri/warehouse/location`

缺失变量会在连接器打开前作为配置错误返回，展开后的密码不会写入正常命令输出。
不要把真实凭据提交到任务模板、测试 fixture 或 issue。

## 8. 命令与退出码

```bash
java -jar data-audit.jar config validate -f task.yaml
java -jar data-audit.jar plan -f task.yaml
java -jar data-audit.jar check -f task.yaml
java -jar data-audit.jar diff -f task.yaml --slice dt=2026-07-27
java -jar data-audit.jar report show ./reports/orders_reconcile/report.json
```

`plan`、`check` 和 `diff` 会访问配置的数据源；先用离线
`config validate` 检查配置结构。

- `0`：命令成功或核验一致
- `1`：发现差异
- `2`：配置或命令参数错误
- `4`：执行失败、连接探测失败或 doctor 未通过
- `5`：边界不稳定，拒绝执行

## 9. 模板选择

- 首次使用：`config init` 或 `templates/jdbc-fallback.yaml`
- Trino 小表：`templates/small-table-trino.yaml`
- Trino 分区表：`templates/partitioned-table-trino.yaml`
- JDBC 分区表：`templates/big-table-partitioned.yaml`
- 无 key 大表：`templates/large-table-nokey.yaml`
- Iceberg snapshot：`templates/iceberg-snapshot-native.yaml`
- 超大表采样回退：`templates/xlarge-sampling-fallback.yaml`
- 服务器场景：`templates/server-*.yaml`

复制模板后先执行 `config validate`。如果需要确认驱动、网络、权限和表结构，再显式
增加 `--test-connection`。
