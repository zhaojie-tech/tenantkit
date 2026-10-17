# MyBatis-Plus Coexistence (full guide)

TenantKit does not implement ORM-level row isolation (see README Non-Goals). The
two compose: TenantKit owns *who the tenant is and how it propagates* (plus
idempotent provisioning); `TenantLineInnerInterceptor` owns *how SQL is
filtered* — and it only needs `TenantContext`.

This page covers the basic wiring, global-table exemptions, multi-datasource,
pagination interplay, dynamic table names, background execution, and a
troubleshooting table.

## 1. Basic wiring

```java
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.handler.TenantLineHandler;
import io.tenantkit.core.TenantContext;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.StringValue;

@Bean
public MybatisPlusInterceptor mybatisPlusInterceptor() {
    MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
    // tenant interceptor MUST be added before the pagination interceptor
    interceptor.addInnerInterceptor(new TenantLineInnerInterceptor(new TenantLineHandler() {
        @Override
        public Expression getTenantId() {
            // fail-fast: a thread without a tenant throws TenantMissingException (TK-1001)
            // instead of silently querying across every tenant
            return new StringValue(TenantContext.required().value());
        }

        @Override
        public String getTenantIdColumn() {
            return "tenant_id";
        }

        @Override
        public boolean ignoreTable(String tableName) {
            return GlobalTables.isExempt(tableName);
        }
    }));
    interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
    return interceptor;
}
```

```java
public final class GlobalTables {
    private GlobalTables() {}

    public static boolean isExempt(String tableName) {
        String t = tableName.replace("`", "").toLowerCase(java.util.Locale.ROOT);
        // tenant directory + provisioning ledger (ADR-0002); add your own global tables
        return t.startsWith("tenantkit_") || t.equals("tenant_catalog") || t.equals("sys_config");
    }
}
```

## 2. Exemptions are a safety obligation, not a preference

- `tenantkit_seed_history` **must** be exempt: provisioning reads the ledger
  outside a tenant context; filtering it looks like "ledger never has records →
  replay every time".
- The tenant directory table **must** be exempt: otherwise the unknown-tenant
  check filters itself.
- Every table **not** exempt gets a `tenant_id` predicate automatically; a table
  missing that column fails loudly at runtime. That is deliberate (fail closed,
  never leak). Maintain `GlobalTables` when adding global tables and pin it with
  a test — `MultiTenantAssert` from `tenantkit-test` asserts two tenants cannot
  see each other's rows.

## 3. Multiple datasources

Register one `MybatisPlusInterceptor` per `SqlSessionFactory`; the tenant handler
implementation is shared because `TenantContext` is thread-scoped, not
datasource-scoped.

```java
@Bean
@ConfigurationProperties("spring.datasource.tenant")
public DataSource tenantDataSource() { return DataSourceBuilder.create().build(); }

@Bean
public MybatisPlusInterceptor tenantInterceptor() { return buildInterceptor(); } // same as §1

@Bean
public SqlSessionFactory tenantSqlSessionFactory(@Qualifier("tenantDataSource") DataSource ds) throws Exception {
    MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
    factory.setDataSource(ds);
    factory.setPlugins(tenantInterceptor());
    return factory.getObject();
}
```

Global operations (directory, ledger) either go through an unfiltered
datasource/`SqlSession` or rely on `ignoreTable`. When both share one database,
prefer exemptions: they are declarative and testable, whereas an unfiltered
datasource depends on naming discipline that refactors erode.

## 4. Pagination interplay

`TenantLineInnerInterceptor` must come **before** `PaginationInnerInterceptor`:
the tenant predicate has to be present when the count query is generated,
otherwise `count` includes other tenants' rows. This is MyBatis-Plus' documented
order — and the first thing to regress on upgrades.

## 5. Dynamic table names (sharding) combined with tenancy

Register `DynamicTableNameInnerInterceptor` **after** the tenant interceptor:

```java
interceptor.addInnerInterceptor(tenantLine);
interceptor.addInnerInterceptor(dynamicTableName); // tenant predicate first, rename second
interceptor.addInnerInterceptor(pagination);
```

If you shard per tenant (`orders_alpha`), that is table-level isolation, a
different path TenantKit does not manage — the tenant name still comes from
`TenantContext.required()`.

## 6. Scheduled jobs / async / MQ consumers

- **Scheduled jobs**: use `Tenants.forEach((id, tenant) -> ...)` — explicit
  per-tenant `runAs`, outer-value restore, per-tenant failure isolation. Do not
  hand-roll `finally { clear() }`.
- **Async**: the starter decorates `applicationTaskExecutor` (tenant + MDC);
  wrap raw executors with `TenantExecutors.wrap(...)`.
- **MQ consumers**: the messaging advice binds the tenant before your listener;
  do **not** re-parse the header yourself — that bypasses the missing-header
  policy.
- **Cross-tenant jobs** (reports, reconciliation): bind explicitly with
  `runAs`, or exempt global tables. `required()` throwing on those threads is a
  feature.

## 7. Troubleshooting

| Symptom | Likely cause | Check |
|---|---|---|
| Seed scripts replay on every run | ledger table not exempt | `GlobalTables` covers `tenantkit_*` |
| Unknown-tenant check always passes / always 404 | directory table filtered by tenant | same; try `SELECT COUNT(*) FROM tenant_catalog` in a tenant scope |
| Scheduled job fails with TK-1001 | no tenant on the task thread | switch to `Tenants.forEach` or `runAs` |
| `count` totals too large | pagination registered before tenant | reorder `addInnerInterceptor` |
| `tenant_id` column missing on a table | global table not exempt | add to `GlobalTables` and pin with a test |
| Async task sees no tenant | raw pool / `CompletableFuture` default pool | `TenantExecutors.wrap` or a Spring-managed executor |

## 8. Scope

TenantKit's own row-level interceptor is a v1.x roadmap item and will reuse the
same exemption discipline (ADR-0002). This page depends only on MyBatis-Plus'
public API (`TenantLineHandler`, `MybatisPlusInterceptor`,
`PaginationInnerInterceptor`); pin the MyBatis-Plus version in your project.
