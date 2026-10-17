# TenantKit — Spring Boot 多租户全家桶 · 设计文档

> 收录声明：本文是设计定稿（v1.5）的仓库内副本，源件由项目发起方在仓库外维护；
> 代码中的「DESIGN §x」javadoc 引用均指向本文。

---

| | |
|---|---|
| 版本 | v1.5（R5 独立复审——外部事实核验 + 开工就绪门修订稿） |
| 状态 | 设计定稿，待开工 |
| 生态 | Java 17 / Spring Boot（v0.1 矩阵 **4.0.x + 4.1.x**；3.5 已于 2026-06-30 OSS EOL——仅存量兼容档、不承诺修复，更早不支持） |
| 计划交付物 | 7 个 Maven 模块（含 starter 与 test 工具），发布 Maven Central |
| License | Apache-2.0 |
| 预估工作量 | v0.1 约 6–8 周（42–55 人日按单人全时、每周约 7 人日有效产出折算——传统口径换算约 8.4–11 周；砍线项见 §11） |

## 1. 一句话定位

**一套开箱即用、覆盖全链路的多租户基建**：请求解析 → 上下文透传（HTTP/异步/RabbitMQ）→
Redis 键隔离 → 幂等租户供给（含种子数据账本），全部由 Spring Boot 自动装配串起来——
而不是让每个团队重写一遍 `TenantContextHolder` + `HandlerInterceptor`，然后在
「定时任务丢租户」「MQ 消息串租户」「新租户四张表没种子」上各踩一遍坑。

## 2. 背景与真实痛点（为什么要存在）

来源：一条真实多租户 SaaS 收银/ERP 线（结算链路跨四表 + Redis + RabbitMQ + 定时任务）。

1. **「结算 422 = 租户四表缺种」事故**：新租户开通后结算接口 422，根因是四张业务表没灌种子数据。
   手工开通流程没有幂等账本，漏一步就是线上事故——这就是 provisioning 模块的直接动机。
2. **手工 `TenantContextHolder`（static ThreadLocal）的传播缺口**：
   - `@Async`/自建线程池一丢租户就查穿租户边界；
   - 定时任务要手写「遍历租户 + try/finally 还原 + 单租户失败不中断」——且手写版
     `finally` 直接 `clear()` 会把外层上下文一并清掉、无每租户超时、目录表靠业务注释豁免租户过滤
     （生产参照 `ScheduleJobHelper` 逐一暴露这三点，全部吸收进 §6.6 设计）；
   - RabbitMQ 发/收两头都要手写 header 透传。
3. **Redis 串租户风险**：key 没有租户前缀约定，业务代码各自拼前缀，迟早写漏。
4. **头伪造**：直连服务带 `X-Tenant-Id` 即可冒充租户，需要显式的信任模型而不是默认信任。

每一条都是多租户团队的公共必经之路，也是 Java 后端面试的高频深水区。
TenantKit 把这条链做成一个有测试、有文档、有信任模型说明的成品。

## 3. 竞争格局与差异化

| 方案 | 覆盖 | 缺口 |
|---|---|---|
| Hibernate 6 原生 multi-tenancy | ORM 层 schema/catalog/discriminator | 只管数据访问；Web 解析、MQ、Redis、供给全不管 |
| MyBatis-Plus `TenantLineInnerInterceptor` | 行级 SQL 改写（中文圈事实标准） | 同样只管数据面；与 TenantKit 是**组合关系**（见下） |
| 各类 GitHub「multi-tenant starter」 | 多数只做 Web 解析 + ThreadLocal | 无 MQ 透传、无 Redis 隔离、无幂等供给、无 Testcontainers 集成测试 |
| 自研（普遍现状） | 按需 | 每家重写一遍，坑各踩一遍 |

**TenantKit 的位置**：不与 ORM 层竞争数据面隔离（明确非目标，见 §4），
专注「**上下文与隔离的全链路工程化**」+「**幂等供给**」两件别人不做完整的事。
事实上多数 MyBatis-Plus 用户会拿 `TenantLineInnerInterceptor` 读 `TenantContext` 组合使用——
文档提供「与 MyBatis-Plus 租户拦截器共存」集成指引（v0.1 交付最小段，完整版随 0.1.x——见 §11 砍线声明）。

## 4. 目标与非目标

**目标（v0.1）**
- 租户解析：策略链（JWT claim / Header / 子域名 / 固定值），显式信任模型与组合 fail-fast。
- 上下文：`TenantContext`（ThreadLocal + Micrometer context-propagation 集成，Loom 演进预留）。
- 透传：`TaskDecorator` 自动包装、`TenantExecutors` 工具、RabbitMQ 双向 header 透传
  （含 DLX 往返**正反例**验证、缺 header 策略）。
- Redis：租户前缀 `RedisTemplate` + Spring Cache `CacheKeyPrefix` 集成 + 共享键白名单 +
  **读路径语义规格**。
- 供给：幂等租户开通（种子脚本 + checksum 账本 + 顾问锁），占位符替换，dry-run。
- 观测：MDC 注入、租户级结构化错误码、可选 Micrometer 指标标签。
- 测试工具包：Testcontainers 工厂 + 多租户隔离断言助手。

**非目标（v0.1 明确不做，写入 README 防误解）**
- **不做 ORM 层行级隔离**（JPA/MyBatis 的 tenant discriminator 拦截器是 v1.x 路线图——先把上下文做对，
  行级过滤才有地基；且 MyBatis-Plus 拦截器生态已存在，重复造轮子价值低，见 §3 组合定位）。
