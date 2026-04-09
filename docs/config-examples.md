# data-audit 配置样例与参数说明

本文面向实际使用者，重点回答三类问题：

- 小表应该怎么配，才能快速得到结果
- 大表 / 分区表应该怎么配，才能先缩圈再下钻
- 湖仓对象应该怎么配，才能利用 snapshot 与 metadata-first 路径

本文只描述当前代码已经实现的行为，不把设计稿里尚未落地的能力写成“已经支持”。

当前默认执行模型已经是 scale-driven：

- `small`: `global row_count + global checksum -> exact diff`
- `large`: `global row_count + grouped checksum -> suspicious slices -> exact diff`
- `large` 无稳定切分且无 key：`xor_checksum_plus_sample`
- `xlarge`: `metadata / routing digest -> suspicious routing groups -> exact diff`
- `xlarge` 无稳定切分且无 key：`sampling`

> 当前正式 v1 字段以 `task / boundary / query_connector / source / target / object / normalize / semantics / output` 为准。优先参考 `templates/*.yaml` 和 `scripts/verify-*.ps1` 里的样例；本文后续部分仍有历史性的 `planner / compare / segment` 术语残留，迁移中不再作为推荐写法。

新增推荐字段：

- `object.estimated_bytes`
- `planner.scale_override`
- `object.group_by`
- `object.routing_strategy`

新增模板：

- `templates/large-table-nokey.yaml`
- `templates/xlarge-sampling-fallback.yaml`

## 1. 当前实现范围

截至当前代码版本，建议按下面的支持矩阵理解：

| 场景 | 当前状态 | 推荐程度 |
| --- | --- | --- |
| `trino -> trino` 小表 / 大表 | 完整支持 | 推荐，适合把 MySQL / PostgreSQL / Hive / Iceberg 等可被 Trino catalog 暴露的对象统一接入 |
| `jdbc -> jdbc` 小表 | 完整支持 | 最推荐，适合作为第一批生产验证场景 |
| `jdbc -> jdbc` 大表 / 分区表 | 完整支持 | 推荐，但应合理设置 `planner` 和 `segment` |
| `jdbc -> iceberg` | 已支持真实对比 | 推荐，用于验证 `snapshot` 边界和 metadata-first |
| `iceberg -> jdbc` | 已支持真实对比 | 推荐，用于回查湖仓到下游结果 |
| `iceberg -> iceberg` | 架构可扩展，当前不作为首推场景 | 谨慎使用 |
| `hudi / delta / paimon` | 设计预留，当前未完整实现 | 当前不要作为正式承诺 |

## 2. 使用前先记住的 8 条规则

### 2.1 `plan` 不是纯静态命令

`plan` 会真实打开 connector，做能力发现和边界判断。  
这意味着以下问题都会在 `plan` 阶段暴露：

- JDBC 驱动缺失
- 网络不通
- 用户名密码错误
- `snapshot` 边界不可解析

### 2.2 JDBC `table` 模式默认会读 `select *`，但显式列集已可下推

当前 JDBC reader 在 `table:` 场景下的默认 SQL 是：

```sql
select * from <table> where 1=1
```

如果同时配置了：

```yaml
object:
  columns:
    include:
      - id
      - status
      - amount
```

当前 JDBC reader 会把显式列集下推为：

```sql
select "id", "status", "amount" from <table> where 1=1
```

如果进入分段下钻，则会变成：

```sql
select "id", "status", "amount", "dt" from <table> where 1=1 and <segment_column> = ?
```

注意：

- 只有显式配置了 `object.columns.include`，JDBC 才会做列投影下推
- 如果没有配置 `object.columns.include`，仍然是 `select *`
- 如果表里有大字段、JSON、TEXT、BLOB，仍然建议优先用 `query:`

### 2.3 `object.columns.include` 当前既是逻辑投影，也是 JDBC 列投影提示

当前它的作用有两层：

- 归一化时只保留这些列
- schema 比较时只关心这些列
- JDBC connector 生成 SQL 时，会把这些列作为投影列下推

但仍要注意两个边界：

- 如果没有配置 `object.columns.include`，JDBC 仍会读取 `*`
- 如果你要精确控制复杂 SQL、过滤条件、排序或函数表达式，仍然建议直接使用 `query:`

