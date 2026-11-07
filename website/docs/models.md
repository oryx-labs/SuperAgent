---
title: Models & cost
description: The coding-agent CLIs SuperAgent runs, the company's API keys, model tiers and where the money goes.
---

# Models & cost

Employees get AI from their first day, on the company's keys. Nobody pays for a personal subscription, and the company sees what is spent.

## Model CLIs

On the Models page, administrators manage the coding-agent CLIs that do the work:

<Shot name="models" alt="The coding CLIs: install, upgrade and the models that work" />

- **Install and upgrade** with one click, from each vendor's official package. A server without Node.js gets a private copy installed for SuperAgent.
- **Model list** — fetched from each CLI and its keys, and checked with a real request, so the list shows what actually works.
- **Supported**: Claude Code, OpenAI Codex, DeepSeek Harness, Kimi Code, Qwen Code, and GLM through Claude Code. CodeBuddy, MiniMax Code and Gemini CLI can be installed and given keys; running work on them comes later.

## API keys

Each CLI has a pool of endpoints and keys, in priority order:

<Shot name="model-keys" alt="A CLI's key pool: priority, health and the reason a key fails" />

- **Several keys and endpoints** — the official API, relays or gateways, each with its own key.
- **Failover** — runs use the highest healthy entry. If a call fails in a way another key can fix (invalid key, quota, the endpoint down), it switches to the next one and retries once, and switches back once the better one recovers.
- **Health checks with reasons** — every key is checked with a real request. A failing one shows why: invalid key, no permission, out of balance, rate-limited, wrong endpoint, can't connect.
- **Official endpoint by default** — a key without its own endpoint goes to the vendor's official address, shown on the page.
- **Keys already on the server** are found at startup and imported.
- Keys are stored encrypted and only ever shown masked.

## Model tiers (preview)

When an administrator turns tiers on, people pick a **tier** instead of a model name:

| Tier | For |
| --- | --- |
| **Fast** | Quick, cheap work; also used for the system's own chores (tagging files, distilling memory) |
| **Standard** | Everyday work |
| **Strong** | Hard problems |

Tiers are worked out from model prices and what can actually run, and administrators can move a model between tiers. Agents, tasks and automations can each have a tier. Tiers are off by default; with them off, models are chosen as before.

## Seeing the cost

- **Every task** records its spend.
- **Analytics** (administrators) shows users and active users, tasks and success rate, agents, scheduled tasks, total spend, tasks per day, and **spend by model**.

<Shot name="analytics" alt="Analytics: tasks, success rate and spend by model" />
