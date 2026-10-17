# Docker 首跑操作手册（slow 层）

> 2026-10-17 已完成首次真实容器执行：GitHub Actions ubuntu runner 上 boot40/boot41
> 双版 `-Pcontainers` 全绿（含 RabbitMQ DLX、Redis 隔离、MySQL 并发）。本手册保留
> 给本地/自建 Docker 复跑与排障；首次运行暴露的问题已记录在 `docs/PROGRESS.md` R8。
>
> 无容器环境时的等价验证：MySQL 锁语义与 Redis 隔离已在本机真实服务上验证通过
> （见 `docs/PROGRESS.md` 的 R7 记录）；**RabbitMQ 必须用容器**（本机无 Erlang），
> 因此 `-Pcontainers` 仍应在第一个 Docker 环境完整跑一次。

## T-1 前一天：预热（不占 CI 20min 预算）

```bash
docker info >/dev/null && echo "docker OK"     # permission denied -> sudo usermod -aG docker $USER 后重登
df -h /var/lib/docker                          # 预留 >=5GB
java -version                                  # 17
docker pull mysql:8.4
docker pull rabbitmq:4-management
docker pull redis:8-alpine
docker pull testcontainers/ryuk:0.12.0         # testcontainers 1.21.4 内置 tag
./mvnw -q -DskipTests -Pboot40 install         # 预热依赖（含 mysql-connector-j / testcontainers）
./mvnw --no-transfer-progress verify -Pboot40 2>&1 | tee /tmp/tk-fast.log   # fast 层应为绿
```

## T-0 当日：单模块点灯（**必须带 `-Djacoco.skip=true`**）

只跑一个测试类时覆盖率必然不达标，不带 `-Djacoco.skip=true` 会在 verify 阶段因
jacoco 门失败，看起来像用例失败。

```bash
# ① MySQL GET_LOCK（含并发 + Hikari 代理连接变体）
./mvnw -pl tenantkit-provisioning verify -Pboot40 -Pcontainers \
  -Dtest='MysqlGetLockConcurrencyTest' -DfailIfNoTests=false -Djacoco.skip=true 2>&1 | tee /tmp/tk-c1.log

# ② Rabbit DLX 正/负例
./mvnw -pl tenantkit-messaging verify -Pboot40 -Pcontainers \
  -Dtest='TenantRabbitIntegrationTest' -DfailIfNoTests=false -Djacoco.skip=true 2>&1 | tee /tmp/tk-c2.log

# ③ Redis 隔离
./mvnw -pl tenantkit-cache-redis verify -Pboot40 -Pcontainers \
  -Dtest='TenantRedisIsolationTest' -DfailIfNoTests=false -Djacoco.skip=true 2>&1 | tee /tmp/tk-c3.log
```

## T-0 当日：全量门（计时，对照 CI 20min/job）

```bash
time ./mvnw --no-transfer-progress verify -Pboot40 -Pcontainers 2>&1 | tee /tmp/tk-containers-boot40.log
time ./mvnw --no-transfer-progress verify -Pboot41 -Pcontainers 2>&1 | tee /tmp/tk-containers-boot41.log
```

## 失败排查（按症状）

- **卡在 Pulling image**：回 T-1 预热；确认镜像源/代理。
- **MySQL 用例**：先看 `tenantkit-provisioning/target/surefire-reports/...MysqlGetLockConcurrencyTest.txt`；
  容器相关看 `docker ps -a --filter label=org.testcontainers=true` 与
  `docker logs $(docker ps -aqf ancestor=mysql:8.4 | head -1) | tail -50`。
  `Container startup failed`/Timeout 多为宿主机慢（3min 上限，`TenantSupport`）。
  断言里出现 `GET_LOCK returned 0` → 竞争对手持有锁（预期语义），检查用例时序。
- **messaging 卡 20s**：先排除「消息没进队列」——`docker logs <rabbit> | tail -50`，
  并临时打印 `template.execute(ch -> ch.messageCount(QUEUE))`。若为队列声明竞态，
  在 `@BeforeAll` 加 `context.getBean(RabbitAdmin.class).initialize()` + await 队列属性。
- **Redis containsExactly 失败**：怀疑残留键，`docker exec <redis> redis-cli KEYS '*'`；
  用例已用唯一后缀，仍失败则看是否有旧容器被复用。
- **jacoco 门红**（非 Docker 问题）：确认是否漏了 `-Djacoco.skip=true`（单类试跑）。
- **超 20min**：`-pl` 分模块计时；`TenantSupportTest` 已只起 redis，若仍超时检查镜像拉取。
- **Ryuk/容器不回收**：`docker ps --filter label=org.testcontainers=true` 应清空；
  残留时检查 `TESTCONTAINERS_RYUK_DISABLED`、socket 权限，手动 `docker rm -f`。

## 收尾

```bash
docker ps --filter label=org.testcontainers=true     # 应为空
grep -R "Tests run" tenantkit-*/target/surefire-reports/*.txt | grep -E "Concurrency|Redis|Rabbit"
```

把三份 `/tmp/tk-c*.log` 的实测耗时记入 `docs/PROGRESS.md` 的 CI 预算基线。