### 2.4 小表不要把唯一键写成 `partition_keys`

如果把 `id` 这类唯一键放进：

```yaml
planner:
  hints:
    partition_keys:
      - id
```

或者：

```yaml
compare:
  segment:
    by:
      - id
```

当前代码会把几乎每一行都当成一个 segment，导致大量小 SQL 往返。  
小表应优先走 `exact diff`，不要走 `segment-first`。

### 2.5 大表分段键要选低基数列

推荐的 segment key：

- `dt`
- `biz_date`
- `partition_day`
- `bucket_id`

不推荐：

- `id`
- `order_id`
- `uuid`
- 几乎唯一的明细键

### 2.6 `report.json` 只会在执行结束时写出

当前实现是执行完成后统一落报告。  
如果任务卡在中间阶段，`report.json` 不会提前出现。

### 2.7 当前已有阶段进度日志和 JDBC 行进度日志，但还不是图形化进度条

当前 `check` 已经会输出阶段进度，例如：

- `Stage 1/6: resolving boundary`
- `Stage 2/6: reading metadata and schema`
- `Stage 3/6: reading source rows for exact diff`
- `Stage 5/6 progress: diffing suspect segment 1/3 [dt=2026-03-10]`

JDBC 读取阶段也会输出实时日志，例如：

- `JDBC read start [jdbc:orders]: select ...`
- `JDBC read progress [jdbc:orders]: fetched 5000 rows`
- `JDBC read complete [jdbc:orders]: rows=120345, elapsedMs=8421`

这意味着：

- 当前已经可以实时看到执行阶段和 JDBC 行读取进度
- 但它还不是百分比式、图形化的进度条
- 如果底层 JDBC 查询在数据库侧迟迟不返回首批结果，程序仍会等待到查询返回或超时

建议在服务器上同时配置：

- JDBC URL 里的 `connectTimeout`、`socketTimeout`
- `options.query_timeout_seconds`
- `options.fetch_size`
- `options.progress_log_interval_rows`
- 用 `nohup` 或调度器后台跑，不要只盯前台终端

### 2.8 YAML 里的 `${PASSWORD}` 当前不会自动替换

当前代码不会自动展开：

```yaml
password: ${SRC_PASSWORD}
```

如果你这样写，需要先在服务器上渲染出一份运行时 YAML。  
或者直接在运行时 YAML 中写实际密码。

## 3. 命令执行顺序

所有场景都建议按下面的顺序执行：

```bash
data-audit plan  -f task.yaml
data-audit check -f task.yaml
data-audit report show /path/to/report.json
```

如果报告里有 `suspect_slices`，再执行：

```bash
data-audit diff -f task.yaml --slice dt=2026-03-10
```

## 4. 场景一：小表精确比对

### 4.1 适用场景

适用于：

- 行数较少，例如几百到几万行
- 有明确业务主键
- 希望直接做精确比对，不希望先走 segment
- 需要快速定位具体差异行

典型例子：

- MySQL 同步到 Doris 后，对某个小结果集做校验
- PostgreSQL 到 PostgreSQL 的小表回归验证
- 任务输出行数不多的指标表、宽表、维表

### 4.2 推荐配置

```yaml
task:
  name: small_table_exact_diff
  description: "小表精确比对示例"
  mode: post_check

boundary:
  type: job_finish
  reference: latest

source:
  type: jdbc
  url: jdbc:mysql://mysql-source.prod:3306/app?connectTimeout=5000&socketTimeout=30000&tcpKeepAlive=true&useSSL=false
  username: app_read
  password: "source_password"
  query: |
    select id, order_no, status, amount, update_time
    from orders_small
    where biz_date = '2026-03-10'
  options:
    dialect: mysql
    query_timeout_seconds: 60
    fetch_size: 500
    progress_log_interval_rows: 100

target:
  type: jdbc
  url: jdbc:mysql://doris-fe.prod:9030/ads?connectTimeout=5000&socketTimeout=30000&tcpKeepAlive=true&useSSL=false
  username: doris_read
  password: "target_password"
  query: |
    select id, order_no, status, amount, update_time
    from ads.orders_small
    where biz_date = '2026-03-10'
  options:
    dialect: doris
    query_timeout_seconds: 60
    fetch_size: 500
    progress_log_interval_rows: 100

object:
  key:
    - id
  columns:
    include:
      - id
      - order_no
      - status
      - amount
      - update_time

planner:
  mode: exact_first
  hints:
    estimated_rows: 1000
    max_exact_rows: 100000
    force_exact_diff: true

normalization:
  timezone: Asia/Shanghai
  trim_string: true
  empty_as_null: true
  case_insensitive_columns:
    - status
  decimal_scale:
    amount: 2

compare:
  diff:
    max_samples: 200

output:
  dir: /opt/data-audit/reports/small_table_exact_diff

state:
  backend: sqlite
  path: /opt/data-audit/state/small_table_exact_diff.db
```

