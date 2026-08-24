# 路线图

MVP 阶段是单进程、单命令、单次任务运行的 CLI。未来可以扩展可选控制面（报告汇聚、模板中心、任务目录），但不改变 CLI-first 的产品边界。

## Milestone 1

- CLI 基础框架
- `task.yaml`
- JDBC / SQL source & target
- planner 自动路径选择
- `L1 schema + L2 summary + L3 segment`
- JSON / HTML / CSV 报告
- SQLite state

## Milestone 2

- `snapshot / version / instant / time_window`
- DML result auditor
- DDL evolution auditor
- suspect slice 精确 diff
- `rename / timezone / precision / null` normalization
- resume / report 复查闭环

## Milestone 3

- Iceberg metadata reader
- Hudi timeline / incremental / CDC evidence reader
- Delta CDF evidence reader
- Paimon snapshot / system table reader
- evidence 模式与根因增强
- 可选控制面能力接入
