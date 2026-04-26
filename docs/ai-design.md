# DataAudit AI Copilot Alpha Design

Date: 2026-04-26
Status: Draft for review

## 1. Positioning

DataAudit AI Copilot is not a sidecar explanation utility. It is an AI-native workflow layer for DataAudit.

DataAudit remains responsible for deterministic audit evidence and final consistency status. The AI Copilot participates in strategy planning, risk discovery, root-cause hypothesis generation, and role-specific report generation.

The core boundary is:

- Deterministic engines decide what was verified: `row_count`, `checksum`, `partition stats`, `bucket diff`, `exact diff`, `sampling`, `proof_mode`, and `confidence`.
- AI decides what should be verified, what risks may exist, how to explain anomalies, and how to produce human-readable reports.
- AI must never execute data modifications, auto-fixes, or declare a deterministic consistency conclusion by itself.

## 2. P0 Scope

Alpha prioritizes three P0 modules:

1. AI-native audit strategy planner.
2. RAG-enhanced difference root-cause analyzer.
3. Multi-role Markdown report generator.

The first implementation should be CLI-first and integrated with the existing Maven project. The recommended shape is:

- Add a new `data-audit-ai` module for AI models, prompt/schema contracts, RAG retrieval, guardrails, strategy planning, analysis, and Markdown rendering.
- Extend `data-audit-cli` with `data-audit ai plan`, `data-audit ai explain`, and `data-audit ai report`.
- Document `dataaudit-ai plan/explain/report` as a thin wrapper command that can be added in a follow-up phase without changing core behavior.
- Add `data-audit check --ai-report` as an integrated post-check workflow after the three standalone commands are stable.

## 3. Core Workflow

The AI workflow should be evidence-first, but AI-first in strategy generation:

```text
table profile
+ field semantics
+ sample data
+ sync context
+ write mode
+ historical cases
+ knowledge base
+ explicit user config
  |
  v
LLM semantic planning + RAG
  |
  v
rule and schema guardrails
  |
  v
DataAudit capability mapping
  |
  v
audit_plan.json
```

The planner should not reduce AI to a supplemental note generator. AI is expected to make the first semantic strategy proposal. Rules and existing DataAudit planning components then constrain, validate, complete, and map that proposal.

When the user already has a DataAudit task YAML, the AI layer should be able to build a richer `table_profile.json` from the existing runtime:

- `TaskFileSpec` supplies source, target, object, normalization, semantics, and output config.
- `SchemaReader` supplies field names, types, and nullability where connectors support it.
- `MetadataReader` supplies table metadata, partitioning, snapshot, and size hints where available.
- `SignalReader` or existing summary engines can supply row counts, checksums, min/max, distinct counts, and partition stats when available.
- User config supplies authoritative overrides such as required keys, ignored columns, forced partitions, and known risk fields.

The AI planner should treat live profile data and explicit config as higher-quality evidence than name-only inference.

## 4. AI Participation Depth

The P0 modules must not degrade into rule checks plus static templates. The intended split is:

| Module | Weak design to avoid | Target AI involvement |
| --- | --- | --- |
| P0-1 AI audit strategy planner | `if/else` based only on table size, key, and partition fields | LLM identifies field semantics, risk fields, verification metrics, execution order, and historical-case-informed checks |
| P0-2 RAG-enhanced root-cause analyzer | Count mismatch maps directly to a generic possible cause | AI extracts anomaly features, retrieves similar cases, reads logs and metrics, ranks hypotheses, and builds evidence chains |
| P0-3 Multi-role report generator | Fill JSON fields into Markdown templates | AI rewrites the same evidence for technical, acceptance, and management audiences, while preserving deterministic status |

The product principle is:

```text
deterministic audit engines provide accuracy
AI Copilot provides intelligence, context, and delivery clarity
```

AI should participate in audit decision support, not deterministic judgment.

## 5. Trust Boundary

DataAudit's core value is trust. AI must not answer questions such as:

- Are these two tables consistent?
- Can this partition pass acceptance?
- Are these 200 differences normal?

Those answers must come from deterministic evidence:

- `row_count`
- `checksum`
- `diff`
- `partition stats`
- `bucket diff`
- deterministic SQL metrics

The reason is practical, not philosophical. LLMs may invent fields, misunderstand SQL semantics, miss boundary conditions, turn hypotheses into conclusions, or produce non-repeatable answers.

The correct boundary is:

| Area | Owner |
| --- | --- |
| SQL execution, metric collection, checksums, diffs, final status | Deterministic DataAudit engine |
| Strategy recommendation, field semantics, risk discovery, anomaly explanation, knowledge retrieval, next checks, role-specific reports | AI Copilot |

This boundary should be visible in every output schema. AI outputs use `confidence` as hypothesis confidence, while DataAudit outputs use `proof_mode` and deterministic `confidence` as audit evidence strength.

## 6. P0-1: AI-Native Audit Strategy Planner

### 6.1 Input

The planner input is `table_profile.json`. It should support more than static schema:

- Source and target system types.
- Source and target table names or query identities.
- Table size estimates: rows, bytes, partition count.
- Field names, types, nullability, precision, scale, comments, and tags.
- Primary key or configured unique keys when known.
- Partition fields when known.
- Sample rows or redacted sample values.
- Basic column statistics when available: null ratio, distinct count, min/max, top values, length distribution.
- Sync context: batch, CDC, full refresh, incremental, snapshot, time-window.
- Write mode: append, upsert, overwrite, merge, stream load, copy into.
- Data boundary: job finish, partition, snapshot, version, time range.
- Historical case references or retrieval corpus.
- User overrides from config, such as forced key fields, ignored columns, required metrics, and known risk fields.

The input should allow missing sections. Missing data must be surfaced in `missing_information`, not silently ignored.

### 6.2 Profile Builder and Config Fallback

Alpha should support two profile creation modes:

- Direct profile mode: the user provides `table_profile.json`.
- DataAudit task mode: the user provides an existing task YAML, and the AI module builds `table_profile.json` from DataAudit config plus connector metadata.

The fallback direction is config-backed, not rule-only:

- If AI identifies a likely key but the config explicitly sets `object.key`, the config key wins and the AI candidate remains as supporting context.
- If AI identifies `dt` as a partition field and the config has no `object.partition_by`, the planner may recommend writing `dt` into the generated DataAudit config proposal, but it must mark the recommendation as `requires_user_confirmation`.
- If live schema exposes decimal precision, timestamp type, or semi-structured field types, these values become evidence for risk analysis.
- If only field names are available, AI may infer semantics but must lower confidence and list missing statistics or samples.
- If LLM output omits mandatory safe checks, guardrails insert global `row_count` and checksum steps from deterministic DataAudit defaults.

### 6.3 AI Responsibilities

The LLM participates in strategy planning by making these judgments:

- Identify candidate business keys from names, comments, types, uniqueness stats, and sample values.
- Identify partition fields, time fields, amount/metric fields, enum fields, text fields, and semi-structured fields.
- Identify precision, timezone, partition-shift, schema-drift, null-handling, and write-mode risks.
- Recommend verification metrics per field category.
- Recommend audit step order based on scale, partitioning, write mode, and available statistics.
- Generate SQL or DSL templates for deterministic checks.
- Explain why each check is needed.
- Identify missing information that would improve the strategy.

Examples:

- `id`, `user_id`, and `order_no` may be candidate business keys.
- `dt` is likely a partition field.
- `amount` is likely a metric field and should receive `SUM`, `MIN`, `MAX`, and decimal precision checks.
- `status` is likely an enum field and should receive distribution checks.
- `create_time` and `update_time` should receive min/max and timezone boundary checks.
- `payload`, `extra_info`, `properties`, `json`, `variant`, and `map` fields should receive semi-structured checks such as key existence, schema drift, null or empty object ratio, and normalized JSON hash.