### 4.3 这份配置会怎么跑

当前代码会把这份任务稳定规划为：

```text
schema -> exact diff
```

执行顺序是：

1. 读取 source schema
2. 读取 target schema
3. 读取 source 全部结果一次
4. 读取 target 全部结果一次
5. 基于内存中的 source/target 行数据同时计算 summary 和 exact diff
6. 输出报告

虽然路径名叫 `schema -> exact diff`，当前实现仍会基于已读取的 source/target 行数据计算一轮 summary，用于报告和根因归类。  
和之前相比，exact diff 路径已经不再额外重复读取一遍 source/target。

### 4.4 为什么推荐用 `query`，而不是 `table`

当前 JDBC 即使支持 `object.columns.include` 下推，`query:` 依然是更稳的写法。  
如果你只想比 5 列，就应该直接在 `query:` 里写清楚 5 列。

建议：

- 小表优先使用 `query`
- 直接把业务过滤条件写清楚
- 两边 query 的投影列、过滤条件尽量对称

### 4.5 关键参数说明

`boundary.type`

- 这里用 `job_finish`
- 表示这是一批任务完成后的结果校验

`source.query` / `target.query`

- 明确规定本次对比的业务范围
- 避免一边查单天、一边查整表

`object.key`

- 当前 exact diff 的主键对齐依据
- 有主键时会走 keyed diff

`object.columns.include`

- 当前用于逻辑列集约束和归一化列顺序
- 建议与 query 里的列保持一致

`planner.mode: exact_first`

- 明确告诉 planner 优先走精确比对
- 适合小表或临时核对场景

`force_exact_diff: true`

- 即便 `estimated_rows` 配错，也强制走小表路径

`normalization`

- 用于消除字符串、时区、小数位数等差异带来的误报

`compare.diff.max_samples`

- 限制报告里最多输出多少条差异样本

`source.options.query_timeout_seconds` / `target.options.query_timeout_seconds`

- 作用：给单条 JDBC 查询设置超时
- 当前已生效
- 建议：服务器验证至少设置 `30` 到 `300`

`source.options.fetch_size` / `target.options.fetch_size`

- 作用：提示 JDBC 驱动按批抓取结果
- 当前已生效
- 建议：小表可设 `100` 到 `1000`，大表可设 `1000` 到 `10000`

`source.options.progress_log_interval_rows` / `target.options.progress_log_interval_rows`

- 作用：每读取多少行打印一次 JDBC 进度日志
- 当前已生效
- 建议：小表可设 `100`，大表可设 `5000` 或 `10000`

### 4.6 预期输出

如果一致，常见结果是：

- `status = CONSISTENT`
- `root_cause = consistent`

如果不一致，常见根因有：

- `latest_state_mismatch`
- `insert_incomplete`
- `delete_not_effective`
- `schema_mismatch`

## 5. 场景二：大表 / 分区表分层比对

### 5.1 适用场景

适用于：

- 表很大，不适合直接全量 exact diff
- 有稳定的分区字段
- 希望先快速找出 suspect partition
- 再对 suspect partition 做下钻

典型例子：

- Hive / MySQL / PostgreSQL 到 Doris 的分区结果表
- ODS / DWD / ADS 按天落地的大表
- 大表但只允许按 `dt`、`biz_date` 等分区定位

### 5.2 推荐配置

