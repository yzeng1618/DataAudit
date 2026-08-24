# examples

本目录提供两类可直接引用的样例数据：AI Copilot 工作流的输入/输出示例，以及 `report.json` 产物契约的参考样本。所有数据均为虚构 fixture，不包含真实主机、凭据或业务数据。

## ai-copilot/

### cases/ — 本地 RAG 语料

5 个历史案例 JSON（Doris stream load 重定向、embedding 维度不匹配、Flink CDC 边界丢失、Iceberg 分区覆盖、Oracle decimal 漂移）。在仓库根目录运行时，本地 RAG **默认自动加载** `examples/ai-copilot/cases/*.json`；也可以用 `--rag-corpus <dir>` 或环境变量 `DATAAUDIT_AI_RAG_CORPUS` 指向自己的案例目录。检索模式由 `--rag-mode lexical|vector|hybrid` 控制，默认 `hybrid`。

### 场景目录 — 工作流各阶段的输入与输出

| 目录 | 内容 |
|---|---|
| `small-table-with-key/` | 输入 `task.yaml` + 输出 `table_profile.json`、`audit_plan.json`（小表、带主键） |
| `large-partitioned-nokey/` | 输入 `task.yaml` + 输出 `table_profile.json`、`audit_plan.json`（大分区表、无主键） |
| `partition-diff/` | 输出 `audit_plan.json`、`audit_result.json`、`root_cause_analysis.json`、`audit_report.md`（分区差异定位） |
| `checksum-mismatch/` | 输出 `audit_result.json`、`root_cause_analysis.json`、`audit_report.md`（checksum 不一致） |
| `embedding-dim-mismatch/` | 输出 `rag_case.json`、`root_cause_analysis.json`、`audit_report.md`（RAG 命中示例） |

文件角色约定：`task.yaml` 是**输入**；`table_profile.json` 由 `ai profile` 产出；`audit_plan.json` 由 `ai plan` 产出；`audit_result.json` 是核验结果；`root_cause_analysis.json` 与 `audit_report.md` 由 AI 分析/报告环节产出。

复现命令（在仓库根目录，使用 mock provider 无需任何 AI 服务）：

```bash
java -jar data-audit-cli/target/data-audit.jar ai plan \
  --task examples/ai-copilot/small-table-with-key/task.yaml \
  --output /tmp/audit_plan.json --ai-provider mock
```

> **重要限制**：这些 `task.yaml` 刻意省略了 `source.url` / `target.url`，因此**只能用于 `ai profile` / `ai plan` 等 AI 子命令**（宽松加载）。它们不能直接喂给 `plan` / `check` / `diff` —— 执行链路会因缺少 `url` 以退出码 2 拒绝。想跑完整核验请参考 `templates/` 或 `docs/config-examples.md` 中带连接信息的完整配置。

## artifact-contracts/

4 个 `report.json` 契约样本（`jdbc-jdbc`、`jdbc-iceberg`、`iceberg-jdbc`、`trino-query-plane`），展示不同连接器组合下产物的完整字段形态，供下游系统开发解析逻辑时对照。字段语义见 [docs/artifact-contracts.md](../docs/artifact-contracts.md)。

> 注意：这些 fixture 使用 `evidence_value_mode: "raw"` 仅为展示字段的完整形态；生产默认是 `masked`，`raw` 必须显式开启（见 [SECURITY.md](../SECURITY.md)）。
