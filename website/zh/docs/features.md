---
title: 能力地图
description: SuperAgent 今天能做的全部事情,按它给公司带来什么来组织。
---

# 能力地图

SuperAgent 从公司的角度设计:服务的是公司和公司的员工,不是一个人和他的助手。这一页是总览,每个领域都有单独的详细页面。

![SuperAgent 功能结构](/diagrams/capabilities-zh.svg)

## 按公司得到什么

| 公司得到什么 | 靠什么做到 | 详见 |
| --- | --- | --- |
| **工作沉淀为公司资产** | Agent 的产出和员工发来的资料都进「文件」;每个 Agent 有长期记忆;运行日志 | [文件与 Skills](./files)、[Agent 与协作](./agents) |
| **协作与延续** | 多人在同一个 Agent 上协作;对话里邀请成员;SuperAgent 组队派活 | [Agent 与协作](./agents)、[SuperAgent](./superagent) |
| **方法复用** | 个人和公司两级 Skills,挂到 Agent 上,任务自动继承 | [文件与 Skills](./files) |
| **模型中立、成本可控** | 各家模型都能用;每个任务自动选够用的最便宜的模型;每个 CLI 一个 Key 池、失败自动切换;按人、按任务、按模型看花费 | [模型与成本](./models) |
| **看得见、用得安全** | 关键决策由人确认;权限;运行日志;密钥加密 | [管理与安全](./admin) |

## 按领域

### 每位员工

- **[待办](./agents#待办)** —— 首页:需要你处理的事,以及 Agent 替你做完的事。
- **[Agent](./agents)** —— 和有性格、模型、Skills、长期记忆的 Agent 对话;发文件、图片、链接给它;和同事在同一个 Agent 上协作。
- **[你的 SuperAgent](./superagent)** —— 把事情丢进去,它做计划、请你批准、组队、派活、回报。
- **[任务与自动化](./tasks)** —— 从需求到交付的长任务,以及按时间或被 API 调用触发的自动化。
- **[文件与 Skills](./files)** —— Agent 的所有产出,按人分文件夹;可复用的工作方法。
- **[连接](./connectors)** —— Agent 能用的外部系统(GitHub、Gitee、公司自己的 MCP 服务),以及飞书消息。

### 管理员

- **[模型与成本](./models)** —— 安装编码 CLI、管理 API Key、看花费。
- **[管理与安全](./admin)** —— 用户与组织架构、系统设置、连接配置、文件存储。

## 支持的模型

SuperAgent 驱动主流的编码 Agent CLI,用公司的 Key 运行:

| CLI | 能执行工作 | 说明 |
| --- | --- | --- |
| Claude Code | ✓ | GLM(智谱)也通过它的 Anthropic 兼容接口运行 |
| OpenAI Codex | ✓ | |
| DeepSeek Harness | ✓ | |
| Kimi Code | ✓ | |
| Qwen Code | ✓ | |
| CodeBuddy、MiniMax Code、Gemini CLI | 仅安装 | 目前能安装、升级、管理 Key,执行工作稍后支持 |

## 还没做的

离职交接、公司级知识汇聚、企业审批流程等,见[路线图](./roadmap)。
