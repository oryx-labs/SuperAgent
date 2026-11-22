# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

> **Never send anything out to a remote, and never run `scripts/package.sh`.** No `git push`, no `scp` / `rsync` / `ssh`
> uploads, no deploying to or running commands on the deploy host. Pulling in is fine (`git pull`, `git fetch`,
> `git clone`), as is local git (`status`, `diff`, `log`, `add`, `commit`). Shipping out is done by the user.

## What this is

SuperAgent is an AI collaborative work system for companies, not a personal assistant: everyone works with AI in one system, and the work becomes company assets (see [README.md](README.md) for the positioning, in priority order). Design features from the company's and its employees' side, not one person's. The core is running and iterating fast; you will often be scaffolding new functionality as well as modifying existing behavior.

## Build & test

Maven multi-module build on **Java 21** / Spring Boot 3.5.3. There is no Maven wrapper — use a locally installed `mvn`.

```bash
mvn -T 1C clean install        # build + test every module (run from repo root), modules in parallel
mvn -T 1C clean install -DskipTests  # build without tests
mvn -T 1C test                 # test all modules

# Scope to one module (-am also builds the modules it depends on):
mvn -pl superagent-task -am test
mvn -pl superagent-core test -Dtest=SomeClassTest        # single test class
mvn -pl superagent-core test -Dtest=SomeClassTest#method # single test method
```

Tests run in parallel (JUnit parallel execution, set in the parent pom), test classes and methods side by side. So a
test must not share anything process-wide with others: name in-memory databases and temp paths uniquely (a UUID, not
`System.nanoTime()`), bind servers to port 0. A test that asserts on JVM-wide state (all tracked CLI processes, all merges
in progress) marks itself `@Isolated`, or takes a `@ResourceLock` that the tests touching the same state also take.

Run the application (superagent-server is the one executable Spring Boot jar):

```bash
scripts/run-local.sh                    # mvn package, copy the jar to run/, run it on :18080 (Ctrl-C stops gracefully)
scripts/run-local.sh --server.port=18099 --superagent.agent.workspace-dir=/tmp/sa-18099/workspaces  # a second instance
mvn clean package -PreleaseTgz          # release tarball (see README)
```

The server runs from its own copy of the jar, so `mvn install` while it runs can't change classes under it (with
`spring-boot:run` the classpath points into `~/.m2`, which `mvn install` overwrites). On shutdown the server waits up
to `superagent.task.shutdown.timeout` (2 min) for running task turns and local merges, then marks what's left as
interrupted by the restart and ends every CLI process; those tasks resume right after the next start.

Smoke-check web wiring: `curl localhost:18080/api/v1/meta`.

The UI is served straight from `superagent-ui/src/main/resources/static` (the script passes it; `spring-boot:run` gets it
from superagent-server's pom), so front-end edits show on a browser refresh. Java changes need `scripts/run-local.sh`
again (it rebuilds and restarts from a fresh copy).

## Module architecture

Eighteen Maven modules, layered; a module may only depend on modules below it (Maven enforces this — a
module only sees what its pom declares):

```text
L6  superagent-server        boots everything (the only executable jar); application.yml lives here
L5  superagent-web           HTTP only: REST controllers, the MCP endpoint, auth/security, GlobalExceptionHandler
L5  superagent-ui            the browser UI: static HTML/CSS/JS served from classpath:/static
L4  superagent-orchestrator  the user's SuperAgent: plans work, hires team members, assigns tasks
L4  superagent-event         the inbox: the company Feishu app (routing to employees), triage + batch schedule, to-dos
L3  superagent-docs         office documents for agents: Feishu / Lark docs and DingTalk docs, acting as each employee
L3  superagent-media        audio / video for agents: speech to text (subtitles, transcripts) on the company's own keys
L3  superagent-cost         cost optimisation: model tiers worked out from prices and what can run (a core ModelRouter)
L3  superagent-agent         conversations: agents, chat turns, memory, characters, collaboration, run log
L3  superagent-task          execution: tasks, session pool, watchdog, the coding flow, automations
L2  superagent-skills        skills: the working standards agents follow (personal or company-wide)
L2  superagent-project       projects: a set of agents and the people who use them, and the tasks and files they produce
L2  superagent-files         work files: what agents produce (documents, images, videos), their storage (local disk, S3-compatible) and agent tools
L2  superagent-platform      administration: users, system settings, model providers, connectors (and the MCP servers runs get)
L1  superagent-runtime       driving the coding-agent CLIs (Claude Code, Codex, DeepSeek, Kimi, Qwen; GLM via Claude Code), per-turn MCP grants, image attachments
L1  superagent-storage       JDBC implementations of the core store interfaces: MySQL; ClickHouse optional for analytics data only
L0  superagent-api           request/response DTOs and API paths
L0  superagent-core          domain records, store interfaces, error base types, small shared utilities
```

- **agent and task are siblings** and never depend on each other; what both need (skills, files) sits
  below them in skills and files. Anything that needs both (SuperAgent, the inbox secretary) is above.
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

`scripts/diff_build.sh <commit-id> [-m <msg>] [-o <dir>]` packages what changed since that commit, for someone to
apply by hand; nothing leaves the machine. It first commits every uncommitted change **locally** (never push, fetch or
any other remote operation), then writes one file, `superagent-<from>-<to>.tar.gz`: a directory with `changes.tar.gz`
(files added or changed between the two commits) and `apply.sh`, which is run from the target's root: it lists the
changes, asks, extracts `changes.tar.gz` over the current directory and deletes the files deleted here. It prints
`<to>`, the commit to pass next time; nothing is remembered between runs. Updating a host with it is done by the user.

## Conventions

- Prefer records for immutable data/DTOs (see `ApiError`).
- Keep the module layering intact (see above); don't introduce a dependency from a lower module to a higher one.
- Put code in the module it belongs to, by that module's responsibility and layer (`com.superagent.<module>[.<area>]`) — not wherever is nearest or currently open. If no existing module is a good fit, create a new module at the right layer (add it to the parent `pom.xml`, declare only downward dependencies, keep packages under `com.superagent.*`) rather than bending an ill-fitting one. The goal is a sensible distribution of code across modules; prefer a new module over piling unrelated concerns into an existing one.
- Keep tests convergent — cover the behaviour with a few meaningful cases, not one per permutation.
- Keep comments sparse — prefer self-documenting code; don't document every field/parameter. Comment the non-obvious (why, invariants, gotchas), not the obvious.
- Everything belongs to one company (multi-tenant). A new table carries `company_id`, and every query on it filters by
  `TenantContext.require()` (fail-closed: no company, no query). Work on another thread or in a background loop runs
  inside `TenantContext.run(companyId, …)`. Settings and config are per company; don't add a new global setting
  (only platform-wide things are global: the CLI install, the model price catalog, the built-in MCP address).
- Let exceptions propagate. Don't catch-and-log at the throw site; a single central handler (`GlobalExceptionHandler`) catches, logs, and returns a specific error to the client. (For streamed/async work that can't reach that handler, the stream's terminal error event is the equivalent central point.)