- 不做租户计费/配额/后台管理 UI。
- 不绑定 ORM、不做 schema-per-tenant 的数据库实例编排（提供钩子，不替代 DBA 流程）。
- 不做 Spring Boot 2.x / 3.5 及更早（EOL 线：3.5 于 2026-06-30 OSS EOL，只留存量兼容档不承诺修复）；
  **v0.1 矩阵＝4.0.x + 4.1.x 双版**（4.0 GA 2025-11、4.1 GA 2026-06-10，均为 OSS 受支持线；
  R5 外部核验裁决——R2 的「3.5+4.0」在定稿两周后即被实况推翻，本轮修正为当前真实矩阵）。
- 上下文为**单租户维度**（生产真实场景另有店铺/用户维度，见 §6.1 扩展钩子说明）。

## 5. 模块划分

```
tenantkit/
├─ tenantkit-core            # TenantId / TenantContext / 解析器 SPI / TenantCatalog / Tenants.forEach
├─ tenantkit-web             # 解析 Filter / 错误码 / @TenantPublic / PathPattern 忽略表
├─ tenantkit-messaging       # RabbitMQ 发送/接收透传（header 常量 + DLX 语义 + 缺失策略）
├─ tenantkit-cache-redis     # 前缀 RedisTemplate / CacheKeyPrefix / 共享键 / 读路径规格
├─ tenantkit-provisioning    # 幂等供给 + 种子账本 + 事件
├─ tenantkit-spring-boot-starter  # 聚合 + 自动装配 + 配置元数据
└─ tenantkit-test            # Testcontainers 工厂 / 隔离断言助手
```

依赖关系单向：`starter → 各功能模块 → core`；每个功能模块自带 `AutoConfiguration.imports`
与 `@ConditionalOnClass` 守卫，可单独引入。

**装配面速查表（R3 补，防实现自由发挥；R4 增 enabled 键列与 core 依赖注记）**：

| 模块 | 装配守卫（@ConditionalOnClass） | 属性类 | enabled 键全称 |
|---|---|---|---|
| web | `jakarta.servlet.Filter` | `TenantKitWebProperties`（`tenantkit.web.*`，模块开关）+ `TenantKitResolutionProperties`（`tenantkit.resolution.*`，解析配置） | `tenantkit.web.enabled` |
| messaging | `org.springframework.amqp.rabbit.core.RabbitTemplate` | `TenantKitMessagingProperties` | `tenantkit.messaging.enabled` |
| cache-redis | `org.springframework.data.redis.core.RedisOperations` | `TenantKitRedisProperties` | `tenantkit.redis.enabled` |
| provisioning | `org.springframework.jdbc.core.JdbcTemplate` | `TenantKitProvisioningProperties` | `tenantkit.provisioning.enabled` |

- core 自身零装配（纯库），optional 依赖两枚：`spring-tx`（forEach 事务检测）、
  `micrometer-context-propagation`（缺席回退手工 decorator）；**core 不引 Security 类型**
  （`authentication` 已用 JDK `Principal`，R5 纠型）——Security 相关校验（含「无 Security 拒绝装配」）
  在 web 模块侧执行。
- 各模块统一 `tenantkit.<m>.enabled`（默认 true；false＝自动装配整体退出，业务 bean 不受影响）。
- 「JwtClaim 策略声明但无 Spring Security → 启动报错」**不是** `@ConditionalOnClass` 缺省的静默
  不装配——显式 fail-fast 检查单独实现（静默即违背 §6.2 哲学），装配矩阵含该负例。

## 6. 核心抽象与 API 设计

### 6.1 core

```java
/** 不可变租户标识；校验在紧凑规范构造器中（record 公开构造器不可绕过），
    默认字符集 [a-z0-9-]{1,32}（防注入、防日志注入），regex 可配（放宽见 R3 警告）。 */
public record TenantId(String value) {
    public TenantId {                       // compact constructor：唯一校验点
        // 校验 + 归一化；非法抛 TenantIdFormatException
    }
    public static TenantId of(String raw) { return new TenantId(raw); }
}

/** 显式作用域 API——无公开 set/清除器（编译期保证），鼓励 runAs/callAs 结构化传递。
    嵌套 runAs 语义：退出时**恢复外层值**而非清空（javadoc 契约，防手写版 clear() 连外层一起清的事故）。
    显式禁用 InheritableThreadLocal——只走显式传递，为 Loom/虚拟线程演进兜底。 */
public final class TenantContext {
    public static TenantId required();            // 未解析 → TenantMissingException（fail-fast）
    public static @Nullable TenantId currentOrNull();
    public static void runAs(TenantId id, Runnable action);
    public static <T> T callAs(TenantId id, Callable<T> action) throws Exception;
}

/** 解析器 SPI。策略链按 order 顺序，第一个非空者胜。 */
public interface TenantResolver {
    Optional<TenantId> resolve(TenantResolutionRequest request);
    int order();
}
// 内置：JwtClaimTenantResolver / HeaderTenantResolver / SubdomainTenantResolver / FixedTenantResolver

/** 解析器输入（容器无关快照——自定义 Resolver 能拿到的全部信息面，R3 补签名；R5 纠型）。
    authentication 用 JDK 类型 Principal：运行时即 Security 的 Authentication 实例
    （JwtAuthenticationToken 实现 Principal）——core 零 Security 依赖、无 Security 环境构造不炸；
    claims 的具体类型判定在 web 模块解析器侧完成。 */
public record TenantResolutionRequest(URI requestUri, String host,
                                      MultiValueMap<String, String> headers,
                                      java.security.Principal authentication) {}

/** 租户清单来源 SPI（R1-P0：core 无法凭空枚举租户）。 */
public interface TenantCatalog {
    List<TenantId> activeTenants();
}
// 内置：config 清单实现（tenantkit.tenants: [a, b]）；DB 查询实现交用户接
//（文档示例翻拍生产 OrganizationService 模式，并写明教训：
//  租户目录表本身必须列入任何行级过滤器的豁免名单——为 v1.x 行级拦截器提前立规矩）
```

