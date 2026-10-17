# Changelog

All notable changes to TenantKit are documented here. Format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/); versioning follows
[SemVer](https://semver.org/) — configuration-key changes are breaking and
always come with migration notes.

## [Unreleased]

## [0.1.0] — pending first release

Initial release. Spring Boot matrix: **4.0.x + 4.1.x** (Java 17+).

### Added
- **tenantkit-core**: `TenantId` (compact-constructor validation, configurable
  charset regex, log-safe bare `toString`), `TenantContext` (explicitly scoped,
  no public mutators, nested `runAs`/`callAs` restore the outer value, no
  `InheritableThreadLocal`), `TenantResolver` SPI + container-agnostic
  `TenantResolutionRequest`, `TenantCatalog` + static config implementation,
  `Tenants.forEach` (per-tenant timeout, failure isolation, outer-transaction
  guard), TK-100x/2xxx/3xxx exception families, Micrometer context-propagation
  bridge with availability-guarded deferred verification.
- **tenantkit-web**: `TenantResolutionFilter` (strategy chain, `ignore-paths`
  short-circuit, `@TenantPublic` startup scan, catalog validation), resolvers
  `jwt-claim` (verified claims only) / `header` (trust-proxy gated) /
  `subdomain` (nesting/port/mixed-case rejected) / `fixed`, trust-model
  composition fail-fast validated before any bean instantiation, RFC 9457
  ProblemDetail with stable codes TK-1001/1002/1003, MDC `tenantId` injection.
- **tenantkit-messaging**: RabbitMQ publisher post-processor (`x-tenant-id`,
  `skip-with-warn` | `fail`), listener advice (`reject-to-dlq` | `log-and-ack`,
  invalid header rejected, listener's original exception type preserved),
  advice-chain merge with kit advice first, slow-layer DLX round-trip tests.
- **tenantkit-cache-redis**: `tenantRedisTemplate` (write-prefix/read-strip,
  `keys`/`scan` pair rewriting with call-time strip binding, shared-key
  allow-list, `strict`/`lenient` no-context policy), `TenantCacheKeyPrefix`
  with startup path pinned lenient. **All key-based ops families are covered**
  (value/hash/list/set/zset/geo/hyperloglog/stream + `bound*Ops`) through
  declarative key-position rewriting; stream reads return records re-keyed to
  their logical form. Raw callbacks and cluster-wide ops are refused by design
  (they cannot be rewritten honestly).
- **tenantkit-provisioning**: idempotent tenant provisioning — seed scripts
  (`${tenantId}` placeholder), SHA-256 checksum ledger with
  `UNIQUE(tenant_id, script_name)`, connection-bound `GET_LOCK` held for the
  whole run, checksum-mismatch hard rejection with explicit `repair`,
  dry-run audit, pluggable `TenantLockManager` (MySQL semantics authoritative).
- **tenantkit-spring-boot-starter**: aggregation + `applicationTaskExecutor`
  decoration via before-initialization BeanPostProcessor
  (`tenantkit.executor.decorate-user-pools` switch).
- **tenantkit-test**: `TenantSupport` singleton Testcontainers factories
  (MySQL/Redis/RabbitMQ), `MultiTenantAssert` isolation assertions,
  `HidingClassLoader` absence-simulation utility.
- Quality gates: dual-version CI (4.0/4.1), frozen 51-case web assembly matrix
  plus per-module matrices (MQ/R/P groups), ArchUnit rules, coverage gates
  (core 90%, modules 85%), fast/slow test layering with Testcontainers
  singleton strategy.
- **tenantkit-cache-redis**: `ValueOperations` is wrapped by the same
  version-adaptive proxy as the other families. Spring Data Redis 4.1 changed the
  interface's abstract method set (`getAndExpire`, `compareAndSet`, `SetSpec`
  variants), so a hand-written implementation cannot satisfy both Boot legs.
- **tenantkit-messaging**: `tenantkit.messaging.raw-container-factories`
  (bean-name list) keeps the tenant advice off DLQ-triage listener factories —
  without it a headerless dead letter would be rejected again at the DLQ
  entrance and lost.
- **tenantkit-benchmarks**: unpublished nanoTime harness measuring key-codec,
  resolution-filter and RabbitMQ-advice overhead; results and methodology in
  `docs/benchmarks.md` (the run itself drove a literal-prefix fast path in the
  shared-key matcher).
- Docs: bilingual manual (5 pages each: concepts / trust model / quickstart /
  production notes / MyBatis-Plus coexistence), ADR-0001..0003, Boot 4.0↔4.1
  compatibility checklist, benchmark report, Docker first-run playbook, Central
  release checklist.

### Pending environment execution (not deferred work)
- First real-container run of the slow suites (DLX round-trip positive and
  negative, Redis isolation, MySQL GET_LOCK concurrency incl. the Hikari
  variant). Tests are written; a Docker host is the missing piece. See
  `docs/ops/docker-first-run.md`.
- Maven Central publication (owner credentials: GitHub org / groupId / GPG /
  Portal token). Pipeline and guards are in place; see
  `docs/ops/central-release-checklist.md`.
