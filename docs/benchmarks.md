# 开销基准（v0.1，实测）

> 方法学：`tenantkit-benchmarks` 模块（不发布），`System.nanoTime` + 预热 8 轮 / 取中位数共 11 轮，
> 每次度量重复 50 万次（filter 构建变体 5 万次），单 JVM 单线程。
> **不是 JMH**：无 fork-per-measurement、无 DCE 屏障，宿主为共享虚机、单次运行间波动可达 2–4 倍。
> 下表给三次独立运行的中位数与区间，仅用于量级判断，**不做跨机比较**。
> 测量日 2026-10-17；JDK 17.0.20.1 / Linux amd64。

## 一、Redis 键改写（`TenantKeyCodec`）

样例：逻辑键 `session:user:1234567890:profile:preferences`（43 字符），租户 `tenant-0123456789abcdef`。

| 项 | 结果 |
|---|---|
| 物理键长度 | 69 字符（**+26 字节**：`t:` + 租户 + `:`） |
| 键长上限 | 租户 ≤32 字符 ⇒ 前缀 ≤35 字节；全局键长 = 逻辑键 + 前缀 |
| encode（配置 `shared-keys: ["app:*"]`） | 中位 **≈110 ns/op**（区间 110–202；优化前 ≈464） |
| encode（无 shared-keys） | 中位 **≈76 ns/op**（69–79） |
| decode（配置 glob） | 中位 **≈124 ns/op**（124–190） |

**基准驱动的一项真实优化**：`shared-keys` 的 Ant 匹配原本对每个键都跑一次
`AntPathMatcher`（配置 glob 时 encode 慢约 4 倍）。现在先做**字面前缀快筛**
（Ant 模式的通配符前字面前缀不匹配时跳过完整匹配），保持语义不变，
中位耗时回落到无 glob 的同一量级。这条记录同时说明：**共享键白名单不是零成本的，
但已从"每个 key 一次模式匹配"降到"一次 startsWith + 偶发模式匹配"**。

**内存含义（不做无背书数字）**：每个租户键多出的字节数 = 前缀长度，与租户名长度线性相关；
32 字符租户下至多 +35 字节/键。真实 keyspace 规模下的总增量 = 键数 × (租户前缀长度)，
用你们自己的 `DBSIZE` × 上述字节数即可估算。

## 二、解析 Filter（每请求）

`TenantResolutionFilter` + `FixedTenantResolver` + MDC 注入 + 上下文绑定/恢复，`MockHttpServletRequest`：

| 变体 | 结果 |
|---|---|
| 含 request/response 构造（每轮新建） | 中位 **≈4.8 µs/请求**（3.0–12.5） |
| 预构建 request/response（纯 filter 路径：解析器 → 目录校验 → MDC → `runAs` → finally 恢复） | 中位 **≈2.0 µs/请求**（1.8–3.8） |

解读：Mock 对象构造占了大头；纯过滤器链路在**微秒级**，相对一次 Redis/DB 网络往返（数百微秒）
可忽略。真实收益不在耗时，而在"每请求都有一次显式解析与目录校验"。

## 三、RabbitMQ 监听 advice（每 delivery）

header 解析 + 字符集校验 + `runAs` + MDC 恢复 + `proceed()`（空处理器）：

| 项 | 结果 |
|---|---|
| 每 delivery 开销 | 中位 **≈0.6 µs**（0.47–1.56） |

解读：纳秒到微秒级，相对消息反序列化与业务处理可忽略；DLX 场景下每条毒消息
也只付一次该成本（reject 不重排队）。

## 复现

```bash
./mvnw -q -pl tenantkit-benchmarks compile exec:java -Pboot40
```

（先 `./mvnw install -DskipTests` 保证模块依赖的 jar 是最新的；`tenantkit-benchmarks`
不发布到 Maven Central。）

## 已知局限

- 非 JMH，无 fork/预热隔离；共享宿主噪声大，看**量级**而不是尾数。
- Filter 基准用 Mock 对象，不含真实容器/网络；真实部署请以你们的网关与容器指标为准。
- Redis 基准只测键改写纯函数，不含真实 Redis 往返（那由服务器与网络决定，与 kit 无关）。
