# Contributing to TenantKit

Thanks for your interest in contributing. This document covers the
environment, the dual-Boot build, test layering, the module definition of
done, and commit discipline.

## Prerequisites

| Tool | Version | Why |
|---|---|---|
| JDK | 17+ | baseline language level |
| Maven | 3.9+ | multi-module build with Boot-version profiles |
| Docker | recent stable | Testcontainers (MySQL / Redis / RabbitMQ) for `slow` tests |

Linux, macOS, or WSL2 is recommended — the `slow` tier needs a working Docker
socket.

## Building Against Both Boot Lines

v0.1 supports Spring Boot **4.0.x and 4.1.x**. The Boot version is switched by
Maven profile; CI runs both as separate jobs.

```bash
./mvnw -Pboot40 verify                  # fast layer only (slow tests excluded by default)
./mvnw -Pboot40 verify -Pcontainers     # + slow layer (Docker required; Testcontainers)
./mvnw -Pboot41 verify                  # same layering on the second Boot leg
```

Run the two fast legs before pushing; run the containers profile on a Docker
host before a release. A change is not done until the fast legs on **both**
Boot versions are green.

## Test Layering: fast vs slow

- Tests tagged `@Tag("slow")` require containers (Testcontainers). They run
  ONLY under `./mvnw verify -Pcontainers` (Docker required) and in the CI jobs
  that pass that profile; a plain `verify` silently excludes them. Suite
  duration is capped in CI (~20 min per job).
- Everything else is the **fast** tier: plain unit tests and
  `ApplicationContextRunner` auto-configuration matrix tests. `mvn test` must
  be fully green at all times and is the default local/PR gate.
- If you add a test that needs a container, tag it `slow`. Do not let a
  container dependency sneak into the fast tier.

## Module Definition of Done

A module is done only when it ships, in the same change set:

1. `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
   (auto-configuring modules; `tenantkit-core` is a pure library and has none),
2. **full configuration metadata** (`additional-spring-configuration-metadata.json`)
   for every `tenantkit.*` property — IDE completion is part of the contract,
3. a **javadoc jar** (doclint failures are build failures; nothing is deferred
   to a later milestone).

## Commit Discipline

- Commit in **logical batches aligned with milestones** (e.g. scaffold + CI
  skeleton, core, web resolution + trust model, messaging, redis,
  provisioning, docs) — history is part of the deliverable, and each batch
  should build and pass its tier's tests.
- **No single "initial commit" dumps.**
- Sign every commit with a DCO trailer (`git commit -s`):

  ```
  Signed-off-by: Your Name <you@example.com>
  ```

  CI checks for the `Signed-off-by` trailer; unsigned commits are not merged.

## Code of Conduct

This project follows the
[Contributor Covenant](https://www.contributor-covenant.org/); by
participating you are expected to uphold it.

## Bugs & Security

- Bugs: GitHub Issues — please state the Boot line (`-Pboot40` / `-Pboot41`).
- Security: see [SECURITY.md](SECURITY.md) — never open public issues for
  vulnerabilities.
