# data-audit v1 Migration

v1 uses the new Trino-first task schema:

- `task`
- `boundary`
- `query_connector`
- `source`
- `target`
- `object`
- `normalize`
- `semantics`
- `output`

Current scale-driven additions:

- `object.estimated_bytes`
- `planner.scale_override`
- `object.group_by`
- `object.routing_strategy`

Removed top-level blocks:

- `planner`
- `compare`
- `state`
- `evidence`
- legacy top-level `dml` / `ddl`
- legacy `normalization`

Field mapping:

- `planner.hints.estimated_rows` -> `object.estimated_rows`
- `planner.hints.estimated_bytes` -> `object.estimated_bytes`
- `planner.hints.partition_keys` or `compare.segment.by` -> `object.partition_by`
- `compare.segment.by` for non-partition grouping -> `object.group_by`
- `object.columns.include` -> `object.columns`
- `normalization.*` -> `normalize.*`
- `ddl.*` -> `semantics.ddl.*`
- `dml.*` -> `semantics.dml.*`
- `state.path` -> fixed to `<output.dir>/state.db`
- `diff --segment` -> `diff --slice`

Endpoint guidance:

- `type: trino`: recommended Trino query-plane endpoint, requires `query_connector.type: trino`
- `type: sql`: compatibility alias of `type: trino` in v1
- `type: jdbc`: direct fallback path
- `type: iceberg`: native snapshot and metadata path

Execution/report guidance:

- default scale classification is `small / large / xlarge`
- unknown scale defaults to `large`
- `large` without stable split and key falls back to `xor_checksum_plus_sample`
- `xlarge` without stable split and key falls back to `sampling`
- report output now exposes `proof_mode`, `confidence`, `no_key_mode`, and `fallback_reason`
