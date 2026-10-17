# Quickstart

## 1. Add the starter

```xml
<dependency>
  <groupId>io.github.zhaojie-tech</groupId>
  <artifactId>tenantkit-spring-boot-starter</artifactId>
  <version>0.1.0</version>
</dependency>
```

## 2. Minimal configuration (single jwt-claim strategy)

```yaml
tenantkit:
  resolution:
    strategies: [jwt-claim]   # tenant read from a verified JWT claim
    jwt-claim: tenant_id
  tenants: [alpha, beta]      # static catalog; swap in a DB-backed TenantCatalog bean
```

Done. Requests are resolved (a missing tenant fails fast with TK-1001 as an
RFC 9457 ProblemDetail), `TenantContext` is bound, MDC carries `tenantId`
(`%X{tenantId}` in a logback pattern), and `@Async` executors plus
RabbitMQ producers/listeners propagate the tenant automatically.

> Do not copy other projects' `[jwt-claim, header]` sample blindly: adding the
> `header` strategy without `trust-proxy: true` fails startup — on purpose.

## 3. Redis key isolation

```xml
<dependency>
  <groupId>io.github.zhaojie-tech</groupId>
  <artifactId>tenantkit-cache-redis</artifactId>
</dependency>
```

Also declare the data-plane template; without it the module starts with a
loud WARN that isolation is NOT active:

```xml
<dependency>  <!-- io.tenantkit:tenantkit-cache-redis:0.1.0 -->
  <groupId>io.github.zhaojie-tech</groupId><artifactId>tenantkit-cache-redis</artifactId><version>0.1.0</version>
</dependency>
<dependency>
  <groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-data-redis</artifactId>
</dependency>
```

```java
import io.tenantkit.cache.redis.TenantRedisTemplate;

@Autowired TenantRedisTemplate redis;   // writes get a t:<tenant>: prefix, reads strip it
redis.opsForValue().set("k", "v");      // actual key: t:alpha:k
```

Cross-tenant shared keys use the allow-list:
`tenantkit.redis.shared-keys: ["shared:*"]`. Injecting the plain
`RedisTemplate` **bypasses prefixing** — that is an opt-in design.

## 4. RabbitMQ propagation

Add `tenantkit-messaging` plus `spring-boot-starter-amqp`. Publishers write
`x-tenant-id` automatically; tenant-less senders (scheduled jobs) follow
`skip-with-warn` by default (`fail` available). Listeners without a header
default to `reject-to-dlq`.

## 5. Idempotent provisioning

**Prerequisites**: `spring-boot-starter-jdbc`, and the default lock manager
requires **MySQL** (`GET_LOCK`); on H2/PostgreSQL provide your own
`TenantLockManager` bean (the default backs off automatically). The ledger DDL
lives in `io.tenantkit.provisioning.SeedLedger.REFERENCE_DDL` — **MySQL
dialect**; adapt the syntax per database (the unique constraint is the
load-bearing part).

```java
import io.tenantkit.core.TenantId;
import io.tenantkit.provisioning.ProvisionOptions;
import io.tenantkit.provisioning.SeedProfile;

provisioner.provision(TenantId.of("gamma"), new SeedProfile("demo"),
        ProvisionOptions.defaults());
```

Seed scripts live under `classpath:db/tenant-seed/<profile>/V<n>__desc.sql`
(`${tenantId}` placeholder supported); the `tenantkit_seed_history` ledger is
created by **your** Flyway/Liquibase migrations. Repeat calls compare checksums
and skip; edited scripts hard-reject until you run `repair` explicitly.

## 6. Testing

```xml
<dependency>  <!-- io.tenantkit:tenantkit-test:0.1.0, test scope -->
  <groupId>io.github.zhaojie-tech</groupId><artifactId>tenantkit-test</artifactId><version>0.1.0</version><scope>test</scope>
</dependency>
```

```java
import io.tenantkit.test.MultiTenantAssert;

MultiTenantAssert.assertThat(() -> repository.read(key))
        .forTenant("alpha").returns("alpha-value")
        .forTenant("beta").returns("beta-value");
```

> Testing note: `MockMvc` does **not** run the servlet filter chain, so tenant
> resolution never happens in it — use a real port
> (`webEnvironment=RANDOM_PORT`) or assert through `MultiTenantAssert`.

Testcontainers factories: `TenantSupport.redis()/mysql()/rabbitmq()`
(JVM-level singletons).

## Run the demo app

```bash
cd examples/demo-app
docker compose up -d          # mysql/redis/rabbitmq
mvn spring-boot:run
curl localhost:8080/api/echo  # fixed tenant alpha: redis echo + async echo + seeded rows
curl -X POST 'localhost:8080/api/provision?tenant=beta'
```
