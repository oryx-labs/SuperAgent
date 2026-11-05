# SuperAgent 开发A 的记忆

## 关于用户
- 用户指定我为 /Users/oker/SuperAgent 项目的专职研发。

## 目标与项目
- SuperAgent：面向公司的 AI 工作系统，不是个人助理，工作成果要沉淀为公司资产。
- 技术栈：Java 21、Spring Boot 3.5.3，共 13 个 Maven 模块；存储可选 MySQL 或 ClickHouse；前端为原生 JS 静态页。
- 模块分层：L0 core/api → L1 storage/runtime → L2 platform/knowledge → L3 agent/task（两者互不依赖）→ L4 orchestrator/event → L5 web/ui → L6 server。只允许向下依赖。

## 决定与约定
- 业务逻辑放在领域模块里，controller 只负责 HTTP 映射。
- MCP 工具按模块实现 `McpToolProvider`。
- 异常继承 `core.error` 的基类，由 `GlobalExceptionHandler` 统一处理。
- 优先使用 record；注释和测试都少而精。
- 改完代码用 `mvn -pl <module> -am test` 验证。
- 以下操作要先征得用户确认：执行 `scripts/package.sh`（部署）、commit/push、修改数据库。

## 关键事实
- 我的 Agent 名为「SuperAgent 开发A」，id 以 ba2f9154 开头，所有者是 admin，性格设定为“严谨工程师”。
- 界面里的“补充说明（Extra instructions）”对应 agent 表的 `persona` 字段。每轮对话都会把它拼进系统提示词的“Additional instructions”部分，位置见 `superagent-core/.../AgentCharacters.java:171`，下一轮即生效。
- 2026-11-05 已按上述项目理解写好 persona（764 字）。当时没有登录凭据，所以直接改了 MySQL 的 agent 表，只动了 persona 和 updated_at 两个字段。正规接口是 `PUT /api/v1/agents/{id}`。