### 6.4 Verification Metric Recommendations

The planner should recommend field-aware deterministic metrics, not only global row count and checksum.

| Field category | Recommended checks |
| --- | --- |
| Amount or metric fields | `SUM(field)`, `MIN(field)`, `MAX(field)`, decimal scale and precision checks |
| Enum or status fields | `GROUP BY field COUNT(*)`, top value distribution comparison, unexpected enum value detection |
| Time fields | `MIN(field)`, `MAX(field)`, timezone offset checks, incremental boundary checks |
| Partition fields | partition row count, partition checksum, missing or extra partition detection |
| Text fields | null rate, empty string rate, length distribution, optional normalized checksum |
| JSON, Variant, Map, or semi-structured fields | key count distribution, required path existence, schema drift, null or empty object ratio, normalized JSON hash |
| Candidate business keys | distinct count, duplicate detection, bucket diff, exact diff key selection |

For example, if the profile contains:

```text
extra_info variant
payload json
properties map
```

The AI planner should recommend:

- JSON key count distribution checks.
- Critical path existence checks when sample paths or config paths are available.
- Schema drift checks between source and target.
- Null and empty object ratio checks.
- Normalized JSON hash checks for deterministic comparison.

### 6.5 Knowledge-Enhanced Planning

RAG should influence the strategy before execution. Historical cases should be retrieved from source/target type, write mode, field types, and symptoms in the table profile.

Examples:

- Oracle `decimal(20,0)` or `number` to lakehouse decimal mapping should increase the priority of decimal precision checks.
- Oracle, MySQL, or PostgreSQL timestamp fields moving into Iceberg, Hive, or Trino should increase the priority of timezone and min/max checks.
- `write_mode=overwrite` with partition fields should increase the priority of partition row count, partition checksum, and missing partition checks.
- Flink CDC or incremental mode should increase the priority of update time boundary checks and source/sink record metric comparison.
- Doris Stream Load contexts should increase the priority of sink retry, redirect, and commit-log checks in root-cause analysis.

The strategy output should explain which retrieved cases affected the plan.

### 6.6 Rule and Schema Guardrails

Rules are not the primary planner, but they are mandatory guardrails:

- Validate all LLM output against JSON Schema.
- Reject mutation SQL: `insert`, `update`, `delete`, `merge`, `truncate`, `drop`, `alter`, `create`, `replace`.
- Require every AI risk and root-cause item to include `confidence`, `evidence`, and `missing_information`.
- Require every recommended step to state its trigger condition, deterministic evidence type, SQL/DSL template, risk points, and execution mapping.
- Ensure no AI field states or implies deterministic consistency.
- Fill minimum safe checks when LLM output is missing or provider is disabled:
  - global row count
  - global checksum
  - partition row count and checksum when partition fields exist
  - bucket diff or exact diff when key fields exist and mismatch evidence requires localization

### 6.7 Existing DataAudit Mapping

The AI plan must be mapped to existing DataAudit concepts, but the mapping is a capability layer, not the primary source of strategy.

The mapping should include:

- `TaskFileSpec` projection where enough information exists.
- `ScaleClassifier` result as deterministic scale evidence.
- `PlanningService` / `ExecutionPlan` output as the currently executable core plan.
- Existing proof concepts: `GLOBAL_CHECKSUM`, `GROUPED_CHECKSUM`, `ROUTING_DIGEST`, `XOR_CHECKSUM_PLUS_SAMPLE`, `SAMPLING`, `EXACT_DIFF`.
- Existing result confidence: `EXACT`, `HIGH`, `MEDIUM`, `LOW`.
- Per-step support status:
  - `SUPPORTED`: current DataAudit can execute this directly.
  - `PARTIAL`: current DataAudit can provide weaker or related deterministic evidence.
  - `UNSUPPORTED`: strategy is valid but not executable by current DataAudit.

This mapping lets the plan be ambitious while staying honest about what Alpha can run.

