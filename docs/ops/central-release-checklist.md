# Maven Central 首发外部前置待办清单

> 本文是**给仓库所有者的外部前置待办清单**——下列事项必须由所有者亲自办理
> （涉及个人/组织账号与密钥，只能由账号持有人办理）。每项标注 `[待用户办理]`，
> 文末汇总当前阻塞状态。

## 1. GitHub 账号 / 组织名确认 [已完成]

- 归属：个人账号 `zhaojie-tech`（Jie Zhao），仓库 `https://github.com/zhaojie-tech/tenantkit`。
- Maven `groupId`：`io.github.zhaojie-tech`（Central 的 GitHub namespace 路线）。
- README 徽章、SECURITY advisories、issue 模板、文档站与 `scm` 已全部指向该仓库；
  错误类型 URI 改用 `urn:tenantkit:errors:`（不依赖域名）。
- 遗留动作：首次 push 前在 GitHub 建仓（私有）；Central namespace 验证见第 2 项。

## 2. Maven Central 门户账号注册与 namespace 验证 [待用户办理]

1. 注册 [central.sonatype.com](https://central.sonatype.com)（Central Portal）账号。
2. 生成并验证发布 namespace（= groupId）：
   - **GitHub namespace 路线**（`io.github.<username>`）：用 GitHub 账号登录
     Portal → 创建 namespace → 按 Portal 页面指引在 GitHub 新建指定名称的公开
     验证仓库（Portal 生成验证码/仓库名）→ 回 Portal 点击 Verify → 通过后验证
     仓库可删除；
   - **自有域名路线**（如 `io.tenantkit`）：需证明域名控制权——按 Portal 提示在
     域名 DNS 添加指定 TXT 记录，然后点击 Verify。
3. 验证通过后该 groupId 才可用于发布；否则 `central-publishing` 插件上传时会报
   namespace 未授权。
- **当前阻塞状态：未注册、未验证。** groupId 最终形态依赖第 1 项结论。

## 3. GPG 密钥生成与公钥分发 [待用户办理]

Central 要求全部 artifact 带 PGP 签名，且签名公钥可在公共 keyserver 检索：

```bash
# 生成（推荐 EdDSA；或 RSA 4096）
gpg --full-generate-key
#   → 选 ECC (sign only) → ECC (sign only)（EdDSA/Ed25519）
#   或 RSA and RSA → 4096
# 查看指纹（40 位十六进制）
gpg --list-secret-keys --keyid-format long
# 公钥分发到两个常用 keyserver（Central 校验会检索）
gpg --keyserver keyserver.ubuntu.com --send-keys <FINGERPRINT>
gpg --keyserver keys.openpgp.org --send-keys <FINGERPRINT>
```

保存要求：

- **私钥与口令务必妥善备份**（`gpg --armor --export-secret-keys <FINGERPRINT>`
  导出后存入密码管理器/离线介质）；口令不得写入仓库，不得以明文进入任何脚本。
- **指纹记录**：`<FINGERPRINT>` = [TO-CONFIRM by owner]（生成后回填本清单）。
- 有效期建议设长或不过期，并记录到期日。

- **当前阻塞状态：未生成、未分发。** 第 4 项的两个 secrets 依赖本步。

## 4. Central Portal User Token 与 GitHub Actions secrets 配置 [待用户办理]

1. 在 Central Portal → 账户页生成 **User Token**（形如一对 username/password，
   与网页登录凭据不同，专供发布流程使用）。
2. 在仓库 Settings → Secrets and variables → Actions 配置四个 secret：

   | Secret 名 | 内容 |
   |---|---|
   | `CENTRAL_USERNAME` | Portal User Token 的 username 段 |
   | `CENTRAL_PASSWORD` | Portal User Token 的 password 段 |
   | `GPG_PRIVATE_KEY` | `gpg --armor --export-secret-keys <FINGERPRINT>` 全文 |
   | `GPG_PASSPHRASE` | GPG 私钥口令 |

3. 最小权限与轮换：Token 仅用于发布流程；一旦泄露立即在 Portal 撤销重发，并同步
   更新 secrets。
- **当前阻塞状态：未配置**（依赖第 2、3 项）。`release.yml` 的发布 job 在 secrets
  就位前不可触发。

## 5. 首次发布前 publish 前校验 [待用户办理 · 与代码侧配合]

secrets 就位后、首次执行 `central-publishing-maven-plugin` 的 `publish` 之前，
跑一次完整门户预发布校验（doclint 首跑常翻车，务必留出返工时间）：

- [ ] `mvn -Pboot40 verify` 与 `mvn -Pboot41 verify` 双版全绿（发版硬门）；
- [ ] 每个模块 **javadoc jar 与 sources jar** 齐全（模块 DoD 的一部分，doclint
      报错逐条修复）；
- [ ] 全部 artifact **PGP 签名有效**，签名公钥可在 keyserver.ubuntu.com /
      keys.openpgp.org 检索；
- [ ] **pom 完整性**：`name` / `description` / `url` / `licenses` / `developers` /
      `scm` 全部齐备（Central 逐项校验，缺一退回）；
- [ ] groupId 与第 2 项验证通过的 namespace 完全一致；
- [ ] 首发版本 `0.1.0`；此后遵循语义化版本，配置键变更属 breaking，记 CHANGELOG
      并附迁移说明。

- **当前阻塞状态：未开始**（依赖第 1–4 项全部完成）。

## 阻塞状态汇总（截至 2026-10-15）

| # | 事项 | 状态 |
|---|---|---|
| 1 | GitHub org / groupId 确认 | 待用户办理（阻塞以下全部） |
| 2 | Central Portal 账号 + namespace 验证 | 待用户办理 |
| 3 | GPG 密钥生成与分发 | 待用户办理 |
| 4 | User Token + Actions secrets | 待用户办理 |
| 5 | publish 前校验 | 待用户办理（依赖 1–4） |

**当前总阻塞状态**：第 1 项未决前，README / SECURITY.md 中的组织名与 groupId 保持
TODO 占位不写死；第 1–4 项全部完成前，M3 里程碑的「Central 首发」处于阻塞状态。
