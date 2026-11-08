---
title: Models & cost
description: The coding-agent CLIs SuperAgent runs, the company's API keys, how models are chosen and where the money goes.
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

## How models are chosen

SuperAgent doesn't tie the company to one model vendor. Every model the company has keys for can be used, and each piece of work goes to the one that fits it.

- **Chats** use the company's **default model** (Admin → System Settings → Basic). If it fails with a model problem (a bad key, no quota, the endpoint down), the chat moves to the next model on the backup list without the user noticing, and goes back once the default recovers. Administrators are told when that happens.
- **Tasks pick their own model.** Before a task runs, the default model judges how hard it is, and the task gets the **cheapest model that can do it**. If it fails, it moves up a level. The task's **Model choice** tab shows what was picked and why.
- **Background chores** (tagging files, distilling memory) use the cheapest model that works.
- **Model prices** (Admin → Models → Prices) lists the models that can run with their official prices. Administrators can enter the price they actually pay (for a relay, say) and switch a model off for tasks. Cost estimates and model choice both use these prices.

## Seeing the cost

- **Every task** records its spend.
- **Analytics** (administrators) shows users and active users, tasks and success rate, agents, scheduled tasks, total spend, tasks per day, and **spend by model**.

<Shot name="analytics" alt="Analytics: tasks, success rate and spend by model" />
