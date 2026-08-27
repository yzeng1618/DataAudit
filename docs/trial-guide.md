# data-audit 试用方案

本文用于在不接入生产数据的情况下验证当前实现，并给出接入一组真实数据后的最小试用步骤。

## 1. 试用目标与版本基线

试用应验证以下能力：

- 能构建并运行单文件 CLI；
- 能在配置校验、运行诊断和真实连接检查三个阶段发现问题；
- 能对一致和不一致的数据输出可解释的计划、状态与证据文件；
- 能在报告中定位异常切片，并在不一致时以退出码 `1` 供调度系统或 CI 识别。

本指南以 `master` 最新提交为基线。开始前先确认检出的提交：

```powershell
git fetch origin
git switch master
git log -1 --oneline
```

## 2. 前置条件

- Windows PowerShell（以下命令基于当前仓库）；Linux/macOS 的等价命令见 README。
- JDK 17；仓库提供 `.tools\jdk-17` 时可使用项目脚本切换当前会话。
- 可访问 Maven Central 或已有本地 Maven 缓存（首次构建需要下载依赖）。
- 真正连接数据源时，还需要 source 与 target 的只读账号、网络连通性和表的 `SELECT`
  权限。不要把密码写进 task YAML 或提交到仓库。

执行下列命令确认环境：

```powershell
. .\scripts\use-java17.ps1
java -version
.\mvnw.cmd -v
```

## 3. 路径 A：无外部依赖的本地冒烟试用（推荐）

此路径自动创建两份本地 SQLite 数据，运行的仍是真实 CLI、JDBC connector 和报告
生成逻辑；不需要 Docker、数据库服务或真实凭据。

1. 在仓库根目录切换 Java 17，并构建执行文件：

   ```powershell
   . .\scripts\use-java17.ps1
   .\mvnw.cmd -q -pl data-audit-cli -am -DskipTests package
   ```

   预期产物为 `data-audit-cli\target\data-audit.jar`。

2. 运行完整本地场景：

   ```powershell
   .\scripts\verify-local-sqlite.ps1
   ```

   脚本会覆盖小表一致、小表差异、分区差异、hash bucket 缩圈、无主键回退、DDL rename
   兼容和不稳定边界拒绝等场景。它将依次调用 `plan`、`check` 和 `report show`。

3. 检查结果目录。例如，先查看一致与差异各一个场景：

   ```powershell
   Get-Content .tmp\verify-local\consistent_small\reports\report.json -Raw
   Get-Content .tmp\verify-local\partition_mismatch\reports\report.json -Raw
   Invoke-Item .tmp\verify-local\partition_mismatch\reports\report.html
   ```

4. 判定通过：脚本以退出码 `0` 结束；一致场景的 `result.status` 为 `CONSISTENT`；
   差异场景的 `result.status` 为 `DIFF_FOUND`，且有 `suspect_slices.csv` 和
   `row_diff_sample.csv`。脚本内部会把“发现预期差异”的 `check` 退出码 `1` 作为
   预期结果处理，因此脚本整体成功并不表示每个场景都一致。

