# 快速上手

## 1. 引入 starter

```xml
<dependency>
  <groupId>io.github.zhaojie-tech</groupId>
  <artifactId>tenantkit-spring-boot-starter</artifactId>
  <version>0.1.0</version>
</dependency>
```

## 2. 最小配置（jwt-claim 单策略）

```yaml
tenantkit:
  resolution:
    strategies: [jwt-claim]   # 从已验签 JWT 的 claim 读租户
    jwt-claim: tenant_id
  tenants: [alpha, beta]      # 静态目录；生产可换 DB 实现的 TenantCatalog bean
```

就绪。请求进来 → Filter 解析租户 → `TenantContext` 绑定 → MDC 注入 `tenantId`（logback pattern 里 `%X{tenantId}` 直接用）→ 异步任务/MQ 消息自动携带。

> 不要照抄别的项目把 `header` 策略加进来而不开 `trust-proxy`——那会启动失败，这是刻意的。

## 3. Redis 键隔离

```xml
<dependency>
  <groupId>io.github.zhaojie-tech</groupId>
  <artifactId>tenantkit-cache-redis</artifactId>
</dependency>
```

```xml
<dependency>
  <groupId>io.github.zhaojie-tech</groupId><artifactId>tenantkit-cache-redis</artifactId><version>0.1.0</version>
</dependency>
<dependency>  <!-- 键隔离依赖数据面模板；缺它时模块会启动 WARN 明示隔离未生效 -->
  <groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-data-redis</artifactId>
</dependency>
```

```java
import io.tenantkit.cache.redis.TenantRedisTemplate;

@Autowired TenantRedisTemplate redis;   // 写自动加 t:<tenant>: 前缀，读剥离
redis.opsForValue().set("k", "v");      // 实际键 t:alpha:k
```

共享键（跨租户）走白名单：`tenantkit.redis.shared-keys: ["shared:*"]`。注意：业务照旧注入原生 `RedisTemplate` 会**绕过前缀**——这是 opt-in 设计。

## 4. RabbitMQ 透传

引入 `tenantkit-messaging` + `spring-boot-starter-amqp`。发送方自动写 `x-tenant-id`；定时任务等无租户发送按 `skip-with-warn` 放行（可配 `fail`）。消费方缺 header 默认 `reject-to-dlq`。

## 5. 幂等开通（直面"四表缺种"事故）

**前置**：`spring-boot-starter-jdbc` + 默认锁管理器要求 **MySQL**
（`GET_LOCK`）；H2/PostgreSQL 等请自备 `TenantLockManager` bean（默认自动退位）。
账本表 DDL 见 `io.tenantkit.provisioning.SeedLedger.REFERENCE_DDL`——**MySQL 方言**，
换库请先改语法（唯一键约束是幂等的地基，不能省）。

```java
import io.tenantkit.core.TenantId;
import io.tenantkit.provisioning.ProvisionOptions;
import io.tenantkit.provisioning.SeedProfile;

provisioner.provision(TenantId.of("gamma"), new SeedProfile("demo"),
        ProvisionOptions.defaults());
```

种子脚本放 `classpath:db/tenant-seed/<profile>/V<n>__desc.sql`（`${tenantId}` 占位符可用），账本表 `tenantkit_seed_history` 由**应用自己的 Flyway/Liquibase** 建表。重复调用逐脚本比对 checksum 跳过；脚本修订导致 checksum 不一致会硬拒放，用 repair 显式重录。

## 6. 测试

```xml
<dependency>
  <groupId>io.github.zhaojie-tech</groupId><artifactId>tenantkit-test</artifactId>
  <version>0.1.0</version><scope>test</scope>
</dependency>
```

```java
import io.tenantkit.test.MultiTenantAssert;

MultiTenantAssert.assertThat(() -> repository.read(key))
        .forTenant("alpha").returns("alpha-value")
        .forTenant("beta").returns("beta-value");
```

Testcontainers 工厂：`io.tenantkit.test.TenantSupport.redis()/mysql()/rabbitmq()`（JVM 级单例）。

> 单测注意：`MockMvc` **不经 Servlet Filter 链**，租户解析不会发生——用真实端口
> （`webEnvironment=RANDOM_PORT`）或直接断言 `MultiTenantAssert`。

## 运行示例应用

```bash
cd examples/demo-app
docker compose up -d          # mysql/redis/rabbitmq
mvn spring-boot:run
curl localhost:8080/api/echo  # 固定租户 alpha 的回显（redis + 异步 + 种子行数）
curl -X POST 'localhost:8080/api/provision?tenant=beta'
```
