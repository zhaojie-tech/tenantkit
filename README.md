# TenantKit

[![CI](https://github.com/zhaojie-tech/tenantkit/actions/workflows/ci.yml/badge.svg)](https://github.com/zhaojie-tech/tenantkit/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.zhaojie-tech/tenantkit-spring-boot-starter)](https://central.sonatype.com/artifact/io.github.zhaojie-tech/tenantkit-spring-boot-starter)

**TenantKit** is a batteries-included multi-tenancy toolkit for Spring Boot 4:
request resolution → context propagation (HTTP / async / RabbitMQ) → Redis key
isolation → idempotent tenant provisioning — all wired together by Spring Boot
auto-configuration, so you don't hand-roll `TenantContextHolder` +
`HandlerInterceptor` and re-discover every classic multi-tenant failure mode.

| | |
|---|---|
| Java | 17+ |
| Spring Boot | **4.0.x + 4.1.x** (v0.1 matrix) |
| License | Apache-2.0 |
| Status | v0.1.0 released on Maven Central |

> Maven coordinates: `io.github.zhaojie-tech` (GitHub-namespace route on Maven Central).

## Why TenantKit

Every multi-tenant team rebuilds the same chain — and trips on the same failures
(drawn from a real tenant SaaS checkout/ERP line):

- **"New tenant returns 422."** A tenant was onboarded, but its four seed tables
  were never populated. A manual onboarding checklist with no idempotent ledger
  means one missed step is a production incident. This is why the provisioning
  module exists.
- **Hand-rolled `TenantContextHolder` leaks context.** `@Async` and custom pools
  drop the tenant and queries cross tenant boundaries; scheduled jobs hand-roll
  "iterate tenants + try/finally restore + don't abort on one failure" — and get
  it subtly wrong (e.g. `finally` clearing the outer context too).
- **RabbitMQ loses the tenant.** Both the publisher and the listener must
  propagate a header by hand, every time.
- **Redis cross-tenant bleed.** Key prefixes are left to individual business
  code; one missed prefix is a data leak.
- **Header spoofing.** Anyone who can call your service directly sends
  `X-Tenant-Id` and impersonates a tenant. TenantKit ships an explicit trust
  model (verified-JWT claims, proxy headers only behind an explicit
  `trust-proxy` flag) instead of default trust.

TenantKit turns that whole chain into a tested, documented product.

## Architecture

Seven modules, one-way dependencies: `starter → functional modules → core`.

```text
                ┌────────────────────────────────────────────┐
                │      tenantkit-spring-boot-starter         │
                │  aggregation + auto-configuration +        │
                │  configuration metadata (IDE completion)   │
                └─────────────────────┬──────────────────────┘
                                      │
       ┌──────────────────┬───────────┴────────┬─────────────────────┐
       ▼                  ▼                    ▼                     ▼
┌──────────────┐  ┌─────────────────┐  ┌─────────────────┐  ┌───────────────────┐
│ tenantkit-web│  │tenantkit-       │  │ tenantkit-      │  │ tenantkit-        │
│ resolution   │  │messaging        │  │ cache-redis     │  │ provisioning      │
│ filter, trust│  │AMQP header in/  │  │ prefixed keys,  │  │ idempotent seeds, │
│ model, TK-1  │  │out, DLX policy  │  │ shared keys,    │  │ checksum ledger,  │
│ error codes  │  │                 │  │ read-path spec  │  │ advisor locks     │
└──────┬───────┘  └────────┬────────┘  └────────┬────────┘  └─────────┬─────────┘
       └───────────────────┴──────────┬─────────┴─────────────────────┘
                                      ▼
                          ┌──────────────────────┐
                          │    tenantkit-core    │  pure library, zero
                          │ TenantId · Tenant-  │  auto-configuration
                          │ Context · resolver   │
                          │ SPI · TenantCatalog  │
                          │ · Tenants.forEach    │
                          └──────────────────────┘

  ┌────────────────────┐
  │   tenantkit-test   │  helper library: Testcontainers factories +
  └────────────────────┘  MultiTenantAssert; not pulled in by the starter
```

Every functional module ships its own `AutoConfiguration.imports` and is guarded
by `@ConditionalOnClass`, so modules can be adopted individually
(`tenantkit.web.enabled` / `tenantkit.messaging.enabled` / `tenantkit.redis.enabled` /
`tenantkit.provisioning.enabled` turn a module's auto-configuration off cleanly;
the starter's async-propagation assembly is excluded via
`spring.autoconfigure.exclude` instead).

## 30-Second Quickstart

Add the starter (pre-release: `0.1.0` until the first Central
publication; `0.1.0` once released):

```xml
<dependency>
  <groupId>io.github.zhaojie-tech</groupId>
  <artifactId>tenantkit-spring-boot-starter</artifactId>
  <version>0.1.0</version>
</dependency>
```

Optional modules — each works standalone and none is pulled transitively by
the starter (the data-plane dependencies stay yours to declare):

```xml
<dependency>  <!-- tenant-prefixed Redis; also needs spring-boot-starter-data-redis -->
  <groupId>io.github.zhaojie-tech</groupId><artifactId>tenantkit-cache-redis</artifactId><version>0.1.0</version>
</dependency>
<dependency>  <!-- idempotent provisioning; also needs spring-boot-starter-jdbc + MySQL (or your own TenantLockManager) -->
  <groupId>io.github.zhaojie-tech</groupId><artifactId>tenantkit-provisioning</artifactId><version>0.1.0</version>
</dependency>
<dependency>  <!-- Testcontainers factories + MultiTenantAssert, test scope -->
  <groupId>io.github.zhaojie-tech</groupId><artifactId>tenantkit-test</artifactId><version>0.1.0</version><scope>test</scope>
</dependency>
```

Minimal configuration — resolve the tenant from a **verified** JWT claim:

```yaml
tenantkit:
  resolution:
    strategies: [jwt-claim]      # jwt-claim | header | subdomain | fixed — order is priority
    jwt-claim: tenant_id         # read from Spring Security's verified JwtAuthenticationToken
    ignore-paths: ["/actuator/**", "/public/**"]
  tenants: [alpha, beta]         # built-in TenantCatalog listing
```

That's it: requests are resolved (missing tenant fails fast with `TK-1001`,
RFC 9457 ProblemDetail), `TenantContext` is populated, `@Async` executors and
`RabbitTemplate`/listeners propagate the tenant automatically, and keys written
through `tenantRedisTemplate` get a per-tenant prefix. Adding a `header` strategy
requires `tenantkit.resolution.trust-proxy: true` — the combo fails fast at
startup otherwise.

## Two-Tenant Isolation Demo

One-command demo app under `examples/demo-app` (MySQL + Redis + RabbitMQ via
compose; three endpoints; doubles as the asciinema recording source — a cast
will be embedded here once recorded on a Docker host):

```bash
# from the repository root: install the modules first (fresh clones)
./mvnw install -DskipTests

cd examples/demo-app
docker compose up -d
../mvnw spring-boot:run          # the wrapper lives at the repository root

curl -s localhost:8080/api/echo                 # fixed tenant alpha: redis echo + async echo + seeded rows
curl -s -X POST 'localhost:8080/api/provision?tenant=beta'   # second call skips, not replays (seed ledger)
```

`POST /api/provision` is idempotent: the first call executes the seed scripts
and records their checksums in the ledger; calling it again reports them as
skipped. Point `tenantkit.resolution.strategies` at `[header]` with
`trust-proxy: true` behind a gateway to walk both tenants live.

## Compatibility

| TenantKit | Spring Boot | Support |
|---|---|---|
| 0.1.x | 4.0.x | Supported — CI matrix, fixes |
| 0.1.x | 4.1.x | Supported — CI matrix, fixes |
| 0.1.x | 3.5.x | **Legacy-compat tier only**: compile smoke tests, no runtime or fix promises (Boot 3.5 reached OSS EOL on 2026-06-30) |
| — | 2.x / 3.4 and older | Not supported (EOL) |

## Non-Goals (v0.1)

> **Reactive stacks:** v0.1 is servlet-only (`TenantContext` is ThreadLocal-based; the
> web module guards on `jakarta.servlet.Filter`). WebFlux auto-configuration is a
> roadmap item, not a silent gap.

To set expectations precisely, TenantKit explicitly does **not**:

1. **Provide ORM-level row isolation.** Tenant-discriminator interceptors for
   JPA/MyBatis are on the v1.x roadmap — the context layer has to be right
   first, or row filtering has no foundation. The MyBatis-Plus
   `TenantLineInnerInterceptor` ecosystem already exists; TenantKit is designed
   to **compose** with it (integration guide ships with 0.1.x), not replace it.
2. **Do tenant billing, quotas, or an admin console UI.**
3. **Bind to a specific ORM, or orchestrate schema-per-tenant database
   instances.** Hooks are provided; your DBA process stays yours.
4. **Support Spring Boot 2.x / 3.5 or older.** 3.5 is the EOL line
   (2026-06-30) — legacy-compat tier only, no fix promises. The v0.1 matrix is
   **4.0.x + 4.1.x**.
5. **Model more than a single tenancy dimension.** `TenantContext` is
   single-tenant dimension only (store/user sub-dimensions are documented as
   extension hooks).

## Documentation

- Manual (docs/manual): [概念/Concepts](docs/manual/index.md) ·
  [信任模型/Trust model](docs/manual/trust-model.md) ·
  [快速上手/Quickstart](docs/manual/quickstart.md) ·
  [生产注意/Production notes](docs/manual/production-notes.md) ·
  [MyBatis-Plus 共存](docs/manual/mybatis-plus.md) (`mkdocs.yml` serves the site)
- [Design document](docs/DESIGN.md) (v1.5, zh) — every `DESIGN §x` javadoc reference
- [Boot 4.0↔4.1 checklist](docs/boot-compat-checklist.md) · [Benchmark report](docs/benchmarks.md) ·
  [ADRs](docs/adr/) · [Roadmap](docs/ROADMAP.md) · [Deferral ledger](docs/BACKLOG-0.1.1.md) ·
  [Docker first-run playbook](docs/ops/docker-first-run.md) ·
  [Central release checklist](docs/ops/central-release-checklist.md)

## Contributing & Security

- Contributing guide: [CONTRIBUTING.md](CONTRIBUTING.md)
- Security policy and vulnerability reporting: [SECURITY.md](SECURITY.md)

## License

[Apache-2.0](LICENSE)