### 6.8 Output

`audit_plan.json` should contain:

- Table classification.
- Semantic analysis.
- Risk analysis.
- Recommended audit metrics.
- Recommended execution steps.
- SQL/DSL templates.
- Existing DataAudit plan mapping.
- Retrieved historical cases.
- Guardrail validation results.
- Missing information.

Illustrative shape:

```json
{
  "plan_id": "orders_ai_plan",
  "planner_mode": "llm_with_rule_guardrails",
  "table_classification": {
    "table_type": "large_partitioned_fact_table",
    "scale_class": "large",
    "reasoning": [
      "estimated_rows indicates large scale",
      "dt appears to be a daily partition field",
      "amount and status indicate fact-table style metrics"
    ]
  },
  "semantic_analysis": {
    "candidate_primary_keys": [
      {
        "field": "order_no",
        "confidence": 0.86,
        "evidence": ["field name contains order_no", "sample values look unique"],
        "missing_information": ["distinct_count for order_no"]
      }
    ],
    "partition_fields": ["dt"],
    "time_fields": ["create_time", "update_time"],
    "metric_fields": ["amount"],
    "enum_fields": ["status"],
    "semi_structured_fields": ["payload"]
  },
  "risk_analysis": [
    {
      "risk_type": "decimal_precision",
      "fields": ["amount"],
      "confidence": 0.88,
      "evidence": ["amount is decimal(20,4)", "source and target types differ"],
      "recommended_checks": ["sum_check", "min_max_check", "scale_precision_check"],
      "missing_information": []
    }
  ],
  "recommended_steps": [
    {
      "step_id": "partition_amount_sum",
      "title": "Partition amount sum check",
      "deterministic_evidence_type": "aggregate_metric",
      "trigger_condition": "metric field exists and table is partitioned",
      "sql_template": "select dt, sum(amount) as amount_sum from ${table} group by dt",
      "risk_points": ["decimal precision drift", "partition overwrite loss"],
      "execution_mapping": {
        "dataaudit_capability": "custom_metric_sql",
        "support_status": "PARTIAL",
        "fallback": "partition checksum"
      },
      "missing_information": []
    }
  ],
  "dataaudit_mapping": {
    "scale_classifier": "large",
    "execution_plan": {
      "signal_strategy": "global_row_count_plus_grouped_checksum",
      "localization_strategy": "partition_window",
      "proof_mode": "GROUPED_CHECKSUM"
    }
  },
  "planner_trace": {
    "retrieved_cases": ["iceberg_partition_overwrite", "oracle_decimal_precision"],
    "guardrails": ["json_schema_valid", "no_mutation_sql_detected"]
  }
}
```

## 7. P0-2: RAG-Enhanced Root-Cause Analyzer

### 7.1 Input

The analyzer consumes:

- `audit_plan.json`
- `audit_result.json` or existing DataAudit `report.json`
- Task context
- Log snippets
- Metrics
- Historical cases
- Knowledge base content

It must understand the existing `ReportModel` evidence fields:

- `result.status`
- `result.root_cause`
- `result.proof_mode`
- `result.confidence`
- `result.suspect_slices`
- `result.diff.samples`
- `evidence.global_signal`
- `evidence.localization`
- `evidence.exact_diff`
- `evidence.notes`

### 7.2 AI Responsibilities

The analyzer produces possible causes and next checks. It must never promote a hypothesis to a deterministic conclusion.

It should:

- Summarize anomalies from deterministic evidence.
- Retrieve similar historical cases.
- Rank possible root causes.
- Attach evidence chains to each hypothesis.
- State confidence as AI hypothesis confidence, separate from deterministic proof confidence.
- Recommend next deterministic checks.
- Identify missing information.

### 7.3 Historical Case Retrieval

The analyzer should not rely only on simple rules such as `row_count mismatch -> missing rows`. It should extract anomaly features and retrieve similar cases before ranking hypotheses.

Feature extraction should include:

