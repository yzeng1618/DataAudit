# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project intends to use [Semantic Versioning](https://semver.org/).

## [Unreleased]

### Added

- Initial CLI-first data consistency audit implementation.
- Apache-2.0 open-source governance baseline.
- `config init`, offline `config validate`, and aggregated `doctor` commands.
- Maven 3.9.9 Wrapper, Java/Maven build gates, CycloneDX SBOM generation,
  cross-platform CI, and tag-based release assets.

### Security

- Diff evidence is masked before persistence by default, with explicit
  `masked`, `hash`, `omit`, and `raw` modes.
- HTML reports escape dynamic content and CSV reports neutralize spreadsheet
  formula prefixes.
- The runtime container uses a dedicated non-root user.

### Changed

- Repository task templates and configuration documentation now match the
  strict v1 configuration model.

[Unreleased]: https://github.com/yzeng1618/DataAudit/commits/master
