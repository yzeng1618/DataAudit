# DataAudit AI Copilot 验收交付版

## 确定性核验状态

- 状态: 确定性核验发现差异
- 差异范围: `dt=2026-04-24`
- proof_mode: `GROUPED_CHECKSUM`
- 验收建议: 不建议验收通过，需完成确定性复核或修复后重跑。

## 可能原因

- 可能是目标端分区 overwrite 覆盖范围不完整，confidence=0.84。
- 该内容不能替代确定性核验结论。