- `TenantMissingException` 默认 fail-fast（生产教训：静默回退 default 租户 = 串租户事故温床），
  公开端点用 `@TenantPublic` 或路径忽略表显式豁免——**豁免必须显式声明**。

### 6.2 web（信任模型）

- `TenantResolutionFilter`（`OncePerRequestFilter`，默认在 Spring Security **之后**——
  正是为了拿到已验签的认证上下文，见下条）。
- **`@TenantPublic` 机制（R3 补；R5 统一语义）**：Filter 在 handler 方法确定前运行、看不见方法注解——
  实现通道＝启动期扫描 `RequestMappingHandlerMapping` 预建豁免路径集；命中路径的语义统一为
  **「仍过 Filter、仍解析，仅抑制租户缺失失败」**（不解析形态只属于 `ignore-paths` 的 Filter 层
  短路）；优先级低于 `ignore-paths`（两者同命中以 ignore-paths 为准）。
- **JwtClaimTenantResolver 只从 Spring Security 的 `SecurityContext` 读取已验签 claims
  （`JwtAuthenticationToken`）**——kit 内禁止解析未验签 JWT（ArchUnit 规则禁止裸
  jwt parse 进入 kit 代码面）。无 Spring Security 依赖时该策略**拒绝装配并启动报错**，
  绝不静默降级为自行解析原始 Authorization 头（那等于信任模型洞开）。
  例外场景（登录页需在 Security 之前解析租户做路由）文档单列取舍说明。
- `HeaderTenantResolver` 仅当 `tenantkit.resolution.trust-proxy: true` 时注册，默认 `false`；
  文档明确攻击面（直连后端伪造头）与网关侧清洗要求。
- `SubdomainTenantResolver`：必须配置 `base-domains: ["saas.example.com"]`，
  仅当主机名严格为 `<tenant>.<base>` 时解析；拒绝多级嵌套、端口歧义、大小写混用；
  未知子域走 TK-1002。信任模型文档将子域名与 header 同列为「需要边界信任」的来源。
- **配置组合 fail-fast（R1-P1）**：显式声明 `header` 策略但 `trust-proxy: false` →
  **启动失败**并提示（安全默认值不得静默吞掉用户配置）；`trust-proxy: true` 但未声明
  header 策略 → 启动警告。全部策略组合在 ApplicationContextRunner 装配矩阵有用例。
- **豁免优先级**：`ignore-paths` 在 Filter 层短路（不解析、不失败）；`@TenantPublic`
  在解析层豁免（仍过 Filter、仍解析但缺失不报错）；两者同时命中以 `ignore-paths` 为准。
- **认证边界**：`JwtClaimTenantResolver` 仅支持 `JwtAuthenticationToken`（已验签 JWT）；
  opaque token 场景显式走 header+trust-proxy 或自行扩展 Resolver SPI——写入信任模型页。
- 错误响应统一 RFC 9457 ProblemDetail + 稳定错误码：
  `TK-1001` 租户缺失（400）、`TK-1002` 未知租户（404/400 可配；子域名场景的枚举面提示
  写进该配置项说明）、`TK-1003` 租户格式非法（400）。

```yaml
tenantkit:
  resolution:
    strategies: [jwt-claim, header]      # 值域枚举：jwt-claim | header | subdomain | fixed（顺序即优先级）
    fixed: demo                          # 策略含 fixed 时必填（FixedTenantResolver 固定值）
    tenant-id-pattern: "[a-z0-9-]{1,32}" # TenantId 校验 regex（默认值；放宽的注入风险警告见 R3）
    jwt-claim: tenant_id                 # claim 名
    header-name: X-Tenant-Id
    trust-proxy: false                   # 默认不信直连头
    subdomain:
      base-domains: ["saas.example.com"]
    ignore-paths: ["/actuator/**", "/public/**"]
    unknown-tenant-status: 404           # 归 TenantKitResolutionProperties（本段内）
  tenants: [alpha, beta]                 # TenantCatalog 内置 config 实现
  messaging:
    publisher-missing-context: skip-with-warn   # skip-with-warn | fail（§6.3 发送方策略）
    listener-missing-header: reject-to-dlq      # reject-to-dlq | log-and-ack（§6.3 消费方策略）
  redis:
    no-context-policy: strict                   # strict | lenient（§6.4 读路径策略）
  executor:
    decorate-user-pools: false          # BeanPostProcessor 包装用户线程池的显式开关（§6.3）
  # 指标键 tenantkit.metrics.tenant-tag 见 §7（默认 false，无 micrometer 时静默无效）
```

