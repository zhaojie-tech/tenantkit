# ADR-0003：Boot 4.x 支持矩阵（4.0.x + 4.1.x）

| | |
|---|---|
| 状态 | Accepted |
| 日期 | 2026-10-15 |
| 关联 | 设计文档 §4（非目标）、§11（M2 差异核对表）、§14 R5（外部事实核验） |

## 背景（Context）

支持矩阵必须锚定「开工时点真实受支持的 Spring Boot OSS 线」，而不是写文档那一刻的
惯性口径。外部事实核验（DESIGN §14 R5，2026-10 时点）给出实况：

- Spring Boot **4.0** 已于 **2025-11 GA**，**4.1** 已于 **2026-06-10 GA**——两者均为
  当前 OSS 受支持线；
- Spring Boot **3.5** 已于 **2026-06-30 OSS EOL**；3.4 及更早（含全部 2.x）均已 EOL。

项目历史上 R2 曾定「3.5.x + 4.0.x」，该矩阵在定稿两周后即被实况推翻；R5 修正为
**4.0.x + 4.1.x**，3.5 降为存量兼容档。这个反复本身即教训：版本矩阵是外部事实，
必须核验后落笔。

另外，Boot 4.0 → 4.1 之间存在若干**真实差异**，不能假设「小版本无差异」靠惯性编译
通过。

## 决策（Decision）

1. **v0.1 支持矩阵 = Boot 4.0.x + 4.1.x 双版**。README 主矩阵、CI 矩阵、发版门均以
   此为准。**3.5 降为存量兼容档**：仅编译冒烟，不承诺运行、不承诺任何修复，不进主
   矩阵（README 仅在 compatibility 段写明其 2026-06-30 EOL 事实）。更早版本不支持。

2. **双版机制 = Maven profile 切 `boot.version` + CI 双 job**：本地
   `mvn -Pboot40 verify` / `mvn -Pboot41 verify`；CI 为每个 Boot 版本建独立 job，
   **双版全绿是发版硬门**。双轨从 M1 起每模块同跑，不留「先单版后适配」的债。

3. **Boot 4.0↔4.1 已知三项真实差异**（R5 外部核验结论，M2 差异核对表逐项列任务，
   不攒一次性验证）：
   - **`spring-boot-starter-amqp` 成为必需**：裸 `spring-rabbit` 依赖不再携带自动
     装配，messaging 模块文档与示例必须显式要求 starter；
   - **Spring AMQP 4.0 移除 `spring-retry`**：依赖重试能力的路径需自行显式引入或
     改造；
   - **AMQP 自动装配包名迁移**：迁至 `org.springframework.boot.amqp.autoconfigure`
     （原 `org.springframework.boot.autoconfigure.amqp`）——涉及条件注解与测试切面
     引用。

   同时确认**未变**项：自动装配 `AutoConfiguration.imports` 路径未变、
   `ContainerCustomizer` 包路径未变——差异核对表按真实差异列任务，不做臆测性改造。

## 后果（Consequences）

- 发版门为「双版矩阵全绿」；单版绿不算通过，ArchUnit 与覆盖率门槛不随矩阵降低。
- 3.5 存量用户可自行编译，但运行问题只能通过升级 Boot 解决，项目不受理该档位的
  bug 报告（SECURITY.md 同口径：无安全修复承诺）。
- 后续 Boot 新 GA 线（如 4.2）沿用同一流程：先外部核验 GA/EOL 事实与真实差异，
  再决定纳入矩阵，不做无核验承诺。
