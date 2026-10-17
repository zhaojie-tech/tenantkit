# Roadmap

Priorities are driven by the incident classes the kit exists to prevent.
Anything here can change before it lands — the changelog is the contract.

## 0.1.x (backlog: docs/BACKLOG-0.1.1.md)

- First `-Pcontainers` run of the slow suites (DLX round-trip, Redis isolation,
  MySQL GET_LOCK concurrency) on a Docker host.
- Benchmarks & overhead report: Redis key-length memory impact, MQ advice cost,
  filter cost.
- MyBatis-Plus coexistence guide — full version (multi-datasource, pagination
  plugin interplay, dynamic table names).
- Remaining Redis ops (Hash/List/Set/ZSet/pipelined) tenant-aware wrappers.
- WebFlux story decision: explicit bridge or reaffirmed non-goal.

## 1.x (planned, the #1 requested capability)

- **ORM-layer row-level isolation**: tenant discriminator for JPA (Hibernate
  multi-tenancy integration) and a first-class MyBatis/MyBatis-Plus
  integration beyond the coexistence guide.
- Rule: context correctness ships first (done in 0.1), row filtering builds on
  it; the tenant directory table and the seed ledger stay exempt
  (ADR-0002 discipline).

## Later / under consideration

- Kafka propagation (the messaging SPI — header write/restore seam — was left
  open in v0.1 on purpose; no promises until there is a tested implementation).
- Reactive (WebFlux) auto-configuration and a reactive `TenantContext` variant.
- Spring Boot 3.5 bridge line — consciously not planned for now (EOL
  2026-06-30); will be re-evaluated based on adoption feedback, see the
  compatibility page in ADR-0003.
