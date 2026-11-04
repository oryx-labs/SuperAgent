
<p align="center">
  <img src="docs/images/superagent-logo.png" alt="SuperAgent logo" style="max-width: 50%;" />
</p>

An open-source, self-hosted AI work platform for enterprises—model-agnostic, governed, and built to accumulate organizational knowledge.

## What is SuperAgent?

SuperAgent is a self-hosted AI workspace that an enterprise deploys once and connects to its identity provider, models, knowledge, business systems, and execution environments. Employees can then use AI for engineering, content, operations, product, and other daily work through a shared, governed workspace.

The enterprise owns the work system—not a collection of disconnected employee subscriptions. Models and agents can change; the organization retains its goals, context, permissions, deliverables, and validated work methods.

## What is it for?

AI models are increasingly capable of understanding goals, using tools, and completing long-running work. The missing piece is how to make that capability simple for employees and manageable for the enterprise.

SuperAgent helps enterprises:

- provide a shared AI work entry point instead of unmanaged individual accounts;
- route work to the right model, agent, tool, and execution environment;
- enforce identity, permissions, policies, approvals, budgets, and audit controls;
- connect AI work to real enterprise systems, knowledge, and environments;
- retain verified deliverables, decisions, evidence, and reusable work methods as organizational knowledge.

## Architecture

SuperAgent separates how work enters, how it is coordinated and governed, and how it runs.

<p align="center">
  <img src="docs/images/superagent-architecture.jpg" alt="SuperAgent three-layer architecture" width="100%" />
</p>

## First use cases

### Engineering

SuperAgent helps teams carry work from a requirement to code, tests, fixes, and delivery. It connects repositories, CI, team communication, and execution environments so that different coding agents can work under the same enterprise controls and produce evidence for the delivered result.

### Content production

SuperAgent gives non-technical teams a daily AI workspace for research, outlining, writing, review, revision, and publishing. Brand guidelines, approved sources, review standards, and successful work can be retained and reused instead of starting from an empty prompt every time.

These use cases require different tools and acceptance criteria, but share the same foundation: identity, collaboration, model and agent routing, governance, cost visibility, and organizational memory.

## Vision

We want every company to own its AI work system, rather than merely distribute disconnected model accounts to individual employees.

Models provide intelligence; SuperAgent turns that intelligence into durable enterprise productivity.

## Build and run

### Requirements

- Java 21+ and Maven (to build); Java 21+ on the server (to run)
- MySQL 8 (the default storage; tables are created on first start)
- The agent CLIs on the server's `PATH`: [Claude Code](https://www.npmjs.com/package/@anthropic-ai/claude-code) and/or [Codex](https://www.npmjs.com/package/@openai/codex). These can also be installed later from the **Models** page.

### Package

From the repository root:

```bash
mvn clean package -PreleaseTgz              # add -DskipTests to skip the tests
```

This produces `superagent-server/target/superagent-<version>.tar.gz`, which unpacks to:

```text
superagent-<version>/
├── bin/      superagent-start.sh, superagent-stop.sh
├── config/   application.properties, superagent.env
├── libs/     superagent.jar
├── logs/     superagent.log, console.out, superagent.pid
└── data/     agent and task workspaces
```

### Configure

- `config/superagent.env`: secrets and deployment values, sourced by the start script. It includes the admin password, MySQL connection, GitHub OAuth, `SERVER_PORT` and `JAVA_OPTS`. Every entry ships commented out. Keep the file at mode `600`.
- `config/application.properties`: overrides the defaults built into the jar. Changes take effect on restart, with no repackaging.

At minimum, set these before the first start:

```bash
SUPERAGENT_ADMIN_PASSWORD=<a strong password>
SUPERAGENT_STORAGE_MYSQL_URL=jdbc:mysql://127.0.0.1:3306/superagent
SUPERAGENT_STORAGE_MYSQL_USERNAME=superagent
SUPERAGENT_STORAGE_MYSQL_PASSWORD=<password>
```

### Start and stop

```bash
tar -xzf superagent-<version>.tar.gz
cd superagent-<version>

bin/superagent-start.sh          # start in the background
bin/superagent-start.sh force    # restart: stop the running process, then start
bin/superagent-stop.sh           # stop
```

- **Start:** checks for Java 21+, then reports success only once `/actuator/health` responds. If the server is already running, it refuses to start a second copy. If startup fails, it prints the end of the log.
- **force:** stops the running process with SIGTERM. The server shuts down gracefully; if it is still running after 35 seconds (`STOP_TIMEOUT`), it is killed. The server then starts again.
- **Working directory:** the scripts can be run from any directory. Relative paths in the config, such as `./data`, resolve against the install directory.

Once started, open `http://<host>:8080` and sign in as the admin user.

### Development

```bash
mvn clean install                                  # build and test all modules
mvn -pl superagent-server spring-boot:run          # run locally on :8080
```

## Status

SuperAgent is currently a product and architecture direction. This repository describes the intended system; it does not represent production-ready capabilities yet.
