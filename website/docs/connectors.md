---
title: Connectors & messages
description: The outside systems agents can use, how employees authorize them, and how Feishu messages reach each employee's SuperAgent.
---

# Connectors & messages

A connector links the company to an outside system. **Administrators add them; employees authorize the ones that act as them.** Agents only get a connector once that is in place, and always with the identity of the person they work for.

## What's on the Connectors page

| Connector | Used for | Authorization |
| --- | --- | --- |
| **SuperAgent** (built in) | SuperAgent's own tools: tasks, files, skills, automations, the team | None. Found automatically at startup, checked, and given to every run. It can't be changed. |
| **Feishu** | Messages to each employee's SuperAgent | Each employee is linked to their Feishu account |
| **GitHub** | Repositories, issues and pull requests | Each employee authorizes with their own GitHub account |
| **Gitee** | Repositories on Gitee | Each employee authorizes with their own Gitee account; the token renews itself |
| **Your own MCP servers** | Any tool an MCP server offers | None, one company token, or each employee's own token |

<Shot name="connectors" alt="The Connectors page: each connector and your own authorization" />

Each row shows your own authorization: authorize, enter a token, revoke. Administrators also see how many people have authorized.

## Adding your own MCP server

Administrators add any MCP server on the Connectors page:

- **Transport** — a command the server runs (stdio), or a URL (HTTP / SSE). Stdio servers work with Claude Code; the other CLIs use HTTP / SSE servers.
- **Authorization** — none; **one company token** for everyone; or **each employee's own token**, so the agent acts as that person.
- Tokens are stored encrypted and never shown again.

## Company apps

GitHub, Gitee and Feishu need the company's own app on that platform (an OAuth app, a Feishu app). An administrator sets these up once under [System Settings → Integrations](./admin#integrations); until then the connector shows as not set up and nobody can authorize it.

## Feishu

One Feishu app serves the whole company.

- **Linking people** — employees are matched to their Feishu account by phone number, automatically. Anyone it can't match gets a bind code on the Connectors page and sends it to the bot.
- **Where messages go** — a direct message to the bot goes to its sender's SuperAgent; a group message goes to every linked member of the group, sorted for each of them.
- **What happens next** — your [SuperAgent](./superagent#messages-from-outside) sorts and handles them; urgent ones can reach you as a Feishu message.
- The bot sees only messages sent to it and messages in groups it's in, never people's private chats with each other.
