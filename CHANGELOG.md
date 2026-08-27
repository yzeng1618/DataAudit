# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project intends to use [Semantic Versioning](https://semver.org/).

## [Unreleased]

### Added

- `demo` command: a zero-dependency first run that generates sample SQLite
  data, audits it, and shows the exact differing rows in about a minute.
- Third-party connector plugins: jars in the directory named by
  `DATAAUDIT_PLUGINS_DIR` are discovered at startup, so new connectors do not
  require changes to this repository.
- A human-readable summary (row counts, difference tally, evidence path, and
  the differing rows themselves in `raw` mode) before the structured
  `check`/`diff` output.

### Changed

- `doctor` probes source/target connections by default (`--offline` skips it),
  and the syntax check message no longer overpromises.
- `masked` and `hash` evidence modes keep the row key readable — it is the
  investigator's only lead; only `omit` suppresses keys.
- Unexpected errors print a single `[FAIL] <root cause>` line instead of a
  bare stack trace (`--stacktrace` restores it), and connections fail fast
  after 8 seconds instead of Hikari's 30-second default.
- Console logging moved to stderr at WARN level (project progress stays at
  INFO), and freemarker's stray `log4j:WARN` lines are gone.

## [0.1.0] - 2026-08-25

### Added

- Initial CLI-first data consistency audit implementation.
- Apache-2.0 open-source governance baseline.
- `config init`, offline `config validate`, and aggregated `doctor` commands.
- Maven 3.9.9 Wrapper, Java/Maven build gates, CycloneDX SBOM generation,
  cross-platform CI, and tag-based release assets.
- Unit tests for every connector module, including ServiceLoader registration
  regression tests.
- Javadoc for all SPI extension contracts.
- JaCoCo coverage reporting, SPDX license headers enforced at build time,
  Dependabot, CodeQL, and CI actions pinned to commit SHAs.

### Fixed

- `type: trino` / `type: sql` endpoints could not be opened from the CLI at
  all: the Trino connector was never registered with `ServiceLoader`.
- The Maven wrapper shipped without its executable bit, so Linux builds failed
  before running anything.
- Unexpected CLI exceptions now exit with code `4` (execution failure) instead
  of `1`, which schedulers would misread as "diff found".

### Security

- Diff evidence is masked before persistence by default, with explicit
  `masked`, `hash`, `omit`, and `raw` modes.
- HTML reports escape dynamic content and CSV reports neutralize spreadsheet
  formula prefixes.
- The runtime container uses a dedicated non-root user.
- Shaded jars aggregate third-party LICENSE/NOTICE files, and EOL log4j 1.x is
  excluded from the hadoop dependency tree.

### Changed

- Repository task templates and configuration documentation now match the
  strict v1 configuration model.
- Release builds align the POM version with the tag, embed the git commit, and
  use a fixed timestamp for reproducible output.
- Runtime dependencies refreshed: HikariCP 6.2.1, Logback 1.5.16,
  Jackson 2.18.3, MySQL Connector/J 8.4.0.
- README restructured into focused guides under `docs/`; examples and
  templates are documented and use environment-variable credentials only.
- Python sidecar packaging moved to PEP 621 `pyproject.toml`
  (requires Python 3.10+).

[Unreleased]: https://github.com/yzeng1618/DataAudit/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/yzeng1618/DataAudit/releases/tag/v0.1.0
