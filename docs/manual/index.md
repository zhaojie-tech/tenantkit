# TenantKit 概念

TenantKit 是一套开箱即用的 Spring Boot 多租户基建：**请求解析 → 上下文透传（HTTP/异步/RabbitMQ）→ Redis 键隔离 → 幂等租户供给**，全部由自动装配串起来。

它不解决的：ORM 层行级隔离（v1.x 路线图；与 MyBatis-Plus `TenantLineInnerInterceptor` 是组合关系——读 `TenantContext` 即可共存）、租户计费/配额、schema-per-tenant 实例编排。

## 模块

| 模块 | 职责 |
|---|---|
| `tenantkit-core` | TenantId / TenantContext（无公开可变器，只有 runAs/callAs 结构化作用域）/ Resolver SPI / TenantCatalog / Tenants.forEach |
| `tenantkit-web` | 解析 Filter、策略链（jwt-claim / header / 子域名 / 固定值）、豁免机制、稳定错误码 TK-1001/1002/1003 |
| `tenantkit-messaging` | RabbitMQ 发/收双向 `x-tenant-id` header 透传、缺 header 策略、DLX 语义 |
| `tenantkit-cache-redis` | 租户前缀 `tenantRedisTemplate`（写加前缀、读剥离）、共享键白名单、no-context 策略 |
| `tenantkit-provisioning` | 幂等开通：种子脚本 + SHA-256 账本 + 连接绑定 GET_LOCK、dry-run、repair |
| `tenantkit-spring-boot-starter` | 聚合 + 异步透传装配（applicationTaskExecutor 装饰器） |
| `tenantkit-test` | Testcontainers 单例工厂 + MultiTenantAssert 隔离断言 |

## 核心心智模型

1. **上下文是显式作用域**：`TenantContext` 没有公开 set/clear，唯一入口 `runAs(id, action)` / `callAs(id, action)`，退出时恢复外层值（不是清空）。跨线程一律显式传递（decorator / MQ header / `TenantExecutors.wrap`），禁用 InheritableThreadLocal。
2. **解析是策略链**：`tenantkit.resolution.strategies` 按声明顺序执行，第一个非空者胜。策略声明与信任配置矛盾时**启动失败**（见信任模型页）。
3. **错误码稳定**：TK-1001 缺租户（400）、TK-1002 未知租户（状态可配默认 404）、TK-1003 格式非法（400），TK-2xxx 供给、TK-3xxx 消息族预留。响应统一 RFC 9457 ProblemDetail。
4. **供给幂等第一**：账本表 `UNIQUE(tenant_id, script_name)` + SHA-256 checksum（换行归一化）；checksum 变更硬拒放，`repair` 显式重录；GET_LOCK 绑定单条专用物理连接全程持有（详见 ADR-0001）。

## Boot 版本支持

v0.1 矩阵 = **Spring Boot 4.0.x + 4.1.x**（双版 CI 门）。3.5 已于 2026-06-30 OSS EOL，仅存量兼容档。详见 ADR-0003 与 `docs/boot-compat-checklist.md`。
