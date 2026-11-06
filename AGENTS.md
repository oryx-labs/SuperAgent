# AGENTS.md

Guidance for AI coding agents working in this repository. The canonical, detailed version lives in [CLAUDE.md](CLAUDE.md); this file mirrors the essentials for tools that read `AGENTS.md`.

## What this is

SuperAgent is the AI work system for companies, not a personal assistant: everyone in the company works in one system, and the work becomes company assets (see [README.md](README.md)). Design from the company's and its employees' side. It iterates fast; you will often be adding new functionality as well as changing existing behavior.

## Build & test

Maven multi-module, **Java 21**, Spring Boot 3.5.3. No Maven wrapper — use a local `mvn`.

```bash
mvn clean install                             # build + test all modules
mvn clean install -DskipTests                 # build, skip tests
mvn -pl superagent-task -am test              # one module (+ its deps)
mvn -pl superagent-core test -Dtest=FooTest   # single test class
mvn -pl superagent-server spring-boot:run     # run everything on :18080 (after mvn install)
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

Put code in the module it belongs to by responsibility and layer — not wherever is nearest or open. If no
existing module fits, create a new one at the right layer (register it in the parent `pom.xml`, depend only
downward, keep packages under `com.superagent.*`) rather than bending an ill-fitting module. Aim for a sensible
distribution of code across modules.

## Deployment

`scripts/package.sh` packages only changed files, syncs them to a remote host over SSH, commits/pushes on both ends (commit message `dev`), and opens/updates a GitHub PR for non-`main` branches. It is an outward-facing, hard-to-reverse action — don't run it incidentally.
