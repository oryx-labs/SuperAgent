# Admin 公司视角：现状差距与第一期 PRD

2026-11-21，只读代码。**事实**带 `文件:行`；**推测**单独标注。工作区有他人未提交改动，行号可能偏移。

## 0. 结论与约束

- 11 项：「部分有」6、「没有」5、「已有」0。第一期做 P0 三项，范围不调整。
- 分层：下层不依赖上层；业务逻辑在领域模块，Controller 只转发；异常上抛 `GlobalExceptionHandler`。
- 租户：新表都带 `company_id`，查询走 `TenantContext.require()`，后台线程放进 `TenantContext.run`。
- 新建 `superagent-admin`（L4）：交接要同时动 agent 与 task（平级、互不依赖），只能放其上。
- **不做**：手动维护组织架构、员工对话全文监控、复杂 BI 编辑器。

## 1. 现状（共用：管理员是角色串 `ADMIN`，`User.java:38`、`SecurityConfig.java:71-72`；归属字段存用户 id：Agent `Agent.java:31`、文件 `WorkFile.java:26`、技能 `Skill.java:18`、任务 `Task.java:32`、自动化 `Automation.java:27`）

### 1.1 离职交接｜没有｜L
- 事实：停用只改 `enabled`（`UserService.java:78-103`），删除不转交（`:105-109`）；调度按 `createdBy` 分队列（`TaskService.java:362`）。
- 查过：`transfer|handover|handoff` 在 core、platform、task、agent、skills、files、project 的 main 代码，仅命中无关项（`UsageContext.java:21`）。
- 推测：运行中任务驻留会话池（`TaskService.java:326`），热改归属会错位。
- 差距：清单、转移、强制交接。模块表：新模块 admin；新表 `handover`、`handover_item`。

### 1.2 预算额度｜部分有｜M
- 事实：`QuotaService` 有账号数、并发数、月 token 上限（`QuotaService.java:28`），调用点 `ChatService.java:588`、`TaskService.java:812,990`；超限只抛异常；仅公司一档，无页面可改（`maxUsers|monthlyTokens` 在 web、ui 无命中）；费用有 `costUsd`（`UsageRecord.java:11`）。
- 差距：金额口径、三级预算、提醒、降级/暂停、配置页。模块表：platform、cost、core、storage；新表 `budget`、`budget_alert`。

### 1.3 公司看板｜部分有｜M
- 事实：分析页 `AnalyticsService.java:48`；公司用量页 `UsageController.java:44`、`TokenUsageService.java:148`；活跃人数=7 天建过任务（`AnalyticsDto.java:6`）；分析页花费取 `Task.spend`（`AnalyticsService.java:73`），与 `costUsd` 口径不同；无部门排行、无交付物计数。
- 差距：上月对比、部门排行、交付物、统一口径。模块表：admin；不加表。

### 1.4 分级管理员｜部分有｜L
- 事实：仅 `ADMIN` 一档；组织模型有上级与团队（`OrgStore.java:8-29`），只读，`setManager/setTeams` 无调用方（`AbstractJdbcOrgStore.java:56,81`，`OrgService.java:16`）。
- 差距：角色与范围过滤；先决条件是外部组织同步。模块表：platform 与各列表接口；新表（角色/负责人标记）待产品拍板，推测需 `admin_scope`。

### 1.5 技能运营｜部分有｜M
- 事实：个人/公司两层（`SkillService.java:331,481`），可改 `space` 提升（`:97`）；无审批状态；无使用统计（`SkillStore.java:24` 只有挂载）。
- 差距：申请、审批、使用统计、闲置提示。模块表：skills；新表 `skill_request`、`skill_use`。

### 1.6 审计日志｜没有｜M
- 事实：仅 Agent 运行日志（`AgentLogService.java:30`），Agent 所有者可看（`AgentLogController.java:28`）；管理动作只写应用日志（`UserService.java:74`）。
- 查过：agent、core 的 main 代码搜 `audit`，仅无关命中（`ProviderCatalog.java:58`）。
- 差距：审计表、记录点、导出。模块表：admin；新表 `audit_log`。