### 6.3 透传（core + messaging）

- **异步**：auto-config 给 `applicationTaskExecutor` 注入 `TenantPropagationTaskDecorator`；
  BeanPostProcessor 兜底包装挂在 **`postProcessBeforeInitialization`**（R1 纠错：
  `ThreadPoolTaskExecutor` 在 `afterPropertiesSet` 里才构建内部线程池，after 阶段再
  `setTaskDecorator` 不生效）。**覆盖边界如实声明**：只覆盖 Spring 管理的
  `ThreadPoolTaskExecutor`/`SimpleAsyncTaskExecutor`；裸 `ExecutorService` bean 与
  `ForkJoinPool.commonPool`（parallelStream、CompletableFuture 默认 executor）不在此列，
  走 `TenantExecutors.wrap(Executor)` 显式路径与文档。
  **Decorator 职责**：复制/恢复 TenantContext **与 MDC**（观测承诺闭环），finally 双清；
  classpath 有 micrometer-context-propagation 时统一走 `ContextSnapshot.wrap` 并注册
  `ThreadLocalAccessor`（**缺席时回退手工 decorator，行为一致**——无依赖行为矩阵入装配测试），
  CompletableFuture 推荐路径文档化。
- **RabbitMQ**：
  - 发送：auto-config 给 `RabbitTemplate` 挂 `MessagePostProcessor`，写入
    `x-tenant-id` header（常量公开，供跨语言生产者对齐）；**无租户上下文的发送方**
    （定时任务/系统消息）策略可配：`skip-with-warn`（默认）/ `fail`。
  - 接收：`ContainerCustomizer` 挂 before-handle advice，恢复上下文 + MDC，finally 清理；
    **缺 header 消息策略可配**：`reject-to-dlq`（默认，落死信人工处置）/
    `log-and-ack`——防「无 header 消息 nack 重排队→死信→再抛」的毒消息循环；
    header 值过 `TenantId` 字符集校验。
  - **advice 合并顺序**：kit advice 自身恒定插入在容器链最前（文档化插入顺序 + 验证用例
    演示验证方法）；用户自组 advice 链时不承诺全局顺序——不做对任意用户配置的保证。
  - **DLX 用例正反例**：正例＝带 header 消息死信往返后上下文还原（header 随原始应用
    header 保留、x-death 追加）；**负例＝无 header 消息死信后按策略进 DLQ 而非循环**。

### 6.4 cache-redis

```yaml
tenantkit.redis:
  key-prefix: true            # 键改写为 t:<tenant>:
  shared-keys: ["app:*"]      # glob 白名单，匹配者不加前缀（跨租户共享数据）
  # no-context-policy 见 §6.2 总表（strict 默认 | lenient）
```

- `tenantRedisTemplate`（键序列化装饰层：**写加前缀、读剥离**——读方向带 shared-keys 与
  前缀匹配守卫；**无租户上下文默认 strict（抛 `TenantMissingException`，与 §6.1 的
  fail-fast 哲学对齐——同一类静默放行不能一边喊危险一边自己干），lenient（原样返回+告警）
  为可配项**）。
- **读路径语义单列规格（R1-P1；R5 钉剥离层）**：**剥离层唯一**——`serializer.deserialize` 不做剥离
  （写加前缀、读原样返还），前缀剥离只发生在 `tenantRedisTemplate` 的 ops 包装层（`keys()`/`scan()`
  返回值统一经包装剥离，包装内对 pattern 与返回值成对处理——注意 keys() 的 pattern 亦过
  keySerializer，该行为随 Spring Data Redis 版本核对）；由此「keys/scan 返回带前缀键名」指
  **原始 serializer 视角**，经本库 ops 包装后呈剥净形态。文档写清出入方向差异与 SCAN/KEYS 注意事项。
- `TenantCacheKeyPrefix` 无缝接入 `RedisCacheManager`；**启动期路径（preload/预热会调
  `compute(cacheName)` 且无租户上下文）固定走 lenient**（该路径 strict 会炸启动），运行期
  读写才按 `no-context-policy`——一句话定死，防实现分叉。
- **opt-in 声明**：业务照旧注入原生 `RedisTemplate` 即绕过前缀——文档明示这是 opt-in 设计，
  并给 `tenantRedisTemplate` 置 `@Primary` 的选项及其对共享基础设施键的取舍。
- **诚实表述**：值序列化与 TTL 语义不变；**键的出入方向语义按本节规格变化**（原稿
  「序列化语义不变」表述过头，已纠正）。

### 6.5 provisioning（直面「租户四表缺种」事故）

```java
public interface TenantProvisioner {
    ProvisionReport provision(TenantId id, SeedProfile profile, ProvisionOptions options);
}
// 三类型签名（R3 补，防实现漂移）：配置为默认值，options 做调用期覆盖
public record SeedProfile(String name) {}                        // ↔ seed-location/<name>/ 目录
public record ProvisionOptions(Boolean dryRun, Duration lockTimeout) {}   // null＝用配置默认
public record ProvisionReport(TenantId tenant, List<ScriptResult> executed,
                              List<ScriptResult> skipped, List<ScriptFailure> failed,
                              boolean duplicateKeyShortCircuit) {}          // 另一实例已记账＝幂等成功
```

