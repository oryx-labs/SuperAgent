# 写作 harness 的文体包（交付说明）

接续任务 247b7678 的写作 harness（kind `article`）。流程不变：需求 → 资料 → 提纲（用户确认）→ 初稿 → 平台核对 → 独立评审 → 修改 → 交付，退回路由、2 轮上限、会话隔离、详情页都在原处。新增的是「文体包」：一个公司级 skill，正文给模型看，顶部一段配置给平台读。

## 改了什么

| 块 | 文件 | 做了什么 |
|---|---|---|
| 文体包配置 | `task/harness/article/GenrePack.java`、`GenrePackException.java`、`GenrePacks.java` | 解析并校验配置块；`GenrePacks` 从公司已启用的公司级 skill 里找文体包（按 skill 名或 genre 标识），把规范 skill 的正文接在文体包后面交给作者 |
| 证据日志 | `EvidenceLog.java` | 解析 `### E3 \| 代码/命令/数字/文档 \| …`；检查格式、代码引用能否解析 |
| 新核对项 | `TechChecks.java`、`MermaidCheck.java`，接入 `ArticleChecks.run` | 代码引用、证据编号、数字溯源、必备小节、具体度、禁用语、Mermaid |
| 只读命令 | `ReadOnlyCommands.java` | 复核时平台重跑证据日志里的命令：白名单、不经 shell、路径限在任务仓库内 |
| 提示词 | `TechPrompts.java`，`ArticlePrompts.java` 小改 | 调研、架构评审、复核员、实现者视角；需求提示按文体包生成 |
| 流程 | `ArticleHarness.java` | 需求步选包（可换包）；研究步分 collect / investigate；核对按配置挑项；评审角色按配置；`ReviewMerge.merge` 增加「保持原严重度」的 extra 项 |
| 选文体 | `CreateTaskRequest`（加 `genre`）、`TaskHarness.start(task, delivery, genre)`、`TaskKinds`、`TaskMcpTools`（`genre` 参数、`article_genres` 工具）、`TaskConfirmPrompt`（确认表「文体」行） | |
| 详情 | `HarnessDto.Article` 末尾加 `genre`、`evidence`；`HarnessState.Article` 加 `genre/pack/guide/evidence` | |
| 内置 skill | `skills/GenreSeeds.java`、`SkillService.seedGenresOnce/companySkills` | 4 个文体包 + 3 份共用规范，每个公司一次（独立标记 `skill.genres.seeded`，老公司也补；删了不再补回） |
| Mermaid 预览 | `static/vendor/mermaid.min.js`（11.17.0，来自本机 `difyPro` 的 npm 包，MIT）、`js/mermaid-view.js`、`files.js`、`task-article.js`、`i18n.js`、`app.css` | 首次遇到图时才加载本地库，不走外部地址；语法错的图显示源码和原因 |

## 文体包配置（schema）

写在 skill 正文最前面，`---` 包起来的 YAML。字段不对，在加载时报错，信息里有 skill 名和字段名（例：`文体包「事故复盘」的配置有误：字段 colour：未知字段，可用的有：…`）。

```yaml
genre: tech-design               # 必填：文体标识，40 字以内
research: collect | investigate  # 默认 collect；investigate = 读仓库做调研，产出证据日志
needs_repo: true | false         # 默认 false；true 时任务必须有可用的本地仓库，否则需求步直接失败
word_range: [3000, 20000]        # 默认 [500, 30000]；0 < 最少 ≤ 最多
required_sections:               # 必填，至少一项；每项是标题，或可接受的几个标题的列表（标题里含其一即可）
  - [背景]
  - [备选方案, 备选]
checks: [code_refs, evidence_ids, numbers, mermaid, concreteness, banned_phrases, links, wechat]
reviewers: [architect, verifier, implementer]   # 每个位置最多一个：editor|architect（打分，必须有）、fact_checker|verifier、reader|implementer
weights: 技术文章                 # 打分权重：公众号 | 报告 | 技术文章 | 讲义，默认 报告
max_open_questions: 5            # 实现者的待澄清问题超过它则评审不通过，默认 5
min_sources: 1                   # collect 模式有资料时至少读到几份，默认 1
standards: [技术画图规范]          # 一并交给作者的其他 skill（要存在，否则加载失败）
banned_phrases: [赋能]            # 加进平台禁用语表
```

校验：未知字段、未知检查项、未知评审角色、同一位置选两个、没有打分角色、`required_sections` 缺失或为空、`word_range` 写反、`evidence_ids` 配了却不是 investigate，都会拒绝。

### 各核对项怎么判

