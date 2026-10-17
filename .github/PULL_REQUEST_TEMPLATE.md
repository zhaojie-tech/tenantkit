## What

## Why (link the issue; the incident it prevents)

## Checklist
- [ ] `mvn verify -Pboot40` green (fast layer, coverage gates, ArchUnit)
- [ ] `mvn verify -Pboot41` green
- [ ] If container-facing behavior changed: `mvn verify -Pboot40 -Pcontainers` on a Docker host
- [ ] New assembly behavior has a matrix case (docs/matrix/assembly-matrix.md addendum)
- [ ] Config keys documented in additional-spring-configuration-metadata.json
- [ ] CHANGELOG.md entry under [Unreleased]
- [ ] DCO signed off (`git commit -s`)