```yaml
tenantkit.provisioning:
  enabled: true
  seed-location: classpath:db/tenant-seed/     # <profile>/V<n>__desc.sql，序号定序
  ledger-table: tenantkit_seed_history
  lock-timeout-seconds: 30                     # GET_LOCK 超时，可配
  dry-run: false                               # true 时只输出将执行的 SQL 审计
```

执行模型（幂等是第一设计约束；**GET_LOCK 是会话级锁不是事务锁**，与 HikariCP 组合有两类
经典事故——跨连接持锁＝锁形同虚设，同连接忘释放＝连接归还池后锁泄漏。规格三层，缺一不可）：

1. **单条专用物理连接全程持锁**：借出一条连接（`ConnectionCallback` 路径），在其上
   `GET_LOCK("tenantkit:provision:<tenant>", timeout)` → **逐脚本**用 `TransactionTemplate`
   在各自独立连接上执行 → **finally 在持锁连接上 `RELEASE_LOCK`**（异常路径必达，
   用例断言锁不泄漏）→ 归还连接。锁超时按配置抛错或等待，语义写入配置元数据。
2. **账本幂等双保险**：账本表 `UNIQUE KEY (tenant_id, script_name)`；已有记录逐脚本比对
   checksum（SHA-256，换行归一化 CRLF/LF，文件名参与），一致跳过、**不一致即硬拒放**
   （禁止静默重放改过 checksum 的脚本——那等于改历史）；并发插入撞 `DuplicateKey`
   视为幂等成功（另一实例已记账）。
3. 单脚本 = 单事务，`${tenantId}` 占位符替换后逐语句执行（默认字符集下无注入面；
   **放宽字符集的指引必须绑定警告：占位符替换同步改为参数化或白名单转义**）；
   任一脚本失败 → 该租户供给整体失败并保留账本现状（已成功脚本不回滚不重放）。
4. 完成发布 `TenantProvisionedEvent`；账本表为**全局表**（未来行级过滤器豁免名单必含它）；
   账本迁移脚本由**应用自己的 Flyway/Liquibase 执行**（kit 不内嵌迁移引擎）。
5. **修复路径**：种子脚本合法修订导致 checksum 不一致时，`provision --repair` 显式确认后
   重录 checksum（输出审计 diff）——杜绝「上线三个月后该租户永久供给失败无出路」。
6. 并发用例：两线程同抢一个 GET_LOCK，断言互斥、超时语义与异常路径锁释放
   （**该用例与 DLX 真实负例按 §11 砍线滑 0.1.1**，容器内缺 header 策略分支的等价单测在 v0.1 在场）。

### 6.6 批量执行（core：Tenants.forEach）

```java
Tenants.forEach((id, tenant) -> { ... }, ForEachOptions.defaults());

// R3 补签名与默认值（防实现漂移）：
public record ForEachOptions(Duration perTenantTimeout, Executor executor,
                             boolean continueOnError, boolean failOnOuterTransaction) {
    public static ForEachOptions defaults() { /* 串行 + 无超时 + 失败隔离聚合 + outerTx=WARN */ }
}
public record ForEachReport(List<TenantRunResult> perTenant) {}   // 聚合报告
public record TenantRunResult(TenantId tenant, boolean success,
                              Throwable failure, Duration elapsed) {}
// 「慢租户不拖垮整批」＝显式配 perTenantTimeout 才成立（默认串行无超时，如实标注）
```

- 租户清单来自 `TenantCatalog`（§6.1）。
- **外层事务设防（R1-P1）**：forEach 本体不开事务；运行时检测到活跃事务（外层
  `@Transactional` 把全部租户打包进一个事务＝单租户失败回滚全体、跨租户数据混事务）
  即 WARN 或抛错（可配），并配一条 ArchUnit 用例锁住 kit 自身与示例应用不犯。

### 6.7 tenantkit-test

- `TenantSupport.redis()/mysql()/rabbitmq()` Testcontainers 工厂（静态单例复用，加速套件）。
- `MultiTenantAssert.assertThat(op).forTenant(a).returns(x).forTenant(b).returns(y)`
  ——隔离性断言助手，示例应用与用户测试共用。

## 7. 观测性

- MDC：解析 Filter 注入 `tenantId`，任务结束清理；**异步路径由 §6.3 decorator 一并恢复 MDC**
  （否则观测承诺自相矛盾）。文档给出 logback pattern 片段。
- 指标：`tenantkit.metrics.tenant-tag` 默认 `false`（高基数标签警告写在配置元数据里），开启后按租户打标；
  classpath 无 micrometer 时该开关静默无效（启动 INFO 提示）。
- **异常层次与错误码族（R3 补全，不再只到 web 层）**：`TenantKitException`（基类）←
  `TenantMissingException`（TK-1001）/ `UnknownTenantException`（TK-1002）/
  `TenantIdFormatException`（TK-1003）/ `TenantProvisioningException`（TK-2xxx 预留：checksum
  不一致、锁超时、种子脚本失败）/ `TenantMessagingException`（TK-3xxx 预留：缺 header 被拒）；
  错误码族全表进文档站。
- 日志脱敏：`TenantId.toString` 不加引号不加花括号，避免日志注入面。
- 开销评估：设计阶段**不写无背书数字**；基准与开销报告（Redis 键长内存影响 / MQ advice / filter
  三项）**滑 0.1.x 交付**（与 §11 砍线声明一致）。

## 8. 测试策略（顶级质量的具体标准）

