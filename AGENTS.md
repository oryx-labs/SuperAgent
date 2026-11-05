# AGENTS.md

Guidance for AI coding agents working in this repository. The canonical, detailed version lives in [CLAUDE.md](CLAUDE.md); this file mirrors the essentials for tools that read `AGENTS.md`.

## What this is

SuperAgent is a self-hosted AI work platform for enterprises, at an early scaffolding stage (see [README.md](README.md)). You will usually be adding new functionality rather than changing existing behavior.

## Build & test

Maven multi-module, **Java 21**, Spring Boot 3.5.3. No Maven wrapper — use a local `mvn`.

```bash
mvn clean install                             # build + test all modules
mvn clean install -DskipTests                 # build, skip tests
mvn -pl superagent-task -am test              # one module (+ its deps)
mvn -pl superagent-core test -Dtest=FooTest   # single test class
mvn -pl superagent-server spring-boot:run     # run everything on :8080 (after mvn install)
```

## Module architecture

Layered; depend only downward (full table and conventions in [CLAUDE.md](CLAUDE.md)):

- **L0** superagent-core (domain records, store interfaces, error bases), superagent-api (DTOs)
- **L1** superagent-storage (JDBC), superagent-runtime (agent CLIs, MCP grants, attachments)
- **L2** superagent-platform (users, settings, providers, connectors), superagent-knowledge (knowledge base, skills)
- **L3** superagent-agent (chat, memory, collaboration), superagent-task (tasks, automations) — siblings, never depend on each other
- **L4** superagent-orchestrator (SuperAgent), superagent-event (inbox)
- **L5** superagent-web (controllers, MCP endpoint, auth), superagent-ui (static UI)
- **L6** superagent-server (boots it all)

Packages follow modules (`com.superagent.<module>…`); business logic stays out of web; each module contributes
its own MCP tools via `McpToolProvider`. The server `@ComponentScan("com.superagent")`s everything.

## Deployment

`scripts/package.sh` packages only changed files, syncs them to a remote host over SSH, commits/pushes on both ends (commit message `dev`), and opens/updates a GitHub PR for non-`main` branches. It is an outward-facing, hard-to-reverse action — don't run it incidentally.