| 检查 | 失败（退回修改） | 警告（只进修改单） |
|---|---|---|
| 代码引用 `path:行号[-止][#符号]` | 文件不存在、路径出仓库、行号范围无效、符号不在那几行 | 任务没有本地仓库，核对不了 |
| 证据编号 `[E3]` | 引用了日志里没有的编号；有日志却一个都没引用 | 日志里的某条没人引用 |
| 数字溯源 | 找不到的数字占 20% 以上（且至少 2 个） | 有找不到的数字 |
| 图 | 语法错、没有「图 N　标题」、编号不对、正文没引用、节点超过 15 | 图里的名字多数在正文没出现 |
| 必备小节 | 缺一个 | — |
| 具体度 | 某个一级小节没有数字/标识符/路径/版本/命令；「提升性能」之类的话同一句里没有数字 | — |
| 禁用语 | 至少 3 处且每千字 3 处以上 | 有 1 处（带句子位置） |

阈值是第一版的猜测，没有用样文校准（已知缺口）。

## 决定

- **一套流程，包是配置**。没有选到包（公司没有任何文体包，或老测试里的 `new ArticleHarness()`）时走原来的流程，原有测试一字未改。
- **评审角色沿用原来的三个位置**：架构评审 = 编辑位（任务模型，可只读读仓库）；复核员 = 事实核查位（检查模型，拿到证据日志和代码引用指到的行；平台另外重跑最多 3 条日志里的命令，输出对不上就是「不成立」）；实现者 = 读者位（检查模型，列开工前必须问作者的问题）。每个都是新会话。
- **实现者的问题**：不超过上限只记录（建议）；超过上限，每个问题都要答，另加一条严重项。
- **文体选择**：`create_task` 的 `genre` 可选；不填、填错都落到「通用长文」并记一条 note；需求步里作者可以说「文体不合适」（`NEEDS_CONFIRMATION` 提问），也可以在 `文体：` 一行写别的包，立即换。
- **文体包正文不靠「作者自己去读 skill」**：平台把包的正文和它的规范直接放进提纲、初稿、重写和调研的提示里。
- **只读命令**：不用 `sh -c`；每段命令直接起进程，管道在 Java 里接；白名单工具；`..`/绝对路径/符号链接指到仓库外都拒绝；git 只允许读子命令，并关掉外部 diff、textconv、pager。
- **范围外**：SuperAgent 自己的 `assign_task` / `start_task` 没有 `genre` 参数（见下文真实运行中的方案文档），目前落到通用长文。

## 验证

`mvn -q -pl superagent-task -am test`、`-pl superagent-skills -am`、`-pl superagent-agent -am`、`-pl superagent-web -am`、两个 `node --check`：最终运行（含最后一次提交）：四条 mvn 命令退出码都是 0；`node --check files.js`、`task-article.js` 退出码 0。

新增测试：

| 测试 | 覆盖 |
|---|---|
| `TechChecksTest`（10 个） | 每个检查的通过和失败/警告；代码引用用本仓库的真实路径（行号从文件里现查） |
| `GenrePackTest`（5 个） | 合法配置、16 种非法配置的信息、公司自建的包不改代码就被找到、规范缺失报错、内置 4 个包都能通过解析 |
| `ReadOnlyCommandsTest`（3 个） | 允许/拒绝的命令（含 `-ofile`、`-nOless`、`--pre=`、`..`、`$()`），在仓库里真跑 `cat pom.xml \| head`，证据日志解析和每类条目的问题 |
| `ArticlePackHarnessTest`（9 个） | investigate 全流程（证据日志、架构/复核/实现者各自新会话与模型、平台重跑命令）；没有证据日志进不了提纲；证据里的代码引用无效时让作者重写一次；待澄清问题太多不通过、一条只记录；命令输出对不上 → 轮次用完进待确认；架构评审的结构问题仍退回提纲；缺仓库立即失败；公司自建的「事故复盘」包（自己的小节、检查、评审角色）；配置错误报错、未知文体回退通用长文 |
| `SkillServiceTest` 新增 | 老公司补种 7 份、可编辑、删除后不补回、第二次启动不重复 |
| `TaskKindTest`、`TaskServiceTest`、`TaskConfirmPromptTest` | `genre` 参数和工具、确认表「文体」行、任务带着文体建成、**链接预读后文体不丢（真实运行里发现的缺陷，已修）** |

Mermaid 预览：用 jsdom（不是真浏览器）加载真实 `index.html` 和脚本，`docs/writing-samples/mermaid-preview-jsdom-verify.js` 渲染一份带 3 张图的 Markdown：2 张生成了 `<svg>`（含 `TaskService`、`ArticleHarness`、`SkillService` 等节点名），1 张语法错的显示为源码并带错误说明；页面没有外部地址的脚本或请求。jsdom 没有排版，脚本里给 SVG 补了 `getBBox` 等，所以版面效果没有看过，要在真浏览器里看一眼。

## 两篇真实文档

