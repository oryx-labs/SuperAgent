# PRD — 把聊天会话升维成「Agent（个体）」

状态：开发中 · 作者：平台 · 日期：2026-11-04

## 背景

当前 SuperAgent 的聊天能力以 `ChatSession`（一次会话）为中心：侧边栏是一列「对话」，
每条对话底层对应一个 CLI agent（Claude Code）session。用户希望把这个概念升维成
**「Agent（个体）」**——一个有名字、有性格/职责（persona）的角色，用户用**现在的聊天形式**
指挥它干活。交互方式完全不变，只是把「对话」换成「Agent」这个概念。

- 一期：1 个 Agent = 1 个 session（即现在的 ChatSession）。
- 二期：1 个 Agent 可挂多个 session。本期为此**预留结构**（session 归属 agent 的建模），
  但不实现多 session。

> 注意：代码里已有的 `AgentType`（`CLAUDE` 这种底层引擎枚举）是另一回事，和本次的
> 「Agent（个体）」概念不是一个层面，保持不动、不要混淆。

## 需求

1. 新概念叫 **Agent（个体）**，独立放在 `com.superagent.core.agent` 包。
2. Agent 是一等实体（有自己的表）；`ChatSession` 增加可空外键 `agentId` 指向它。一期强制
   1 Agent : 1 session，但接口/存储按「session 属于 agent」建模，二期放开 1:N 零重构。
3. persona 以**正规方式注入**底层 CLI：Agent 的 persona 在运行一轮对话时，作为
   `--append-system-prompt <persona>` 真正传给 Claude CLI。
4. 本期**不做头像**。Agent 无头像字段，前端列表用名字即可。
5. 「指挥 Agent 干活」= 在 Agent 聊天里让它去开 task，**复用现有 MCP 链路**
   （ChatService 已把 SuperAgent 的 task 工具暴露给聊天 agent）。本期**不改 Task 模块**。
6. 存量数据**不迁移**：老 ChatSession 的 `agentId` 为 null 即可，仍能正常打开、不报错，
   Agent 名展示留空；不做任何自动归属。

## 技术方案

分层约束不变：`core ← api ← storage ← {web, worker}`，不得反向依赖。不可变数据/DTO 用 record。

### 1) Agent 实体（core）

- `com.superagent.core.agent.Agent`（record）：
  `id, name, persona, model(默认模型，可空), status(AgentStatus), createdAt, updatedAt`。
  persona 为自由文本（角色特点/职责）。提供 `withName/withPersona/withModel/touched/deleted`
  等不可变辅助（仿 `ChatSession`/`Task`）。
- `com.superagent.core.agent.AgentStatus`：`ACTIVE` / `DELETED`（软删除）。
- `com.superagent.core.agent.AgentStore`（SPI，仿 `ChatStore` 的 session 部分）：
  `create / find / list / update`（软删除走 `status=DELETED`，经 `update`）。

### 2) ChatSession.agentId + AgentRunRequest.systemPrompt（core）

- `ChatSession` 增加可空字段 `agentId`；同步 3 个 `with*`（`withAgentSessionId` /
  `withTitle` / `touched`）保持 `agentId`。
- `AgentRunRequest` 增加 `systemPrompt` 字段（第 9 个分量，可空）。为空表示不注入 persona。
- `ChatStore` 增加 `listSessionsByAgent(agentId)`：按 agent 查询其 session（二期 1:N 的基础；
  一期用于解析 Agent 的唯一 session）。

### 3) 存储（storage）

- 新增 `AbstractJdbcAgentStore` + `MysqlAgentStore` + `ClickHouseAgentStore`，严格仿照
  `AbstractJdbcTaskStore`/`MysqlTaskStore`/`ClickHouseTaskStore`：
  - MySQL：内联 `CREATE TABLE IF NOT EXISTS agent`，`update` 用 `ON DUPLICATE KEY UPDATE`。
  - ClickHouse：`ReplacingMergeTree(updated_at)` + 读 `FINAL`，`update` = 重新插入新版本；
    DDL 放 `db/clickhouse/schema.sql`。