### 1.7 使用策略｜没有｜M
- 事实：可用模型全公司一份（`TaskModelRouter.java:65`）；单任务上限仅编码审查（`SettingsService.java:25`）。
- 查过：core、platform、cost、task 的 main 代码搜 `allowedModels|policy`，无相关命中。
- 差距：按部门白名单、通用成本上限。模块表：platform、cost；新表 `usage_policy`。

### 1.8 老板周报｜没有｜M
- 事实：自动化支持 cron（`AutomationService.java:95`）；站内通知 `NoticeService.java:141`、飞书推送 `InboxNotifyRequested.java:4`。
- 查过：task、event、platform、agent 的 main 代码搜 `weekly|周报`，仅提示词文案。
- 差距：生成与推送，依赖 1.3。模块表：admin，线程放 `TenantContext.run`；不加表。

### 1.9 ROI 视图｜没有｜S
- 事实：用量带 `taskId`、`agentId`（`UsageRecord.java:11`），文件带 `taskId`（`WorkFile.java:28`）。
- 查过：web、platform、project、cost 的 main 代码和 `usage.js` 中无按项目汇总接口或页面；推测任务是否挂项目条目，需读 `ProjectItemStore`。
- 差距：按项目汇总页。模块表：admin；不加表。

### 1.10 系统健康｜部分有｜M
- 事实：Key 池有优先级、切换、复查（`CredentialService.java:161,170,233`），状态字段 `ProviderCredential.java:19`；任务有 `startedAt`（`Task.java:35`）；无失败率与排队时长聚合。
- 差距：失败率、排队时长。模块表：admin；新表 `model_call_outcome`（推测）。

### 1.11 资产盘点｜部分有｜M
- 事实：资产在各自表（`AgentStore.java:17`）；分析页只有数量（`AnalyticsDto.java:9`）；用户状态 `User.java:42`；最近使用时间仅任务、用量有。
- 差距：分布页、孤儿与闲置。模块表：admin；不加表。

## 2. 第一期 PRD

### 2.1 离职交接
- 故事：管理员先看清单再确认，把离职人资产转给接手人；不交接不能停用。
- 功能：①清单五类：Agent（SuperAgent 不转）、文件、个人技能、进行中任务、自动化；另列协作与项目成员身份（移除）、个人连接凭证（撤销）。②预览→确认→逐项执行，失败可重试。③`RUNNING` 任务不热转，`PENDING/NEEDS_CONFIRMATION/WAITING` 可转。④停用或删除前须有完成的交接，否则 409。⑤通知接手人与管理员。
- 数据：`handover(company_id,id,from_user,to_user,status,created_by,created_at,finished_at)`；`handover_item(company_id,handover_id,kind,item_id,item_name,status,message)`。
- 接口：`POST /api/v1/admin/handovers/preview`、`POST /api/v1/admin/handovers`、`POST …/{id}/retry`、`GET …`；`PUT/DELETE /api/v1/users/{id}` 加检查。
- 页面：用户页「交接并停用」对话框（选人→清单→确认→结果）、「交接记录」标签。
- 权限：`ADMIN`；接手人为同公司另一启用用户。
- 验收：①预览与库一致，转后接手人可见。②`RUNNING` 被拒并说明。③未交接停用返回 409。④部分失败记 `PARTIAL`，重试只处理失败项。⑤成员身份移除、凭证撤销、两方各收通知。⑥隔离测试：他公司不可见。

