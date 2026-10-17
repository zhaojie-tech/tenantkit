# 信任模型

多租户系统的第一安全命题是「**谁有资格声称自己是谁**」。TenantKit 的默认值全部从"不信任"出发。

## 解析来源与信任等级

| 策略 | 来源 | 信任要求 |
|---|---|---|
| `jwt-claim` | Spring Security `JwtAuthenticationToken`（**已验签**） | 无边界信任要求；kit 内禁止解析未验签 JWT（ArchUnit 规则） |
| `header` | `X-Tenant-Id` 请求头 | **必须** `trust-proxy: true`，且边界（网关）必须清洗入站租户头；直连后端 = 任何客户端可冒充任意租户 |
| `subdomain` | `Host` 的子域标签 | 与 header 同级边界信任；多级嵌套/大小写混用拒绝，base-domains 配端口启动失败 |
| `fixed` | 配置值 | 内部/单租户部署用 |

opaque token 场景：jwt-claim 不适用（只认已验签 JWT），走 header+trust-proxy 或自定义 Resolver SPI。

## 组合 fail-fast（不是警告）

- 声明 `header` 策略但 `trust-proxy` 未显式开 → **启动失败**（安全默认值不吞用户配置）；
- `trust-proxy: true` 但没声明 `header` 策略 → 启动 WARN（无害的过度声明）;
- 声明 `jwt-claim` 但 classpath 无 Spring Security → **启动失败**，绝不静默降级为自行解析 Authorization 头（那等于信任模型洞开）；
- `fixed` 策略缺 `fixed` 值、`subdomain` 缺 `base-domains` → 启动失败。

## 豁免机制（缺租户的显式出路）

缺租户默认 fail-fast（TK-1001）——静默回退 default 租户是串租户事故温床。两条显式豁免通道：

1. `ignore-paths`：Filter 层短路，**不解析、不失败**（适合 actuator/健康检查）；
2. `@TenantPublic`：仍过 Filter、仍解析，仅抑制"缺失失败"（适合登录页等公共端点）。

两者同命中以 `ignore-paths` 为准。

## 异步与消息边界的信任

- 异步：租户随任务显式传递（decorator / `TenantExecutors.wrap`），工作线程结束后双清（上下文+MDC），不外泄。
- RabbitMQ：发送方写 `x-tenant-id`（常量公开，跨语言生产者对齐）；接收方校验字符集后才绑定上下文；缺 header 按 `reject-to-dlq`（默认）进死信人工处置——宁可死信，不猜租户。