```yaml
task:
  name: partitioned_table_reconcile
  description: "大表分区结果校验示例"
  mode: post_check

boundary:
  type: job_finish
  reference: latest

source:
  type: jdbc
  url: jdbc:hive2://hive-server.prod:10000/dw
  username: hive_read
  password: "source_password"
  table: dw.orders_ads
  options:
    dialect: hive
    query_timeout_seconds: 300
    fetch_size: 2000
    progress_log_interval_rows: 5000

target:
  type: jdbc
  url: jdbc:postgresql://postgres-ads.prod:5432/ads?connectTimeout=5000&socketTimeout=30000&tcpKeepAlive=true
  username: ads_read
  password: "target_password"
  table: public.orders_ads
  options:
    dialect: postgres
    query_timeout_seconds: 300
    fetch_size: 2000
    progress_log_interval_rows: 5000

object:
  key:
    - order_id
  columns:
    include:
      - order_id
      - shop_id
      - status
      - amount
      - dt

planner:
  mode: segment_first
  hints:
    estimated_rows: 200000000
    partition_keys:
      - dt
    max_exact_rows: 100000

normalization:
  timezone: Asia/Shanghai
  trim_string: true
  empty_as_null: true
  decimal_scale:
    amount: 2

compare:
  segment:
    by:
      - dt
  diff:
    max_samples: 200

output:
  dir: /opt/data-audit/reports/partitioned_table_reconcile

state:
  backend: sqlite
  path: /opt/data-audit/state/partitioned_table_reconcile.db
```

### 5.3 这份配置会怎么跑

当前代码会稳定走：

```text
schema -> summary -> segment -> diff
```

实际执行步骤：

1. source 全局 summary
2. target 全局 summary
3. 如果全局 summary 一致，且 schema 一致，则直接通过
4. 如果全局 summary 不一致，则：
   - 从 source 枚举 `dt` distinct values
   - 从 target 枚举 `dt` distinct values
   - 合并成一个分区值集合
5. 对每个 `dt` 分别计算 source/target summary
6. summary 不一致的 `dt` 记为 suspect segment
7. 对 suspect `dt` 再做 keyed diff

### 5.4 为什么 `segment.by` 要用 `dt`

当前分段引擎会把 `segment.by` 的第一个字段当成切片键。  
如果你写：

```yaml
segment:
  by:
    - order_id
```

并且 `order_id` 几乎唯一，那么它会退化成“按行分段”。  
所以大表场景一定要选低基数列。

推荐：

- `dt`
- `biz_date`
- `partition_day`

不推荐：

- `id`
- `order_id`
- `uuid`

### 5.5 如果只校验单天分区，怎么配

如果你只想校验 `dt = '2026-03-10'` 这一批，建议 source/target 两边都写 `query:`，而不是一边写 query、一边写整表。

例如：

```yaml
source:
  query: |
    select order_id, shop_id, status, amount, dt
    from dw.orders_ads
    where dt = '2026-03-10'

target:
  query: |
    select order_id, shop_id, status, amount, dt
    from public.orders_ads
    where dt = '2026-03-10'
```

如果单天数据量很小，还可以进一步改成：

```yaml
planner:
  mode: exact_first
  hints:
    estimated_rows: 50000
    max_exact_rows: 100000
```

### 5.6 关键参数说明

`planner.mode: segment_first`

- 显式要求走大表分层路径
- 适合已知是大表 / 分区表的场景

`estimated_rows`

- 不是运行时实时统计值
- 是给 planner 的估算值
- 用来决定是否走 exact diff

`partition_keys`

- 当前既影响 planner 的对象判断，也影响默认 `segment_strategy`
- 推荐与 `compare.segment.by` 保持一致

`compare.segment.by`

- 当前真正生效的切片字段
- 只取第一个值作为 segment column

### 5.7 预期输出

常见报告内容：

- `status = DIFF_FOUND`
- `suspect_slices = [dt=2026-03-10]`
- `resume_hint = data-audit diff -f task.yaml --slice dt=2026-03-10`

## 6. 场景三：湖仓表 snapshot 校验

### 6.1 适用场景

适用于：

- JDBC 源端和 Iceberg 目标表对比
- Iceberg 源端和 JDBC 目标表对比
- 希望利用 `snapshot` 边界和 metadata-first 路径

当前最推荐的湖仓对象是 `Iceberg`。  
`Hudi / Delta / Paimon` 仍然是设计预留，不要按“已完整实现”理解。

### 6.2 推荐配置：JDBC -> Iceberg

