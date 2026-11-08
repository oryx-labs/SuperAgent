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

### Complex tasks

For a big job, start a **complex task**. It plans the work, splits it into subtasks and runs them side by side, then sums up the results.

- **Split and run in parallel** — by default up to 10 subtasks, 5 running at once. Administrators change these limits under System Settings → Basic.
- **Waits for its subtasks** — while they run, the task shows as waiting for subtasks; when all have finished it writes the summary and finishes on its own.
- **The right model for each part** — planning and the summary use the default model; each subtask runs on the cheapest model that is good enough for it.
- **Recovers from failures** — a failing subtask first moves to a stronger model and is retried. If it still fails, the summary says so and the task may hand that part out once more. After at most three summary rounds it writes its conclusion, naming anything that is missing.
- **Runs without stopping** — neither the task nor its subtasks pause to ask you.
- **One line in the list** — the task list shows the complex task; its detail page has a Subtasks tab, and its spend includes the subtasks'.
- In a chat, an agent can start one for you.

### Follow-on tasks

To carry on from a finished task, click **Continue** on its detail page, or ask an agent in a chat. The new task starts with the previous task's conclusion, short summaries of up to five earlier tasks in the chain, and the previous task's working directory. The detail page shows which task it continues and what came after it.

### Tags and filters

- When a task finishes it gets **two to four topic tags**, reusing tags you already have where they fit. You can edit them on the detail page. Subtasks aren't tagged.
- **Filter** the task list by tag, agent or source. The list shows how many tasks match and what they cost, and each filter option shows its own count and spend.

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
