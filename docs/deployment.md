# 部署指南

在服务器上做一次真实校验，推荐直接走**单 jar** 模式；如果服务器已有容器运行环境，也可以走**单容器**模式。这也是项目仅有的两类默认部署目标。

典型接入方式：

- Shell / cron 手工或定时触发
- 调度器任务完成后的 post-hook
- CI / CD 或数据发布后的结果审计步骤

## 1. 单 jar 部署

### 1.1 准备运行环境

- 服务器需要 `Java 17`
- 建议提前建好工作目录，例如：

```bash
mkdir -p /opt/data-audit/{bin,tasks,reports,state,logs}
```

### 1.2 构建并上传产物

在构建机执行：

```bash
./mvnw -q -DskipTests package
```

上传以下文件到服务器：

- `data-audit-cli/target/data-audit.jar`
- 选定的 `task.yaml`

`data-audit.jar` 是 fat jar，默认已打包 SQLite / PostgreSQL / MySQL / Trino 以及 Iceberg 所需运行时依赖。

### 1.3 准备任务配置

可以直接从 `templates/` 拷贝一份再改：

- 小表：`templates/small-table-once.yaml`
- 分区表：`templates/big-table-partitioned.yaml`
- Hive JDBC：`templates/hive-jdbc-partitioned.yaml`
- Doris JDBC：`templates/doris-jdbc-result.yaml`
- Iceberg：`templates/lakehouse-snapshot.yaml`

把密码放到环境变量里，不要写死在 YAML：

```bash
export SRC_PASSWORD='***'
export TGT_PASSWORD='***'
```

JDBC 场景建议同时补上这些参数：

- URL：`connectTimeout`、`socketTimeout`
- `options.query_timeout_seconds`
- `options.fetch_size`
- `options.progress_log_interval_rows`

### 1.4 先跑 `plan`

```bash
cd /opt/data-audit
java -jar ./bin/data-audit.jar plan -f ./tasks/task.yaml
```

这一步主要确认三件事：

- 配置能正常解析
- 边界是否稳定
- planner 选中的路径是否符合预期

### 1.5 再跑 `check`

```bash
java -jar ./bin/data-audit.jar check -f ./tasks/task.yaml
```

`check` 会输出阶段日志和 JDBC 读取进度，例如：

- `Stage 1/6: resolving boundary`
- `Stage 3/6: reading source rows for exact diff`
- `JDBC read progress [jdbc:orders]: fetched 5000 rows`
- `Stage 5/6 progress: diffing suspect segment 1/3 [dt=2026-03-10]`

如果需要后台执行：

```bash
nohup java -jar ./bin/data-audit.jar check -f ./tasks/task.yaml > ./logs/check.log 2>&1 &
tail -f ./logs/check.log
```

执行完成后会在 `output.dir` 下生成 `report.json`、`report.html`、`manifest.json`、`suspect_slices.csv`、`row_diff_sample.csv`；运行状态默认写到 `output.dir/state.db`。

### 1.6 查看结果

```bash
java -jar ./bin/data-audit.jar report show ./reports/<task-name>/report.json
```

### 1.7 对可疑 slice 继续下钻

如果 `report.json` 里有 `result.resume_hint`，可以直接执行类似命令：

```bash
java -jar ./bin/data-audit.jar diff -f ./tasks/task.yaml --slice dt=2026-03-10
```

## 2. 单容器部署

> 注意：Dockerfile 是单阶段构建，`docker build` 之前需要先在本机执行
> `./mvnw -q -DskipTests package` 产出 `data-audit-cli/target/data-audit.jar`。

```bash
./mvnw -q -DskipTests package
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

容器镜像声明了 `/tasks`、`/reports`、`/state`、`/logs` 四个运行目录，并以专用非 root 用户（UID 10001）运行。

任务 YAML 中的 JDBC URL、用户名、密码、Trino query connector URI/用户名/密码以及 Iceberg URI/warehouse/location 等运行时字段支持 `${ENV_VAR}` 展开。生产配置建议只在 YAML 中保留 `${SRC_PASSWORD}`、`${TGT_PASSWORD}` 这类引用，真实值通过容器环境变量或调度器 secret 注入；缺失变量会在打开 connector 前以退出码 `2` 失败，输出只包含变量名和字段路径，不会打印展开后的 secret。

## 3. 推荐的服务器验证顺序

第一次上服务器，建议固定按这个顺序跑：

1. `plan`
2. `check`
3. `report show`
4. 必要时 `diff --slice`

推荐先从当前已经稳定的组合开始：

- `jdbc -> jdbc`：完整支持，适合首批生产验证
- `jdbc <-> iceberg`：已支持真实对比，适合继续验证 snapshot 边界与 metadata-first 路径

对性能的当前建议：

- 小表优先走 `global row_count + global checksum`
- 大表 / 分区表优先走 `grouped checksum -> localization -> exact diff`
- 超大表优先走 `metadata / routing digest -> localization`
- 如果只比较少数列，显式配置 `object.columns` 或直接使用 `query:`

## 4. 服务器场景模板

推荐直接从下面 3 份服务器模板起步（完整内容见 `templates/` 目录）：

- [`templates/server-mysql-to-doris.yaml`](../templates/server-mysql-to-doris.yaml) — MySQL 明细/结果表同步到 Doris 后的任务后校验
- [`templates/server-hive-to-postgres.yaml`](../templates/server-hive-to-postgres.yaml) — Hive 数仓分区结果同步到 PostgreSQL 后的分区级校验
- [`templates/server-jdbc-to-iceberg.yaml`](../templates/server-jdbc-to-iceberg.yaml) — JDBC 源端与 Iceberg 目标表之间的 snapshot 边界校验（planner 先走 `metadata-first`，必要时进入统一的 summary / segment / diff）

## 5. 调度器接入

挂到 cron 或调度器后置步骤时，请依赖退出码语义（完整定义见 README「退出码」一节）：

- `0`：一致
- `1`：发现差异 —— 视为业务差异告警
- `2`：配置错误
- `4`：执行失败（连接、权限、驱动、执行或诊断失败）—— 视为运行失败，需人工处理
- `5`：边界不稳定，拒绝执行

cron 示例：

```bash
0 * * * * cd /opt/data-audit && /usr/bin/java -jar ./bin/data-audit.jar check -f ./tasks/task.yaml >> ./logs/task.log 2>&1
```

## 6. 未来可选控制面

以下能力只预留能力位，不作为当前依赖：报告汇聚与检索、任务模板目录、策略模板中心、多次运行趋势对比。
