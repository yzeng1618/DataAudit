# DataAudit AI Copilot 技术排查版

## 确定性核验状态

- 状态: 确定性核验发现差异
- AI 不覆盖该状态。

## AI 可能原因与证据链

- 假设: 可能是 embedding 模型版本或向量维度不一致导致 checksum 不一致，confidence=0.82
  - evidence: 日志或指标包含 embedding_dim; 确定性结果显示 checksum 异常
  - missing_information: 源端和目标端 embedding 模型版本; 向量字段 schema 明细

## 下一步

- 检查 `embedding_dim` 分布
- 核对 embedding 模型版本
