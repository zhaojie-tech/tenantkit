# TenantKit 实施进度（docs/PROGRESS.md）

> 维护者按里程碑更新。验收依据 = DESIGN.md v1.5 §11。

## 状态总览

| 里程碑 | 状态 | 备注 |
|---|---|---|
| M1 | **完成** | 双版全绿；矩阵 51 条冻结并全实现 |
| M2 | **完成（本机可验证部分全绿）** | messaging（fast 38 双版绿/99.1%）+ cache-redis（fast 52 双版绿/98.4%）+ 异步透传 + 差异核对表；DLX 正反例 slow 用例已写待容器 |
| M3 | **代码与文档全部落地，Central 真发版待用户凭据** | provisioning（fast 62 绿/96.0%）+ MultiTenantAssert + demo-app + MkDocs 三页 + release.yml/central profile；门户预发布校验已过 doclint |

## M1 交付记录（2026-10-15）

- 冻结装配矩阵用例表 51 条：`docs/matrix/assembly-matrix.md`（W01–W45 + K01–K06，先冻结后实现）
- 仓库脚手架：多模块父 POM + boot40(4.0.8)/boot41(4.1.1) profile 双版机制 + Maven Wrapper + ci.yml 双 job 骨架
- tenantkit-core：TenantId（紧凑构造器校验/可配 pattern/裸 toString）/ TenantContext（无公开可变器/嵌套恢复/禁 InheritableThreadLocal）/ Resolver SPI + 请求快照 / TenantCatalog + config 实现 / Tenants.forEach（超时/失败隔离/外层事务设防）/ 异常族 TK-100x·2xxx·3xxx / micrometer context-propagation 桥（守卫后置验证）
- tenantkit-web：TenantResolutionFilter（ignore-paths 短路→解析链→TK-1003→目录校验 TK-1002→@TenantPublic 抑制缺失→TK-1001）/ 四内置解析器 / 信任模型组合 fail-fast（BFPP 先于一切 bean 实例化）/ RFC 9457 ProblemDetail / AutoConfiguration.imports + 全量配置元数据
- tenantkit-test：TenantSupport 单例容器工厂（懒加载）+ HidingClassLoader 无依赖回退模拟工具
- 质量门实测：core 行覆盖 96.8%（门 90%）；web 行覆盖 87.2%（门 85%）；test 模块容器路径门挂 -Pcontainers（fast 层无 Docker 无法诚实覆盖）；ArchUnit 6 规则绿；javadoc/sources jar（doclint on）构建通过
- 文档面：README（英文门面五件套）/ SECURITY / CONTRIBUTING / ADR-0001~0003 / docs/ops/central-release-checklist.md

### 实测数字（2026-10-15，本机）

| 项 | 结果 |
|---|---|
| boot40 全量 verify（fast 层 + 覆盖率门 + ArchUnit） | BUILD SUCCESS，108 tests / 0 fail |
| boot41 全量 verify | BUILD SUCCESS，同 108 tests |
| core 覆盖率 | LINE 96.8%（183/189），门 90% |
| web 覆盖率 | LINE 87.2%（258/296），门 85% |
| 套件时长 | 全仓 fast 层 < 1min（容器层未在本机执行） |

### 本机环境限制（如实声明）

- **无 Docker**：TenantSupport 容器启动用例（@Tag("slow")）本机未执行；M2 的「解析→异步→MQ 透传→Redis 隔离」封闭清单验收必须在有 Docker 的 CI 或用户机上跑。
- M1 验收项「双版 CI 起跑」= ci.yml 骨架就绪 + 本机双版 verify 绿；GitHub 仓库建立后 push 触发首跑（外部前置未到位，见阻塞）。

## 待办 / 阻塞

- [阻塞-用户] GitHub 账号/组织名确认（推送前必须）
- [阻塞-用户] Maven Central groupId + GPG（清单：docs/ops/central-release-checklist.md）
- [阻塞-环境] 本机 Docker 缺失（容器层测试交 CI）
- [0.1.1 issue 挂名] DLX 真实负例往返、provisioning 并发竞争用例（§11 砍线声明，登记不消失）
- [偏差记录] core 新增 spring-core 编译依赖（TenantResolutionRequest 冻结签名用 MultiValueMap 所需，DESIGN §5 optional 双依赖之外，已在 M1 汇报偏差段声明）


## M2/M3 交付记录（2026-10-15 续）