用 `RealRunDriver`（只在 `-Dsa.real=research|design` 时跑）把真实的 `ArticleHarness` 和 4 个内置文体包接上 Claude Code CLI：作者和架构评审用默认模型，复核员和实现者用 sonnet，每个评审一个新会话；在本仓库上各跑一次。和平台实际运行不同的地方：用户的回答（确认需求、确认提纲）由驱动代答，没有人看提纲；作者用 Write 工具写稿而不是 `file_write`；没有 MySQL、没有任务页。

| | 技术调研 | 技术方案 |
|---|---|---|
| 题目 | Mermaid 语法校验：现有 Java 轻量校验够不够，要不要换官方 parser | SuperAgent 的 assign_task 和 start_task 支持指定写作文体 |
| 文档 | `docs/writing-samples/技术调研-Mermaid语法校验.md` | `docs/writing-samples/技术方案-assign_task指定文体.md` |
| 证据日志 | 37 条 | 62 条 |
| 平台核对 | 6 次，最后一次通过（剩 1 项警告） | 10 次，最后一次通过（剩 5 项警告） |
| 评审 | 4 轮，69–74 分，最后一轮 24 个问题，停在「等用户确认」 | 5 轮，76–77 分，最后一轮 22 个问题，停在「等用户确认」 |
| 路由 | 1 次补资料、2 次修改 | 1 次退回提纲、1 次补资料、修改若干次 |

### 对着规则逐条看

**扎实、具体**：两篇都做到了。技术调研里每个判断都有证据编号（36 个不同编号被引用），对比表的格子写依据或「未验证」，没有「较好」「一般」；方案文档引用了 18 处 `path:行号`，59 个证据编号。平台的代码引用核对在运行中真正起了作用：作者先后写了 `MermaidCheck.java:29-30`、`files.js:231` 这种只有文件名的引用，被判失败后改成了从仓库根算起的路径。方案文档还找到了一个我自己改动里的真实缺陷（`TaskService.withMaterials` 丢 genre），已修并加了测试。

**没有 AI 味**：禁用词（值得注意的是、赋能、全方位、综上所述等）在两篇里一个都没有；每节结论先行；没有总结段。句子偏长：调研文档中途有 16% 的句子超过 60 字（警告，最后一次核对时已消掉），方案文档最后一次核对仍有一条句子长度警告。

**图**：调研 1 张、方案 2 张，都是 Mermaid，编号 `图 N`、正文引用、节点数在上限内。但两篇的图都收到「图里的名字多数在正文里没出现」的警告：图里用的是代码标识符和 `FB`、`NB` 这样的简写节点，正文没逐个提到。这条规则对这类图过严或者作者没照做，没有改。

**没做到的，要如实写**：

1. **两篇都没有通过评审**，轮次用完后停在「等用户确认」。主要原因不是文档质量，是复核员：它拿不到仓库，只看证据日志和最多 12 段代码，对其余的陈述只能判「无法核实」，而「无法核实」在流程里是要改的问题。每轮有 21%–54% 的陈述被判无法核实（调研 6–15 条/轮，方案 3–27 条/轮；其中只有 1–2 条/轮是平台因依据对不上而改判的），作者每轮在改措辞，下一轮又冒出新的。要改的问题每轮 8–33 个，打分停在 70 多。平台自己的核对是过的。这说明现在的评审对技术文档太严，不能照原样上线；建议：让复核员能只读地查仓库（和架构评审一样），并把「无法核实」在技术包里降为记录、占比超过阈值才退回。我没有改，因为改了就要重跑这两个真实运行来验证。
2. 调研文档只做了只读的量取，没有跑实验，所以速度、误报全是「未验证」，文档自己也这么写。这是只读约束的后果，不是写作的问题。
3. 方案文档中途有一稿 3975 字，超过目标 3000 字的 30%（那次核对失败，后来改回）；证据 62 条，有 5–9 条没有被引用（警告）。
4. 提纲没有人看过（驱动代答），所以「提纲由用户确认」这一步没有被真实检验。
5. 只跑了各一次，没有样本量，不能据此校准阈值。

## 已知缺口

- 复核员无法只读查仓库（上面第 1 条），评审对技术文档偏严。
- `assign_task`、`start_task`（SuperAgent 的派活工具）没有 `genre` 和 `repo` 参数；方案文档有完整的改法。
- 评审、核对的阈值没有用 20–30 篇样文校准。
- 禁用语、模糊词表是写在代码里的固定表（可以用 `banned_phrases` 追加，不能删）。
- Mermaid 校验是 Java 里的结构检查，不是官方 parser：白名单外的图类型会被判「第一行要写图的类型」，只有 flowchart、sequence、er、state 做逐行检查（调研文档有详细分析）。
- 代码引用要从仓库根算起的完整路径，不接受只写文件名。
- 预览的 Mermaid 只在 jsdom 里验证过，没有真浏览器；没有给聊天消息里的 Markdown 加图渲染。
- `needs_repo` 只接受本地目录（管理员限定目录时要在目录内），远程仓库地址不会被克隆。
- 详情页没有展示证据日志（数据已在 `harness.article.evidence`）。
