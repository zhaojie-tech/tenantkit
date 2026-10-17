# 与 MyBatis-Plus 租户拦截器共存（完整版）

TenantKit 不做 ORM 层行级隔离（见 README Non-Goals）。两者是**组合关系**：
TenantKit 负责「租户是谁、怎么传播、供给幂等」，`TenantLineInnerInterceptor`
负责「SQL 怎么过滤」——拦截器只需要读 `TenantContext`。

本页覆盖：基础接法、目录表与账本表豁免、多数据源、分页插件协同、动态表名、
定时任务/异步/MQ 消费、常见事故与排查表。

## 1. 基础接法

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
    // 租户拦截器必须在分页拦截器之前（官方顺序要求）
    interceptor.addInnerInterceptor(new TenantLineInnerInterceptor(new TenantLineHandler() {
        @Override
        public Expression getTenantId() {
            // fail-fast：没有租户上下文的线程直接抛 TenantMissingException（TK-1001），
            // 绝不静默落到全表查询
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
        // 全局表：租户目录 + 供给账本（ADR-0002）。不豁免 = 自己把自己过滤没
        return t.startsWith("tenantkit_")
                || t.equals("tenant_catalog")
                || t.equals("sys_config"); // 按你们的全局表清单补充
    }
}
```

## 2. 豁免名单是安全义务，不是配置项

- `tenantkit_seed_history`（供给账本）**必须**豁免：provisioning 在租户上下文之外读账本，
  被行级过滤会表现为「账本永远查不到 → 每次供给都重放」。
- 租户目录表**必须**豁免：否则「查目录」这一步自己就被租户过滤，未知租户校验逻辑自锁。
- 反之，任何**不在**豁免名单里的表都会被自动追加 `tenant_id` 条件：表缺列会在运行期报错，
  这是刻意的（宁可炸，不可漏）。新增全局表时同步维护 `GlobalTables`，并加一条集成测试
  （TenantKit 的 `tenantkit-test` 里 `MultiTenantAssert` 可直接用于断言两租户互不可见）。

## 3. 多数据源

按数据源分别注册 `MybatisPlusInterceptor`（每个 `SqlSessionFactory` 各自持有），
租户 handler 共享同一个实现类即可——`TenantContext` 是线程级，与数据源无关。

```java
@Bean
@ConfigurationProperties("spring.datasource.tenant")
public DataSource tenantDataSource() { return DataSourceBuilder.create().build(); }

@Bean
public MybatisPlusInterceptor tenantInterceptor() { return buildInterceptor(); } // 同上

@Bean
public SqlSessionFactory tenantSqlSessionFactory(@Qualifier("tenantDataSource") DataSource ds) throws Exception {
    MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
    factory.setDataSource(ds);
    factory.setPlugins(tenantInterceptor());
    return factory.getObject();
}
```

注意：全局操作（目录表和账本）要走**未挂租户拦截器的数据源/SqlSession**，或者走
`ignoreTable` 豁免。两套数据源共用同一个全局库时，优先用豁免而不是引用未过滤数据源——
豁免是声明式的，漏配可测；第二条路径靠命名约定，容易在重构中丢。

## 4. 与分页插件协同

`TenantLineInnerInterceptor` 必须注册在分页拦截器**之前**：先注入租户条件，再算 count，
否则 `count` 会把别的租户数据算进总数。这是 MyBatis-Plus 官方顺序，但升级时最容易回退。

## 5. 动态表名（分表）与租户的组合

`DynamicTableNameInnerInterceptor` 在租户拦截器**之后**注册：

```java
interceptor.addInnerInterceptor(tenantLine);
interceptor.addInnerInterceptor(dynamicTableName); // 先租户条件，再改表名
interceptor.addInnerInterceptor(pagination);
```

动态表名的解析器拿到的表名不含租户条件；租户过滤会作用在最终 SQL 上。
如果你按租户分表（`orders_alpha`），那就不是行级隔离而是表级隔离——这是另一条路径，
TenantKit 不参与，`tenant_name` 的来源仍可用 `TenantContext.required()`。

## 6. 定时任务 / 异步 / MQ 消费

- **定时任务**：用 `Tenants.forEach((id, tenant) -> ...)` 显式逐租户执行（自动
  `runAs` + 恢复外层值 + 单租户失败隔离）；不要手写 `finally { clear() }`。
- **异步**：`applicationTaskExecutor` 已由 starter 装饰（MDC 与租户一起传递）；
  裸 `ExecutorService` 用 `TenantExecutors.wrap(...)`。
- **MQ 消费**：`tenantkit-messaging` 的 advice 在 listener 执行前绑定租户；
  **不要**在 listener 里自己再解析 header——重复解析与校验会绕过 kit 的缺 header 策略。
- **系统级查询**（跨租户报表、对账）：显式 `TenantContext.runAs` 指定租户，或对
  全局表走豁免。`TenantContext.required()` 在这些线程上抛错是特性，不是缺陷。

## 7. 常见事故与排查

| 现象 | 可能原因 | 排查 |
|---|---|---|
| 每次供给都重放种子 | 账本表未豁免，行级过滤后查不到记录 | 检查 `GlobalTables` 是否覆盖 `tenantkit_*` |
| 未知租户校验永远通过/永远 404 | 目录表被租户过滤 | 同上；`SELECT COUNT(*) FROM tenant_catalog` 在租户上下文中试跑 |
| 定时任务启动即 TK-1001 | 任务线程无租户上下文 | 改 `Tenants.forEach`；或在操作前 `runAs` |
| `count` 总数偏大 | 分页拦截器注册在租户之前 | 调整 `addInnerInterceptor` 顺序 |
| 某张表报 `tenant_id` 列不存在 | 该表是全局表但未豁免 | 加入 `GlobalTables` 并用测试钉住 |
| 异步任务查不到租户 | 用了裸线程池或 `CompletableFuture` 默认池 | 改 `TenantExecutors.wrap` 或注入 Spring 管理的执行器 |

## 8. 版本边界

- v0.1 交付本页完整版 + 上面的可复制接法；行级拦截器本身（TenantKit 实现）在 v1.x 路线图，
  届时会复用同一套 `GlobalTables` 豁免纪律（ADR-0002）。
- MyBatis-Plus 版本以你们项目为准；本页只依赖其公开 API（`TenantLineHandler`、
  `MybatisPlusInterceptor`、`PaginationInnerInterceptor`）。
