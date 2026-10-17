# 生产注意事项（v0.1）

## Redis：前提

- 需要 `spring-boot-starter-data-redis`（或任意 `RedisTemplate` bean）；
  都没有时模块启动会 **WARN 明示隔离未生效**——"以为隔离了"是这类事故的起点。
- `tenantRedisTemplate` 非 `@Primary`：注入原生 `RedisTemplate` 即绕过前缀（opt-in 设计）。

## Redis：KEYS 与 SCAN

- `tenantRedisTemplate.keys(pattern)` 的 pattern 会**加租户前缀后下发**，返回键名
  **剥离前缀后**返回（业务视角无 `t:<tenant>:`）。生产环境慎用 `KEYS`（O(N) 阻塞），
  优先 `scan(ScanOptions)`：match 语义与 KEYS 略有差异（match 不过 keySerializer、
  原样进连接，返回值统一经 `deserializeKey` 剥离）。
- **SCAN 的剥离在调用发生时绑定租户**（防惰性消费期间上下文被切换导致的错位剥离），
  但请仍然在同一个租户作用域内消费完整个 `Cursor`。
- `SCAN` 无 pattern 时：strict 抛 TK-1001；lenient **原样返回全库物理键**
  （会看到所有租户的 `t:*`）——语义如实，勿在 lenient 下裸用无 pattern scan。
- v0.1 覆盖面：Value 读写 / keys / scan / delete / hasKey；其余 ops 显式
  `UnsupportedOperationException`（见 javadoc）。绕过前缀的两个官方口子：
  注入原生 `RedisTemplate`（opt-in 设计）、共享键白名单 `shared-keys`。
- 键长成本：前缀 `t:<tenant>:` 恒定 3 + 租户长度 + 2 字节。租户名上限 32 字符时，
  每 key 至多 +37 字节（含分隔符）——精确内存开销基准随 0.1.x（BACKLOG #3）。

## Provisioning：前提与方言

- **默认锁管理器只支持 MySQL**（`GET_LOCK` 会话锁）。H2/PostgreSQL 等请自备
  `TenantLockManager` bean（`@ConditionalOnMissingBean`，用户 bean 自动取代默认）。
- `SeedLedger.REFERENCE_DDL` 是 **MySQL 方言**（`ENGINE=InnoDB`/`UNIQUE KEY`）；
  换库按方言改写，唯一键 `(tenant_id, script_name)` 必须保留——它是并发幂等的地基。
- 亚秒级锁超时会被向上取整到 1 秒（`GET_LOCK` 的整数秒语义；0 表示不等待）。

## Provisioning：连接池与锁

- `GET_LOCK` 绑定**单条专用物理连接**全程持有，脚本事务各自另用连接：HikariCP
  `maximumPoolSize` 至少 = 并发供给数 × 2 + 日常余量；池太小会表现为「取锁连接成功、
  脚本事务等池」的假死。连接池容量注意已写入 `TenantLockManager` javadoc。
- 账本表 `tenantkit_seed_history` 建表 DDL 见 `SeedLedger.REFERENCE_DDL`，由应用自身的
  Flyway/Liquibase 迁移执行；缺表时启动 WARN，provision 时抛 TK-2004 带同一 DDL。
- checksum 硬拒放（TK-2001）是设计行为：修订种子脚本必须走 `repair` 显式重录
  （只重录不执行）或追加 `V(n+1)` 脚本，禁止静默改历史。

## Messaging：DLQ 消费者必须用不带租户 advice 的容器工厂

`reject-to-dlq` 下，无 header 消息在首次拒绝后进入 DLQ；DLQ 队列通常没有下一级死信交换机。
**如果 DLQ 消费者仍挂租户 advice，它会把这条消息再拒绝一次——无处可去，消息直接丢失**
（"进了 DLQ"变成"从 DLQ 消失"）。做法：

1. 为 DLQ 消费者建独立的 `SimpleRabbitListenerContainerFactory`，**不设置 advice chain**；
2. 使用 starter 时，把该工厂的 bean 名加入 `tenantkit.messaging.raw-container-factories`，
   阻止 kit 的 BeanPostProcessor 给它挂 advice；
3. `@RabbitListener(queues = "...", containerFactory = "yourDlqFactory")` 指向它。

这类消费者天然没有租户上下文：按"人工 triage"处理，不要调用 `TenantContext.required()`。

## Messaging：拓扑与毒消息

- `reject-to-dlq`（默认）依赖队列配置 DLX 才真的有「死信可去」；未配置 DLX 的队列在
  reject 后消息直接丢弃。建议所有业务队列显式配 DLX + 告警。
- 无 header 消息（外部生产者、系统消息）走 `reject-to-dlq` 会进死信——对接第三方生产者时
  要么让对方带 `x-tenant-id`（常量 `TenantMessagingHeaders.TENANT_HEADER`，跨语言对齐），
  要么显式配 `log-and-ack` 并自担上下文缺失后果。
- 发送方无租户默认 `skip-with-warn` 放行（定时任务/系统消息场景），要求严格时配 `fail`。

## Web

- Filter 层错误响应固定 `application/problem+json`，无内容协商变体（v0.1 范围）。
- Jackson 不在 classpath 时错误体由内置极简 writer 产出，形状与 Jackson 路径完全一致。
- `unknown-tenant-status` 默认 404；若你的客户端会枚举租户名做探测，考虑 400 减少信息面。

## 测试与本地验证

- `MockMvc` 不经 Servlet Filter 链：租户解析、豁免、错误码都测不到——用
  `webEnvironment=RANDOM_PORT` 的真实端口（参考 `TenantKitRealServerIntegrationTest`）。
- 想本地看 javadoc jar：`./mvnw -Prelease package`（默认构建不产 sources/javadoc）。

## 观测

- logback pattern 建议：`%d %-5level [%X{tenantId:-}] %logger - %msg%n`。
- `TenantId.toString` 是裸值（无引号无花括号），可直接拼日志；默认字符集
  `[a-z0-9-]{1,32}` 本身防日志注入，放宽 regex 前先读 provisioning 占位符注入面警告。
