# SuperAgent 的记忆

## 关于用户
- 用户在筹备「SuperAgent 行动营」课程，自己的写作 skill 放在 `/Users/oker/Desktop/doc/写作 skills`。

## 目标与项目
- SuperAgent 行动营：4 周、16 节课，带学员做出一个可运行的企业 SuperAgent 最小系统，不承诺生产级能力。
- 课程面向有软件工程经验、用过 AI 编程工具的开发者，实现语言以 Java 21、Spring Boot、MySQL 为主，Agent 供应商可替换。
- 课程从 DeepSeek 的《Super-agent 设计探索》出发，但不声称复刻 DeepSeek 内部系统，也不声称获得官方授权。
- 四层职责：入口、控制面、Router、Worker。主链路：请求 → Task → 分派 Worker → 真实 Coding Agent → 事件/用量回写 → 人工验收。
- 主场景是知识付费公司的研发任务，例如「会员平台续费提醒」。内容生产是迁移练习。
- 工程素材是现有 SuperAgent 仓库（Java/Spring 多模块、Chat/Task 服务、Claude CLI 适配、事件流、初步 MCP）。开课前要把它整理成可复现的教学起点。

## 决定与约定
- 三个写作 skill 的分工：
  - oker-writing-style：技术博客、架构说明、项目复盘，已去掉课程章节。
  - ai-course-writing：课程定位、大纲、行动营方案、验收、作业。「中央判断」「能力证据」只用来理清思路，不写进正文。
  - ai-course-writing-original：单节讲义、视频讲稿、教学文章，是行动营 16 节正文的默认文风。大纲只在用户明确要求时才用这个文风。
- 成员「行动营作者」由「Skill 导入员」改名而来，专门写行动营，挂了 ai-course-writing 和 ai-course-writing-original 两个 skill。oker-writing-style 不挂给任何成员。
- 平台 skill 只有单份 Markdown 正文（上限 200K 字符），references 要内联进正文。Codex 专用的 openai.yaml 不导入。
- 整理文档进知识库时只改格式，不改正文措辞，保留所有【待确认】。
- 补教学仓库代码不归「行动营作者」，需要时另提计划，加一名工程成员。

## 进行中与待办
- 等用户回答《开写前确认清单》（04 号文件）里的 21 项（A1、B1～B5、C1～C5、D1～D8、E1～E2）。要先答的三项和助手的建议：
  - A1 写作顺序：建议 B，先出第 01 节样稿。
  - D2 Attempt、Artifact、Router 三个对象：建议 C，前两个补代码，Router 只讲职责。
  - D3 教学仓库：建议 A，从当前仓库裁剪。
- 其余各项已给出默认方案，用户回「其他按默认」就按默认执行：
  - C1 课程名用「SuperAgent 实战营」，需用户亲自确认，会影响宣发。
  - C2 我们的系统写「SuperAgent」，提到 DeepSeek 文章时保留原文「Super-agent」。
  - 「你好，我是 Robert」只在第 01、16 节用。每节 4000～7000 字，视频 20～30 分钟。
  - D1：第 10 节改成「交给下一讲（第 11 节）的 Task」。
  - D4：每节对应一个 Git tag。
  - D5：Coding Agent 主线用 Claude Code，第 13 节 IM 用飞书，教学仓库补 Docker Compose。
  - D6：补一个最小的 MCP Client。
  - D7：删掉 Muse、OpenClaw、Hermes Agent、Agent OS 这几个没有出处的参照。
  - D8：标题和周名以大纲为准，宣发随后同步。
  - E1：讲义写成完整版，不依赖直播。
  - E2：验收只写提交清单，暂不做评分表。
  - 讲义不写讲师个人经历。讲师履历由讲师本人校对。
- 收到答复后的下一步：先出大纲修订稿（05 号文件），再写第一周讲义。
- 宣发物料还有不一致要用户拍板：价格（旧稿 ¥399/¥699、VIP 五折）、角标、直播场次。
- 3 个写作 skill 的核对只到首尾、标题、代码块、表格、分隔线计数，没做逐字比对。以后平台把正文写进工作区时，拿那份和源文件逐字比一遍。
- 团队原有成员张三、李四、王五不见了，已提醒用户确认是否有意删除，尚无回复。

## 关键事实
- 平台 skill id（分类「写作」）：
  - oker-writing-style：`ba489068-8537-4798-9a91-0acf25f117eb`
  - ai-course-writing：`0a356a5a-f7b2-4137-99ac-8be4e8a36e9d`
  - ai-course-writing-original：`4174c2a3-1dd3-4409-b991-b6edc8b799b3`
- 平台原有 skill：写作规范、画图规范（设计师）、运营文案。
- 导入用的 skill 文件在工作区 `skills-build/`。三份原文原样存在工作区 `raw/` 目录。
- 知识库文件夹「DeepSeek Super Agent 行动营」里的文件：00-索引与待确认清单、01-课程大纲-讨论稿、02-宣发物料-旧版、03-宣发物料-新版整理稿、04-开写前确认清单（都是 .md）。
- 知识库在磁盘上的路径：`/Users/oker/SuperAgent/superagent-server/data/knowledge/admin/DeepSeek Super Agent 行动营/`。
- 仓库和大纲的差距：
  - 仓库里没有：Attempt、Artifact、Router 的类，也没有 Docker Compose 文件（大纲第 9 节要用）。
  - 仓库里已有：Task 状态机、`TaskWatchdog`、`ClaudeCliAgentRunner`、`CodexCliAgentRunner`、飞书事件入口、`McpController`（MCP Server）。这些在 `superagent-worker`、`superagent-web` 两个模块下，另有 `superagent-event` 模块。