```yaml
task:
  name: jdbc_to_iceberg_orders
  description: "JDBC 到 Iceberg 的 snapshot 校验示例"
  mode: post_check

boundary:
  type: snapshot
  reference: latest

source:
  type: jdbc
  url: jdbc:postgresql://postgres-source.prod:5432/app?connectTimeout=5000&socketTimeout=30000&tcpKeepAlive=true
  username: app_read
  password: "source_password"
  query: |
    select order_id, status, amount, dt
    from public.orders
    where dt = '2026-03-10'
  options:
    dialect: postgres

target:
  type: iceberg
  catalog: prod
  catalog_type: hadoop
  warehouse: hdfs:///warehouse/iceberg
  database: dw
  table: orders
  snapshot_id: latest

object:
  key:
    - order_id
  columns:
    include:
      - order_id
      - status
      - amount
      - dt

planner:
  scale_override: xlarge

normalize:
  decimal_scale:
    amount: 2

ddl:
  mode: compatible
  type_rules:
    - from: integer
      to: long
      action: allow

output:
  dir: /opt/data-audit/reports/jdbc_to_iceberg_orders

state:
  backend: sqlite
  path: /opt/data-audit/state/jdbc_to_iceberg_orders.db
```

### 6.3 这份配置会怎么跑

当前代码会走：

```text
boundary metadata -> schema -> summary -> segment -> diff
```

执行顺序大致是：

1. 解析 JDBC 端逻辑边界
2. 解析 Iceberg `snapshot` 边界
3. 读取 Iceberg schema、snapshot summary、manifest hints
4. source/target 各自做 summary
5. 如果有 `dt`，按 `dt` 找 suspect segment
6. 对 suspect segment 做真实 diff
7. 报告里额外附带 manifest hint

### 6.4 Iceberg 端当前支持哪些字段

当前 Iceberg endpoint 主要支持：

- `location`
- `catalog`
- `catalog_type`
- `warehouse`
- `database`
- `table`
- `snapshot_id`
- `uri`

其中：

- 如果配了 `location`，会优先按 `HadoopTables.load(location)` 读取
- 否则按 `catalog + database + table` 读取

### 6.5 当前 Iceberg 的真实能力边界

当前已经实现：

- snapshot 边界解析
- metadata 读取
- manifest hint 输出
- 原生数据读取
- `jdbc <-> iceberg` 的真实 diff

当前尚未作为正式能力承诺的：

- Hudi / Delta / Paimon 同等级原生支持
- CDC / CDF evidence reader
- row-level change 专用 reader

## 7. 参数说明总表

下面按 section 逐段说明“参数用途、当前是否生效、推荐值”。

### 7.1 `task`

`task.name`

- 作用：任务名，报告和 state 中会引用
- 当前是否生效：生效
- 推荐：必须唯一、语义清晰

`task.description`

- 作用：说明任务用途
- 当前是否生效：只做信息展示
- 推荐：写清 source、target、边界和业务范围

`task.mode`

- 作用：任务模式
- 当前是否生效：当前固定按 `post_check` 理解
- 推荐：保留默认值 `post_check`

### 7.2 `boundary`

`boundary.type`

- 作用：决定比较的稳定边界类型
- 当前实现推荐值：
  - `job_finish`
  - `snapshot`
- 当前实现不推荐直接依赖：
  - `version`
  - `instant`
  - `time_window`

`boundary.reference`

- 作用：边界引用值
- 常见值：
  - `latest`
  - 具体 `snapshot_id`
  - 具体业务分区值

`boundary.grace_period`

- 作用：设计上用于边界等待
- 当前是否生效：未实际使用

### 7.3 `source` / `target` 通用字段

`type`

- 作用：指定 endpoint 类型
- 当前已稳定支持：
  - `jdbc`
  - `iceberg`

`url`

- 作用：JDBC 连接串
- 当前只对 `jdbc` 生效
- 建议在 URL 里显式加：
  - `connectTimeout`
  - `socketTimeout`
  - `tcpKeepAlive`

`username` / `password`

- 作用：数据库认证
- 当前生效
- 注意：`${ENV_VAR}` 当前不会自动展开

`table`

- 作用：表名模式
- 当前 JDBC 下默认生成 `select * from <table> where 1=1`
- 如果显式配置了 `object.columns.include`，会下推为显式列投影
- 适合：
  - 表不宽
  - 表字段不大
  - 不需要精确控制列集

