# Production Notes (v0.1)

## Provisioning: prerequisites and dialect

- The **default lock manager requires MySQL** (`GET_LOCK` session locks). On
  H2/PostgreSQL register your own `TenantLockManager` bean
  (`@ConditionalOnMissingBean` — your bean replaces the default). Without one,
  provisioning fails with TK-2002 naming that remedy.
- `SeedLedger.REFERENCE_DDL` is **MySQL dialect** (`ENGINE=InnoDB`,
  `UNIQUE KEY`). Adapt the syntax per database; keep the unique constraint on
  `(tenant_id, script_name)` — it is the foundation of concurrent idempotency.
- Positive sub-second lock timeouts are rounded **up** to 1 second (integer
  seconds in `GET_LOCK`; `0` means "do not wait").

## Provisioning: connection pool and lock

- `GET_LOCK` is held on one dedicated physical connection for the whole run
  while each script transaction uses another connection: size HikariCP at
  roughly `(concurrent provisioning runs × 2) + headroom`. Too small a pool
  looks like "lock acquired, scripts wait forever for a connection".
- The ledger table DDL is `SeedLedger.REFERENCE_DDL`, migrated by your own
  Flyway/Liquibase. Missing table → startup WARN and TK-2004 with the DDL.
- A checksum mismatch hard-rejects (TK-2001) by design: revise seeds via
  `repair` (re-records only) or append a `V(n+1)` script — never rewrite
  history silently.

## Messaging: DLQ consumers must use an advice-free factory

Under `reject-to-dlq`, a headerless message is rejected once and dead-lettered;
the DLQ itself usually has no further dead-letter target. **If the DLQ consumer
still carries the tenant advice, it rejects the message a second time — with
nowhere left to go, the message is lost** ("landed in the DLQ" becomes
"disappeared from the DLQ"). Do this instead:

1. create a dedicated `SimpleRabbitListenerContainerFactory` for DLQ consumers
   **without** an advice chain;
2. with the starter, add that factory's bean name to
   `tenantkit.messaging.raw-container-factories` so the kit's BeanPostProcessor
   leaves it alone;
3. point `@RabbitListener(..., containerFactory = "yourDlqFactory")` at it.

Such consumers have no tenant context by nature: treat them as manual triage and
never call `TenantContext.required()`.

## Messaging: topology and poison messages

- `reject-to-dlq` (default) needs the queue's DLX configured; a queue without
  DLX drops rejected messages. Configure DLX + alerting on every business queue.
- Headerless messages (third-party producers, system messages) land in the DLQ
  under the default policy. Make producers send `x-tenant-id`
  (`TenantMessagingHeaders.TENANT_HEADER`, published for cross-language use), or
  configure `log-and-ack` and accept the missing-context consequences.
- Tenant-less publishers default to `skip-with-warn`; configure `fail` when a
  missing tenant must abort the send.

## Redis: prerequisites

- Requires `spring-boot-starter-data-redis` (or any `RedisTemplate` bean). With
  neither, the module starts with a **loud WARN** that isolation is NOT active
  — "assumed isolation" is how this class of incident starts.
- `tenantRedisTemplate` is **not** `@Primary`: injecting the raw template keeps
  bypassing prefixing (opt-in design).

## Redis: KEYS and SCAN

- `keys(pattern)` rewrites the pattern with the tenant rule and strips returned
  keys. Prefer `scan(ScanOptions)` in application code — `KEYS` is O(N) and
  blocks Redis.
- Without a string pattern, `scan` throws under STRICT (it would see the whole
  keyspace); under LENIENT it passes through **unrewritten and unstripped,
  exposing every tenant's physical keys**. Consistent with lenient's philosophy,
  stated here because "no pattern" has no single key to leave unprefixed.
- Covered surface (v0.1 full): `opsForValue`, `opsForHash`, `opsForList`,
  `opsForSet`, `opsForZSet`, `opsForGeo`, `opsForHyperLogLog`, `opsForStream`,
  all `bound*Ops` (key encoded at binding). Only **declared key positions** are
  rewritten; values/fields are never inspected. Stream reads re-key returned
  records back to logical form.
- Raw callbacks (`execute`, `executePipelined`) and `opsForCluster` are
  **refused by design**: they cannot be key-rewritten, and a tenant-prefixed
  view of them would be a lie. Use the raw template deliberately for those.
- Key-length cost: prefix `t:<tenant>:` per key (≤35 bytes for a 32-char
  tenant); measure your own keyspace (`DBSIZE × prefix length`). See
  `docs/benchmarks.md` for measured codec overhead.

## Web

- Filter-level error responses are always `application/problem+json` with a
  top-level `errorCode` (TK-xxxx). No content negotiation variants in v0.1.
- The writer is classpath-independent by design: no Jackson 2/3 dependency
  (An earlier revision consulted Jackson 2 when present — on Boot 4 that type in
  a bean signature broke startup for consumers without Jackson 2).
- `unknown-tenant-status` defaults to 404; use 400 if clients could enumerate
  tenant names through it.

## Testing

- `MockMvc` does **not** run the servlet filter chain: resolution, exemptions
  and the TK wire shape are not exercised. Use a real port
  (`webEnvironment=RANDOM_PORT`; see `TenantKitRealServerIntegrationTest`) or
  assert through `MultiTenantAssert`.
- To see javadoc/sources jars locally: `./mvnw -Prelease package` (the default
  build ships only the main jar).

## Observability

- Suggested logback pattern: `%d %-5level [%X{tenantId:-}] %logger - %msg%n`.
- `TenantId.toString` is the bare value (no quotes/braces) — log-injection-safe
  under the default charset; read the provisioning injection warning before
  relaxing the regex.
