# Security Policy

## Reporting a Vulnerability

Please do **not** open a public GitHub issue for security reports.

- **Only channel:** GitHub Security Advisories — use "Report a vulnerability" on
  this repository:
  `https://github.com/zhaojie-tech/tenantkit/security/advisories/new`
  Reports stay private until an advisory is published.

When reporting, please include: affected module(s) and version, Spring Boot
version (4.0.x / 4.1.x), your `tenantkit.*` configuration (redact secrets), a
minimal reproduction (config + code path), and your impact assessment.

You will receive an acknowledgment within 5 business days and a status update
at least every 7 days until a fix or a decision ships (best effort while the
project is pre-1.0). Fixes land on the latest supported line first; see the
version table below.

## Supported Versions

| Version | Spring Boot | Security fixes |
|---|---|---|
| 0.1.x | 4.0.x, 4.1.x | **Yes** — supported lines |
| 0.1.x | 3.5.x (legacy-compat tier) | **No** — compile smoke only; Boot 3.5 is OSS EOL since 2026-06-30 |
| < 0.1.0 | — | n/a (not yet released) |

## Trust-Model Reports — What Belongs Here

TenantKit ships an explicit tenant trust model. Reports in these areas are
treated as **high priority**:

- **Header spoofing / trust-boundary bypass.** Any path where
  `HeaderTenantResolver` (or subdomain resolution) can take effect without
  `tenantkit.resolution.trust-proxy: true`, or where a configuration combination
  that should fail fast at startup silently activates instead.
- **Unverified JWT paths.** Any kit code path that derives the tenant from a
  raw/unsigned JWT rather than Spring Security's verified
  `JwtAuthenticationToken` — including the JwtClaim strategy silently activating
  without Spring Security instead of refusing auto-configuration at startup.
- **Silent fallback.** Any code path that falls back to a default/ambient tenant
  instead of raising `TenantMissingException` — silent fallback is the canonical
  cross-tenant incident.
- **Exemption bypass.** `ignore-paths` / `@TenantPublic` precedence violations,
  or public-path handling that skips resolution where the documentation promises
  it still runs.
- **Isolation failures.** RabbitMQ header propagation drops, Redis prefix or
  shared-key guards letting tenant A read tenant B's keys, and provisioning
  ledger checksum-bypass (replaying a modified seed script without `--repair`).

**Out of scope:** applications that intentionally disable documented fail-fast
defaults in their own configuration, and gateway deployments that do not
sanitize proxied headers (deployment concern — TenantKit documents the trust
boundary; operating it is yours).