`query`

- 作用：自定义 SQL 模式
- 当前非常推荐
- 适合：
  - 控制列投影
  - 控制过滤条件
  - 避免整表读取

`options.dialect`

- 作用：指定 JDBC 方言
- 当前支持：
  - `postgres`
  - `mysql`
  - `hive`
  - `doris`

`options.query_timeout_seconds`

- 作用：单条 JDBC 查询超时
- 当前已生效
- 推荐：服务器验证时显式配置

`options.fetch_size`

- 作用：JDBC 抓取批大小提示
- 当前已生效
- 推荐：大表或慢查询场景显式配置

`options.progress_log_interval_rows`

- 作用：每读取多少行打印一次进度日志
- 当前已生效
- 推荐：根据数据规模配置 `100`、`1000`、`5000`

### 7.4 `target.type: iceberg` 相关字段

`location`

- 作用：直接按表路径读取 Iceberg
- 当前已生效

`catalog`

- 作用：catalog 名称
- 当前已生效

`catalog_type`

- 作用：catalog 类型
- 当前已支持：
  - `hadoop`
  - `hive`
  - `rest`

`warehouse`

- 作用：warehouse 根路径
- 当前已生效

`database`

- 作用：命名空间 / database
- 当前已生效

`table`

- 作用：表名
- 当前已生效

`snapshot_id`

- 作用：显式指定 snapshot
- 当前已生效

`uri`

- 作用：catalog 服务 URI
- 当前已生效

### 7.5 `object`

`object.key`

- 作用：业务主键
- 当前是否生效：强生效
- 有 key 时走 keyed diff
- 无 key 时走 multiset diff

`object.columns.include`

- 作用：逻辑列集
- 当前是否生效：生效
- 当前 JDBC 是否下推 SQL：在 `table`/框架生成 SQL 场景下会下推
- 如果未配置该字段：仍然读取 `*`
- 推荐：与 `query` 的投影列保持一致

### 7.6 `planner`

`planner.mode`

- 当前支持：
  - `auto`
  - `exact_first`
  - `segment_first`
  - `metadata_first`

推荐理解：

- `auto`：默认模式
- `exact_first`：小表 / 临时核对
- `segment_first`：大表 / 分区表
- `metadata_first`：湖仓对象

`planner.scale_override`

- 作用：显式覆盖 scale classifier 的结果
- 当前可选：
  - `small`
  - `large`
  - `xlarge`
- 推荐：默认不配，只在已知规模估算不可靠时覆盖

`planner.hints.estimated_rows`

- 作用：告诉 planner 这是多大规模的数据
- 当前非常重要
- 推荐：尽量按真实数量级填写，不要乱写

`planner.hints.partition_keys`

- 作用：告诉 planner 哪些字段适合作为分段依据
- 当前生效
- 推荐：只填低基数字段

`planner.hints.max_exact_rows`

- 作用：small/big 的阈值
- 当前生效
- 如果 `estimated_rows <= max_exact_rows`，planner 倾向走 exact diff

`planner.hints.force_exact_diff`

- 作用：强制 exact diff
- 当前生效
- 小表场景强烈推荐

`planner.hints.prefer_metadata`

- 作用：是否优先使用 metadata 能力
- 当前对 Iceberg 生效

### 7.7 `normalization`

`timezone`

- 作用：统一时间字段的时区表示
- 当前对 `Timestamp` 生效

`trim_string`

- 作用：字符串去首尾空格
- 当前生效

`empty_as_null`

- 作用：空字符串按 `null` 处理
- 当前生效

`case_insensitive_columns`

- 作用：指定哪些列忽略大小写
- 当前生效

`decimal_scale`

- 作用：指定小数字段统一精度
- 当前生效
- 特别适合：
  - `MySQL decimal` vs `Doris decimal`
  - JDBC 驱动返回 `BigDecimal` / `Double` 不一致

### 7.8 `compare`

`compare.levels`

- 设计上表示比较层级
- 当前是否作为硬开关生效：未作为硬开关消费

`compare.summary.metrics`

- 设计上表示摘要指标清单
- 当前是否逐项开关生效：未逐项消费
- 当前 summary 仍固定计算：
  - `row_count`
  - `null_count`
  - `min/max`
  - `checksum`
  - `approx_distinct`

