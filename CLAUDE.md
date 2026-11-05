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
mvn -pl superagent-task -am test
mvn -pl superagent-core test -Dtest=SomeClassTest        # single test class
mvn -pl superagent-core test -Dtest=SomeClassTest#method # single test method
```

Run the application (superagent-server is the one executable Spring Boot jar):

```bash
mvn install -DskipTests && mvn -pl superagent-server spring-boot:run   # everything on :8080
mvn clean package -PreleaseTgz                                         # release tarball (see README)
```

Smoke-check web wiring: `curl localhost:8080/api/v1/meta`.

## Module architecture

Thirteen Maven modules, layered; a module may only depend on modules below it (Maven enforces this — a
module only sees what its pom declares):

```text
L6  superagent-server        boots everything (the only executable jar); application.properties lives here
L5  superagent-web           HTTP only: REST controllers, the MCP endpoint, auth/security, GlobalExceptionHandler
L5  superagent-ui            the browser UI: static HTML/CSS/JS served from classpath:/static
L4  superagent-orchestrator  the user's SuperAgent: plans work, hires team members, assigns tasks
L4  superagent-event         the inbox: Feishu bot connector, triage + batch schedule, secretary, to-dos
L3  superagent-agent         conversations: agents, chat turns, memory, characters, collaboration, run log
L3  superagent-task          execution: tasks, session pool, watchdog, the coding flow, automations
L2  superagent-knowledge     organizational knowledge: the file knowledge base and shared skills
L2  superagent-platform      administration: users, system settings, model providers, connectors
L1  superagent-runtime       driving the coding-agent CLIs (Claude Code, Codex), per-turn MCP grants, image attachments
L1  superagent-storage       JDBC implementations of the core store interfaces (MySQL, ClickHouse)
L0  superagent-api           request/response DTOs and API paths
L0  superagent-core          domain records, store interfaces, error base types, small shared utilities
```

- **agent and task are siblings** and never depend on each other; what both need (skills, the knowledge
  base) sits below them in knowledge. Anything that needs both (SuperAgent, the inbox secretary) is above.
- **Packages follow modules**: `com.superagent.<module>[.<area>]`, e.g. `com.superagent.task.automation`,
  `com.superagent.platform.provider`. Controllers stay in `com.superagent.web.<area>`.
- **Business logic lives in the domain modules**, not in web: a controller only maps HTTP onto a service.
- **MCP tools are contributed per module**: implement `McpToolProvider` (in runtime) next to the service it
  exposes; `McpController` only speaks the protocol and dispatches by the run's grant kind (chat / task / SuperAgent).
- **Errors**: domain exceptions extend the `com.superagent.core.error` bases (`NotFoundException`,
  `ForbiddenException`, `ConflictException`, `BusyException`); `GlobalExceptionHandler` maps those bases,
  so it never needs to know a module's exceptions.
- **UI scripts** (`superagent-ui/.../static/js/*.js`) are classic scripts sharing one global scope, loaded in
  order by `index.html` (core.js first, automations.js — which boots the app — last). A function called at
  load time must be defined in the same or an earlier file.

The server sets `@ComponentScan(basePackages = "com.superagent")`, so every module's beans are discovered —
keep packages under `com.superagent.*`.

## Deployment

`scripts/package.sh` is the deploy path, not a local build helper. It computes the set of changed/untracked/deleted files versus `origin/<branch>`, tars **only those files**, `scp`s them to a remote host, then on the remote runs pull → extract → commit → push, and finally commits the same files locally. It also opens/updates a GitHub PR for non-`main` branches. Note it commits with the message `dev` on both ends — this explains the flat `dev` commit history. All values crossing the SSH boundary are base64-encoded to avoid shell injection. Treat running this script as an outward-facing, hard-to-reverse action.

## Conventions

- Prefer records for immutable data/DTOs (see `ApiError`).
- Keep the module layering intact (see above); don't introduce a dependency from a lower module to a higher one.
- Keep tests convergent — cover the behaviour with a few meaningful cases, not one per permutation.
- Keep comments sparse — prefer self-documenting code; don't document every field/parameter. Comment the non-obvious (why, invariants, gotchas), not the obvious.
- Let exceptions propagate. Don't catch-and-log at the throw site; a single central handler (`GlobalExceptionHandler`) catches, logs, and returns a specific error to the client. (For streamed/async work that can't reach that handler, the stream's terminal error event is the equivalent central point.)
