# DataAudit AI Copilot 技术排查版

## 确定性核验状态

- 状态: 确定性核验发现差异
- 状态来源: DataAudit 确定性 audit result，AI 不覆盖该状态。

## AI 可能原因与证据链

- 假设: 可能是 decimal precision/scale 或 normalization 配置差异导致 checksum 不一致，confidence=0.68
  - evidence: 确定性结果显示 checksum mismatch; 日志包含 decimal amount
  - missing_information: 字段级 checksum; decimal scale 配置

## 下一步

- 检查 `normalize.decimal_scale`
- 对 `amount` 执行 SUM/MIN/MAX
