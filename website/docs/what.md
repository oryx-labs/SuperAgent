---
title: What is SuperAgent
description: SuperAgent is an AI collaborative work system for companies. Everyone works with AI in one system, and the work becomes the company's.
---

# What is SuperAgent

**SuperAgent is an AI collaborative work system for companies, not a personal assistant.** Everyone in the company works with AI in one system, and that work becomes company assets that stay when people change.

## Where it starts

AI can do real work now. Models write code, documents and analyses, and official agents such as Claude Code and Codex carry a job through on their own. Employees are already using them: they buy their own tokens, install agents on their own laptops, and get faster.

But all of it happens in personal places. The accounts, the spending and the results belong to each person. The company can't see what was done, can't build on it, and loses it when people leave. Everyone gets faster, and the company doesn't get any better at its work. SuperAgent gathers that scattered use into one system the company runs.

| Today | With SuperAgent |
| --- | --- |
| Only people who tinker get AI working | Employees have AI from their first day; nothing to buy or set up |
| Results stay in personal accounts and leave with the person | Work happens in the company's system and becomes company assets |
| Nobody can pick up where someone else left off | Several people work with the same agent; agents and their context stay with the company |
| Good methods stay with whoever found them | Methods become skills, personal first, then company-wide |
| Everyone pays separately and uses the most expensive model | One set of company keys; each task gets the cheapest model that can do it |
| The company can't see or control what AI does | Usage, run logs and analytics; people decide at key points |
| Personal agents can't reach company systems | Feishu, DingTalk, Git and docs connected once, used with each person's own authorization |

## How it fits together

SuperAgent drives the official agent CLIs instead of rebuilding them, and works at the company level: who does the work, where it runs, which model it uses, what it costs, and where the results go. The **control plane** holds the interface, the business logic, the data and the secrets; the agents do the work in **sandboxes**, one per employee.

![SuperAgent layered architecture](/diagrams/architecture-layers-en.jpg)

This is the target architecture. Today's release runs the control plane and the agent CLIs on one server; see [Architecture](./architecture) for the details and what's still being built.

## What it does, in priority order

1. **Work becomes assets.** Everyone works in one system. What agents produce and what people send them is filed in Files, and each agent builds long-term memory.
2. **Collaboration.** Several people work with the same agent; your SuperAgent breaks work down and hands it to agents and tasks.
3. **Reusable methods.** Personal and company skills, and agents whose setup, memory and files belong to the company.
4. **Model-neutral and cost-efficient.** No personal subscriptions; the company's keys with failover; each task gets the cheapest model that can do it; spend per person, task and model.
5. **Visible and safe.** People approve plans and decisions; analytics, permissions, run logs and encrypted secrets.

See the [capability map](./features) for everything it does today, and the [roadmap](./roadmap) for what's coming.

## In short

**Kept under control, kept by the company, cheaper to run, and finished reliably.**

## Status

SuperAgent is open source, self-hosted and iterating fast. The docs describe what works today; what isn't built yet is on the [roadmap](./roadmap).

## License

SuperAgent is licensed under the [Apache License 2.0 with the Commons Clause](https://github.com/oryx-labs/SuperAgent/blob/main/LICENSE):

- **Free to use** for individuals and inside your company, and you can modify it for your own use.
- **Not for sale**: no reselling, no paid hosted service built on it, and no selling modified versions or products built mainly on it.

Versions released before this license change remain under the plain Apache License 2.0.

## Next

- [Why SuperAgent](./why): the judgments behind it, who it's for, and where it's going.
- [Architecture](./architecture): how it's built.
- [Capability map](./features): everything it can do, area by area.
- [Quick Start](./getting-started): deploy it for your company.
