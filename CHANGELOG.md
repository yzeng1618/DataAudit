# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project intends to use [Semantic Versioning](https://semver.org/).

## [Unreleased]

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