- Which deterministic checks failed: global count, checksum, partition stats, metric sums, enum distribution, bucket diff, exact diff.
- Whether the anomaly is global or concentrated in partitions, buckets, time windows, or enum values.
- Source and target types.
- Sync mode and write mode.
- Boundary type and boundary reference.
- Deterministic proof strength from `proof_mode` and audit `confidence`.
- Log and metric signals such as source records, sink records, checkpoint status, commit success, and retry or redirect messages.

Example:

```text
current anomaly:
target has fewer rows, differences concentrate in one dt partition, write_mode=overwrite

retrieved case:
Iceberg overwrite partition coverage mismatch caused missing target rows

AI hypothesis:
overwrite partition coverage may be incomplete; inspect snapshot and target partition file changes
```

### 7.4 Log and Metric Understanding

The analyzer should accept log snippets and metric summaries from systems such as SeaTunnel, Flink, YARN, Prometheus, JDBC sinks, Iceberg writers, and Doris Stream Load.

AI should use these signals to distinguish:

- Task failure causing incomplete output.
- Task success with data inconsistency.
- Source read completeness issue.
- Sink write, retry, commit, filtering, or overwrite issue.
- Boundary drift or incremental window mismatch.

For example:

```text
audit evidence:
source_count=10000000, target_count=9999800

metrics:
source_records=10000000, sink_records=9999800

logs:
sink commit success
```

The AI hypothesis should lean toward sink write, filter, commit, or target overwrite issues, while still listing missing details such as sink commit files, rejected row counts, and target snapshot changes.

### 7.5 Evidence Chain Output

Root-cause analysis must be evidence-chain-oriented. Each possible cause should include direct audit evidence, retrieved case evidence, log or metric evidence, missing information, and next deterministic checks.

Illustrative shape:

```json
{
  "possible_cause": "target partition overwrite coverage may be incomplete",
  "confidence": 0.82,
  "evidence": [
    "diff is concentrated in dt=2026-04-24",
    "write_mode=overwrite",
    "log snippet contains overwrite partition dt=2026-04-24",
    "target row count is 200 lower than source row count"
  ],
  "retrieved_cases": [
    "iceberg_overwrite_partition_missing_rows"
  ],
  "missing_information": [
    "Iceberg snapshot manifest changes",
    "sink commit detail",
    "target partition file count before and after job"
  ],
  "recommended_checks": [
    "inspect Iceberg snapshot for dt=2026-04-24",
    "compare target partition file counts",
    "run bucket diff for dt=2026-04-24"
  ]
}
```

### 7.6 Output

`root_cause_analysis.json` should contain:

- `anomaly_summary`
- `possible_root_causes`
- `confidence`
- `evidence`
- `retrieved_cases`
- `recommended_checks`
- `missing_information`
- `ai_safety_notice`

The safety notice should explicitly say that the analysis is probabilistic and does not replace DataAudit deterministic results.

## 8. P0-3: Multi-Role Audit Report Generator

### 8.1 Templates

Alpha supports at least three templates:

- `technical`
  - anomalous partitions
  - SQL investigation statements
  - log evidence
  - metric evidence
  - next diff commands
  - retrieved similar cases
- `acceptance`
  - deterministic audit status
  - difference scope
  - risk level
  - acceptance recommendation
  - next handling suggestion
- `management`
  - overall status
  - impact scope
  - current risk
  - expected handling path
  - whether release or acceptance is blocked

This makes the report generator an AI delivery assistant instead of a static Markdown template writer. The same evidence should be rewritten for different audiences without changing the deterministic facts.

### 8.2 Report Rules

The report generator may use AI for language and structure, but deterministic fields must be copied or derived from audit evidence:

- Pass/fail comes from `audit_result.status` or existing `report.result.status`.
- Difference scope comes from `suspect_slices`, diff samples, and metric mismatches.
- Proof strength comes from `proof_mode` and deterministic `confidence`.
- AI root-cause items must remain phrased as possible causes.

