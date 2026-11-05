# PRD — 任务分类 + 编码任务三步流程

## 背景

SuperAgent 的任务系统当前是"单轮执行":`create → 一次 agent 调用 → 写 output.md → 结束`;进度条是假的启发式(每个 `tool_use` 事件 +5%)。所有任务不分类型,用同一套 `wrapBrief` 模板跑一轮。

我们要给任务引入"类型"的概念,并为**编码任务**固化一套**后端强制**的三步流程(PRD → 开发 → 验收),在任务详情页结构化展示阶段状态、PRD 与验收证据。本期只实现 `CODING`,其余类型占位,行为保持现状。

现状关键事实(已核对):
- `Task`(core,record)已带 `flow` 字段(字符串),全程闲置,DB/DTO/mapper 都已带它 → **零迁移复用它存 kind**。
- `TaskService`(web)有会话池 `TaskSessionPool`:任务优先复用空闲会话(`--resume` 续同一会话上下文),`NEEDS_CONFIRMATION` 时 `pause`(保留会话),终态时 `release`。阶段之间续同一会话天然契合这套机制。
- `AgentRunRequest` 已支持 `--resume`(`agentSessionId` 非空即续)。
- 前端静态资源已内置 `marked.min.js` + `purify.min.js`,可渲染 PRD markdown。

## 需求

1. **任务分类枚举** `TaskKind { CODING, WRITING, RESEARCH, STATS, GENERAL }`,以小写名存入 `Task.flow`(零迁移)。
2. **分类来源**:
   - `McpController.create_task` 增加可选参数 `kind`。
   - 关键词兜底分类器 `TaskKindClassifier`(代码/接口/bug/重构/编译/实现→coding;写作/文案/报告→writing;统计/报表/数据→stats;调研/研究/对比→research;否则 general)。未显式给 kind 时用它判。
   - 前端任务表单加 kind 下拉(默认"自动")。
3. **编码任务固化三步**(真多轮,每步一轮 agent,阶段间 `--resume` 续同一会话):
   - 第1步 PRD:prompt 要求产出 PRD(markdown:背景/需求/技术思路),**存 DB**(不落文件)。门禁:没拿到 PRD → 自动纠偏重试一次,仍没有 → `NEEDS_CONFIRMATION("第1步未产出 PRD")`。
   - 第2步 开发:resume,实现改动。
   - 第3步 验收:resume,跑测试/构建/curl;前端任务尽量 Playwright 截图,无无头浏览器则如实降级为"构建通过 + 关键 DOM 断言",不编造。门禁:无验收证据 → `NEEDS_CONFIRMATION("缺少验收证据")`。
   - 任一阶段 agent 仍可输出 `NEEDS_CONFIRMATION:` 暂停;用户回复后 `resume` **当前阶段**(记住停在哪一步)。
   - 进度条真实映射:PRD 完 33% / 开发完 66% / 验收完 100%。
4. **阶段状态 + PRD 存 DB(不落文件)**:新增 `task_phase` 表(`task_id` 主键、`phases_json` TEXT/MEDIUMTEXT、`updated_at`),一行存该任务阶段数组 JSON,PRD markdown 放在 PRD 那一步的 `content`。core 加 `TaskPhaseStore`(只存原始 JSON 字符串,不引 Jackson);storage 加 `AbstractJdbcTaskPhaseStore`+`Mysql*`+`ClickHouse*`,严格仿照现有 TaskStore 三件套。web 用 Jackson 序列化 phases。
5. **详情页展示**:`TaskDetailDto` 加 `phases: List<TaskPhaseDto>`(key/name/status/content/evidence),`detail` 从 store 填充;前端详情页加三步进度条 + 每步状态 + PRD(markdown 渲染)+ 验收证据。
6. **权限**:不跳过权限。`superagent.task.allowed-tools` 白名单加编码所需工具(Read,Glob,Grep,LS,Edit,Write,Bash),`skip-permissions` 保持 false。

## 技术思路

### 分层
- **core**:`TaskKind`(含 `fromStorage`/`storageName`)、`TaskKindClassifier`(纯静态关键词)、`TaskPhase`(record)、`TaskPhaseStatus`(PENDING/RUNNING/DONE)、`TaskPhaseStore`(原始 JSON 字符串 SPI)。
- **api**:`CreateTaskRequest` 追加 `kind`;新增 `TaskPhaseDto`;`TaskDetailDto` 追加 `phases`。
- **storage**:`AbstractJdbcTaskPhaseStore` + `MysqlTaskPhaseStore`(内联 DDL + `ON DUPLICATE KEY UPDATE`)+ `ClickHouseTaskPhaseStore`(`ReplacingMergeTree(updated_at)` + `FINAL`);`db/clickhouse/schema.sql` 加 `task_phase`;`JdbcStorageConfiguration` 注册两个 bean。
- **web**:`TaskService` 承担编排 + 门禁;`McpController` 加 kind;`flows/coding.md` 节奏文案注入 prompt。
- **worker**:无需改。

### TaskService 编排(核心)
把现有单轮 `runTurn` 的"跑一轮 agent 并落事件/消息"抽成可复用的 `executeTurn(...) → TurnResult{finalText, cost, cancelled}`(不 settle)。再分派:
- 非编码 → 原 `runTurn` 逻辑(保留 `nudgeProgress` 假启发式)。
- 编码 → `runCoding`:从"第一个非 DONE 阶段"开始,逐阶段 `executeTurn`。
  - 每轮后:取消→STOPPED;`NEEDS_CONFIRMATION:` 标记→停在当前阶段、settle 确认;否则过阶段门禁。
  - PRD 门禁:内容空→纠偏重试一次→仍空→`NEEDS_CONFIRMATION("第1步未产出 PRD")`;否则 `content=PRD`。
  - 验收门禁:证据空→`NEEDS_CONFIRMATION("缺少验收证据")`;否则 `evidence=最终回复`。
  - 过门禁→阶段 DONE,进度 33/66/100。
- `kind` 由 `Task.flow()` 派生(`TaskKind.fromStorage`),无需给调度链路加参数。`wrapBrief` 标志区分"初次执行"(用阶段 prompt)与"用户回复 resume"(用户内容作当前阶段首轮 prompt)。
- 整个 `runCoding` 期间会话保持 BUSY;只有 settle(确认→pause / 终态→release)触碰会话池,与现有机制一致。阶段进度边界:PRD running 10 / done 33;DEV done 66;ACCEPT done 100。

### Phase 持久化
`TaskPhaseStore` 只存 JSON 字符串;web 用 `ObjectMapper().findAndRegisterModules()`(带 parameter-names module,正确反序列化 core record)做 `List<TaskPhase> ↔ JSON`。`create` 时若 CODING 则初始化三条 PENDING 阶段。

### 风险/取舍
- 阶段状态仅 PENDING/RUNNING/DONE;任务级 `NEEDS_CONFIRMATION` 已表达"暂停",当前 RUNNING 阶段即"停在哪一步",足够续跑且收敛。
- storage DB 测试用 H2(MySQL 兼容模式)跑 `MysqlTaskPhaseStore` 真实 save/find/delete。

## 验收
- `mvn clean install` 全量通过。
- 收敛单测:`TaskKindClassifier` 分类;编码三步状态流转(含门禁触发 `NEEDS_CONFIRMATION`);phases DB 存取。
- `kind=coding` 能看到 PRD(DB)+ 三步状态;非编码行为不变。
- `detail` 返回 phases;前端详情页有三步进度条 + PRD 渲染 + 验收证据。
