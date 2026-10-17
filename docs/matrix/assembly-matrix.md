# TenantKit v0.1.0 装配矩阵用例表（冻结版）

| | |
|---|---|
| 状态 | **FROZEN @ M1 Day 1**（冻结先于实现；实现必须逐条对应该表，ID 不得删改） |
| 依据 | DESIGN.md v1.5 §5 装配面速查表 / §6.1–6.2 / §8 测试策略 |
| 下限 | ≥40 用例；本表共 **51**（web 域 45 + core/test 域 6） |
| 层次 | 【装】= ApplicationContextRunner 装配层断言（bean 在/不在、启动成/败）；【行】= Mock Servlet 行为层断言（解析语义、豁免、错误码） |
| 变更纪律 | 实现期发现表本身有误：走 ADR + 维护者确认，不改既有 ID 语义；只允许追加 W46+ / K07+ |

## 组 A —— 模块开关与无依赖回退（web）

| ID | 配置上下文 | 层 | 期望 |
|---|---|---|---|
| W01 | 全默认（无任何 `tenantkit.*` 属性） | 装 | 上下文启动成功；`TenantResolutionFilter` 不注册（strategies 为空 ⇒ 模块静默退出，无解析面） |
| W02 | `web.enabled=false` + strategies=[fixed], fixed=demo | 装 | 无 Filter、无 resolver；其余 bean 不受影响（模块级开关整体退出） |
| W03 | strategies=[fixed], fixed=demo（enabled 默认 true） | 装 | Filter + `FixedTenantResolver` 均注册；两个属性类（`TenantKitWebProperties`/`TenantKitResolutionProperties`）值绑定正确 |
| W04 | 隐藏 `jakarta.servlet.Filter`（无 Servlet API） | 装 | web 自动装配整体退出，上下文启动成功（`@ConditionalOnClass` 守卫回退，不炸） |
| W05 | strategies=[header]，trust-proxy 未显式声明（默认 false） | 装 | **启动失败**（组合 fail-fast），失败信息含 `trust-proxy`（安全默认值不得静默吞配置） |
| W06 | trust-proxy=true，strategies 不含 header | 装 | 启动成功 + WARN 日志（断言日志记录） |
| W07 | strategies=[jwt-claim]，隐藏 `org.springframework.security`（无 Security classpath） | 装 | **启动失败**（显式 fail-fast 非静默退出），失败信息含 `jwt-claim` |
| W08 | strategies=[jwt-claim]，Security 在 classpath | 装 | `JwtClaimTenantResolver` 注册，上下文启动成功 |

## 组 B —— 策略组合与解析优先级（行）

| ID | 配置 + 请求 | 层 | 期望 |
|---|---|---|---|
| W09 | strategies=[fixed], fixed=alpha | 行 | 解析为 `alpha` |
| W10 | strategies=[header], trust-proxy=true, X-Tenant-Id: beta | 行 | 解析为 `beta` |
| W11 | strategies=[header,fixed]，无 header | 行 | 回落 fixed 值 |
| W12 | strategies=[header,fixed]，header=beta | 行 | header 胜（链序语义：第一个非空者胜） |
| W13 | strategies=[fixed,header]，header=beta | 行 | fixed 胜（顺序反转证明 order 决定优先级） |
| W14 | strategies=[jwt-claim,header]，JwtAuthenticationToken(claim tenant_id=alpha) + header=beta, trust-proxy=true | 行 | 解析为 `alpha`（jwt 胜，header 不看） |
| W15 | strategies=[jwt-claim,header]，匿名 + header=beta, trust-proxy=true | 行 | 解析为 `beta`（jwt 空则下移） |
| W16 | strategies=[subdomain], base-domains=[saas.example.com]，Host: tenant1.saas.example.com | 行 | 解析为 `tenant1` |
| W17 | strategies=[subdomain], base-domains 配置，Host: saas.example.com（裸域） | 行 | 不解析 → TK-1001 路径 |
| W18 | strategies=[subdomain]，Host: tenant1.other.com | 行 | 不解析 |
| W19 | strategies=[subdomain]，未配置 base-domains | 装 | **启动失败**（base-domains 必填校验） |
| W20 | strategies=[subdomain,header]，Host 不匹配 + header=beta, trust-proxy=true | 行 | 回落解析为 `beta` |
| W21 | strategies=[subdomain]，Host: a.b.saas.example.com（多级嵌套） | 行 | 不解析（拒绝嵌套） |
| W22 | strategies=[subdomain]，base-domains=["saas.example.com:8443"]（值含端口） | 装 | **启动失败**（端口歧义拒绝） |
| W23 | strategies=[subdomain]，Host: Tenant1.saas.example.com（大小写混用） | 行 | 不解析（拒绝大小写混用） |