`compare.summary.hash.*`

- 设计上表示 hash 配置
- 当前是否完全按配置生效：未完整消费
- 当前实现使用内部 hash 逻辑

`compare.segment.by`

- 作用：指定 segment 字段
- 当前强生效
- 只使用第一个字段

`compare.segment.chunk_rows`

- 设计上表示分批行数
- 当前未实际消费

`compare.segment.digest.*`

- 设计上表示 digest 配置
- 当前未完整按配置消费

`compare.diff.max_samples`

- 作用：限制差异样本条数
- 当前生效

`compare.diff.exact_when_suspect`

- 设计上表示 suspect 后是否精确下钻
- 当前代码路径本身就是 suspect 后精确下钻，这个字段未被单独消费

### 7.9 `dml`

当前 DML 配置已进入根因归类逻辑。

`dml.insert`

- 当前默认：`completeness`
- 影响 `missing_in_target` 的归因

`dml.update`

- 当前默认：`latest_state`
- 影响 `row_mismatch` 的归因

`dml.delete.mode`

- 当前支持：
  - `hard_delete`
  - `soft_delete`
  - `delete_marker`
- 影响 `extra_in_target` 的归因

`dml.merge`

- 当前默认：`latest_state`
- 影响 merge 类差异的归因口径

### 7.10 `ddl`

`ddl.mode`

- 当前支持：
  - `strict`
  - `compatible`
  - `logical_only`

推荐：

- 普通异构 JDBC 场景优先 `compatible`
- 强一致回归用 `strict`
- 只关心逻辑结果时用 `logical_only`

`ddl.rename_mapping`

- 作用：列重命名映射
- 当前生效
- 影响：
  - schema compare
  - normalization
  - keyed diff

`ddl.type_rules`

- 作用：显式允许某些类型变化
- 当前生效

`ddl.partition_evolution`

- 作用：对分区演进的容忍策略
- 当前主要体现在 DDL 报告和 metadata 解释上

### 7.11 `evidence`

`evidence.enabled`

- 设计上表示旁证模式
- 当前未作为正式数据路径使用

`evidence.type`

- 设计预留
- 当前不要作为已生效能力理解

### 7.12 `output`

`output.dir`

- 作用：输出目录
- 当前强生效

`output.format`

- 设计上表示输出格式
- 当前未按此字段裁剪输出
- 当前会固定输出：
  - `report.json`
  - `report.html`
  - `suspect_slices.csv`
  - `row_diff_sample.csv`
  - `manifest.json`

### 7.13 `state`

`state.backend`

- 当前实际只支持 `sqlite`

`state.path`

- 作用：SQLite state 文件路径
- 当前强生效

## 8. 当前性能模型与调优建议

### 8.1 当前执行模型是什么

当前实现不是流式 compare，而是“分阶段读取 + 内存归一化 + 分层裁决”：

- `schema -> exact diff`
  - source 读取一次
  - target 读取一次
  - 基于内存中的 source/target 行数据同时计算 summary 和 diff
- `schema -> summary -> segment -> diff`
  - 先做 source/target 全局 summary
  - 再枚举 segment values
  - 再对每个 segment 做 summary
  - 最后只对 suspect segment 做 exact diff
- `boundary metadata -> schema -> summary -> segment -> diff`
  - 先读 lakehouse metadata
  - 再按上面的 summary / segment / diff 路径继续

### 8.2 为什么 10000 行也可能觉得慢

常见原因通常不是“10000 行太多”，而是下面这些组合因素：

- 两端都在走 JDBC，网络往返和数据库执行时间占主导
- 使用了 `table:` 且没有配置 `object.columns.include`，导致读取了所有列
- 表里有大字段、JSON、TEXT、BLOB
- 分段键选错成唯一键，导致大量小查询往返
- `summary -> segment -> diff` 路径下，同一个对象会被多次读取
- 底层 JDBC 查询没有超时，数据库端慢查询会把 `check` 拖得很长

### 8.3 当前已经做了哪些优化

截至当前版本，已经落地的优化包括：