The report must not state that AI verified data consistency.

### 8.3 Suggested Follow-Up Questions

Reports should include a short "questions you may ask next" section when useful. This is not full interactive Q&A in Alpha, but it prepares the product direction for a future Copilot conversation layer.

Example questions:

- Why not run a full-table exact diff?
- Does this difference block acceptance?
- Why is overwrite currently a likely hypothesis?
- What is the smallest next deterministic check?
- If the job must be rerun, which partition or bucket should be rerun first?

## 9. RAG and Knowledge Base

Alpha should start with a local corpus:

```text
examples/ai-copilot/cases/*.json
examples/ai-copilot/knowledge/*.md
```

Case files should include:

- case id
- title
- source type
- target type
- sync mode
- write mode
- symptoms
- evidence patterns
- likely causes
- recommended checks
- tags

Initial cases should cover:

- Oracle decimal precision drift.
- Iceberg partition overwrite causing missing target partitions.
- Flink CDC incremental boundary missing rows.
- Doris Stream Load 307 redirect or retry behavior.
- RAG dataset embedding dimension mismatch.

The first implementation can use lexical retrieval with scoring by tags, source/target type, risk type, and symptom keywords. Vector retrieval can be added in a follow-up phase behind the same `RagRetriever` interface.

## 10. One-Month Alpha Priorities

The first Alpha should emphasize capabilities that visibly require AI while staying trustworthy:

| Capability | Module | One-month recommendation |
| --- | --- | --- |
| Field semantic recognition | P0-1 | Build |
| Risk field recognition | P0-1 | Build |
| Strategy generation from table profile | P0-1 | Build |
| Historical-case adjustment to strategy | P0-1 | Build a simplified RAG version |
| SQL generation | P0-1 | Generate templates only; do not execute AI SQL directly |
| Anomaly feature extraction | P0-2 | Build |
| Historical failure retrieval | P0-2 | Build a simplified RAG version |
| Root-cause hypothesis ranking | P0-2 | Build |
| Evidence chain generation | P0-2 | Build |
| Automatic repair | P0-2 | Out of scope |
| Multi-role report rewriting | P0-3 | Build |
| Automatic summary | P0-3 | Build |
| Investigation command generation | P0-3 | Build as suggestions |
| Interactive Q&A | P0-3 | Out of scope |

The five strongest AI signals for an Alpha demo are:

1. Field semantic recognition.
2. Risk field recognition.
3. RAG retrieval of historical cases.
4. Root-cause evidence chain generation.
5. Multi-role report generation.

## 11. Demo Flow

A strong demo should show AI participating in decision support while deterministic engines keep the final audit status trustworthy.

Step 1: input table profile:

```json
{
  "table": "t_order",
  "columns": [
    {"name": "order_id", "type": "varchar", "comment": "order number"},
    {"name": "user_id", "type": "bigint", "comment": "user id"},
    {"name": "amount", "type": "decimal(20,2)", "comment": "order amount"},
    {"name": "status", "type": "varchar", "comment": "order status"},
    {"name": "create_time", "type": "timestamp", "comment": "created time"},
    {"name": "dt", "type": "date", "comment": "partition date"}
  ],
  "has_primary_key": false,
  "estimated_size_gb": 500,
  "partition_keys": ["dt"],
  "source": "oracle",
  "target": "iceberg"
}
```

Step 2: AI semantic analysis:

```json
{
  "candidate_business_keys": ["order_id"],
  "metric_fields": ["amount"],
  "enum_fields": ["status"],
  "time_fields": ["create_time", "dt"],
  "risk_fields": [
    {
      "field": "amount",
      "risk": "decimal precision mapping risk"
    },
    {
      "field": "create_time",
      "risk": "timestamp timezone conversion risk"
    },
    {
      "field": "dt",
      "risk": "partition shift risk"
    }
  ]
}
```

Step 3: AI-generated audit strategy:

```json
{
  "strategy": "large_partitioned_table_with_business_key_candidate",
  "steps": [
    "global_row_count",
    "global_checksum",
    "partition_row_count_by_dt",
    "partition_checksum_by_dt",
    "sum_check_for_amount_by_dt",
    "distribution_check_for_status_by_dt",
    "min_max_check_for_create_time_by_dt",
    "bucket_diff_for_abnormal_partition"
  ]
}
```

Step 4: deterministic audit result:

```json
{
  "row_count_equal": false,
  "diff_partition": "dt=2026-04-24",
  "source_count": 500000,
  "target_count": 499800,
  "amount_sum_equal": true,
  "status_distribution_equal": false,
  "write_mode": "overwrite",
  "logs": [
    "overwrite partition dt=2026-04-24",
    "sink commit success"
  ]
}
```

Step 5: RAG-enhanced root-cause hypotheses:

```json
{
  "retrieved_cases": [
    "Iceberg overwrite partition coverage mismatch caused missing target rows",
    "status enum mapping mismatch caused distribution drift"
  ],
  "likely_causes": [
    {
      "cause": "target partition overwrite coverage may be incomplete",
      "confidence": 0.84,
      "evidence": [
        "difference is concentrated in one dt partition",
        "write_mode=overwrite",
        "log contains overwrite partition",
        "target has 200 fewer rows"
      ]
    }
  ]
}
```

Step 6: generate role-specific reports:

- Technical report: SQL checks, log evidence, metrics, bucket diff suggestions.
- Acceptance report: deterministic status, affected scope, risk level, acceptance recommendation.
- Management summary: overall status, business impact, blocker status, next handling path.

## 12. CLI Design

Recommended commands:

```bash
data-audit ai plan --input table_profile.json --output audit_plan.json
data-audit ai explain --plan audit_plan.json --result audit_result.json --output root_cause_analysis.json
data-audit ai report --plan audit_plan.json --result audit_result.json --analysis root_cause_analysis.json --template technical --output audit_report.md
```

Documented wrapper commands:

```bash
dataaudit-ai plan --input table_profile.json --output audit_plan.json
dataaudit-ai explain --plan audit_plan.json --result audit_result.json --output root_cause_analysis.json
dataaudit-ai report --plan audit_plan.json --result audit_result.json --analysis root_cause_analysis.json --template technical --output audit_report.md
```

Future integrated workflow:

```bash
data-audit check -f task.yaml --ai-report
```

This future command should run deterministic `check` first, then generate AI analysis and Markdown reports from the resulting `report.json`.

## 13. Example Set

Alpha should include examples for:

1. Large partitioned table without primary key.
2. Small table with primary key.
3. Global checksum mismatch.
4. Partition diff.
5. RAG dataset embedding dimension mismatch.

Each example should include:

- input profile or audit result
- generated `audit_plan.json`
- generated `root_cause_analysis.json` when relevant
- generated Markdown report when relevant

## 14. Implementation Boundaries

The AI module should avoid introducing mandatory network dependencies in Alpha. The implementation should support:

- `provider=disabled`: rule and local RAG only
- `provider=mock`: deterministic test fixture responses
- `provider=openai` or other providers through `AiClient` in a follow-up provider integration

LLM output must use JSON Schema as much as possible. Because provider support differs, the local system must still validate parsed JSON and repair or reject unsafe output.

No generated SQL should be executed by the AI module. SQL templates are recommendations only unless explicitly mapped to existing deterministic DataAudit execution paths in a follow-up execution feature.

## 15. Testing Strategy

Tests should cover:

- Semantic planning from representative table profiles.
- Guardrail rejection of mutation SQL.
- Required `confidence`, `evidence`, and `missing_information` fields.
- Mapping AI steps to supported, partial, and unsupported DataAudit capabilities.
- Root-cause analyzer phrasing as hypotheses rather than conclusions.
- Markdown report deterministic status derivation from audit results.
- All five required examples.

The first implementation should keep tests deterministic by using rule mode and mock AI responses.
