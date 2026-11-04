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
- 3 个写作 skill 已导入平台，不挂给任何成员。
- 平台 skill 只有单份 Markdown 正文（上限 200K 字符），references 要内联进正文。Codex 专用的 openai.yaml 不导入。
- 整理文档进知识库时只改格式，不改正文措辞，保留所有【待确认】。

## 进行中与待办
- 3 个写作 skill 的核对只到首尾、标题、代码块、表格、分隔线计数，没做逐字比对。以后有任务用到这些 skill、平台把正文写进工作区时，拿那份和源文件逐字比一遍。
- 成员「Skill 导入员」已完成任务，是否删除等用户决定。
- 等用户拍板宣发物料里的不一致：
  - 课程名有三个版本，名字里带不带 "DeepSeek" 未定。
  - 角标、价格（旧稿 ¥399/¥699、VIP 五折）、直播场次不一致。
  - 第 01、02、03、16 节标题在大纲和宣发稿里不一致；"Super Agent" 和 "SuperAgent" 写法混用。
  - 讲师履历写「主导过」还是「参与」，需讲师本人确认。
  - 大纲第 10 节「交给第三周的任务接口」疑似笔误，应为「交给下一讲的 Task」。
- 可做的后续方向（用户未选）：定稿宣发物料、整理教学仓库、写第一周讲义和视频脚本、课程设计评审。
- 团队原有成员张三、李四、王五不见了，已提醒用户确认是否有意删除，尚无回复。

## 关键事实
- 平台 skill id（分类「写作」）：
  - oker-writing-style：`ba489068-8537-4798-9a91-0acf25f117eb`
  - ai-course-writing：`0a356a5a-f7b2-4137-99ac-8be4e8a36e9d`
  - ai-course-writing-original：`4174c2a3-1dd3-4409-b991-b6edc8b799b3`
- 平台原有 skill：写作规范、画图规范（设计师）、运营文案。
- 导入用的 skill 文件在工作区 `skills-build/`。
- 知识库文件夹「DeepSeek Super Agent 行动营」里有 4 个文件：
  - 00-索引与待确认清单.md
  - 01-课程大纲-讨论稿.md
  - 02-宣发物料-旧版.md
  - 03-宣发物料-新版整理稿.md
- 三份原文原样存在工作区 `raw/` 目录。