- 异步透传：TenantPropagationTaskDecorator（micrometer 在场时 snapshot.wrap 叠加手工恢复，双路径行为一致）/ TenantExecutors / starter BPP（before-init，Spring 7 移除 decorator 读取器→bean 名集合幂等）
- messaging：发送 x-tenant-id（skip-with-warn|fail TK-3001）、接收 advice（reject-to-dlq TK-3002|log-and-ack、非法值 TK-3003、保监听器原始异常类型）、advice 链合并 kit 恒在首；矩阵 MQ01–MQ10
- cache-redis：TenantRedisTemplate 写加前缀读剥离、keys/scan 成对改写+调用时绑定剥离、shared-keys 先于策略判定（修 STRICT 误抛陷阱）、TenantCacheKeyPrefix 按 spring-data 4.x 签名（compute→String、"::"）；矩阵 R01–R12；v0.1 ops 覆盖面 javadoc 声明
- provisioning：三层模型（连接绑定 GET_LOCK/账本 UNIQUE+SHA-256/finally 必释放）、checksum 硬拒放 TK-2001、锁超时 TK-2002、repair 重录、dry-run、${tenantId} 占位符、TenantLockManager SPI（H2 可测、MySQL 语义为准）；矩阵 P01–P08；账本 REFERENCE_DDL 公开
- demo-app：compose（mysql/redis/rabbitmq）+ 3 端点 + schema.sql + 种子脚本
- 文档：mkdocs.yml + docs/manual 三页（概念/信任模型/快速上手）；README 演示段可执行化；docs/boot-compat-checklist.md（8 项含 4 项本仓实测）；docs/BACKLOG-0.1.1.md 砍线登记

### 实测数字（R3 时点，历史记录；当前数字见本节末尾）

| 模块 | fast 测试 | 行覆盖 |
|---|---|---|
| core | 76 | 92.1%（门 90） |
| web | 64 | ≥85%（门实测过） |
| messaging | 38 | 99.1% |
| cache-redis | 53 | 98.4% |
| provisioning | 64 | 96.0% |
| starter | 7 | ≥85% |
| test | 6 | 工具类跨模块消费，模块内不设门（BACKLOG #7） |
| **合计** | **308** | 全门通过；slow 层待 Docker 首跑 |


## 独立评审轮 R1–R3（2026-10-15）

- R1 四路并行评审：架构 API（7/10，P1×10）、对抗式安全（6.5/10，P1×3）、测试与质量门审计（P0×3）、文档与开源就绪（7/10，P0×5）。三路独立撞出同一 messaging BPP 覆盖用户 MPP 的真实 bug（交叉验证）。
- R2 修复轮：13 项 P1 + 3 项 P0 全部落地（filter Ordered/受检异常透传/jwt 守卫类/pattern 提前应用/策略大小写/wrap MDC/forEach cached pool/环境目录兜底/MPP 合并/Direct 工厂覆盖/scan 策略检查/供给边界校验/DLQ 竞态测试重写/门数学修正/BOM 顺序/scm+developers/demo 可复现/DCO 门/CODE_OF_CONDUCT/DESIGN 入库/TK advice）。
- R3 冷读者终审：「有条件签」，签字条件三项（K09 入册/PROGRESS 数字/两项偏差登记）已在本次提交全部完成；R3 新提两个 P2：web 用户 catalog 歧义**已修**（W48），StringRedisTemplate 值语义**javadoc 注明 + BACKLOG #8**。
- 偏差台账补充：①catalog 装配 web 侧保留 + starter 侧兜底（@ConditionalOnMissingBean 双向守卫，用户 bean 恒胜）②ContainerCustomizer→factory BPP（覆盖面更广，DESIGN §6.3 文本偏差）③DESIGN §12 R6「MQ header SPI 留缝」v0.1 未兑现（TenantHeaderWriter/Reader 通用接口），挂 BACKLOG ④TenantValueOperations.getOperations() 保留 delegate 出口（与 getDelegate 同契约，javadoc 已声明）。


## R4 独立评审轮（产物级消费者 + 证伪 + 并发 + 发布物，2026-10-15 同日）

- R4 四路：**产物级消费者测试**（/tmp 独立工程按文档消费——发现 2 个 P0：Jackson 2 硬依赖炸 Boot 4 启动、Redis 模块 100% 静默失效）、**修复证伪**（16 项逐条攻击，咬出策略拼写归一化只修 case 维度 P1 + DCO 假红 P2）、**并发资源深潜**（P0/P1 无，P2×9 最重为锁超时截断导致 slow 假通过）、**发布物审计**（release.yml 凭据路径错 + SNAPSHOT 假绿两个 P0）。
- R4→R5 修复全部落地：web 去 Jackson 耦合（手写 writer 单一源）+ canonical 策略匹配 + WebMvc 排序 + 真实服务器集成测试；Redis afterName(DataRedisAutoConfiguration)+失活 WARN+R13 回归测试；core MDC worker-restore/有界池/RejectedExecution 捕获/全局态改写告警；provisioning 锁超时钳制/错误码分类/DDL 方言声明；starter 实例级装饰+CompositeTaskDecorator；发布链路（凭据经 setup-java、SNAPSHOT 护栏、demo excludeArtifacts、双腿 containers、60min）；文档（FQCN/前置条件/MockMvc 说明/MySQL 锁/demo 可复现）。
- 台账新增 #9–#12；DESIGN.md 设计决策未改动（仅评审措辞做过中性化处理）。


## R6 收尾轮（2026-10-17，挂账清零）

