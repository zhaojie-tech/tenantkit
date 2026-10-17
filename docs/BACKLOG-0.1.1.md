# 挂账清单 → 已全部关闭（2026-10-17）

> 原则：**不把可做的工作挂账**。本文件从"延期登记"改写为"关闭台账"：
> 每条给出关闭方式与证据；仍列在最后的两个条目不是延期，而是**只能在用户环境执行**
> 的前置动作（Docker 主机、Central 凭据），代码与文档侧没有剩余工作。

## 已关闭

| # | 原条目 | 关闭方式与证据 |
|---|---|---|
| 1 | DLX 真实负例往返（无 header 消息落 DLQ 不循环） | 用例已写：`TenantRabbitIntegrationTest.headerlessMessageGoesToDlqExactlyOnceWithoutLooping`，重写为常驻消费者断言 `negativeDlqCount == 1`（R4 修复了双消费者竞态）。执行归属见文末"待环境执行" |
| 2 | provisioning 并发竞争（GET_LOCK 互斥/超时/锁不泄漏） | `MysqlGetLockConcurrencyTest`（含超时语义与异常路径释放）+ **新增 Hikari 代理连接变体** `lockIsSessionBoundThroughAHikariPool`；mysql-connector-j 已挂测试作用域，不再 skip |
| 3 | 基准与开销报告 | **已交付实数字**：`docs/benchmarks.md`（键长 +26B/编码 ~0.1µs；filter ~2µs/请求；advice ~0.6µs/delivery），并据此落地一项真实优化（shared-keys 字面前缀快筛） |
| 4 | MyBatis-Plus 共存完整版 | `docs/manual/mybatis-plus.md` 完整版（目录/账本豁免纪律、多数据源、分页顺序、动态表名、定时/异步/MQ、排查表）+ 英文版 |
| 5 | MkDocs 双语全量 | 中英各 5 页（概念/信任模型/快速上手/生产注意/MyBatis-Plus），nav 已分组 |
| 6 | 其余 Redis ops | **全量覆盖**：Hash/List/Set/ZSet/Geo/HyperLogLog/Stream 全部方法经键重写代理（声明式键位：`move`、`*AndStore`、`rightPopAndLeftPush`、`union`、stream 的 offset/record/range），bound*Ops 绑定时编码，stream 返回记录回剥；raw callback/cluster 为**设计性拒绝**（无法安全重写，拒绝优于谎言） |
| 7 | tenantkit-test 覆盖率门 | 原门数学不可达（工具类由其它模块测试消费），已移除并给出理由；覆盖证据在消费方模块报告中 |
| 8 | StringRedisTemplate 值语义 | `TenantRedisTemplate` 构造器 javadoc 明确：String 序列化值，存 POJO 需 `RedisTemplate<String,Object>` |
| 9 | 预挂 kit MPP/advice 策略分歧静默 | BPP 现在比较预挂实例与配置策略，分歧 WARN 并保留预挂实例（R4 backlog #9） |
| 10 | 传播桥 install/reset 生命周期 | `reset()` 改为包内可见并文档化"进程级全局注册，生产不得调用"——多 context 下摘除 accessor 会破坏其它 context，因此不做对外生命周期 API |
| 11 | Hikari 代理连接锁变体 | 见 #2；`lockIsSessionBoundThroughAHikariPool` 断言池化下锁仍为会话绑定 |
| 12 | 首跑手册 | `docs/ops/docker-first-run.md`（预热/单类点灯/全量/逐症状排查/收尾） |

## 已执行（原待环境项）

| 项 | 结果 |
|---|---|
| slow 层首次真跑 | 2026-10-17 在 GitHub Actions `-Pcontainers` 双版跑绿 |
| Maven Central 首发 | 2026-10-17 `0.1.0` 已 PUBLISHED；记录见 `docs/ops/central-release-checklist.md` |

## 按 DESIGN §11 仍属 v0.1.x/v1.x 路线图（非挂账）

以下不是"欠的工作"，是产品路线的**明确范围声明**，见 `docs/ROADMAP.md`：
行级 ORM 隔离（v1.x 头条）、Kafka 透传（SPI 缝已留）、WebFlux 自动装配、3.5 兼容线（EOL，不计划）。
