# data-audit

[![CI](https://github.com/yzeng1618/DataAudit/actions/workflows/ci.yml/badge.svg)](https://github.com/yzeng1618/DataAudit/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)

**English** | [中文](README.zh-CN.md)

> A task-post consistency audit CLI for databases and lakehouse tables.

`data-audit` does not sit in your sync pipeline and does not depend on SeaTunnel, DataX, or Flink CDC. It runs **after** a job finishes, on a stable boundary — `job_finish` for batch, `snapshot` / `version` / `instant` / `time_window` for streaming — and answers the questions a plain "hash compare" cannot:

- Is the result actually correct after the job ran?
- Which partition, snapshot, or time window is wrong?
- Is it missing rows, duplicates, an ineffective delete — or a false alarm caused by DDL / schema evolution?
- Can the next re-check cover only the affected scope?

One-line contrast: classic table-compare tools answer *"do these two tables look alike after migration?"*; `data-audit` answers *"now that the job finished, is this boundary correct — and if not, which rows, and why?"*

## 60-second start

Java 17+ is all you need. Grab `data-audit-*.jar` from [Releases](https://github.com/yzeng1618/DataAudit/releases) (~150 MB — it bundles every connector runtime, nothing else to install), then:

```bash
java -jar data-audit.jar demo
```

`demo` generates two small SQLite databases with three planted differences, writes a ready-to-run `task.yaml`, audits them for real, and shows you **exactly which row differs and in which column**. To audit your own databases:

```bash
java -jar data-audit.jar config init -o task.yaml
# edit task.yaml (see the example below)
java -jar data-audit.jar doctor -f task.yaml
java -jar data-audit.jar check -f task.yaml
```

`doctor` probes the source and target connections by default, so configuration problems surface as a single `[FAIL]` line before anything runs. Building from source is documented in [docs/development.md](docs/development.md) — that path is for contributors, not required for trying it out.

> In the rest of this document, `data-audit` stands for `java -jar data-audit.jar`.

## How it works

1. No mid-sync checking — only post-boundary result auditing.
2. The default path is not a full-table hash. A planner picks the cheapest sufficient route through `global signal -> localization -> exact/sample diff`: small objects go `row_count + checksum`, large/partitioned objects add `grouped checksum -> localization`, extra-large objects start `metadata / routing digest` first, and an unstable boundary refuses to run at all.
3. `exact diff` is the strongest proof; grouped checksums, routing digests, XOR checksums, and sampling narrow the search and grade the confidence. Every report states `proof_mode / confidence / no_key_mode / fallback_reason` explicitly.

Execution stages: `Spec Load -> Capability Discovery -> Boundary Resolve -> Plan Build -> Layered Execute -> Report Persist`. Comparison logic lives only in `data-audit-core`; connectors read data and metadata and advertise capabilities. Full architecture: [docs/design.md](docs/design.md).

| Scale | Typical objects | Typical boundary | Default path |
| --- | --- | --- | --- |
| `small` | JDBC tables, query results | `job_finish` | `row_count + checksum -> exact diff on mismatch` |
| `large` | big / partitioned tables | `job_finish` / `partition` / `time_window` | `grouped checksum -> localization -> exact diff` |
| `xlarge` | lakehouse objects, huge tables | `snapshot` / `version` / `instant` | `metadata / routing digest -> localization -> exact diff or sampling` |

## Configuration example

```yaml
task:
  name: orders_reconcile
  mode: post_check

boundary:
  type: snapshot
  reference: latest

source:
  type: jdbc
  url: jdbc:postgresql://source.example:5432/app
  username: audit_reader
  password: ${SOURCE_PASSWORD}
  query: |
    select order_id, status, amount, update_time, dt
    from public.orders
    where dt = '2026-03-10'
  options:
    dialect: postgres

target:
  type: iceberg
  table: orders
  snapshot_id: latest

object:
  key: [order_id]
  columns: [order_id, status, amount, update_time, dt]
  partition_by: [dt]
  estimated_rows: 5000000

output:
  dir: ./reports/orders_reconcile
```

Full configuration reference: [docs/config-examples.md](docs/config-examples.md). Ready-made task templates live in [`templates/`](templates/).

## Commands

```bash
data-audit demo
data-audit config init -o task.yaml
data-audit config validate -f task.yaml
data-audit doctor -f task.yaml
data-audit plan  -f task.yaml
data-audit check -f task.yaml
data-audit diff  -f task.yaml --slice dt=2026-03-10
data-audit report show ./reports/orders_reconcile/report.json
```

- `demo` — the zero-dependency end-to-end demo described above
- `check` — run the audit; `plan` — build the comparison plan without executing
- `diff` — drill into one suspect slice; `report` — inspect a persisted report
- `config init/validate` — scaffold and lint a task file (offline unless `--test-connection`)
- `doctor` — static checks plus a default connection probe (`--offline` to skip)

Unexpected errors print one `[FAIL] <root cause>` line and exit with code 4; add `--stacktrace` for the full trace.

| Exit code | Meaning |
| --- | --- |
| `0` | consistent |
| `1` | differences found |
| `2` | configuration error |
| `4` | execution failure (connection, permission, driver, or unexpected error) |
| `5` | unstable boundary, refused to run |
| `6` | `ai plan` requires human review (`REVIEW_REQUIRED`) |

Treat `1` as a business alert and `2`/`4`/`5` as operational failures in schedulers.

## Connectors

- `type: trino` — recommended query plane for anything a Trino catalog can expose (MySQL / PostgreSQL / Oracle / Hive / Iceberg / Hudi / Delta / Paimon); `type: sql` is an alias
- `type: jdbc` — direct fallback with dialects for `postgres` / `mysql` / `hive` / `doris`
- `type: iceberg` — native metadata-first path (snapshots, manifests, partition summaries)

**Third-party connectors need no changes to this repository**: implement the [SPI](data-audit-spi/src/main/java/io/github/dataaudit/spi/connector/ConnectorFactory.java), register it in `META-INF/services`, and drop the jar into the directory named by `DATAAUDIT_PLUGINS_DIR`. Built-ins stay first in line, so plugins add endpoint types but never shadow supported ones.

## AI Copilot (alpha)

AI helps with strategy planning, risk hints, root-cause hypotheses, and report writing — it never decides whether data is consistent; that verdict comes only from deterministic checks. The provider is **off by default** and API keys are read from environment variables only.

```bash
data-audit ai plan --task task.yaml --output audit_plan.json
data-audit check -f task.yaml --ai-report
```

Details: [docs/ai-copilot.md](docs/ai-copilot.md).

## Output and evidence

`check` writes `report.json`, `report.html`, `manifest.json`, `suspect_slices.csv`, and `row_diff_sample.csv` into `output.dir`, plus a resumable `state.db`. The console opens with a human-readable summary — row counts, a difference tally, the differing rows themselves in `raw` mode, and the evidence path.

Sample values are protected before persisting (`output.value_mode`):

- `masked` (default) — values become `***`; **row keys stay readable**, they are the investigator's lead
- `hash` — SHA-256 pseudonyms for values, keys stay readable
- `omit` — suppresses keys and values entirely
- `raw` — plaintext; enable only in access-controlled locations

Threat model and details: [SECURITY.md](SECURITY.md).

## Modules

```text
data-audit-spi               extension contracts (connector / state / report SPI)
data-audit-core              comparison engine and planner
data-audit-connector-*      trino / jdbc / iceberg connectors
data-audit-state-sqlite      local run state
data-audit-report            JSON / HTML / CSV report writers
data-audit-ai                AI copilot
data-audit-cli               command line entry (ships data-audit.jar / dataaudit-ai.jar)
data-audit-it                integration tests (Testcontainers, auto-skipped without Docker)
data-audit-agent             optional Python LangGraph sidecar
```

## Documentation

Most in-depth docs are currently written in Chinese; the CLI itself is English.

| Document | Contents |
| --- | --- |
| [README.zh-CN.md](README.zh-CN.md) | full Chinese README |
| [docs/design.md](docs/design.md) | architecture and design |
| [docs/config-examples.md](docs/config-examples.md) | configuration reference |
| [docs/deployment.md](docs/deployment.md) | server / container / scheduler deployment |
| [docs/ai-copilot.md](docs/ai-copilot.md) | AI copilot guide |
| [docs/development.md](docs/development.md) | building from source, verification matrix |
| [docs/roadmap.md](docs/roadmap.md) | roadmap |
| [examples/README.md](examples/README.md) | sample data and fixtures |

## Contributing

Licensed under [Apache 2.0](LICENSE). Please read [CONTRIBUTING.md](CONTRIBUTING.md); report vulnerabilities privately per [SECURITY.md](SECURITY.md).

```bash
./mvnw verify
python -m pytest -q data-audit-agent
```

Releases are cut from `v*` tags with reproducible builds, embedded commit provenance, a CycloneDX SBOM, SHA-256 checksums, and a container image at `ghcr.io/yzeng1618/data-audit` — see [Releases](https://github.com/yzeng1618/DataAudit/releases).