每个场景的产物位于 `.tmp\verify-local\<scenario>\reports\`：

| 文件 | 用途 |
| --- | --- |
| `report.json` | 给调度、告警或程序消费的结构化审计结果 |
| `report.html` | 给人工排查的可视化报告 |
| `manifest.json` | 本次生成的产物清单 |
| `suspect_slices.csv` | 被定位到的异常分区或 bucket |
| `row_diff_sample.csv` | 有主键时的行级差异样例 |
| `state.db` | 下次复查可使用的本地运行状态 |

## 4. 路径 B：接入一组真实小表

建议先选择任务已完成、边界稳定、规模在 10 万行以内且具有稳定主键的非生产敏感数据。
先跑一次副本或只读库，再决定是否扩大范围。

1. 创建隔离的试用目录并生成最小任务文件：

   ```powershell
   New-Item -ItemType Directory -Force .\trial | Out-Null
   java -jar .\data-audit-cli\target\data-audit.jar config init -o .\trial\task.yaml
   ```

2. 编辑 `.\trial\task.yaml`。可从 `templates\small-table-once.yaml` 复制字段；至少
   填写 source/target 的 JDBC URL、只读用户名、`table`（或 `query`）、比较列和主键。
   PostgreSQL 示例：

   ```yaml
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
     key: [order_id]
     columns: [order_id, status, amount, dt]
     estimated_rows: 10000

   boundary:
     type: job_finish
     reference: latest

   output:
     dir: ./trial/reports/orders
     value_mode: masked
   ```

   对分区表，加入 `object.partition_by: [dt]`；对固定批次，建议在 source 与 target
   使用等价的 `query`，将 `dt` 限制为同一个已完成的日期。对于 Iceberg 的 snapshot
   或通过 Trino 查询的数据，分别从 `templates\iceberg-snapshot-native.yaml` 或
   `templates\small-table-trino.yaml` 开始，避免把 JDBC 参数混入其他 connector。

3. 在当前 PowerShell 会话注入密码，而不写入文件：

   ```powershell
   $env:SOURCE_PASSWORD = '由安全凭据系统提供'
   $env:TARGET_PASSWORD = '由安全凭据系统提供'
   ```

4. 按“离线配置 → 连接诊断 → 只生成计划 → 执行”顺序运行：

   ```powershell
   $jar = '.\data-audit-cli\target\data-audit.jar'
   java -jar $jar config validate -f .\trial\task.yaml
   java -jar $jar config validate -f .\trial\task.yaml --test-connection
   java -jar $jar doctor -f .\trial\task.yaml --format json
   java -jar $jar plan -f .\trial\task.yaml
   java -jar $jar check -f .\trial\task.yaml
   ```

   `config validate` 不访问数据源；加上 `--test-connection` 才会打开连接并读 schema。
   `doctor` 汇总 Java、connector、输出目录检查，并默认探测 source/target 连接
   （`--offline` 可跳过）。先人工审阅 `plan` 的规模档位、策略和边界，再执行
   `check`，可避免意外对大表发起不合适的读取。

5. 解释退出码并查看报告：

   ```powershell
   java -jar $jar report show .\trial\reports\orders\report.json
   ```

   - `0`：执行成功且数据一致；
   - `1`：执行成功但发现差异，应查看 `report.json`、HTML 和 suspect slices；
   - `2`：任务文件或命令参数不合法；
   - `4`：连接、权限、驱动、执行或诊断失败；
   - `5`：数据边界不稳定，工具已拒绝执行。

## 5. 分阶段试用与验收建议

| 阶段 | 范围 | 成功标准 | 失败时优先排查 |
| --- | --- | --- | --- |
| 冒烟 | 路径 A 的本地 SQLite | 脚本成功，产物齐全 | JDK 17、Maven 缓存、jar 是否生成 |
| 连通性 | 一对真实小表 | `validate --test-connection` 与 `doctor` 成功 | URL、网络、只读权限、方言、环境变量 |
| 一致性 | 已知应一致的已完成批次 | `check` 返回 0，报告为 `CONSISTENT` | 两侧过滤条件、时区、decimal scale、稳定边界 |
| 差异定位 | 人工制造或已知有差异的批次 | `check` 返回 1，报告可定位切片/样例 | 主键、`partition_by`、比较列、输出脱敏模式 |
| 扩容 | 分区表或大表 | `plan` 选择合理的分层路径，资源不超限 | `estimated_rows`、分区列、超时、并行度、无 key 回退 |

## 6. 生产前检查

- source 和 target 均使用最小权限的只读账号；报告目录与 `state.db` 限制访问并设置保留期。
- 保持 `output.value_mode: masked`（默认）；只有经过明确审批时才可选 `hash` 或 `raw`。
- `boundary` 必须指向已完成的 job、snapshot、version、instant 或时间窗；不要对仍在写入的
  表运行核验。
- `object.key` 选择稳定且唯一的业务键；没有稳定 key 时，报告会说明 `no_key_mode` 和
  证明强度，不能将采样结果当作完全证明。
- 在调度中把退出码 `1` 视为业务差异告警，把 `2`、`4`、`5` 视为运行失败/需人工处理，
  不要把它们混为数据不一致。

更多完整配置项见 [配置参考](config-examples.md)，连接器范围和验证矩阵见
[功能验证说明](functional-verification.md)。
