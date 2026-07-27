# Security Policy

## Reporting a vulnerability

Do not publish security vulnerabilities, credentials, production data, or
working exploit details in a public issue.

Use GitHub private vulnerability reporting when it is enabled for this
repository. If it is unavailable, contact the repository maintainers through
the repository owner's private channels and provide only enough public context
to arrange a private report.

Include the affected version, configuration, impact, reproduction steps, and
any proposed mitigation. Remove secrets and customer data.

## Supported versions

Until the first stable release, security fixes target the latest code on the
default development branch. A version support table will be published with the
first stable release.

## Sensitive task and report data

Task files can contain database endpoints, user names, partition identifiers,
and environment-variable names. Keep secrets in environment variables or a
secret manager rather than committed YAML.

Generated reports may contain evidence derived from source and target systems.
`output.value_mode` controls diff sample keys and values:

- `masked` replaces non-null values with `***` and is the default.
- `hash` writes a SHA-256 pseudonym for correlation; it is not encryption and
  low-cardinality values may still be guessed.
- `omit` removes sample keys and values.
- `raw` preserves plaintext evidence and must be enabled explicitly.

`slice_key` and `resume_hint` remain operational inputs for drilldown and can
contain business partition values. Protect report directories accordingly.

HTML output escapes report content and CSV output neutralizes spreadsheet
formula prefixes. These controls do not replace access control, retention, or
secure deletion policies in the deployment environment.
