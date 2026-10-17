# Trust Model

The first security question in multi-tenancy is **who is allowed to claim to be
who**. Every TenantKit default starts from "don't trust".

## Sources and trust levels

| Strategy | Source | Trust requirement |
|---|---|---|
| `jwt-claim` | Spring Security `JwtAuthenticationToken` (**verified**) | No boundary trust needed; parsing unverified JWTs inside the kit is forbidden (ArchUnit rule) |
| `header` | `X-Tenant-Id` request header | **Requires** `trust-proxy: true` plus a boundary (gateway) that strips inbound tenant headers; a directly connected client can impersonate any tenant |
| `subdomain` | Subdomain label of `Host` | Boundary trust like header; multi-level nesting and mixed-case hosts rejected; base-domains with a port fail startup |
| `fixed` | Configured value | Internal / single-tenant deployments |

Opaque tokens: `jwt-claim` does not apply (verified JWTs only) — use
`header` + `trust-proxy` or a custom `TenantResolver`.

## Composition fail-fast (not a warning)

- Declaring `header` with `trust-proxy` unset (default false) → **startup
  fails** (safe defaults never silently swallow an explicit configuration);
- `trust-proxy: true` without the `header` strategy → startup WARN (harmless
  over-declaration);
- Declaring `jwt-claim` without Spring Security on the classpath → **startup
  fails**; TenantKit never degrades to parsing the raw Authorization header —
  that would be an open trust hole;
- `fixed` without `tenantkit.resolution.fixed`, or `subdomain` without
  `base-domains` → startup fails.

## Exemptions (the explicit way out of "missing tenant")

A missing tenant fails fast by default (TK-1001) — silently falling back to a
default tenant is how cross-tenant leaks are born. Two explicit exemption
channels:

1. `ignore-paths`: filter-level short-circuit, **no resolution, no failure**
   (actuator/health endpoints);
2. `@TenantPublic`: still passes the filter, still resolves, only suppresses
   the *missing* failure (login pages and other public endpoints).

When both match, `ignore-paths` wins.

## Trust across async and message boundaries

- Async: the tenant travels explicitly with the task (decorator /
  `TenantExecutors.wrap`); worker threads clear context **and** MDC on exit.
- RabbitMQ: publishers write `x-tenant-id` (constant published for
  cross-language producers); listeners validate the charset before binding the
  context; missing headers go to `reject-to-dlq` by default — a dead letter is
  better than a guessed tenant.
