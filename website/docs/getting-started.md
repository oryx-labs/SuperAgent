---
title: Quick Start
description: Deploy SuperAgent for your company with Docker or the release tarball, then set it up and take the first work through it.
---

# Quick Start

SuperAgent is self-hosted: you run it on your own server, with MySQL. Each [release](https://github.com/oryx-labs/SuperAgent/releases) comes as a Docker image and a tarball.

## Option 1: Docker (recommended)

The image has Java 21, Node.js 24 and the coding-agent CLIs preinstalled (Claude Code, Codex, Gemini CLI, Qwen Code, Kimi Code, DeepSeek Harness).

```bash
cp docker/.env.example docker/.env      # set the admin and MySQL passwords
docker compose -f docker/docker-compose.yml --env-file docker/.env up -d
```

This starts SuperAgent and MySQL on `http://<host>:18080`. If the server can't reach the model APIs directly, set `HTTPS_PROXY` in `docker/.env`.

## Option 2: Release tarball

Requirements: **Java 21+** and **MySQL 8** (tables are created on first start). The coding CLIs can be installed later from the **Models** page.

```text
superagent-<version>/
├── bin/      superagent-start.sh, superagent-stop.sh
├── config/   application.yml, superagent.env
├── libs/     superagent.jar
├── logs/
└── data/     agent and task workspaces
```

Set these in `config/superagent.env` before the first start (keep it at mode `600`):

```bash
SUPERAGENT_ADMIN_PASSWORD=<a strong password>
SUPERAGENT_STORAGE_MYSQL_URL=jdbc:mysql://127.0.0.1:3306/superagent
SUPERAGENT_STORAGE_MYSQL_USERNAME=superagent
SUPERAGENT_STORAGE_MYSQL_PASSWORD=<password>
```

```bash
bin/superagent-start.sh          # start in the background
bin/superagent-start.sh force    # restart
bin/superagent-stop.sh           # stop
```

The start script checks for Java 21+ and reports success once the server answers its health check.

## Set it up (administrator)

Open `http://<host>:18080` and sign in as the admin. Everything else is set up in the console, not in files:

1. **Models** — install the CLIs you want and add the company's API keys. Each key is checked with a real request; a failing one tells you why.
2. **System Settings → Basic** — company name, logo, the default model, and the public address if people reach the server through a proxy or a domain.
3. **System Settings → Integrations** — the company's GitHub / Gitee OAuth apps and the Feishu app, if you use them. See [Connectors & messages](./connectors).
4. **System Settings → File storage** — keep files on the server, or use S3-compatible storage.
5. **Users** — add the people who may sign in (username and password, or phone number).

## First work (everyone)

1. Open **Agents** and create an agent: give it a persona, a model and skills, then chat.
2. Or drop something into your **SuperAgent**: it plans, asks you to approve, and gets a team on it.
3. Long-running work goes to **Tasks**; recurring work to **Automations**.
4. On the **Connectors** page, authorize the systems you want agents to use as you (e.g. GitHub).
5. Check your **To-do** page: what needs you, and what your agents finished.

## For developers

```bash
mvn clean install                           # build and test all modules
mvn -pl superagent-server spring-boot:run   # run everything on :18080
```

Release from a pull request titled `release: …` with the version bumped in the pom: merging it publishes the GitHub Release (tarball + `.sha256`) and the Docker images.