- **所有挂账关闭**（见 docs/BACKLOG-0.1.1.md 关闭台账）：基准报告交付实测数字并据此优化；MyBatis-Plus 完整版（中英）；MkDocs 双语各 5 页；**Redis 全 ops 覆盖**（Hash/List/Set/ZSet/Geo/HLL/Stream + bound*Ops，代理式键重写、stream 记录回剥、raw callback 仍为设计性拒绝）；预挂 MPP/advice 策略分歧 WARN；传播桥 reset 收敛为包内；Hikari 锁变体 slow 用例；Docker 首跑手册。
- 基准驱动的优化：`shared-keys` Ant 匹配加字面前缀快筛，encode 中位 ≈464ns → ≈110ns（`docs/benchmarks.md`）。
- 新增验证：R13（Boot 真 DataRedis 装配顺序回归）、W49（策略拼写变体）、W50（filter order）、真服务器集成测试 8 条、Redis 全 ops 12 条、stream 记录重键 3 条、Hikari 锁 1 条（slow）。


### 当前实测（R6 终态，2026-10-17；boot40 与 boot41 均 EXIT:0）

| 模块 | fast 测试 | 行覆盖 |
|---|---|---|
| core | 85 | 96.6%（门 90） |
| web | 74 | 92.7%（门 85） |
| messaging | 40 | 96.1% |
| cache-redis | 69 | 90.6% |
| provisioning | 68 | 94.8% |
| starter | 9 | 89.1% |
| test | 6 | 工具类跨模块消费，模块内不设门 |
| **合计** | **351** | 全门通过 |

slow 层用例（未在本机执行）：DLX 正/负例 2、Redis 隔离 5、MySQL 并发（含 Hikari 变体）4、
容器工厂冒烟 1。执行手册见 `docs/ops/docker-first-run.md`。


## R7 发布前验证（2026-10-17）

- **定版**：全仓 `0.1.0`（父 POM 与全部模块；README/quickstart 坐标同步），双 Boot 版本全量 verify 绿，8 个 javadoc jar 重建过 doclint。
- **容器环境**：本机无任何容器运行时（docker/podman/nerdctl 均缺失、无 socket）→ Testcontainers 层无法在本机执行。失败形态已确认是**显式报错**（`Could not find a valid Docker environment`），不会静默跳过。
- **等价真实验证（本机服务，非容器）**：
  - MySQL 8.0.46（用户级实例，3307）：锁互斥 / TK-2002 超时（实测 2.09s）/ 会话绑定（`IS_USED_LOCK`）/ 持有者退出后即时获取（21ms）/ 异常路径释放（12ms）/ Hikari 代理连接下仍为会话绑定 —— **10/10 通过**。
  - Redis 8.0.2（源码编译，6380）：双租户隔离、共享键、`keys()` 作用域+剥离、hash、list 双键 `rightPopAndLeftPush`、set `move`、zset、stream 记录键回剥、`boundValueOps`、strict TK-1001、物理键前缀 —— **18/18 通过**。
  - demo-app（真实启动，MySQL+Redis）：`/public/health`、`/api/echo`（Redis+异步+种子计数）、`/api/provision` 首次 `executed=1`、二次 `skipped=1`（幂等）、非法租户 TK-1003 —— 全部通过。**由此暴露并修复**：`@RequestParam` 未显式命名 + 父 POM 未开 `-parameters`（BOM 模式不使用 starter-parent）导致的运行期绑定失败。
- **唯一未验证层**：RabbitMQ（本机无 Erlang、无容器）。首个 Docker 环境（CI ubuntu runner 或用户机）执行 `-Pcontainers` 即覆盖；用例与首跑手册已就绪。
- **GitHub Actions**：本机无 `gh` CLI、无远端凭据，无法触发；workflow 语法已校验，job 命令与本地执行一致。


## R8 首次 GitHub CI（2026-10-17，全绿）

- 仓库公开推送后 CI 首跑：`verify (boot40)` / `verify (boot41)` / `release packaging` 全部 success（容器层真实执行，含 RabbitMQ DLX、Redis 隔离、MySQL GET_LOCK 并发与 Hikari 变体）。
- **首跑发现并修复三个真实问题**（本地无容器时不可能发现）：
  1. **DLQ 消费者被租户 advice 二次拒绝 → 死信丢失**：无 header 消息首次拒绝后进 DLQ，而 DLQ 消费者仍挂同一条 advice，再次拒绝且 DLQ 无下一级死信目标，消息消失。新增 `tenantkit.messaging.raw-container-factories`（bean 名排除）并写入生产文档；矩阵 MQ11 锁定；负例用例改由无 advice 工厂消费。
  2. **Spring Data Redis 4.1 的 `ValueOperations` 抽象方法集变化**（新增 `getAndExpire`/`compareAndSet`/`SetSpec` 变体）导致手写实现无法双版编译。`ValueOperations` 改为与其他 ops 一致的版本自适应代理，删除手写类。
  3. **并发供给用例假定赢家顺序**（`results.get(0)` 必须是先拿锁者）——真实竞态下会翻转。断言改为「一次执行 2、一次跳过 2」的集合语义。
- 结论：双版矩阵 + 容器层 CI 的价值在首日即兑现——三个问题中两个是产品级（DLQ 丢消息、双版不可编译），一个是测试级。
