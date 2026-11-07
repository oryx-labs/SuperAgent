---
title: Files & skills
description: Everything agents produce and everything people send them, in personal folders; skills at the personal and company level.
---

# Files & skills

## Files

Files is where work results live, so they stay with the company instead of in a chat history.

<Shot name="files" alt="Files in personal folders, with topic tags" />

- **What's in it** — everything agents deliver (documents, reports, code files, images, videos, slides) and everything people send them (files and links).
- **Traceable** — each file links back to the agent, conversation or task that made it.
- **One current version** — when an agent revises a file it replaces it rather than piling up copies.
- **Who sees what** — the person who started the work and the agent's members.
- **Folders** — everyone has their own folders. Agents file new work into a fitting folder, and add one to three topic tags, so things are easy to find.
- **Ask instead of clicking** — agents can find, move and organize files and folders; your SuperAgent can also read, rename, re-tag and delete them for you.
- **Preview** in the page, or download.

Where the content is stored is the administrator's choice: on the server, or in S3-compatible object storage. See [file storage](./admin#file-storage).

## Skills

A skill is a reusable working method: instructions, and images if they help, that an agent follows.

<Shot name="skills" alt="Skills at the personal and company level" />

| Level | Who can use it | Who can change it |
| --- | --- | --- |
| **Company** | Everyone | Administrators |
| **Personal** | Just you | You |

- **Mount** skills on an agent; tasks inherit the skills of the agent that runs them.
- **Loaded when needed** — an agent sees the list and opens a skill only when the work calls for it, so many skills don't crowd its context.
- **From a chat** an agent can list, read, create and update skills, and attach or detach them. Creating a skill never mounts it by itself, and deleting one asks you to confirm.