| 层 | 手段 | 门槛 |
|---|---|---|
| 单元 | core/解析器/占位符替换/校验器 表驱动 | 行覆盖 ≥ 90%（core） |
| 自动装配 | ApplicationContextRunner **参数化表驱动矩阵**（每模块 × 条件开关 × 策略组合 fail-fast ×
  无依赖回退；web 支 ≥40 用例：策略有序子集 × trust-proxy × Security 在否 × base-domains × 开关） | 矩阵用例全绿（下限数随 M1 用例表冻结） |
| 集成 | Testcontainers（MySQL/Redis/RabbitMQ）：解析→透传→隔离→供给全链路；DLX 正例、
  checksum 不一致拒放（**DLX 真实负例与并发竞争用例按 §11 砍线滑 0.1.1，issue 挂名登记不静默消失**） | 全绿 |
| 架构 | ArchUnit：`TenantContext` **无公开可变器（编译期保证）** + kit 内部与示例应用不绕过 runAs/callAs（ArchUnit 管不到下游用户代码，口径如此） | CI 门 |
| 示例应用 | `examples/demo-app`：两租户数据 + compose 一键起，`MultiTenantAssert` 断言互不可见 | README asciinema 素材源 |
| 兼容 | Boot **4.0.x / 4.1.x** 双版矩阵（3.5 存量兼容档：仅编译冒烟，不承诺运行） | 全绿 |

**总体门**：全模块覆盖率 ≥ 85%，双版矩阵绿 + ArchUnit 绿才允许发版。

## 9. CI/CD 与发布

- GitHub Actions：`ci.yml`（矩阵 + Testcontainers on ubuntu runner；**容器策略**：静态单例容器 +
  fork=1 + fast（单测/Runner）/slow（容器）分层 + 按 Boot 版 × 模块拆 job，套件时长上限 20min）；
  `release.yml`：tag → 测试门 → Maven Central（central-publishing 插件，GPG 签名，javadoc/source jar）；
  **模块 DoD**：每模块落地即交付 `AutoConfiguration.imports` + 全量配置元数据 + javadoc jar
  （不攒到 M3）；Central 首发（GPG/账号在 M1 申请）前跑一次门户预发布校验（doclint 首跑常翻车）。
- 语义化版本；配置键变更属 breaking，记 CHANGELOG 并给迁移说明。
- 文档：MkDocs 站点（概念 → 各模块 → 信任模型 → 读路径/锁语义 → 升级 →
  MyBatis-Plus 共存指引（**v0.1 最小段 + 完整版滑 0.1.x**））；
  配置项全量 `additional-spring-configuration-metadata.json`，IDE 提示可用。

## 10. 仓库结构

```
tenantkit/
├─ tenantkit-*/（7 模块）
├─ examples/demo-app/        # compose 一键演示（两租户隔离 + 供给流程；基准任务滑 0.1.x）
├─ docs/                     # MkDocs 站点
│                            #   + adr/（ADR-0001 GET_LOCK 连接绑定与账本幂等模型；ADR-0002 TenantCatalog
│                            #     SPI 与目录表豁免纪律；ADR-0003 Boot 4.x 支持）
├─ LICENSE · SECURITY.md · CONTRIBUTING.md
└─ README.md                 # 五件套：痛点 / 模块架构图 / 30 秒 Quickstart YAML / 两租户隔离 GIF / 徽章
```

提交纪律：按里程碑逻辑批次提交（禁一次性 initial commit）。
语言策略：英文 README 门面 + 中文文档站。

## 11. 里程碑（R3 任务级压测后重排，含砍线声明）

| 里程碑 | 范围 | 验收 |
|---|---|---|
| M1（第 1–2 周） | **首日冻结装配矩阵用例表**（下限数与用例清单是 M1 交付物，先冻结再写实现）+ 仓库脚手架（父 POM + **双版机制＝Maven profile 切 `boot.version` + CI 双 job**/ci.yml 骨架）+ GPG/Central 账号申请 + core（TenantId/TenantContext/TenantCatalog/forEach/请求快照）+ web（解析/错误码/信任模型/组合 fail-fast/@TenantPublic）+ **装配矩阵参数化用例表（含无依赖回退）** + **tenantkit-test 容器工厂前移至此**（M2 集成测试前置；MultiTenantAssert 留 M3）+ **Boot 双轨从 M1 起每模块双版跑** | 矩阵 ≥40 用例绿；双版 CI 起跑 |
| M2（第 3–4 周） | 透传（decorator+MDC、RabbitMQ 双向 + 缺 header 策略）+ Redis（前缀/读路径/no-context 策略）+ **Boot 4.0↔4.1 差异核对表**（R5 外核修正：imports 路径未变、`ContainerCustomizer` 包路径未变——真实差异＝必须用 `spring-boot-starter-amqp`（裸 spring-rabbit 不带装配）、Spring AMQP 4.0 移除 spring-retry、AMQP 自动装配包名迁 `org.springframework.boot.amqp.autoconfigure`——逐项列任务，不攒一次性验证） | **封闭清单验收**：解析→异步→MQ 透传→Redis 隔离全绿（含 DLX 正例）；缺 header 策略分支以容器单测覆盖；真实 DLQ 往返负例与供给并发竞争用例按砍线滑 0.1.1 |
| M3（第 5–7 周，含 1–2 周缓冲） | provisioning（连接绑定锁/账本/dry-run/--repair）+ MultiTenantAssert + demo-app（**收敛形态**：compose + 3 端点 + 录制脚本，复用集成测试夹具）+ MkDocs（精简 3 页起步：概念/信任模型/快速上手）+ Central 首发（含门户预发布校验） | asciinema 演示 + Central 上架 |

