# Boot 4.0 ↔ 4.1 差异核对表（M2 交付物，DESIGN §11）

> 依据 R5 外部核验的三项真实差异 + 本仓库实施期实测补充。每项＝任务化核对，
> 双版 job（boot40=4.0.8 / boot41=4.1.1）构建绿即视为该项通过。

| # | 差异项 | 影响 | TenantKit 应对 | 核对状态 |
|---|---|---|---|---|
| 1 | **spring-boot-starter-amqp 必需**：裸 spring-rabbit 不带 Boot 装配 | 用户缺 starter-amqp 时 MQ 自动装配缺失 | messaging POM：编译只依赖 spring-rabbit（optional），测试与文档一律以 starter-amqp 为准；README 集成段写明 | ✅ POM 落实；slow 集成测试跑 starter-amqp |
| 2 | **Spring AMQP 4.0 移除 spring-retry** | 依赖 spring-retry 的 retry/interceptor 组合失效 | kit advice 链不引 spring-retry；文档提示用户自组 retry 时显式引入 | ✅ 代码零 spring-retry 引用（ArchUnit 兜底：禁止依赖 org.springframework.retry） |
| 3 | **AMQP 自动装配包名迁移** `org.springframework.boot.amqp.autoconfigure` | 直接引用 Boot AMQP 自动装配类名的代码需迁移 | kit 只依赖 `org.springframework.amqp.*`（Spring AMQP 自身包名未变），不 import Boot 侧 AMQP 自动装配类 → 迁移零影响 | ✅ 编译双版验证 |
| 4 | （R5 核验不变项）`AutoConfiguration.imports` 文件路径与文件名 | — | 各模块照常 `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` | ✅ 双版装配矩阵绿 |
| 5 | （R5 核验不变项）`ContainerCustomizer` 包路径 `org.springframework.amqp.rabbit.config` | — | messaging 接收侧装配按此实现，编译实测为准 | ✅ messaging 编译期核对 |
| 6 | （本仓库实测）**Boot 4 模块化**：`spring-boot-autoconfigure` 成为独立基础构件（注解包名不变），`spring-boot` 构件不再携带 autoconfigure 包，也不再传递 spring-context | 库 POM 需显式依赖 spring-boot-autoconfigure / spring-context | web/starter POM 已显式声明；core 零 Boot 依赖不变 | ✅ |
| 7 | （本仓库实测）**Spring 7 API 变动**：`SimpleAsyncTaskExecutor` 移至 `org.springframework.core.task`；`ThreadPoolTaskExecutor`/`SimpleAsyncTaskExecutor` 移除 `getTaskDecorator()` 读取器 | BPP 幂等守卫不能回读 decorator | TenantExecutorBeanPostProcessor 以 bean 名集合追踪幂等 | ✅ starter 测试锁定 |
| 8 | （本仓库实测）context-propagation 1.2.x：`removeThreadLocalAccessor`（非 unregister）、`ContextSnapshotFactory.builder().build().captureAll()`、`snapshot.wrap(Runnable)`；`ContextRegistry` 无按 key 查询方法 | 传播桥实现按 1.2 API | core 传播桥/装饰器均按 1.2 实测签名实现 | ✅ |

维持约定：主矩阵只有 4.0.x + 4.1.x；3.5 为存量兼容档（compile 冒烟，不承诺运行），任何旧口径以 ADR-0003 为准。