### 2.2 预算额度
- 故事：老板设公司/部门/个人月预算，到 80% 提醒，到 100% 按策略处理。
- 功能：①美元月预算三级，任一到顶触发；部门按团队路径，花费计入所在团队及上级，无组织数据时隐藏。②80% 每范围每月只提醒一次（`budget_alert` 去重）：通知管理员（个人档含本人），公司档加飞书。③100% 策略 `NONE`（默认）/`DOWNGRADE`（新任务只用「用于任务」里最便宜模型且不升档，对话用最便宜可用模型）/`PAUSE`（拒绝新对话与新任务，排队不调度，调高预算或下月恢复）。④扩展 `QuotaService`：保留原三项；在 `checkTokens`（`QuotaService.java:74`）旁加 `checkBudget(user)`，于 `ChatService.java:588`、`TaskService.java:812` 调用，`TaskService.java:990` 对 `PAUSE` 不放行；`UsageStore` 加按人金额 SQL 聚合，缓存 1 分钟。⑤每 5 分钟扫描，放进 `TenantContext.run`。⑥用量页「我的」显示已用/预算。
- 数据：`budget(company_id,scope_type,scope_id,monthly_usd,on_limit,updated_by,updated_at)`；`budget_alert(company_id,scope_type,scope_id,month,level,at)`。
- 接口：`GET/PUT /api/v1/admin/budgets`、`DELETE /api/v1/admin/budgets/{type}/{id}`；`GET /api/v1/usage` 加本人 `budget`。
- 页面：管理员「预算」页（三区：公司/部门/个人，进度条、策略下拉）；用量页预算条。
- 权限：配置 `ADMIN`；看自己=本人。
- 验收：①公司预算 10 美元，过 8 美元时管理员收站内通知与飞书，同月不重复。②`PAUSE` 到顶后新对话/任务被拒，调高后恢复。③`DOWNGRADE` 选最低价模型且不升档。④无组织数据无部门入口。⑤UTC 月切换清零。⑥原三项限额不变，`QuotaServiceTest` 通过。⑦隔离测试通过。

### 2.3 公司看板
- 故事：老板一眼看到本月花费、趋势、谁用得多、产出了什么。
- 功能：①指标卡：本月花费及较上月涨跌、完成任务数、交付物数、活跃人数（本月有用量记录的不同用户，排除 SYSTEM）。②趋势：按日花费叠加完成任务。③按人、按部门排行（花费、完成任务、交付物）。④现有用量页加「产出」：公司范围（`views.js:117`）每人行加「完成任务」「交付物」两列，`/admin/usage` 响应不改，产出走新接口，前端按用户 id 合并。⑤花费统一取 `costUsd`，无价格记录提示条数。
- 数据：不加表；读 `UsageStore`（新增聚合）、`TaskStore`、`WorkFileStore`（新增按期计数）、`OrgStore`。
- 接口：`GET /api/v1/admin/dashboard?period=&tz=`。
- 页面：「看板」页；用量页两列。
- 权限：`ADMIN`。
- 验收：①花费=本月 `costUsd` 之和。②只聊天的人计入活跃。③按人合计=公司花费。④完成任务按 `finishedAt` 且 `DONE`，交付物按任务交付 `origin`。⑤无组织数据无部门排行。⑥非管理员 403，隔离通过。

## 3. 编码任务

| 任务 | 模块与文件 | 依赖 |
|---|---|---|
| T1 用量聚合 | core `UsageStore`；storage `JdbcUsageStore` | 无 |
| T2 预算服务 | core `budget/*`；storage `MysqlBudgetStore`；platform `BudgetService`、`QuotaService` | T1 |
| T3 执行挂钩 | agent `ChatService`；task `TaskService`；cost `TaskModelRouter`（开工前查他人改动） | T2 |
| T4 预算提醒 | platform `BudgetAlerts` | T2 |
| T5 预算接口页面 | api、web `BudgetController`、ui `budgets.js`、`usage.js`、`views.js`、`index.html`、`i18n.js`、`app.css` | T2 |
| T6 admin 骨架 | 根 `pom.xml`、`superagent-admin/pom.xml`、web/server pom；`CLAUDE.md` 模块表待用户确认 | 无 |
| T7 交接服务 | core `handover/*`；storage `MysqlHandoverStore`；admin `HandoverService`；agent/task/files/skills/project 各加转移方法 | T6 |
| T8 强制交接与页面 | platform `UserService`；web `UserController`、`HandoverController`；ui `handover.js` | T7 |
| T9 看板服务 | core `WorkFileStore`；admin `CompanyDashboardService`；web `DashboardController` | T1、T6 |
| T10 看板页面 | ui `dashboard.js`、`usage.js`、`views.js`、`index.html`、`i18n.js`、`app.css` | T9 |

顺序：先并行 T1、T6；再 T2、T7、T9；再 T3、T4、T5、T8、T10。三条线互不依赖。

## 4. 风险
- 组织同步缺失，部门维度第一期无数据。
- 对话降级无现成入口，T3 可能偏大（推测）。
- 美元是价格表估算；交接不热转运行中任务。
