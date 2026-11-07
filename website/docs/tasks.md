---
title: Tasks & automation
description: Long-running work from requirement to delivery, and automations that run on a schedule or an API call.
---

# Tasks & automation

## Tasks

A task is a piece of work an agent carries from requirement to result, on its own, while you do something else.

<Shot name="tasks" alt="The task list: status, model, source and spend" />

- **Start one** from the Tasks page, from a chat, by your SuperAgent, or from an automation.
- **Runs in the background** in a managed session. A watchdog notices a stuck run, and tasks pick up again after a restart.
- **Asks when it must.** When a decision is yours, the task pauses and waits; you answer in the task and it continues. Waiting tasks show on your [to-do](./agents#to-do) page.
- **Steer it** at any time by sending it a message.
- **Coding flow** — engineering tasks follow fixed phases: requirement, development, acceptance.
- **Results** — deliverables land in [Files](./files); the task shows them as its output files. The event stream shows what was done, step by step.
- **Spend** — every task records what it cost.

The Tasks page shows counters (all, in progress, waiting for you, done today, failed, spend this month) and each task's agent, model, source, time, spend and status.

## Automation

Automations keep recurring work running without anyone remembering to start it.

<Shot name="automation" alt="Automations: scheduled, cron, one-off and API-triggered" />

| Trigger | Use it for |
| --- | --- |
| **Schedule** | Every day, every week, every hour… |
| **Cron** | Anything a cron expression can say |
| **Once** | At a set time |
| **API** | When another system calls a URL with the automation's own token |

- Create one from the Tasks page or **just ask in a chat** ("every Monday at 9, summarize…").
- Preview the next run times before saving, run it now, pause and resume it.
- Each run starts a task, so it has the same results, spend and history as any other.
