# Security Policy

## Reporting a vulnerability

Do not publish security vulnerabilities, credentials, production data, or
working exploit details in a public issue.

Report vulnerabilities privately through GitHub private vulnerability
reporting: open the Security tab of
<https://github.com/yzeng1618/DataAudit> and choose "Report a vulnerability".
While the repository is private, or if the feature is unavailable, email the
maintainer at <yzeng1618@gmail.com> instead, sharing only enough public
context to arrange a private report.

Include the affected version, configuration, impact, reproduction steps, and
any proposed mitigation. Remove secrets and customer data.

## Response expectations

We aim to acknowledge new reports within 7 days and to share an initial
assessment within 30 days. Please allow up to 90 days of coordinated
disclosure before publishing details, longer if a fix has to ship through a
scheduled release.

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
