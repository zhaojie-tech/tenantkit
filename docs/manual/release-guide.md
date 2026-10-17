# 从代码到 Maven Central：GitHub Release 与 Maven 发布的完整指南

> 本文以 TenantKit 0.1.0 的真实发布过程为样本，讲清两件事：**GitHub Release 和 Maven 发布分别是什么、为什么需要、以及从零到上架的完整操作**。文中所有命令都可以直接照做，踩过的坑单独成章。

---

## 目录

1. [先分清两个“发布”](#1-先分清两个发布)
2. [核心概念：坐标、不可变性与签名](#2-核心概念坐标不可变性与签名)
3. [为什么要有这些约束](#3-为什么要有这些约束)
4. [整体架构与状态机](#4-整体架构与状态机)
5. [事前准备（一次性）](#5-事前准备一次性)
6. [全过程：从本地到 Central](#6-全过程从本地到-central)
7. [GitHub Release 怎么做](#7-github-release-怎么做)
8. [自动化：release.yml 逐段解读](#8-自动化releaseyml-逐段解读)
9. [故障排查手册](#9-故障排查手册)
10. [版本、分支与发布节奏](#10-版本分支与发布节奏)
11. [安全与合规](#11-安全与合规)
12. [附录：清单、模板与速查](#12-附录清单模板与速查)

---

## 1. 先分清两个“发布”

很多人把 “release” 和 “publish” 混为一谈。它们服务于两类完全不同的消费者：

| | **GitHub Release** | **Maven 发布（Central）** |
|---|---|---|
| 面向谁 | 人：用户、贡献者、面试官 | 构建工具：Maven / Gradle / IDE |
| 内容 | 源码快照（tag）、变更说明、可选附件 | `jar` / `pom` / `.asc` 签名 / `sources` / `javadoc` |
| 存放位置 | GitHub 仓库的 Release 页 | Maven 中央仓库（`repo1.maven.org`） |
| 消费方式 | 阅读、下载、克隆 | `<dependency>` 一行引入 |
| 不可变性 | tag 可以移动（不推荐） | **坐标+版本永久不可变** |
| 认证 | 仓库写权限 | namespace 验证 + Portal token + GPG |
| 失败影响 | 用户看不到 changelog | 下游构建直接失败 |

**一句话：GitHub Release 负责“让人知道这个版本存在”，Maven Central 负责“让构建工具能把它拉下来”。** 两者缺一不可：

- 只有 GitHub Release：用户得自己 clone、构建、`mvn install`，开源采用率基本为零；
- 只有 Maven Central：用户能拉依赖，但不知道改了什么、不敢升级，也找不到对应源码。

---

## 2. 核心概念：坐标、不可变性与签名

### 2.1 Maven 坐标（GAV）

一个制品由 **groupId : artifactId : version** 唯一确定，例如：

```
io.github.zhaojie-tech : tenantkit-spring-boot-starter : 0.1.0
```

同一个 GAV 下还可以有 classifier（如 `sources`、`javadoc`）与打包类型（`jar`、`pom`）。

### 2.2 不可变性

Central 上的版本一旦发布，**永久不可覆盖、不可删除**。这不是限制，而是供应链的根基：

- 下游的构建可复现：昨天拉到的 `0.1.0` 和今天拉到的字节一致；
- 攻击者无法“偷换”已发布版本；
- 修复只能发新版本（`0.1.1`）。

因此发布前的本地预演和 CI 门禁不是形式主义，而是最后一道保险。

### 2.3 签名（GPG 分离签名）

Central 要求每个制品旁边有一个 `.asc` 分离签名：

```
tenantkit-core-0.1.0.jar
tenantkit-core-0.1.0.jar.asc      ← GPG 签名
tenantkit-core-0.1.0.pom
tenantkit-core-0.1.0.pom.asc
tenantkit-core-0.1.0-sources.jar
tenantkit-core-0.1.0-javadoc.jar
```

校验方用你的公钥验证签名，确认“这个 jar 确实由持有私钥的人发布，且内容未被篡改”。

### 2.4 namespace 与 groupId

groupId 必须是你**验证过所有权**的 namespace：

- **域名路线**：`com.example` → 在域名 DNS 加 TXT 记录；
- **GitHub 路线**：`io.github.<用户名或组织>` → 用 GitHub 账号证明（Portal 会要求在该账号下建一个指定名称的临时公开仓库，验证后可删）。

TenantKit 走的是后者：`io.github.zhaojie-tech`。

### 2.5 发布通道：Portal 与 OSSRH

旧的 OSSRH（`oss.sonatype.org`）已由 **Central Portal（central.sonatype.com）** 取代。Portal 的发布模型是：

```
上传（upload） → 校验（VALIDATED） → 发布（PUBLISHED）
```

- `VALIDATED` 表示签名、POM 元数据、sources/javadoc 等检查全部通过，但**还没公开**；
- `PUBLISHED` 才是真正出现在 `repo1.maven.org`；
- `autoPublish=true` 可让校验通过后自动发布；我们保留 `false`，把最后一步留人工确认。

### 2.6 SNAPSHOT 与正式版本

- `0.1.0-SNAPSHOT` 是开发快照，可以反复覆盖，永远不进正式仓库；
- `0.1.0` 是正式版本，不可变。

**最隐蔽的坑**：发布流程如果没把版本从 SNAPSHOT 改成正式版，上传会走 Portal 的 snapshots 通道——命令返回成功，正式仓库里却什么都没有。TenantKit 在 CI 里加了显式护栏：

```bash
if grep -rn "<version>.*-SNAPSHOT</version>" pom.xml | grep -v "<parent>" | grep -q .; then
  echo "::error::SNAPSHOT version detected"; exit 1
fi
```

---

## 3. 为什么要有这些约束

| 约束 | 它解决的真实问题 |
|---|---|
| 版本不可变 | 依赖投毒、构建不可复现、“昨天还能编译今天不行” |
| GPG 签名 | 制品被中间人替换；确认发布者身份 |
| sources/javadoc jar | IDE 里能跳转源码、看文档；审查依赖时不用反编译 |
| POM 元数据（name/url/licenses/developers/scm） | 法律归属清晰、可追溯维护者、漏洞通报有对象 |
| namespace 验证 | 防止有人用 `com.google.*` 之类的坐标冒充官方 |
| 人工 Publish 闸门 | 校验通过不等于你想发；最后一步留给人 |
| CI 双版本矩阵 + 容器测试 | 发布的是“被测过的组合”，不是“我机器上能跑” |

一句话：**这些不是官僚流程，而是把“信任”工程化。** 开源项目真正的资产是信任，Central 的约束是在替整个生态守住它。

---

## 4. 整体架构与状态机

```
开发者                      GitHub Actions                     Central Portal
  │                              │                                  │
  ├─ git tag v0.1.0 ────────────▶│                                  │
  │                              ├─ checkout tag                    │
  │                              ├─ JDK + Maven 缓存                │
  │                              ├─ verify (Boot 4.0 + 4.1, 容器层)  │
  │                              ├─ GPG 签名 (verify 阶段)           │
  │                              ├─ deploy ────────────────────────▶│ upload
  │                              │                                  ├─ VALIDATED
  │                              │◀───────── deploymentId ──────────┤
  │                              │                                  │
  │  (你在 Portal 点 Publish) ───────────────────────────────────────▶ PUBLISHED
  │                              │                                  │
  │                              │                                  ├─ repo1 同步
  └─ gh release create ──────────┘                                  │
```

关键点：

1. **tag 是发布入口**：tag 指向的提交必须是“要发布的那个提交”；
2. **CI 负责可复现的构建与签名**，不依赖某台开发机的环境；
3. **Portal 负责校验与公开**，校验失败会给出具体原因（缺 sources、签名不可检索等）；
4. **GitHub Release 与 Portal 发布相互独立**：通常先让制品上线，再写 Release notes 指向它。

---

## 5. 事前准备（一次性）

### 5.1 账号与 namespace

1. 用 GitHub 登录 https://central.sonatype.com ；
2. **View Namespaces → Add Namespace**，填 `io.github.<你的用户名>`；
3. 按 Portal 指引在 GitHub 创建验证仓库 → Verify，状态变为 **Verified**；
4. groupId 必须与验证通过的 namespace 完全一致（大小写、连字符都要一致）。

### 5.2 GPG 密钥

```bash
# 生成（RSA 4096，有效期建议 2 年）
gpg --full-generate-key
#   Real name / Email 用你希望公开的身份
#   口令：强口令，后面要作为 CI secret

# 查看指纹
gpg --list-secret-keys --keyid-format long

# 分发公钥（两个 keyserver 都发）
gpg --keyserver keyserver.ubuntu.com --send-keys <KEY_ID>
gpg --keyserver keys.openpgp.org   --send-keys <KEY_ID>

# 导出给 CI 的私钥（注意保密）
gpg --armor --export-secret-keys <KEY_ID> > private.asc
```

> `keys.openpgp.org` 需要点邮件确认链接后公钥才可检索；不确认的话，Central 校验可能失败。

### 5.3 Portal User Token

Portal 头像 → **Generate User Token**，得到一对 `username / password`。它**不是登录密码**，只用于发布 API。

### 5.4 GitHub Actions Secrets

| Secret | 内容 |
|---|---|
| `CENTRAL_USERNAME` | Token 的 username |
| `CENTRAL_PASSWORD` | Token 的 password |
| `GPG_PRIVATE_KEY` | `private.asc` 的全文 |
| `GPG_PASSPHRASE` | GPG 口令 |

```bash
gh secret set CENTRAL_USERNAME --repo <owner>/<repo>
gh secret set CENTRAL_PASSWORD --repo <owner>/<repo>
gh secret set GPG_PASSPHRASE   --repo <owner>/<repo>
gh secret set GPG_PRIVATE_KEY  --repo <owner>/<repo> < private.asc
```

### 5.5 POM 必备元数据

Central 会逐项校验，缺一退回：

```xml
<name>…</name>
<description>…</description>
<url>https://github.com/<owner>/<repo></url>
<licenses><license><name>Apache License, Version 2.0</name>…</license></licenses>
<developers><developer><id>…</id><name>…</name><email>…</email></developer></developers>
<scm><url>…</url><connection>scm:git:…</connection></scm>
```

### 5.6 构建插件（已在 TenantKit 中固化）

```xml
<profile>
  <id>release</id>   <!-- sources + javadoc -->
  …
</profile>
<profile>
  <id>central</id>
  <build><plugins>
    <plugin>
      <groupId>org.apache.maven.plugins</groupId>
      <artifactId>maven-gpg-plugin</artifactId>
      <version>3.2.7</version>
      <executions><execution>
        <id>sign-artifacts</id><phase>verify</phase><goals><goal>sign</goal></goals>
      </execution></executions>
    </plugin>
    <plugin>
      <groupId>org.sonatype.central</groupId>
      <artifactId>central-publishing-maven-plugin</artifactId>
      <version>0.12.0</version>
      <extensions>true</extensions>
      <configuration>
        <publishingServerId>central</publishingServerId>
        <autoPublish>false</autoPublish>
        <waitUntil>validated</waitUntil>
        <excludeArtifacts>
          <excludeArtifact>tenantkit-demo-app</excludeArtifact>
        </excludeArtifacts>
      </configuration>
    </plugin>
  </plugins></build>
</profile>
```

三个容易忽略的点：

- `extensions=true` 才会把发布逻辑挂到 `deploy` 生命周期；
- `excludeArtifacts` 按 **artifactId** 排除不该发布的模块（示例应用、基准模块）；
- `maven.deploy.skip` 对 Central 插件**无效**，别指望它排除模块。

---

## 6. 全过程：从本地到 Central

### 阶段 1 · 定版

```bash
mvn versions:set -DnewVersion=0.1.0 -DgenerateBackupPoms=false
grep -rn "0.1.0-SNAPSHOT" --include=pom.xml .   # 必须为空
```

版本号必须与 tag 名一致（`v0.1.0` ↔ `0.1.0`），这是可追溯性的基础。

### 阶段 2 · 本地预演（不签名、不上传）

```bash
mvn -Prelease clean package -DskipTests
ls tenantkit-core/target/*.jar
# 期望：主 jar + -sources.jar + -javadoc.jar
```

检查点：

- 每个要发布的模块都有三件套；
- `javadoc` 构建零错误（doclint 首次经常翻车）；
- POM 元数据齐全。

### 阶段 3 · 签名演练（可选）

```bash
# ~/.m2/settings.xml 配置 server id=central（token 用户名/密码）
mvn -Pboot40,release,central install -DskipTests -Dgpg.passphraseEnvName=GPG_PASSPHRASE
```

这一步会真正调用 GPG。看到 `Signer 'gpg' is signing …` 说明密钥与口令都正确。

### 阶段 4 · 打 tag 并推送

```bash
git tag -a v0.1.0 -m "TenantKit 0.1.0"
git push origin v0.1.0
```

tag 推送触发发布工作流。**tag 应指向已经过 CI 的 main 提交**，不要在 tag 上临时改代码。

### 阶段 5 · CI 自动上传

发布工作流至少要做四件事：

1. 双版本（或目标版本矩阵）跑完整测试，含容器层；
2. 导入 GPG 并自检；
3. SNAPSHOT 护栏；
4. `deploy -P<boot>,release,central`，等待 `VALIDATED`。

成功日志关键行：

```
Installing Central Publishing features
Using credentials from server id central in settings.xml
Uploaded bundle successfully, deploymentId: <uuid>
Deployment ... has been VALIDATED
```

### 阶段 6 · Portal 人工发布

打开 **central.sonatype.com → Publishing → Deployments**，找到状态为 `Validated` 的 deployment，点 **Publish**。

> 这是不可逆动作。`autoPublish=false` 的意义就是：校验通过后仍由人确认“这就是我要发的版本”。

### 阶段 7 · 同步与验收

```bash
# 任选一个模块，检查目录内容
curl -s https://repo1.maven.org/maven2/io/github/<owner>/<artifact>/0.1.0/ | head
# 期望：jar / pom / .asc / sources / javadoc 均在

# 用全新本地仓库做一次真实解析（排除本机缓存与镜像干扰）
mvn -s /tmp/no-mirror-settings.xml -Dmaven.repo.local=/tmp/central-check \
    dependency:get -Dartifact=<groupId>:<artifactId>:0.1.0
```

同步通常几分钟内完成；搜索索引可能更慢。**验收必须直连 `repo1`**——如果本机配置了阿里云等镜像，新坐标可能还没同步，这会被误判为发布失败。

### 阶段 8 · 收尾

```bash
# 下一轮开发版本
mvn versions:set -DnewVersion=0.1.1-SNAPSHOT -DgenerateBackupPoms=false
git commit -am "chore: back to 0.1.1-SNAPSHOT"
git push
```

tag 保持不动；CHANGELOG 记下发布日期；README 徽章从 pending 换成真实徽章。

---

## 7. GitHub Release 怎么做

### 7.1 它包含什么

- **tag**：代码快照的锚点（与 Maven 版本一致）；
- **标题与说明**：这一版改了什么、是否兼容、升级注意事项；
- **附件**（可选）：SBOM、额外的二进制、迁移脚本；
- **讨论入口**：用户反馈、issue 关联。

### 7.2 用 CLI 创建

```bash
gh release create v0.1.0 \
  --repo <owner>/<repo> \
  --title "TenantKit 0.1.0" \
  --notes "第一个公开发布。模块、支持的 Spring Boot 版本、Maven Central 坐标见下……"
```

或者用网页：Releases → Draft a new release → 选择已有 tag → 写说明 → Publish。

### 7.3 写 Release Notes 的要点

1. **消费方式**：给出 Maven 坐标片段，让用户能立刻用；
2. **兼容性**：支持的运行时/框架版本矩阵；
3. **变更摘要**：新增、修复、破坏性变更分开写；
4. **已知限制**：诚实标注未验证/不支持的范围；
5. **链接**：CHANGELOG、文档、Central 页面。

### 7.4 与 Maven 发布的关系

推荐顺序：**先让 Central 制品上线，再发 GitHub Release**，这样 Release 里的坐标是马上可用的。两者版本号必须一致，tag 必须指向同一提交。

---

## 8. 自动化：release.yml 逐段解读

```yaml
name: release
on:
  push:
    tags: ['v*']
  workflow_dispatch:

jobs:
  publish-central:
    runs-on: ubuntu-latest
    timeout-minutes: 60
    steps:
      - uses: actions/checkout@v4

      - name: Set up JDK 17 + Central credentials
        uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '17'
          cache: maven
          server-id: central
          server-username: CENTRAL_USERNAME
          server-password: CENTRAL_PASSWORD
```

**这里有一个大坑**：`server-username/server-password` 填的是**环境变量名**，setup-java 会把
`${env.CENTRAL_USERNAME}` / `${env.CENTRAL_PASSWORD}` 写进 `settings.xml`。如果这些变量没有在
deploy 步骤的环境里定义，插件会带着空凭据上传，得到 **401**。

```yaml
      - name: Import GPG key and verify it is usable
        env:
          GPG_PRIVATE_KEY: ${{ secrets.GPG_PRIVATE_KEY }}
        run: |
          echo "$GPG_PRIVATE_KEY" | gpg --batch --import
          gpg --list-secret-keys --keyid-format long

      - name: Guard — releases must not be SNAPSHOT versions
        run: |
          if grep -rn "<version>.*-SNAPSHOT</version>" pom.xml | grep -v "<parent>" | grep -q .; then
            echo "::error::SNAPSHOT version detected"; exit 1
          fi

      - name: Tests must pass before publish
        run: ./mvnw --no-transfer-progress verify -Pboot40 -Pcontainers

      - name: Second matrix leg
        run: ./mvnw --no-transfer-progress verify -Pboot41 -Pcontainers

      - name: Publish to Maven Central (portal validation)
        env:
          GPG_PASSPHRASE: ${{ secrets.GPG_PASSPHRASE }}
          CENTRAL_USERNAME: ${{ secrets.CENTRAL_USERNAME }}
          CENTRAL_PASSWORD: ${{ secrets.CENTRAL_PASSWORD }}
        run: |
          ./mvnw --no-transfer-progress -Pboot40,release,central clean deploy \
            -DskipTests -Dgpg.passphraseEnvName=GPG_PASSPHRASE
```

设计要点：

- **发布前跑完整测试**（含容器层），而不是 `-DskipTests` 图快；
- **`gpg.passphraseEnvName`** 让口令从环境变量读取，不出现在进程参数里；
- **`waitUntil=validated`** 等校验结果，失败即让 job 红；
- **`autoPublish=false`** 保留人工闸门；
- **超时 60 分钟**：双版本容器测试 + 上传 + 等待校验，30 分钟可能不够。

---

## 9. 故障排查手册

| 现象 | 根因 | 解决 |
|---|---|---|
| `401 Invalid request`（上传时） | deploy 步骤没有 `CENTRAL_USERNAME/PASSWORD` 环境变量；settings.xml 里是空值 | 在 deploy 步骤 `env:` 注入两个 secret |
| `Unable to get publisher server properties for server id: central` | settings.xml 里没有 `id=central` 的 server | setup-java 的 `server-id` 必须与 `publishingServerId` 一致 |
| namespace 未授权 / 403 | namespace 未验证，或 groupId 与验证值不一致 | Portal 看 Namespaces 状态；逐字符核对 groupId |
| 校验失败：缺 sources / javadoc | 忘记 `-Prelease` | 发布命令同时带 `release` 与 `central` profile |
| 校验失败：签名不可检索 | 公钥没分发；keys.openpgp.org 未确认 | 重发 keyserver；点确认邮件 |
| `No secret key` / `Bad passphrase` | CI 未导入私钥；口令不符 | 在 job 里 `gpg --list-secret-keys` 自检 |
| 命令成功但 Central 没有制品 | 版本仍是 SNAPSHOT，走了 snapshots 通道 | 加 SNAPSHOT 护栏；发布前 grep |
| 插件抛 `UnrecognizedPropertyException: warnings` | 插件版本落后于 Portal API | 升级 `central-publishing-maven-plugin`（0.12.0+） |
| 同版本被拒 | Central 不允许覆盖 | 升版本重发 |
| `repo1` 404 但 Portal 显示 PUBLISHED | 同步延迟；或本地走了镜像 | 等几分钟；直连 `repo1` 验证；检查镜像同步 |
| `javadoc` 构建失败 | doclint 错误 | 修 javadoc；`doclint=all,-missing` 已较宽松 |
| 发布 job 超时 | 冷缓存 + 容器镜像拉取 + 双版本测试 | 提高 timeout；预热镜像/缓存 |
| GitHub Release 的 tag 与 Central 版本不一致 | 手工失误 | 发布清单里核对 tag ↔ version |

---

## 10. 版本、分支与发布节奏

### 10.1 推荐模型

```
main:  0.1.1-SNAPSHOT ──▶ 发布时定版 0.1.1 ──▶ tag v0.1.1 ──▶ 回到 0.1.2-SNAPSHOT
                         │
                         └─ 修复走 0.1.2（已发布版本不可改）
```

- **main 永远是可构建的 SNAPSHOT**；
- **发布 = 定版 + tag + 发布 + 回填 SNAPSHOT**；
- **hotfix 不发补丁包改旧版本**，而是新版本号；
- 0.x 阶段可以接受不兼容变更，但必须在 CHANGELOG 明确标注。

### 10.2 tag 纪律

- 使用 annotated tag（带说明），不要 lightweight；
- tag 指向的提交必须已经在 main 上通过 CI；
- **不要移动已发布 tag**（`--force` 重打 tag 会让“v0.1.0 到底是哪个提交”失去意义）；
- tag 名与 Maven 版本严格对应。

### 10.3 CHANGELOG

Keep a Changelog 格式，按 `Added / Changed / Fixed / Removed / Security` 分类；发布日期与版本号对齐。已发布版本的条目**只增不改**。

---

## 11. 安全与合规

1. **最小暴露**：Portal token、GPG 私钥、口令只放 GitHub Secrets；不进仓库、不进日志、不贴聊天。
   - 一旦泄露：立即在 Portal 重新生成 token，更新 secret；GPG 私钥泄露则吊销并重新生成密钥。
2. **token 轮换**：建议每次发布后轮换一次，或至少每季度。
3. **GPG 备份**：私钥与吊销证书离线保存；注意有效期（本文示例为 2 年），过期前续期。
4. **签名密钥唯一**：所有版本使用同一把密钥，便于信任链延续。
5. **POM 元数据真实**：维护者邮箱、SCM 地址必须可达，安全报告才有落点。
6. **发布产物可审计**：CI 日志、deployment ID、tag、Central 页面形成完整证据链。
7. **依赖与构建安全**：固定插件版本（本文的经验：0.7.0 与新版 Portal 不兼容）、使用 `mvnw` 固定 Maven 版本、CI 上锁 JDK 版本。
8. **聊天/日志泄露的处置**：token 轮换（已完成）、检查是否有异常 deployment、必要时在 Portal 撤销会话。

---

## 12. 附录：清单、模板与速查

### 12.1 发布前检查清单

- [ ] 版本已定版，全仓无 `-SNAPSHOT`
- [ ] CHANGELOG 已更新，日期正确
- [ ] POM 元数据（name/description/url/licenses/developers/scm）齐全
- [ ] `-Prelease package` 产出三件套，javadoc 零错误
- [ ] 本地签名演练通过（`gpg --list-secret-keys`）
- [ ] CI 双版本 + 容器层全绿
- [ ] git 工作区干净，tag 指向已通过 CI 的提交
- [ ] 4 个 GitHub Secrets 存在
- [ ] 发布后在 Portal 点 Publish
- [ ] `repo1` 验收 + 全新仓库解析测试
- [ ] GitHub Release 说明已写（含坐标）
- [ ] README 徽章/状态更新
- [ ] 回填下一版 SNAPSHOT
- [ ] 轮换 Portal token（如曾暴露）

### 12.2 `~/.m2/settings.xml` 模板（本地发布用）

```xml
<settings>
  <servers>
    <server>
      <id>central</id>
      <username>TOKEN_USERNAME</username>
      <password>TOKEN_PASSWORD</password>
    </server>
  </servers>
</settings>
```

### 12.3 无镜像的验证 settings（排查同步问题）

```xml
<settings xmlns="http://maven.apache.org/SETTINGS/1.0.0">
  <mirrors/>
</settings>
```

### 12.4 命令速查

```bash
# 定版
mvn versions:set -DnewVersion=X.Y.Z -DgenerateBackupPoms=false

# 本地预演
mvn -Prelease clean package -DskipTests

# 签名演练
mvn -Pboot40,release,central install -DskipTests -Dgpg.passphraseEnvName=GPG_PASSPHRASE

# 打 tag
git tag -a vX.Y.Z -m "Release X.Y.Z" && git push origin vX.Y.Z

# 查看发布状态（需要 token）
curl -u "$USER:$TOKEN" https://central.sonatype.com/api/v1/publisher/deployments

# 验收
curl -s https://repo1.maven.org/maven2/<group path>/<artifact>/X.Y.Z/ | head

# GitHub Release
gh release create vX.Y.Z --title "X.Y.Z" --notes "..."
```

### 12.5 术语表

| 术语 | 含义 |
|---|---|
| GAV | groupId:artifactId:version |
| namespace | Central 中你验证过的 groupId 前缀 |
| deployment | 一次上传到 Portal 的制品集合 |
| VALIDATED | 校验通过但未公开 |
| PUBLISHED | 已公开，`repo1` 可拉取 |
| classifier | 同坐标下的变体（sources/javadoc） |
| 分离签名 | 与制品分离存放的 `.asc` 文件 |
| staging | 旧 OSSRH 的暂存仓库概念，Portal 中对应 deployment |

---

## 结语

发布不是“把 jar 传上去”，而是**把一次构建的信任传递出去**：tag 证明来源，签名证明完整，元数据证明归属，CI 证明质量，Portal 的不可变性证明历史不可篡改。

TenantKit 0.1.0 的发布过程中遇到的两个问题——**凭据变量注入**与**插件版本漂移**——都不是代码问题，而是发布工程问题。它们说明：发布链路本身也需要测试、护栏和文档。把这份指南当作模板，下一个版本可以在 15 分钟内完成从 tag 到上架的全过程。
