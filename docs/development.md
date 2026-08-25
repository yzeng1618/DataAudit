# 本地开发与验证

面向贡献者的本地环境、验证脚本与测试矩阵说明。通用贡献流程见 [CONTRIBUTING.md](../CONTRIBUTING.md)。

## 基线验证

任何平台，提交前的基线验证：

```bash
./mvnw verify
python -m pytest -q data-audit-agent
```

Windows 下将 `./mvnw` 替换为 `.\mvnw.cmd`。需要 Docker 的 Testcontainers 集成测试在无 Docker 环境会自动跳过而不是失败；Windows 上 CI 通过 `-DexcludedGroups=requires-posix-filesystem` 排除依赖 POSIX 文件系统的用例。

`verify` 同时执行 SPDX license header 检查——新增源文件运行 `./mvnw spotless:apply` 自动补齐头部；jacoco 覆盖率报告输出在各模块 `target/site/jacoco/`。

## 本地 Java 17 环境（Windows，可选）

仓库预留了项目局部运行方式，不需要改全局 `JAVA_HOME`。前提是自行下载一份 JDK 17 解压到仓库根目录的 `.tools\jdk-17`（该目录被 .gitignore 忽略，不随仓库分发）。之后在 PowerShell 会话里执行：

```powershell
. .\scripts\use-java17.ps1
```

这会把当前会话切到仓库内的 `.tools\jdk-17`，然后打印 `java -version` 和 `mvn -v`。

> `scripts/` 下的验证脚本均为 Windows PowerShell 可选辅助脚本，都依赖上述项目局部 JDK。系统里已有 Java 17 的贡献者可以直接用 `mvnw` 跑等价的测试类。

## 本地真实验证（第一层）

不依赖 Docker 的真实验证脚本，会创建两个本地 SQLite 数据库来模拟 `source / target`，然后执行真实的 `plan / check / report show`：

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

第二层验证脚本覆盖 JDBC 方言适配、`jdbc <-> iceberg` 真实对比和 PostgreSQL E2E：

```powershell
. .\scripts\use-java17.ps1
.\scripts\verify-second-layer.ps1
```

如果当前环境没有 Docker，且希望像第一层一样在本地固定目录落报告和状态文件：

```powershell
. .\scripts\use-java17.ps1
.\scripts\verify-second-layer-local.ps1
```

本地第二层场景矩阵：

| 场景 | 结果 | 路径 | 说明 |
| --- | --- | --- | --- |
| `postgres_simulated_jdbc` | `CONSISTENT` | `global row_count + global checksum` | 无 Docker 环境下用 `dialect: postgres` 走真实 CLI/JDBC 流程，底层由本地 SQLite 承载数据 |
| `hive_jdbc_partitioned` | `DIFF_FOUND` | `global row_count + grouped checksum -> exact diff` | 已定位 `dt=2026-03-10` |
| `doris_jdbc_result_diff` | `DIFF_FOUND` | `global row_count + global checksum -> exact diff` | 根因 `value_mismatch` |
| `jdbc_to_iceberg_consistent` | `CONSISTENT` | `global row_count + grouped checksum` | JDBC 源端与 Iceberg 目标端的真实一致性校验 |
| `jdbc_to_iceberg_diff` | `DIFF_FOUND` | `global row_count + grouped checksum -> exact diff` | 根因 `value_mismatch` |
| `iceberg_to_jdbc_partitioned` | `DIFF_FOUND` | `global row_count + grouped checksum -> exact diff` | 根因 `value_mismatch`，已定位 `dt=2026-03-10` |

脚本执行成功后，验证产物会落到仓库根目录下的 `.tmp/verify-second-layer`，每个场景生成 `report.json`、`report.html`、`manifest.json`、`suspect_slices.csv`、`row_diff_sample.csv`、`state.db`。

脚本内部执行的测试类：

- `SqliteDialectCliIntegrationTest` — 用真实 `connector-jdbc` 分别验证 `dialect: hive` 和 `dialect: doris`，确认 planner 路径、分段逻辑和报告输出在通用 JDBC 模型下成立
- `ReflectionIcebergMetadataReaderTest` — 验证 `connector-iceberg` 能读取本地 Iceberg table 的 snapshot、schema 和 manifest hints
- `IcebergMetadataCliIntegrationTest` — 验证 `jdbc -> iceberg` 和 `iceberg -> jdbc` 会进入 `metadata-first` 路径并执行真实 diff
- `JdbcCliIntegrationTest` — 使用 Testcontainers 跑 PostgreSQL 真实 JDBC E2E；无 Docker 时明确标记为 `SKIPPED`

## 发布流程

版本发布由 `v*` 标签触发（`.github/workflows/release.yml`）：流水线会先把 POM 版本对齐到 tag（`versions:set`），以 `-Dgit.commit=$GITHUB_SHA` 注入提交溯源，跑完整验证后发布 GitHub Release，产物包含完整 CLI jar、AI wrapper jar、CycloneDX SBOM 和 SHA-256 校验文件。构建声明了固定的 `project.build.outputTimestamp` 以支持可复现构建。
