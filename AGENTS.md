# AGENTS.md

Guidance for AI coding agents working in this repository. The canonical, detailed version lives in [CLAUDE.md](CLAUDE.md); this file mirrors the essentials for tools that read `AGENTS.md`.

## What this is

SuperAgent is a self-hosted AI work platform for enterprises, at an early scaffolding stage (see [README.md](README.md)). You will usually be adding new functionality rather than changing existing behavior.

## Build & test

Maven multi-module, **Java 21**, Spring Boot 3.5.3. No Maven wrapper — use a local `mvn`.

```bash
mvn clean install                             # build + test all modules
mvn clean install -DskipTests                 # build, skip tests
mvn -pl superagent-web -am test               # one module (+ its deps)
mvn -pl superagent-core test -Dtest=FooTest   # single test class
mvn -pl superagent-web -am spring-boot:run    # run REST app on :8080
mvn -pl superagent-worker -am spring-boot:run # run background worker (no HTTP)
```

## Module architecture

Strict dependency direction: **core ← api ← {web, worker}**. web and worker never depend on each other.

- **superagent-core** — domain model, shared abstractions, utilities (`spring-context` only).
- **superagent-api** — API contracts/DTOs (e.g. `ApiError`) shared by web and worker + Jackson.
- **superagent-web** — HTTP entry point: REST controllers, validation, actuator; bootable, port 8080.
- **superagent-worker** — background jobs/agent runs/scheduled tasks; bootable non-web context.

Both bootable apps `@ComponentScan("com.superagent")`, so shared beans in core/api are discovered automatically — keep packages under `com.superagent.*`. Don't add a dependency from a lower module to a higher one.

## Deployment

`scripts/package.sh` packages only changed files, syncs them to a remote host over SSH, commits/pushes on both ends (commit message `dev`), and opens/updates a GitHub PR for non-`main` branches. It is an outward-facing, hard-to-reverse action — don't run it incidentally.
