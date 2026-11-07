---
title: Administration & security
description: Users and the org chart, system settings, integrations, file storage, and how SuperAgent keeps AI use safe and visible.
---

# Administration & security

The administration area is for administrators only. Its sidebar: **Users**, **Models**, **Analytics**, and **System Settings** (Basic, Integrations, File storage).

## Users and the org chart

- **Provisioned access** — only people an administrator added can sign in. Add someone with a username and password, or with their phone number.
- **Org chart** — a read-only view of teams and who reports to whom. It drives message sorting (your manager and direct reports matter more).
- **Roles** — administrators manage the company's setup; everyone else uses it.

<Shot name="users" alt="Users and the read-only org chart" />

## System Settings

### Basic

- Company name and short name.
- The default model for agents.
- The company logo, shown at the top and on the sign-in page.
- The **public address** browsers use (behind a proxy or a domain), used for OAuth callbacks.
- Whether [model tiers](./models#model-tiers-preview) are on.

<Shot name="settings" alt="Basic settings: company, logo, default model, public address" />

### Integrations

The company's own apps on outside systems, set up once and used by everyone:

<Shot name="integrations" alt="Integrations: the company's GitHub, Gitee and Feishu apps" />

| System | What you fill in |
| --- | --- |
| GitHub | The OAuth app's Client ID and Client Secret |
| Gitee | The OAuth app's Client ID and Client Secret |
| Feishu | The Feishu app's App ID and App Secret |

The page shows the callback address to register at each provider. Secrets are stored encrypted, never shown again, and kept when you leave the field empty. These used to live in the configuration file; now they're here.

### File storage

Where file contents are kept: **on the server** (the default) or in **S3-compatible object storage**. There is a connection test before you switch.

<Shot name="file-storage" alt="File storage: on the server or S3-compatible" />

## How AI use stays safe and visible

- **People decide.** Plans wait for your approval; a task asks when a decision is yours; inviting someone to an agent, or deleting a skill, needs a click to confirm.
- **Permissions.** Only an agent's owner changes its setup; files are visible to the people involved; administration is for administrators.
- **Outside words can't act.** When a SuperAgent handles messages from outside, it only has read-only and inbox tools.
- **Records.** Run logs per agent, task event streams and spend.
- **Secrets.** API keys, tokens and app secrets are encrypted at rest, never returned to the browser, and passed to runs through the environment, never the command line.
- **Per-run access.** Each agent run gets its own short-lived credential for SuperAgent's tools, scoped to the person it works for.

Not built yet: enterprise approval workflows and isolating what agents run on the server. See the [roadmap](./roadmap).

## Deployment

Self-hosted: a release tarball or a Docker image (with the coding CLIs preinstalled) and MySQL. ClickHouse is also supported as storage. See [Quick start](./getting-started).