## 组 C —— TenantId 校验与错误码（行）

| ID | 配置 + 请求 | 层 | 期望 |
|---|---|---|---|
| W24 | header=alpha（默认 pattern [a-z0-9-]{1,32}） | 行 | 解析成功 |
| W25 | header=UPPER | 行 | `TenantIdFormatException` → 400 + TK-1003 ProblemDetail（type/title 稳定） |
| W26 | header="a b"（含空格） | 行 | 400 + TK-1003 |
| W27 | header 64 字符超长 | 行 | 400 + TK-1003 |
| W28 | `tenant-id-pattern: "[a-zA-Z0-9]{1,16}"` + header=Alpha | 行 | 解析成功（regex 可配） |
| W29 | 受保护路径无任何租户来源 | 行 | 400 + TK-1001 ProblemDetail |
| W30 | 解析得 `ghost`，tenants=[alpha,beta]（config 目录校验） | 行 | TK-1002，状态码 = `unknown-tenant-status` 默认 404 |
| W31 | 同 W30，`unknown-tenant-status: 400` | 行 | TK-1002 返回 400（可配） |

## 组 D —— 豁免机制（行）

| ID | 配置 + 请求 | 层 | 期望 |
|---|---|---|---|
| W32 | ignore-paths=[/actuator/**]，GET /actuator/health，无租户 | 行 | 200 直通；**解析器未被调用**（Filter 层短路，不解析不失败） |
| W33 | @TenantPublic 标注 /public/echo，无租户 | 行 | 通过（抑制缺失失败），`TenantContext.currentOrNull()==null` |
| W34 | @TenantPublic 路径 + header=alpha, trust-proxy=true | 行 | 上下文解析为 alpha（仍过 Filter、仍解析语义） |
| W35 | 同一命中 ignore-paths 与 @TenantPublic | 行 | ignore-paths 胜（解析器未被调用） |
| W36 | 启动后检查 @TenantPublic 豁免路径集 | 装 | 启动期扫描 `RequestMappingHandlerMapping` 预建集包含 /public/echo |
| W37 | 非豁免路径 /api/x 无租户（对照负例） | 行 | 400 + TK-1001 |

## 组 E —— 解析请求快照与 SPI（行）

| ID | 配置 + 请求 | 层 | 期望 |
|---|---|---|---|
| W38 | 注入记录型测试 Resolver，请求带 URI/Host/多值 header | 行 | `TenantResolutionRequest` 快照字段 requestUri/host/headers 与请求一致 |
| W39 | 两个自定义 Resolver order=100/200 | 行 | order 小者先执行（链序由 order 决定） |
| W40 | 第一 resolver 返回 empty，第二返回值 | 行 | 第二个值胜（第一个非空者胜） |
| W41 | 请求携带多值 header（Accept 两个值） | 行 | MultiValueMap 完整透传 |
| W42 | 请求 authentication 为自定义 `Principal` 实现（匿名时 null） | 行 | Principal 原样传递（JDK 类型，core 无 Security 依赖下可独立构造断言） |

## 组 F —— 目录与配置校验（装）

| ID | 配置上下文 | 层 | 期望 |
|---|---|---|---|
| W43 | tenants=[alpha,beta] | 装 | TenantCatalog 内置 config 实现注册，`activeTenants()` 返回有序清单 [alpha, beta] |
| W44 | tenants 未配置 | 装 | TenantCatalog bean 不注册，上下文启动成功（无目录 ⇒ forEach 等消费方运行时明确报错） |
| W45 | strategies=[jwt]（值域外拼写） | 装 | **启动失败**（strategies 枚举绑定错误，fail-fast 不吞） |

## 组 K —— core / tenantkit-test 域（装）

| ID | 配置上下文 | 层 | 期望 |
|---|---|---|---|
| K01 | 仅引入 tenantkit-core（Spring 上下文） | 装 | core 零装配：无任何 kit bean 注册，上下文启动成功 |
| K02 | core + spring-tx 缺席 | 装 | `Tenants.forEach` 可用；外层事务检测降级为无检测（optional 依赖回退一致） |
| K03 | core + micrometer-context-propagation 在场 | 装 | `ThreadLocalAccessor` 注册进 `ContextRegistry` |
| K04 | core + micrometer 缺席 | 装 | 不注册 accessor；runAs/callAs 行为与在场时一致 |
| K05 | tenantkit-test `TenantSupport` 工厂单例语义（不启动容器） | 装 | 两次获取返回同一引用；懒加载（未显式 start 前不触碰 Docker）；容器启动用例 @Tag("slow") 另册 |
| K06 | starter 聚合（core+web 一起引） | 装 | bean 面与单独引 web 完全一致（聚合无副作用） |

## M2/M3 模块矩阵追加（冻结纪律：只追加不改既有行）

| 组 | 用例 | 状态 |
|---|---|---|
| messaging | MQ01–MQ10（无 amqp 回退/enabled=false/默认装配/策略绑定/非法枚举 fail-fast/BPP 幂等/advice 合并 kit 在首/预挂不重复） | ✅ 全绿（双版） |
| cache-redis | R01–R12（默认装配/非 Primary/无 data-redis 回退/enabled=false/无 template 不装配/byte[] 键 WARN/strict-lenient/sharedKeys/keyPrefix=false 直通/用户 CacheKeyPrefix 优先/行为探针 ×2） | ✅ 全绿（双版） |
| provisioning | P01–P08（默认装配/enabled=false/无 spring-jdbc 回退/缺 JdbcTemplate/四键绑定/用户 provisioner/用户 lockManager/缺表 WARN） | ✅ 全绿（双版） |
| web (R2 补) | W46 策略大小写变体（JWT-Claim）与全小写行为一致：fail-fast 照常触发、resolver 照常装配；W47 Filter 活跃但无 catalog 时启动 WARN 指出 TK-1002 校验失活 | ✅ 全绿（双版） |
| web (R3 补) | W48 servlet 应用中用户 TenantCatalog bean 优先于 config catalog（无 NoUniqueBean 启动失败） | ✅ 全绿（双版） |
| starter | K07–K08（executor BPP before-init 生效/开关关闭时用户池不受扰）；K09 非 servlet 环境下 tenantkit.tenants 兜底装配（config catalog 注册 + Tenants.forEach 实跑 + 用户 catalog 优先） | ✅ 全绿（双版） |

冻结表本体（W01–W45 + K01–K06）语义未动。

## 与实现的绑定关系

- 测试类：`tenantkit-web` 的 `TenantKitWebAssemblyMatrixTest`（W 组）与 `tenantkit-core` 的 `CoreAssemblyMatrixTest`（K 组），参数化用例 ID 与本表一一对应，禁止用例静默缺失。
- 无 Security classpath 模拟：测试内 `HideClassesClassLoader` 过滤 `org.springframework.security.*` 后喂给 `ApplicationContextRunner.withClassLoader`。
- 行为层用 MockHttpServletRequest/MockFilterChain 驱动，不依赖真实容器（容器层全链路归 M2/M3 集成测试）。
