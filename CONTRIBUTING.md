# Contributing to data-audit

Thank you for helping improve `data-audit`.

## Development environment

- Java 17 or newer
- Maven 3.9 or the repository Maven Wrapper
- Python 3.10 or newer for `data-audit-agent`
- Docker when running Testcontainers integration tests

## Build and test

Run the Java reactor:

```bash
./mvnw verify
```

On Windows:

```powershell
.\mvnw.cmd verify
```

Run the optional Python sidecar tests:

```bash
python -m pytest -q data-audit-agent
```

Tests that require Docker or a POSIX filesystem are identified explicitly. Do not hide a failure by broadly skipping tests.

## Change guidelines

- Add a focused test for every bug fix and behavior change.
- Keep deterministic comparison results independent from AI providers.
- Keep comparison logic in `data-audit-core`; connectors expose capabilities and evidence through the SPI.
- Update task examples and strict template tests whenever configuration changes.
- Do not commit credentials, API keys, production data, or customer-derived fixtures.
- Sanitize logs and reports included in issues or tests.

Use Conventional Commit prefixes such as `feat:`, `fix:`, `docs:`, `test:`, and `build:`.

## Pull requests

1. Create a focused branch.
2. Run the relevant module tests and the full verification when practical.
3. Document compatibility, security, and report-schema impact.
4. Keep unrelated formatting or refactoring out of the change.

By submitting a contribution, you agree that it is licensed under Apache License 2.0.