**砍线声明（R3 诚实口径，取代旧「最低集」句）**：基准任务与开销报告、MyBatis-Plus 共存指引完整版、
MkDocs 双语全量页**滑至 0.1.x**；DLX 真实负例与供给并发竞争用例滑 0.1.1；
**测试门槛不随砍线降低**——滑走的用例在 issue 里挂名登记，不静默消失。

## 12. 风险与开放问题

| # | 风险/问题 | 应对 |
|---|---|---|
| R1 | 行级隔离缺席可能被误读为「不完整」 | README 非目标声明 + v1.x 路线图 + §3「与 MyBatis-Plus 共存」组合定位 |
| R2 | BeanPostProcessor 包装用户线程池有侵入性 | 默认只包 `applicationTaskExecutor`，包装行为显式开关；before-initialization 挂点与覆盖边界写死（§6.3）；变更记 CHANGELOG |
| R3 | `TenantId` 字符集限制可能不适合存量系统 | 校验规则可配（regex），但放宽指引必须绑定警告：占位符替换同步改参数化/白名单转义，否则重新打开注入面（§6.5） |
| R4 | 种子脚本格式自造（非 Flyway）会被质疑 | 文档写明为什么不用 Flyway 管租户级种子（Flyway 面向单库历史，租户维度是二维空间，账本模型不同）；保留 Flyway 风格命名便于迁移心智 |
| R5 | 名称可用性（Maven Central groupId/artifactId） | 开工前核验；备选 `Tenant4j` |
| R6 | RabbitMQ 之外的 MQ（Kafka）需求 | SPI 设计留缝（header 写入/恢复接口），v0.1 只交付 RabbitMQ，不空头承诺 |
| R7 | Boot 4.x 兼容成本（starter 结构重组验证） | **4.0.x / 4.1.x 双版自 M1 同跑**（差异核对表列 M2 任务）；双版矩阵 CI 门 |

## 13. 面试叙事（这个项目证明什么）

- 分布式上下文传播的完整链路设计（ThreadLocal 边界、before-init 线程池包装、MQ header、MDC、context-propagation）。
- 幂等供给工程：会话锁连接绑定 + 账本唯一键 + checksum 纪律——每个细节对应一次真实事故或一类经典并发陷阱。
- 信任模型与安全默认值（已验签 claims 才可信、不信任直连头、fail-fast 而非静默回退、组合配置启动校验）。
- 测试工程：Testcontainers 全链路、并发竞争与锁泄漏用例、DLX 正反例、ArchUnit 架构门。

## 14. 评审记录