- `check` 已有阶段进度日志
- JDBC 读取已有实时行进度日志
- exact diff 路径不再重复读取 source/target 两遍
- 显式配置 `object.columns.include` 时，JDBC 会下推列投影
- JDBC 支持 `query_timeout_seconds`
- JDBC 支持 `fetch_size`
- JDBC 支持 `progress_log_interval_rows`

### 8.4 当前还没有做的优化

这些能力还没有落地，所以大表性能上限仍然有限：

- 真正的流式 diff
- chunked/streaming summary 聚合
- segment 级别的并行调度
- 统一的内存上限治理
- 统一的全局超时策略
- `compare.segment.chunk_rows` 的真实消费

### 8.5 小表性能建议

小表建议直接按下面的思路配置：

- `planner.mode=exact_first`
- `force_exact_diff=true`
- `estimated_rows` 写真实数量级
- 显式写 `query:`
- 只选择真正需要比较的列
- `progress_log_interval_rows` 设成 `100` 或 `500`

### 8.6 大表 / 分区表性能建议

大表建议按下面的思路配置：

- `planner.mode=segment_first`
- `partition_keys` / `compare.segment.by` 只选低基数字段
- source/target 尽量限制到同一业务范围
- 显式配置 `object.columns.include`
- JDBC 配置 `fetch_size`
- JDBC 配置 `query_timeout_seconds`
- 不要把 `id/order_id/uuid` 这种唯一键当 segment key

### 8.7 JDBC 推荐调优参数

小表示例：

```yaml
options:
  dialect: mysql
  query_timeout_seconds: 60
  fetch_size: 500
  progress_log_interval_rows: 100
```

大表示例：

```yaml
options:
  dialect: doris
  query_timeout_seconds: 300
  fetch_size: 2000
  progress_log_interval_rows: 5000
```

### 8.8 如果你只想先判断是不是性能配置问题

建议按这个顺序排查：

1. 先跑 `plan`，确认没有误走大表路径
2. 看 `check` 日志停在 `Stage 3/6`、`Stage 4/6` 还是 `Stage 5/6`
3. 看 `JDBC read start` 对应的是 source 还是 target
4. 直接在数据库端执行同样的 SQL，看是不是数据库本身慢
5. 如果只是比少量列，改成 `query:` 或显式配置 `object.columns.include`

## 9. 常见错误配置与修正建议

### 错误 1：一边单天，一边整表

错误写法：

```yaml
source:
  query: |
    select * from orders where dt = '2026-03-10'

target:
  table: ads.orders
```

问题：

- source 是单天
- target 是整表
- 全局 summary 会天然不一致

修正：

- target 也写成同范围 query

### 错误 2：100 行小表却配置成 500 万大表

错误写法：

```yaml
planner:
  mode: auto
  hints:
    estimated_rows: 5000000
    partition_keys:
      - id
```

问题：

- planner 会误判为大表
- `id` 还是唯一键，segment 会非常碎

修正：

```yaml
planner:
  mode: exact_first
  hints:
    estimated_rows: 100
    max_exact_rows: 100000
    force_exact_diff: true
```

### 错误 3：希望只比 5 列，却用了 `table:`

错误写法：

```yaml
source:
  table: orders

object:
  columns:
    include:
      - id
      - status
```

问题：

- 如果没有显式写 `query:`，容易忽略真实读取列集
- 没配 `object.columns.include` 时，当前 JDBC 仍然会执行 `select *`

修正：

- 至少显式配置 `object.columns.include`
- 更稳的是改成 `query:`，只查需要的列

## 10. 推荐模板入口

仓库内现有模板如下：

- [small-table-once.yaml](../templates/small-table-once.yaml)
- [big-table-partitioned.yaml](../templates/big-table-partitioned.yaml)
- [lakehouse-snapshot.yaml](../templates/lakehouse-snapshot.yaml)
- [server-mysql-to-doris.yaml](../templates/server-mysql-to-doris.yaml)
- [server-hive-to-postgres.yaml](../templates/server-hive-to-postgres.yaml)
- [server-jdbc-to-iceberg.yaml](../templates/server-jdbc-to-iceberg.yaml)

推荐使用方式：

1. 先根据场景挑一个最接近的模板
2. 先跑 `plan`
3. 确认 `scale_class / signal_strategy / localization_strategy` 是否符合预期
4. 再跑 `check`
5. 最后根据 `report.json` 和 `resume_hint` 做复查