- `ChatSession.agentId` 列：改 `AbstractJdbcChatStore`（insert/mapper/新增 by-agent 查询）、
  MySQL 内联 `chat_session` DDL、ClickHouse `db/clickhouse/schema.sql` 的 `chat_session` 表。
  列可空。
- `JdbcStorageConfiguration` 注册 `AgentStore` bean（MySQL/ClickHouse 两套，与 `TaskStore` 一致）。

### 4) API 契约（api）

- 新增 `agent/AgentDto`（`id,name,persona,model,status,sessionId,createdAt,updatedAt`；
  `sessionId` 为一期 1:1 的归属 session，前端据此进入聊天）、
  `agent/CreateAgentRequest(name,persona,model)`、`agent/UpdateAgentRequest(name,persona,model)`。
- `chat/CreateSessionRequest` 增加可空 `agentId`。
- `chat/SessionDto` 增加 `agentId`（存量会话为 null）。

### 5) web

- 新增 `agent/AgentService` + `agent/AgentController`（`/api/v1/agents` CRUD + 软删除）。
  - **创建 Agent 时自动创建其归属 session**（经 `ChatService.createSession`，带 `agentId`），
    保证「点开 Agent 就能聊天」。
  - `AgentService` 经 `ChatService` 解析 Agent 的 `sessionId`。
- `ChatService`：
  - `createSession` 支持带 `agentId`（写入 `ChatSession.agentId`）。
  - `runTurn` 组装 `AgentRunRequest` 时，按 `session.agentId → AgentStore` 取 persona 放入
    `systemPrompt`；agent 不存在或 persona 空则传 null。同时若 Agent 配置了 `model` 则作为本轮
    模型覆盖（否则沿用全局默认）。
  - `toDto` 回填 `SessionDto.agentId`。
- `TaskService` 组装 `AgentRunRequest` 处补 `systemPrompt=null`（保持现状）。

### 6) worker

- `ClaudeCliAgentRunner.buildCommand`：当 `request.systemPrompt()` 非空时，在 `--` 分隔符之前
  追加 `--append-system-prompt <persona>`。

### 7) 前端（静态资源）

- 侧边栏从「对话列表」改为「**Agent 列表**」（只展示名字，无头像）。点 Agent 进入它底层的
  session 聊天，聊天交互**完全不变**。
- 「+ 新建对话」改为「**+ 新建 Agent**」，弹出表单：名字 / 特点（persona，多行）/ 默认模型（可空）。
- 侧边栏的重命名/删除作用在 **Agent** 上（重命名改名、删除软删 Agent）。
- 存量没有 agentId 的老会话：仍可通过会话接口打开、不报错；不在新 Agent 列表里自动出现
  （不做自动归属）。
- `i18n.js` 补中英文案；`app.css`/`index.html` 视需要微调。
- 前端是静态资源，服务器从 `~/.m2` 的 JAR 读，改完需 `mvn -pl superagent-web install -DskipTests`
  重新打 JAR 生效。

## 存量兼容

- 老 `chat_session` 行 `agent_id` 为 NULL：`ChatSession.agentId=null`，`getSession` 正常返回、
  `runTurn` 走「无 persona」分支（`systemPrompt=null`），不报错。
- 不做数据迁移、不自动把老会话归属给任何 Agent。

## 验收

- `mvn clean install` 全量通过；新增收敛单测覆盖：Agent 存取（round-trip + 软删除过滤）、
  session 关联 `agentId`、persona 注入到 `AgentRunRequest.systemPrompt` 并被 CLI 命令携带。
- 新建 Agent（名字+特点）→ 侧边栏出现 → 点进去聊天 → persona 作为 `--append-system-prompt`
  注入底层 CLI（代码/测试层面可验证）。
- 老会话 `agentId` 为空仍可正常打开。
- Agent 聊天里仍能通过 MCP 创建/指挥 task（现有能力不回归）。