| 轮次 | 评审者 | 结论 | 主要修订 |
|---|---|---|---|
| R1 | 架构评审（Java/Spring 方向） | FIX-THEN-GO | P0×3 全部吸收：①JwtClaimTenantResolver 锁定 SecurityContext 已验签 claims、无 Security 拒绝装配、ArchUnit 禁裸 parse（原设计信任模型核心悬空）；②GET_LOCK 连接绑定三层规格（专用连接全程持锁/账本 UNIQUE 键+DuplicateKey 幂等/锁超时与异常路径释放断言，原设计在 HikariCP 下锁形同虚设或泄漏）；③TenantCatalog SPI（原 Tenants.forEach 无租户清单来源）+ 目录表豁免教训入文档。P1×9 全部吸收：BPP 改 before-initialization（after 挂点无效）；MQ 缺 header 毒消息循环策略（reject-to-dlq）+ 发送无上下文策略 + advice 外层序；配置组合 fail-fast；子域名 base-domains 校验；Redis 读路径规格（keys/scan 带前缀、剥离守卫）并纠正「序列化语义不变」过头表述；forEach 外层事务设防；ForEachOptions 补齐（否则「慢租户不拖垮整批」是空头承诺）；Boot 矩阵更新 3.4/3.5（3.3 已 EOL）+ Boot 4 显式延后；TaskDecorator 补 MDC 恢复 + context-propagation 注册。P2 择要吸收：record compact constructor、ArchUnit 口径纠正（原引用不存在的 set* API）、字符集放宽绑定注入警告、checksum 规格补全（SHA-256/换行归一化）、opt-in 声明、MyBatis-Plus 组合定位、单租户维度声明、嵌套 runAs 语义、禁 InheritableThreadLocal。工期改 2–3 周 + §11 砍线声明 |
| R2 | 横切组合评审 + 对抗式评审 | FIX-THEN-GO | P0×1 吸收（当场翻车级）：Boot 矩阵在 2026-10 整体过期（3.4/3.5 均 EOL、当前主版本 Boot 4.0 被排到 v0.2）——改为 **v0.1 矩阵 = 3.5.x + 4.0.x**，starter 重组验证成本计入 M2，4.1 跟进。P1×2 吸收：删除无背书性能数字（「纳秒级/微秒级」），改为基准随 0.1.x 发布；Redis 无上下文读路径默认 strict 抛 TenantMissingException（原「原样返回+告警」与自家 fail-fast 哲学自相矛盾），lenient 降为可配。P2 择要吸收：`provision --repair` 显式修复路径；advice 顺序口径从「保证」降为「文档化插入顺序+验证方法」；豁免优先级（ignore-paths vs @TenantPublic）一句话定死；JWT-only 认证边界写入信任模型页；M3 卸载（GPG/Central 账号提前 M1 申请、GIF 换 asciinema）。横切吸收：LICENSE/SECURITY/CONTRIBUTING/docs/adr 入树（三条种子 ADR）、README 五件套与提交纪律、语言策略显式化 |
| R3 | API/配置完备性审计 + 落地工期压测（两路并行评审） | FIX-THEN-GO | API 审计 P0×3 全部吸收：①`TenantResolutionRequest` 补 record 签名（SPI 契约核心原为零定义）；②`SeedProfile/ProvisionOptions/ProvisionReport` 三类型补字段清单与「配置为默认、options 覆盖」语义；③messaging 双策略与 redis no-context 三个「正文说可配却无键名」的键全部入 §6.2 总表。P1×5 全部吸收：@TenantPublic 机制（Filter 看不见注解→启动期扫 HandlerMapping 预建豁免路径集）；ForEachOptions 补签名+ForEachReport+core 对 spring-tx optional；异常层次 TenantKitException+TK-2xxx/3xxx 预留；FixedTenantResolver 配置键与 strategies 值域枚举；§5 装配面速查表（模块×守卫类×属性类+fail-fast 显式实现声明）。P2 择要：TenantCacheKeyPrefix 启动期固定 lenient；模块级 enabled 统一；yaml 代码块标注纠正；tenant-id-pattern 键。工期压测 P0×3 全部吸收：④2–3 周→**5–7 周**诚实口径（任务级加总 42–55 人日 vs 原 15 人日，差 3 倍）；⑤M2 验收改封闭清单（原与砍线声明自相矛盾）；⑥tenantkit-test 容器工厂前移 M1。P1 全部吸收：Boot 双轨差异核对表任务化+M1 起双版跑；无 micrometer 回退入矩阵；Testcontainers CI 策略（单例+fork=1+fast/slow+时长上限）；装配矩阵量化（web ≥40 用例）。P2：模块 DoD（imports+metadata+javadoc 不攒 M3）；demo-app 收敛为 compose+3 端点；MkDocs 精简 3 页起步；基准/MyBatis-Plus 指引滑 0.1.x |
| R4 | 修订核验 + 冷读者终审（两路并行评审） | 通过（残留缺口收口） | 核验：①§6.2 总表补 metrics 键指路注；②§8 集成行与 §6.5 并发用例条加「滑 0.1.1」标注（原与砍线打架）；③§6.5 重复编号「5.」修正（并发用例=6，且发现 R3 编辑残留旧条已删）；④core 对 spring-security-core/micrometer-context-propagation 补 optional 声明（TenantResolutionRequest 引 Authentication 曾与「core 不绑 Security」叙事有张力）；⑤TenantRunResult 补签名。冷读者：⑥双版机制定死＝Maven profile 切 boot.version + CI 双 job；⑦速查表增 enabled 键全称列；⑧unknown-tenant-status 归属注记；⑨M1 首日加「冻结用例表」（先冻结再实现）；⑩§9 MyBatis-Plus 指引加滑 0.1.x 注。冷读者开工之问（Boot 3.5 形态 imports 是否 M2 推翻 M1）已由 ⑥+差异核对表回应：M1 按 3.5 形态写，M2 核对表逐项调整并双版门禁 |
| R5 | **独立复审：外部事实核验 + 开工就绪门**（两路并行评审，联网核验 + 文档独读） | READY-WITH-FIXES → 已收口 | 外核 P0（矩阵二次过期）：**Boot 4.1 已于 2026-06-10 GA、4.0 GA 2025-11，当前 OSS 受支持矩阵＝4.0.x + 4.1.x；3.5 于 2026-06-30 EOL**——「3.5+4.0」矩阵修正为 **4.0+4.1**（3.5 降存量兼容档不承诺），R2 的「4.0 为当前主版本」表述删除。外核修正：imports 路径未变 / `ContainerCustomizer` 包路径未变——M2 差异核对表改写为真实差异三项（starter-amqp 必需性、Spring AMQP 4.0 移除 spring-retry、AMQP 自动装配包名迁移）。外核证实：GET_LOCK 会话锁语义、BPP before-init 约束、context-propagation 坐标、Testcontainers 单例实践均准确。就绪门 5 项收口：**人日↔周口径声明**（42–55 人日按每周约 7 人日折算→6–8 周，传统口径 8.4–11 周；删悬空「压缩至 4–5 周」）；@TenantPublic 语义统一「仍解析、仅抑制缺失失败」（原两处互斥）；web 模块属性类拆分（`TenantKitWebProperties` 模块开关 + `TenantKitResolutionProperties` 解析配置），unknown-tenant-status 移入 resolution 段；`TenantResolutionRequest.authentication` 改 JDK `Principal` 类型（core 零 Security 依赖，消除无 Security 场景 NoClassDefFoundError）；Redis 剥离层钉死（serializer 不剥、ops 包装剥）+ keys() pattern 过 keySerializer 注记；基准/demo 与砍线口径对齐；MyBatis-Plus 指引口径与 §3 对齐。**§14 本表历史行中的旧版本口径（3.4/3.5 等）均为历史记录，以本行为准** |
