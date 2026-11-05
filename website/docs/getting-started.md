---
title: Quick Start
description: Build, configure, run SuperAgent, and take your first work through it.
---

# Quick Start

SuperAgent is a Maven multi-module build on **Java 21** / Spring Boot. `superagent-server` is the one executable Spring Boot jar.

## Requirements

- **Java 21+** and **Maven** to build; **Java 21+** on the server to run.
- **MySQL 8** — the default storage; tables are created on first start.
- The agent CLIs on the server's `PATH`: [Claude Code](https://www.npmjs.com/package/@anthropic-ai/claude-code) and/or [Codex](https://www.npmjs.com/package/@openai/codex). These can also be installed later from the **Models** page.

## Build a release

From the repository root:

```bash
mvn clean package -PreleaseTgz          # add -DskipTests to skip the tests
```

This produces `superagent-server/target/superagent-<version>.tar.gz`, which unpacks to:

```text
superagent-<version>/
├── bin/      superagent-start.sh, superagent-stop.sh
├── config/   application.yml, superagent.env
├── libs/     superagent.jar
├── logs/     superagent.log, console.out, superagent.pid
└── data/     agent and task workspaces
```

## Configure

Edit `config/superagent.env` before the first start (keep it at mode `600`):

```bash
SUPERAGENT_ADMIN_PASSWORD=<a strong password>
SUPERAGENT_STORAGE_MYSQL_URL=jdbc:mysql://127.0.0.1:3306/superagent
SUPERAGENT_STORAGE_MYSQL_USERNAME=superagent
SUPERAGENT_STORAGE_MYSQL_PASSWORD=<password>
```

`config/application.yml` overrides the defaults built into the jar; changes take effect on restart, with no repackaging.

## Start and stop

```bash
tar -xzf superagent-<version>.tar.gz
cd superagent-<version>

bin/superagent-start.sh          # start in the background
bin/superagent-start.sh force    # restart: stop the running process, then start
bin/superagent-stop.sh           # stop
```

Start checks for Java 21+ and reports success only once `/actuator/health` responds. Once started, open `http://<host>:8080` and sign in as the admin user.

## Take your first work through it

1. **Sign in** as the admin user.
2. On the **Models** page, add a model provider (and install a coding CLI if needed).
3. Create your first **Agent**, or open a **Task**.
4. Hand work to your **SuperAgent** via the inbox and let it plan, assign, and report back.

## Develop locally

```bash
mvn clean install                           # build and test all modules
mvn -pl superagent-server spring-boot:run   # run everything on :8080
```

Smoke-check the web wiring: `curl localhost:8080/api/v1/meta`.
