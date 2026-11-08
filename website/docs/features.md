---
title: Capability map
description: Everything SuperAgent can do today, organized by what it gives the company.
---

# Capability map

SuperAgent is built from the company's side: the company and its employees, not one person and an assistant. This page is the overview. Each area links to a page with the details.

![SuperAgent capabilities](/diagrams/capabilities-en.svg)

## By what the company gets

| What the company gets | What makes it work | Details |
| --- | --- | --- |
| **Work becomes company assets** | Files for everything agents make and people send; long-term memory per agent; run logs | [Files & skills](./files), [Agents & collaboration](./agents) |
| **Collaboration and continuity** | Several people on one agent; projects that group agents, people, tasks and files; invitations from chat; a SuperAgent that hires a team and hands out work | [Agents & collaboration](./agents), [SuperAgent](./superagent) |
| **Reusable methods** | Skills at the personal and company level, mounted on agents and inherited by tasks | [Files & skills](./files) |
| **Model-neutral, cost under control** | Any vendor's models; each task gets the cheapest model that can do it; one key pool per CLI with failover; spend per person, task and model | [Models & cost](./models) |
| **Visible and safe** | Human confirmation for key decisions, permissions, run logs, encrypted secrets | [Administration & security](./admin) |

## By area

### For every employee

- **[To-do](./agents#to-do)** — the home page: what needs you, and what your agents finished.
- **[Agents](./agents)** — chat with agents that have a persona, a model, skills and long-term memory; send them files, images and links; work on the same agent with colleagues; group work into [projects](./agents#projects).
- **[Your SuperAgent](./superagent)** — drop work in; it plans, asks you to approve, forms a team, hands out tasks and reports back.
- **[Tasks & automation](./tasks)** — long-running work from requirement to delivery; complex tasks that split into parallel subtasks; follow-on tasks; tags and filters; and work that runs on a schedule or an API call.
- **[Files & skills](./files)** — everything agents produce, in personal folders; reusable working methods.
- **[Connectors](./connectors)** — the outside systems agents can use (GitHub, Gitee, your own MCP servers), and messages from Feishu.

### For administrators

- **[Models & cost](./models)** — install the coding CLIs, manage API keys, see the spend.
- **[Administration & security](./admin)** — users and the org chart, system settings, integrations, file storage.

## Supported models

SuperAgent drives coding-agent CLIs and runs them on the company's keys:

| CLI | Runs work | Notes |
| --- | --- | --- |
| Claude Code | ✓ | Also used for GLM (Zhipu) through its Anthropic-compatible endpoint |
| OpenAI Codex | ✓ | |
| DeepSeek Harness | ✓ | |
| Kimi Code | ✓ | |
| Qwen Code | ✓ | |
| CodeBuddy, MiniMax Code, Gemini CLI | Install only | Install, upgrade and manage keys today; running work on them comes later |

## What isn't built yet

Handover when someone leaves, company-wide knowledge consolidation, enterprise approval workflows and more are on the [roadmap](./roadmap).
