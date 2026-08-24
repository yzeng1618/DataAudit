# AI Copilot 使用指南

AI Copilot Alpha 的目标是让 AI 参与核验策略规划、风险识别、根因假设和交付表达，但**不让 AI 直接判断数据是否一致**。最终一致性状态仍然只能来自确定性核验结果，例如 `row_count`、`checksum`、partition stats、bucket diff 和 exact diff。

> 下文的 `data-audit` 代表 `java -jar data-audit-cli/target/data-audit.jar`。

## 命令入口

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

## Provider 配置

AI provider **默认关闭**，命令会走确定性规则和本地 RAG fallback。需要接入外部结构化 JSON provider、OpenAI-compatible provider 或官方 OpenAI Java SDK provider 时，可以显式传入：

```bash
data-audit ai plan --task task.yaml --output audit_plan.json --ai-provider http-json --ai-endpoint http://localhost:8080/ai
data-audit ai explain --plan audit_plan.json --result audit_result.json --output root_cause_analysis.json --ai-provider http-json --ai-endpoint http://localhost:8080/ai
data-audit ai report --plan audit_plan.json --result audit_result.json --analysis root_cause_analysis.json --template technical --output audit_report.md --ai-provider http-json --ai-endpoint http://localhost:8080/ai
```

OpenAI-compatible provider 示例（模型名替换为你的服务实际提供的模型）：

```bash
data-audit ai plan --task task.yaml --output audit_plan.json --ai-provider openai-compatible --ai-endpoint https://your-compatible-base-url --ai-model your-model-name
```

官方 OpenAI SDK provider 示例：

```bash
data-audit ai plan --task task.yaml --output audit_plan.json --ai-provider openai-sdk --ai-model your-model-name
```

也可以使用环境变量：`DATAAUDIT_AI_PROVIDER`、`DATAAUDIT_AI_ENDPOINT`、`DATAAUDIT_AI_API_KEY`、`DATAAUDIT_AI_MODEL`。provider request 会携带 compact response schema，provider 输出会先经过结构化校验、SQL 安全检查、必填字段护栏和确定性 baseline 补齐；失败时默认回退到规则路径。API key 不要写入仓库文件；本地测试时请放在 shell 环境变量或被 `.gitignore` 忽略的 `.env` 文件中。

## `check --ai-report`

`check --ai-report` 会先运行确定性 `check`，再在 `output.dir` 下写出 AI sidecar：

- `table_profile.json`
- `profile_review.md`
- `ai_audit_plan.json`
- `root_cause_analysis.json`
- `ai_audit_report_<template>.md`

AI sidecar 生成失败不会改变确定性 check 的退出码或 `report.json` 内容。

## Table Profile 与 Quality Gate

默认产品流程是用户只提供现有 `task.yaml`：

```text
task.yaml
  -> 自动构建 table_profile.json
  -> Profile Quality Gate
  -> audit_plan.json / profile_review.md
```

`table_profile.json` 是系统生成的中间画像，不要求用户手写。系统默认读取 task 中的 source、target、object、normalize、semantics 和 output 配置；connector schema/metadata/signal 是优先证据；stats/sample 是有限采集，带超时、最大行数和最大字段数限制。采集失败会降级为 `missing_information`，不会阻断普通 plan。采集限制可通过 `--profile-max-sample-rows`、`--profile-max-sample-fields`、`--profile-timeout-ms` 或 `DATAAUDIT_PROFILE_MAX_SAMPLE_ROWS`、`DATAAUDIT_PROFILE_MAX_SAMPLE_FIELDS`、`DATAAUDIT_PROFILE_TIMEOUT_MS` 调整。

Profile Quality Gate 有三种状态：

- `CONFIRMED`：画像质量足够，直接生成 `audit_plan.json`。
- `REVIEW_REQUIRED`：只有 key、partition、write_mode、sync_mode、timezone 这些高影响项低置信或冲突时触发；系统写出 `profile_review.md` 和控制台摘要，并以退出码 `6` 停止生成 plan。
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

显式配置优先于 AI 推断；例如 `object.key`、`object.partition_by`、`normalize.timezone` 和 `semantics.ai.write_mode` 会作为强证据进入 plan。

## 本地 RAG

本地 RAG 默认会在存在时加载 `examples/ai-copilot/cases/*.json`，也可以通过 `--rag-corpus` 或 `DATAAUDIT_AI_RAG_CORPUS` 指定历史案例目录。检索模式通过 `--rag-mode lexical|vector|hybrid` 或 `DATAAUDIT_AI_RAG_MODE` 选择，默认 `hybrid`。`vector` 模式当前使用离线确定性的 `HashingEmbeddingClient`，适合 CI 和 demo；后续可在 `EmbeddingClient` 边界后替换为真实 embedding 服务。

## Repair 与 Ask

`data-audit ai repair` 会生成可 review 的 `repair_plan.json`，并可用 `--task task.yaml --patched-task patched-task.yaml` 输出配置级 task YAML copy。它只会写入 `normalize.timezone`、`semantics.ai.write_mode`、`semantics.ai.sync_mode` 这类安全配置补丁；source/target 数据不会被自动修改。

`data-audit ai ask` 是单轮 Q&A，回答会区分 deterministic facts、AI hypotheses 和 recommended checks。

## 数据安全

默认不落原始敏感样本，只写脱敏值、hash、模式或统计摘要。真实样本、明文主键、手机号、邮箱、证件号等不应进入 `table_profile.json`。

## 延伸阅读

- 完整设计：[ai-design.md](ai-design.md)
- RAG 生产化：[rag-production.md](rag-production.md)
- Python agent sidecar（可选 LangGraph 工作流）：[../data-audit-agent/README.md](../data-audit-agent/README.md)
- 输入/输出示例：[../examples/README.md](../examples/README.md)
