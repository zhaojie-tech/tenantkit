# TenantKit Concepts

TenantKit is a batteries-included multi-tenancy toolkit for Spring Boot 4:
**request resolution → context propagation (HTTP/async/RabbitMQ) → Redis key
isolation → idempotent tenant provisioning**, all wired together by Spring Boot
auto-configuration.

It intentionally does not do ORM-layer row-level isolation (v1.x roadmap; it
composes with MyBatis-Plus `TenantLineInnerInterceptor` — see the coexistence
guide), tenant billing/quota, or schema-per-tenant instance orchestration.

## Modules

| Module | Responsibility |
|---|---|
| `tenantkit-core` | TenantId / TenantContext (no public mutators; structured `runAs`/`callAs` scopes only) / Resolver SPI / TenantCatalog / Tenants.forEach |
| `tenantkit-web` | Resolution filter, strategy chain (jwt-claim / header / subdomain / fixed), exemptions, stable error codes TK-1001/1002/1003 |
| `tenantkit-messaging` | RabbitMQ `x-tenant-id` propagation both directions, missing-header policy, DLX semantics |
| `tenantkit-cache-redis` | Tenant-prefixed `tenantRedisTemplate` (write-prefix, read-strip), shared-key allow-list, no-context policy |
| `tenantkit-provisioning` | Idempotent provisioning: seed scripts + SHA-256 ledger + connection-bound `GET_LOCK`, dry-run, repair |
| `tenantkit-spring-boot-starter` | Aggregation + async propagation assembly (applicationTaskExecutor decorator) |
| `tenantkit-test` | Testcontainers singleton factories + `MultiTenantAssert` isolation assertions |

## Core mental model

1. **Context is an explicit scope.** `TenantContext` has no public set/clear —
   the only entry points are `runAs(id, action)` / `callAs(id, action)`, which
   restore the outer value on exit. Cross-thread propagation is always explicit
   (task decorator / MQ header / `TenantExecutors.wrap`);
   `InheritableThreadLocal` is deliberately not used.
2. **Resolution is a strategy chain.** `tenantkit.resolution.strategies` runs in
   declaration order; the first non-empty result wins. Contradictions between
   declared strategies and trust settings **fail startup** (see the trust model
   page).
3. **Error codes are stable.** TK-1001 missing tenant (400), TK-1002 unknown
   tenant (status configurable, default 404), TK-1003 invalid format (400);
   TK-2xxx provisioning and TK-3xxx messaging families. Responses are RFC 9457
   ProblemDetail.
4. **Provisioning is idempotent first.** Ledger table
   `UNIQUE(tenant_id, script_name)` + SHA-256 checksums (line-ending
   normalized); checksum drift hard-rejects and `repair` re-records explicitly;
   `GET_LOCK` is bound to a single dedicated physical connection for the whole
   run (see ADR-0001).

## Reactivity (v0.1 boundary)

v0.1 is **servlet-stack only**. `TenantContext` is ThreadLocal-based and the
web module guards on `jakarta.servlet.Filter`; WebFlux applications are an
explicit non-goal for now. The Micrometer context-propagation bridge means the
tenant does flow through reactive bridges once you adopt context-propagation
consumers, but no WebFlux auto-configuration ships in 0.1.

## Boot versions

v0.1 matrix = **Spring Boot 4.0.x + 4.1.x** (dual-version CI gate). Boot 3.5
reached OSS EOL on 2026-06-30 and is a legacy-compat tier only. See ADR-0003
and `docs/boot-compat-checklist.md`.
