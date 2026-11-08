---
title: Architecture
description: SuperAgent's three layers, how they talk, the model gateway, and how one codebase serves both SaaS and self-hosted deployments.
---

# Architecture

::: info Target architecture
This page describes where SuperAgent's architecture is going. Today's release runs the control plane and the agent CLIs on one server; isolated sandboxes, Workers on several machines and the model gateway are being built (see the [roadmap](./roadmap)).
:::

SuperAgent doesn't rebuild agents. It drives the official agent CLIs (Claude Code, Codex, Qwen Code, Kimi and more) and works at the company level: who does the work, where it runs, which model it uses, what it costs and where the results go. Because an agent runs whatever commands it comes up with, it is treated as untrusted, and the architecture keeps deciding apart from doing.

## Three layers

| Layer | Trust | Does | Never holds |
| --- | --- | --- | --- |
| **Control plane** | Trusted | The interface and APIs, all business logic, multi-tenancy and permissions, scheduling, the model gateway, the MCP tool service, data and file storage | — |
| **Worker** (one per machine) | Semi-trusted | Starts and stops sandboxes with resource limits, relays their output, prepares workspaces, reports heartbeats | Database credentials, model keys, refresh tokens |
| **Sandbox** (one per employee) | Untrusted | Runs the agent CLI and the agent's commands, holds the employee's workspace | All of the above |

![SuperAgent layered architecture](/diagrams/architecture-layers-en.jpg)

- **One control plane serves every company.** All companies' data is in one database, separated by a company column.
- **The runtime is a plugin.** microVM, gVisor and Docker can all run sandboxes. Each declares its isolation level, and the scheduler decides from it whether companies can share a machine.
- **The control plane schedules, Workers execute.** Only the control plane sees every machine's capacity, each company's quota and where each employee's sandbox lives.

## Deployment and traffic

Only five kinds of traffic are allowed at run time. The control plane never connects to a sandbox directly, and a sandbox can reach neither its Worker nor the database.

![Multi-machine deployment and the five allowed kinds of traffic](/diagrams/architecture-deployment-en.jpg)

1. **Worker → control plane.** A Worker connects out and keeps the connection open; runs go down it and output comes back. Workers open no inbound ports.
2. **Sandbox → control plane.** Only the MCP tool service and the model gateway, with a per-run token bound to one company, one employee and one run.
3. **Sandbox → egress proxy → internet.** The proxy blocks the intranet, the control plane and the cloud metadata address, and lets the rest through by policy.
4. **Control plane ↔ Feishu / DingTalk.** Message connections and document APIs are handled by the control plane; employees' tokens never enter a sandbox.
5. **Model gateway → model upstreams.** Vendor APIs, or the customer's own model gateway.

## One chat turn

![The path of one chat turn, from the browser to the model and back](/diagrams/architecture-chat-sequence-en.jpg)

## Model gateway

Every model call goes through the gateway in the control plane, which keeps SuperAgent neutral about model vendors:

- **Mostly a pass-through.** It swaps in the real key, routes by model and reads token usage from the response. Tool calls, reasoning and prompt caching stay intact.
- **Three protocols cover the vendors**: Anthropic Messages, OpenAI Chat Completions and OpenAI Responses. The scheduler picks a CLI that speaks what the upstream supports.
- **A company's own model gateway is a first-class upstream.** Its model list and prices come from the company, and per-employee keys and request headers can be passed along so its own reports still work.

## One codebase

| Deployment | What it looks like |
| --- | --- |
| **One machine** | Control plane, Worker and sandboxes on one machine with docker compose. The layers keep their boundaries, so growing needs no change to the architecture. |
| **Many machines** | Two or more control-plane instances share MySQL and object storage; each machine runs one Worker. |
| **SaaS and self-hosted** | The same source code. Self-hosted is one company and can use the Docker runtime; SaaS mixes companies on shared machines and uses microVM or gVisor. |

The full technical document, with modules, key designs and data flows, is in the repository at `docs/prd/superagent-architecture.html`.
