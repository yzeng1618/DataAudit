# Production RAG Retrieval

DataAudit RAG is an auxiliary evidence layer. It can surface similar historical
cases and recommended deterministic checks, but it never replaces `report.json`
or deterministic audit status.

## Default Runtime

Default local and CI execution stays offline:

- retrieval mode: `hybrid`
- lexical retriever: local JSON case files plus built-in cases
- vector retriever: deterministic `local-hashing` embeddings
- vector store: no external service required

No embedding API key, network endpoint, pgvector, Milvus, or Elasticsearch
service is required unless explicitly configured.

## Corpus Authoring Rules

Production case files are JSON files under a corpus directory such as
`examples/ai-copilot/cases/`. Each case must include:

- `id`: stable, unique case id
- `title`: short human-readable case title
- `source_type`: source system or connector family
- `target_type`: target system or connector family
- `symptoms`: observed mismatch or operational symptoms
- `evidence_patterns`: concrete tokens from logs, metrics, reports, or configs
- `likely_causes`: hypothesis wording, not deterministic conclusions
- `recommended_checks`: deterministic checks an operator can run next
- `tags`: retrieval tags for lexical and hybrid matching

Invalid case files are excluded from production retrieval. Duplicate ids do not
override built-in cases.

## External Embedding Configuration

The Java CLI supports optional external embedding configuration for RAG:

```powershell
java -jar data-audit-cli/target/data-audit.jar ai explain `
  --plan audit_plan.json `
  --result report.json `
  --output root_cause_analysis.json `
  --rag-mode hybrid `
  --rag-corpus examples\ai-copilot\cases `
  --rag-embedding-provider http-json `
  --rag-embedding-endpoint http://localhost:8080/embed `
  --rag-embedding-model text-embedding `
  --rag-embedding-api-key $env:EMBEDDING_API_KEY
```

If an external embedding provider fails, DataAudit falls back to
`local-hashing` by default. Add `--rag-embedding-fail-fast` to fail instead.

The `http-json` embedding endpoint accepts:

```json
{"input": "text to embed", "model": "text-embedding"}
```

and may return either:

```json
{"embedding": [0.1, 0.2]}
```

or an OpenAI-style shape:

```json
{"data": [{"embedding": [0.1, 0.2]}]}
```

## Vector Store Boundary

The production boundary is `VectorStore`. The first backend is local in-memory:

```powershell
--rag-mode hybrid --rag-vector-store local-memory
```

Future pgvector, Milvus, Elasticsearch, or managed vector search adapters should
plug in behind the same interface. Default CI must continue to run without
those services.

## Retrieval Quality Fixtures

The required local quality fixtures are:

- `oracle-decimal-drift`
- `iceberg-partition-overwrite`
- `flink-cdc-boundary-miss`
- `doris-stream-load-redirect`

Tests assert that scenario-shaped signals rank the expected case family first.

## Evidence-Linked Output

`root_cause_analysis.json` includes:

- retrieved case ids
- matched evidence references
- hypothesis confidence
- missing information
- recommended deterministic checks

Markdown reports must phrase RAG-informed causes as hypotheses, such as
`假设` or `可能`, and must not turn retrieved cases into deterministic root
cause claims.
