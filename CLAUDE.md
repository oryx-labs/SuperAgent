# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

SuperAgent is a self-hosted AI work platform for enterprises. This repository is at an early stage — it currently describes the intended system and its module skeleton rather than production capabilities (see [README.md](README.md)). Expect to be scaffolding new functionality more often than modifying existing behavior.

## Build & test

Maven multi-module build on **Java 21** / Spring Boot 3.5.3. There is no Maven wrapper — use a locally installed `mvn`.

```bash
mvn clean install              # build + test every module (run from repo root)
mvn clean install -DskipTests  # build without tests
mvn test                       # test all modules

# Scope to one module (-am also builds the modules it depends on):
mvn -pl superagent-web -am test
mvn -pl superagent-core test -Dtest=SomeClassTest        # single test class
mvn -pl superagent-core test -Dtest=SomeClassTest#method # single test method
```

Run the applications (both are executable Spring Boot jars):

```bash
mvn -pl superagent-web -am spring-boot:run      # REST entry point on :8080
mvn -pl superagent-worker -am spring-boot:run   # background worker (no HTTP server)
```

Smoke-check web wiring: `curl localhost:8080/api/v1/meta`.

## Module architecture

Four Maven modules with a strict dependency direction — **core ← api ← {web, worker}**. Nothing depends on web or worker; web and worker never depend on each other.

- **superagent-core** — domain model, shared abstractions, cross-cutting utilities. No Spring Boot, only `spring-context`. Depend on this for anything shared.
- **superagent-api** — public API contracts and DTOs (e.g. `ApiError`) shared by web and worker, plus Jackson. Put request/response shapes and stable wire types here, not in web.
- **superagent-web** — HTTP entry point: REST controllers, validation, actuator. Bootable (`SuperAgentWebApplication`), port 8080, graceful shutdown.
- **superagent-worker** — background execution: long-running jobs, agent runs, scheduled tasks. Bootable (`SuperAgentWorkerApplication`) as a non-web Spring context (`spring.main.web-application-type=none`).

Key convention: both bootable apps set `@ComponentScan(basePackages = "com.superagent")` so beans contributed by `core` and `api` are discovered alongside the module's own. When adding a shared `@Component`/`@Configuration` in core or api, it is picked up by both apps automatically — keep package names under `com.superagent.*`.

Actuator exposes only `health,info` until real endpoints exist.

## Deployment

`scripts/package.sh` is the deploy path, not a local build helper. It computes the set of changed/untracked/deleted files versus `origin/<branch>`, tars **only those files**, `scp`s them to a remote host, then on the remote runs pull → extract → commit → push, and finally commits the same files locally. It also opens/updates a GitHub PR for non-`main` branches. Note it commits with the message `dev` on both ends — this explains the flat `dev` commit history. All values crossing the SSH boundary are base64-encoded to avoid shell injection. Treat running this script as an outward-facing, hard-to-reverse action.

## Conventions

- Prefer records for immutable data/DTOs (see `ApiError`).
- Keep the core → api → app layering intact; don't introduce a dependency from a lower module to a higher one.
- Keep tests convergent — cover the behaviour with a few meaningful cases, not one per permutation.
- Keep comments sparse — prefer self-documenting code; don't document every field/parameter. Comment the non-obvious (why, invariants, gotchas), not the obvious.
- Let exceptions propagate. Don't catch-and-log at the throw site; a single central handler (`GlobalExceptionHandler`) catches, logs, and returns a specific error to the client. (For streamed/async work that can't reach that handler, the stream's terminal error event is the equivalent central point.)
