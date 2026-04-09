# data-audit Current Design

本文档描述当前代码已经实现的执行模型，不再保留旧版 `object_class / selected_path / schema-first` 设计。

## 1. 总体目标

`data-audit` 是一个任务后一致性审计 CLI。它不参与同步链路，只在稳定边界上比较 `source / target` 的结果状态，并输出：

- `status`
- `root_cause`
- `proof_mode`
- `confidence`
- `suspect_slices`
- `diff sample`

核心原则是：

- 先用低成本信号判断是否异常
- 再把范围从全局收窄到局部
- 只在必要时执行 `exact diff`
- 报告必须说明本次结论的证据强度

## 2. 执行模型

当前实现已经统一为 scale-driven pipeline：

- `small`
  - `global row_count + global checksum`
  - 一致则直接结束
  - 不一致时进入 `exact diff`
- `large`
  - `global row_count + grouped checksum`
  - 先定位异常分片
  - 再对异常分片做 `exact diff`
- `xlarge`
  - 优先读取 `metadata / routing digest`
  - 定位异常路由组
  - 对异常组做 `exact diff`
  - 无稳定切分且无 key 时退化到 `sampling`

## 3. 规模判定

规模先由 `planner.scale_override` 决定；如果没有显式覆盖，则按 `object.estimated_rows / object.estimated_bytes` 分类：

- `small`
  - `estimated_rows <= 100000`
  - 且 `estimated_bytes <= 300MB` 或未填写
- `xlarge`
  - `estimated_rows > 100000000`
  - 或 `estimated_bytes > 30GB`
- 其余都是 `large`
- 两者都缺失时，默认 `large`

## 4. Boundary

当前只保留 boundary gate，不再把 `schema / ddl` 当作前置执行门槛。

支持的稳定条件：

- `job_finish`
- 指定时间范围稳定
- `snapshot / version`

边界不稳定时直接返回：

- `status = UNSTABLE_BOUNDARY`

此时不产出数据类 `root_cause`。

## 5. Planner 输出

当前 `plan` 输出的核心字段只有：

- `scale_class`
- `signal_strategy`
- `localization_strategy`
- `proof_mode`
- `decision_trace`
- `boundary`
- `reason`
- `refuse_reason`

已经移除：

- `object_class`
- `selected_path`
- `executed_levels`
- `signal_backend`

## 6. 证明方式与置信度

当前实现中的 `proof_mode`：

- `GLOBAL_CHECKSUM`
- `GROUPED_CHECKSUM`
- `ROUTING_DIGEST`
- `XOR_CHECKSUM_PLUS_SAMPLE`
- `SAMPLING`
- `EXACT_DIFF`

当前实现中的 `confidence`：

- `EXACT`
- `HIGH`
- `MEDIUM`
- `LOW`

约束如下：

- 完成 `exact diff` 才会给 `EXACT`
- 小表全局一致时使用 `GLOBAL_CHECKSUM + HIGH`
- 无 key 大表一致或异常时使用 `XOR_CHECKSUM_PLUS_SAMPLE + MEDIUM`
- 超大表无稳定切分 fallback 到采样时使用 `SAMPLING + LOW`

## 7. Root Cause

数据类根因固定为四类：

- `boundary_drift`
- `row_count_mismatch`
- `duplicate_or_missing`
- `value_mismatch`

优先级是：

`boundary_drift > row_count_mismatch > duplicate_or_missing > value_mismatch`

其中：

- `row_count_mismatch` 用于数量漂移
- `duplicate_or_missing` 用于 multiset / 缺失 / 多余行
- `value_mismatch` 用于行内容不一致

## 8. No-Key Fallback

当前无 key 场景的行为如下：

- `large` 且无 `partition_by / group_by / key`
  - 使用 `no_key_xor`
  - `proof_mode = XOR_CHECKSUM_PLUS_SAMPLE`
  - `no_key_mode = true`
  - `fallback_reason = no_key_xor_fallback`
- `xlarge` 且无稳定切分且无 key
  - 使用 `sampling`
  - `proof_mode = SAMPLING`
  - `confidence = LOW`

## 9. 报告模型

当前报告顶层包含：

- `plan`
- `result`
- `evidence`

其中：

- `plan`
  - `scale_class`
  - `signal_strategy`
  - `localization_strategy`
  - `decision_trace`
  - `boundary`
  - `reason`
- `result`
  - `status`
  - `root_cause`
  - `proof_mode`
  - `confidence`
  - `no_key_mode`
  - `fallback_reason`
  - `suspect_slices`
  - `sampling_summary`
  - `diff`
- `evidence`
  - `global_signal`
  - `localization`
  - `exact_diff`
  - `notes`

已经从公开 schema 中移除：

- `schema_issues`
- `dml_audit`
- `ddl_audit`
- `signal_backend`

## 10. Connector 能力

当前主路径仍然是三类 connector：

- `trino`
  - 支持 grouped signal pushdown
  - 支持 routing signal pushdown
- `jdbc`
  - 支持 global / grouped signal
  - 作为通用 fallback
- `iceberg`
  - 支持 snapshot boundary
  - 支持 metadata stats
  - 支持 routing signal

## 11. 本地验证

当前仓库内有两套本地验证脚本：

- `scripts/verify-local-sqlite.ps1`
  - 覆盖小表、大表、无 key fallback、边界拒绝执行等主链路
- `scripts/verify-second-layer-local.ps1`
  - 覆盖 JDBC 方言与 `jdbc <-> iceberg / iceberg -> jdbc` 组合场景

它们已经按新版 schema 和 proof model 更新。

## 12. 迁移说明

如果你仍然在阅读旧资料，请以当前实现为准：

- 不再使用 `object_class`
- 不再使用 `selected_path`
- `diff --segment` 已改为 `diff --slice`
- `suspect_segments.csv` 已改为 `suspect_slices.csv`
- 不再把 `schema / dml / ddl` 作为主报告字段

如需完整规范说明，优先参考：

- `README.md`
- `docs/config-examples.md`
- `templates/*.yaml`
