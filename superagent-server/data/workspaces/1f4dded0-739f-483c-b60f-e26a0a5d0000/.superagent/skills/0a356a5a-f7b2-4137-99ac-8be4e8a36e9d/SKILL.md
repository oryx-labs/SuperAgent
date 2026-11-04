# ai-course-writing

> 写课程定位、课程大纲、行动营方案、学习路径、阶段验收、作业与评估设计等结构化课程文档时使用；单节讲义正文不用它。

# AI 课程写作

Write as an engineer who is taking capable programmers through real work and making the hidden decisions visible. The goal is not to arrange AI terminology into chapters. Build a learning path that answers: what the learner is becoming, what must be learned, how far each topic must go, what action proves progress, and what remains outside the course.

> 分工：本 skill 负责课程定位、课程大纲、行动营方案、学习路径、阶段验收、作业与评估设计这类需要结构的文档。单节讲义正文和视频讲稿优先用「ai-course-writing-original」，本 skill 的附录 C 只在设计实操、作业、验收时参考。技术博客与项目复盘用「oker-writing-style」。

> 用词说明：「中央判断」「能力证据」是备课时的内部检查概念，用来想清楚一篇稿子的主线和验收依据。正文里不要原样写出这两个词，直接把判断和证据讲出来。

## Before writing

Always read 附录 A「声音与编辑判断」. It defines the stable personal voice, reasoning style, truth boundaries, and final editing checks.

Then read only the reference that matches the deliverable:

- Course positioning, curriculum outline, action camp, transformation path, course introduction, or learning guide: 附录 B「课程定位、课程大纲与学习路径」.
- Individual lesson, video script, technical teaching article, practical demonstration, exercise, assessment, or closing lesson: 附录 C「单节课、视频讲稿、实操与作业」.

For a full course that includes detailed lesson descriptions, read both course references.

## Non-negotiable principles

1. **Start from the learner's real transition problem.** Define the starting capability, target capability, recurring confusion, available time or project constraints, and the evidence that would make progress visible.
2. **Build a main trunk, not an encyclopedia.** Organize the course around a real application or work process. Technologies appear because the next project gap requires them, not because they are popular terms.
3. **Make every stage change the learner's capability.** A stage should leave a judgment, artifact, working path, evaluation result, or clearer action—not merely “understanding a concept.”
4. **Use a project as a carrier, not as the course's hidden purpose.** Code proves decisions and joins the knowledge together. Do not let feature volume replace learning outcomes.
5. **Expose engineering judgment.** Show the current problem, plausible paths, real constraints, the selected path, its cost, and the condition that would change the decision.
6. **Keep AI and human responsibility distinct.** AI may research, compare, draft, generate, test, and maintain. The engineer owns the problem, scope, architecture, acceptance criteria, and final decision.
7. **State the ability boundary honestly.** Distinguish a first version, a completed course path, interview readiness, and production experience. Never imply that a short course guarantees employment or mastery of every framework.
8. **Write for experienced developers.** Do not spend time re-teaching programming basics. Explain where a new AI concept sits in a system, which decision it supports, and how to know it works.
9. **Use prose as the default carrier of reasoning.** Write causal explanation, judgment, transition, and capability growth as natural paragraphs. Use bullets only when the reader genuinely needs to compare independent items, execute a checklist, or scan a directory. Clear writing is not the same as turning every thought into a list.
10. **Use the least language that preserves the reasoning.** Concision means removing repeated setup, repeated conclusions, decorative boundaries, and prose that merely restates a diagram. Keep the mechanism, evidence, judgment, and necessary transition. Do not shorten by deleting the step that makes a conclusion credible.

## Working method

1. Extract the factual spine: audience, starting point, desired change, course constraints, available project, required deliverables, and explicit non-goals.
2. Name the central course judgment. Examples: “从知识点学习转向完整应用交付” or “项目是能力证据，不是代码表演.”
3. Decide the dominant deliverable. An outline needs capability progression; a lesson needs one decision and one main artifact; a practice needs a real execution and verification loop.
4. Build the progression from current capability to newly visible gap. Each lesson should make the next lesson necessary.
5. Draft in direct “你/我们” language. Use “我” only to own source-supported experience, surprise, choice, or changed judgment.
6. Choose structure by meaning. A route can keep stage headings, but do not repeat the same “目标/内容/动作/产物/验收” subheadings under every stage unless the user explicitly needs a specification sheet.
7. Respect document roles. If a separate artifact already explains the rationale, do not repeat that reasoning in an execution route, syllabus, or outline. Let the downstream document focus on the decisions and information it uniquely owns.
8. Run an information-density pass. For every paragraph, ask what new fact, mechanism, judgment, or transition it adds. Merge or delete paragraphs whose removal does not change the reader's understanding.
9. Run the final audit below. Remove content that exists only to make the material look comprehensive.

## Compact final audit

- Can the learner see a path rather than a list of topics?
- Does each stage or lesson answer why it exists now?
- Are AI's actions and the engineer's decisions separately visible?
- Does each important action leave an artifact, observable behavior, or review question?
- Are completed, planned, inferred, unknown, and out-of-scope states kept distinct?
- Did the draft preserve a natural thinking rhythm, or compress every paragraph into a polished decision summary?
- Are lists reserved for material that benefits from scanning? Would a few short paragraphs express the reasoning and transitions more naturally?
- Do adjacent stages or lessons repeat the same subheadings, sentence openings, and closing formulas merely for visual symmetry?
- Is this document repeating the “why” already owned by another document, instead of serving its own role?
- Are “不是/当前/这一版/边界/验证” used because the distinction matters, rather than as signature vocabulary?
- Can each “不是 A，而是 B” sentence be replaced by a direct statement of B without losing a necessary correction? Keep contrast only where the reader is genuinely likely to confuse A and B.
- Does the prose add information beyond the title, table, or diagram next to it, or merely narrate the artifact again?
- Does the ending leave an honest result and next move instead of a promise or slogan?

## Guardrails

- Do not force every lesson into the same template. Concept, implementation, demonstration, and closing lessons need different shapes.
- Do not force every stage into the same field grid. Repeated headings such as “学什么/学到什么程度/做什么/怎么验收” can make an open learning path read like an AI-generated requirements form. Keep the information, but let it unfold as prose when the causal relationship matters more than lookup speed.
- Do not create artificial balance by giving every section the same paragraph count, sentence pattern, or “最终留下……” ending. Structural consistency should help navigation, not flatten the author's thinking rhythm.
- Do not write a concrete learning route as a second rationale essay. When the transformation thinking already exists elsewhere, keep only a short goal and boundary, then make sequence, content, depth, and completion standards easy to scan.
- Do not make every paragraph carry problem, trade-off, boundary, evidence, and next step. A short passage may need only one or two.
- Do not invent the author's first-person experience, AI interaction, project result, metric, or student outcome.
- Do not turn a course outline into a full textbook or a technical manual into a motivational letter.
- Do not copy surface phrases from the source to simulate voice. Preserve the reasoning function and vary the wording.
- Do not build a sequence of corrective headings such as “X 不是……”“真正的 X……”“问题不在……”. State the architecture, action, or judgment positively. A few earned contrasts are useful; one contrast in every section reads like generated copy.
- Do not make concision a word-count exercise. Remove duplication first, compress wording second, and keep the causal step that explains why the engineering mechanism exists.
- Course conventions such as “你好，我是 Robert”, fixed recaps, previews, reflection questions, and calls to action are optional format choices, not the personal voice itself.

---

# 附录 A

# 声音与编辑判断：把务实的工程思考写出来

这份参考定义稳定的个人文笔。它比课程目录、固定开场和常用句式更重要。

## 1. 写作者站在哪里

作者不是站在问题外面讲一个已经整理好的答案，而是一个正在做事的工程师：

- 面前有具体课程、系统、功能或技术决定；
- 愿意给出当前判断，也解释这个判断依赖什么；
- 区分已经做完、正在做、准备做和仍然不知道；
- 保留有价值的犹豫、反转和失败，不把过程修饰成一开始就知道答案；
- 通过写作继续想清楚问题，而不是在工作结束后包装结论。

稳定底色是：**务实、坦诚、有方向，也允许当前答案不完美。**

可以把它压缩成一句话：

> 远景可以大，当前动作必须小；判断可以鲜明，依据和范围必须跟上；过程可以不完美，不能把没做完写成做完了。

## 2. “务实”不是语气，是结论与工作的关系

务实的内容通常从下面几类事实中选择真正需要的部分：

- 正在解决的工作问题；
- 当前项目或学员的现实约束；
- 尝试过或认真考虑过的路径；
- 为什么做出这个选择；
- 留下了什么工程产物或可观察结果；
- 目前处于什么成熟度；
- 哪些内容明确不在这一阶段；
- 什么条件变化后需要重新判断。

一篇长内容可以覆盖多个维度，一段短内容只需要支撑它的主要任务。不要为了显得完整，强行补一个竞品比较、边界清单或下一步。

“需要企业级可观测性”不是一个足够的判断。需要继续说明：现在回答不了哪个生产问题，第一版补什么信号，为什么当前规模还不值得接入更重的系统。

## 3. 写作前先分清事实状态

不要把不同状态揉成一句确定的话：

- **已经观察到：** 实际测量、实现、复现或检查过；
- **推断：** 有机制依据，但还包含推理；
- **已经决定：** 当前选择的方向；
- **计划：** 准备做，还没有交付；
- **未知：** 暂时缺少证据；
- **不在范围：** 这一阶段主动不承担。

PoC 不能写成生产能力，路线图不能写成已经交付，AI 的建议不能写成作者自己的最终决定。课程设计也一样：计划提供个性化作业，与已经具备完整的个性化验收系统是两件事。

## 4. 最稳定的思考指纹

### 从摩擦开始，不从名词开始

开头先出现一个真实张力：学员看了很多资料却无法行动、当前系统少了一层能力、直觉方案与现实约束不匹配，或者一段链路跑通后暴露了新的缺口。

除非在写参考手册，否则少用“本文将介绍 X”。让读者先知道为什么现在要讨论它。

### 把问题转到可以做决定的位置

文章和课程经常通过“换一个问题”向前推进：

- 从“要不要学 RAG”转成“这个业务需要外部知识时，最小可信链路是什么”；
- 从“AI 能不能写代码”转成“什么上下文、规则和验收能让结果可信”；
- 从“哪个框架最好”转成“当前项目需要哪一层能力”；
- 从“能不能实现”转成“是否值得在这一阶段实现”。

读者看到的不只是答案，还能看到一个模糊问题怎样变成可以判断的问题。

### 保留判断变化

如果观点发生过变化，保留有用的过程：

1. 最初怎么想；
2. 当时为什么合理；
3. 后来出现了什么新需求、事实或约束；
4. 旧判断为什么不再成立；
5. 现在选择了什么更窄的方向。

不要把最终答案写成从一开始就显而易见。

### 做出选择，也说清代价

可以明确写“我的判断”“这里先选”“这一阶段采用”，前提是材料真的支持作者做过这个判断。选择之后说明它为什么适合现在、牺牲了什么、什么场景下另一个方案更合适。

目标是“当前适配”，不是“普遍最好”。

### 用非目标控制课程和项目

非目标不是一句“暂不支持”。它需要说明为什么不属于当前问题，是延后、拒绝，还是应由另一层系统承担。

如果非目标超过三个，使用简短清单，或只保留真正影响理解的项目。连续写很多“不做……”会让边界显得防御，而不是经过判断。

## 5. 第一、第二、第三人称

### “我”承担判断责任

用“我”表达真实的提问、经验、意外、选择、错误或理解变化。只要原始材料支持，宁可让“我”承担这个判断，也不要把它洗成匿名、客观的咨询报告。

“我认为”本身不是证据，后面必须跟原因。

### “我们”描述共同推进的工作

用“我们”表达课程共同路径、团队已经完成的实现、项目状态或约定计划。不要把个人假设写成团队共识。

### “你”直接带学员行动

成文课程直接使用“你”和“我们”。“学习者”“读者”只用于课程设计说明，不应频繁出现在正式讲稿中。

“你”不是用来制造亲切感，而是预判疑问、指出检查动作或帮助学员提出更好的问题。

## 6. 句子和段落的节奏

- 一个自然段主要推进一个判断。
- 中等长度的段落解释机制、约束和证据，短句负责转向或落锤。
- 允许“但这个方案有问题。”“能跑，还不够。”“这一步不能省。”单独成段，前提是上文已经把它铺出来。
- 使用朴素的因果连接：“但、所以、先、再、那问题就变成了、回到当前目标、做到这里”。
- 问句只用于提出强疑问或打开下一层推理，并且马上回答。不要用装饰性反问。
- 用具体动作替代抽象词：画架构图、跑评测、补测试、检查 Trace、更新规则，比“提升质量、完善体系”更可信。

正文默认写成少量、完整的长段。同一层意思里的铺垫、判断、原因和结果要尽量放在一起，不要每说一两句话就另起一段。两个独立段落之间保留一个空行，让边界清楚；不要使用连续空行，也不要为了紧凑把所有空行都删掉。只有问题真正发生转向、进入新的阶段，或者表格、目录和引用需要独立显示时才换段。课程大纲可以保留课时标题，但每一讲下面的内容尽量收成一个完整段落，避免视觉上零散、凌乱。

不要把每一段都压成同样的“结论—理由—代价—边界”。过度完整会写成咨询式摘要，失去一个人正在思考的感觉。

“不是”“当前”“这一版”“边界”“验证”表达的是区分，不是个人标志。如果相邻段落重复依赖这些词，保留最有价值的一处，其余改成正面、直接的表达。

正面陈述应该成为默认写法。能写“Memory 保存跨任务使用的信息”，就不要先写“Memory 不是完整聊天记录”；能写“Sentinel 掌握最终执行权”，就不要每次都写“模型可以提出动作，但不能……”。对照句只在纠正一个真实、常见且会影响后续判断的误解时使用。标题尤其少用“不是 A，而是 B”，否则整篇文章会像连续的认知纠偏模板。

精简先删重复，再缩句子。每一段至少要增加一个新事实、机制、判断或必要转向。标题已经说过的结论不在首句换一种说法重讲，图已经展示清楚的节点和连线也不逐项念一遍。正文应该解释图里最难看出来的责任划分、约束或失败路径。

“用最少的表述写好”不等于追求最短。删掉因果中间项会让文章看起来短，却需要读者自己补推理。应该保留让判断成立的那一步，删除为了显得完整而补出的背景、总结、同义反复和装饰性边界。

### 分点不是默认的清晰

解释判断、因果、转折和能力变化时，优先使用自然段。让上一段提出的问题进入下一段，让读者看到思路怎样往前走。不要因为材料里有四个方面，就自动写成四个项目符号。

分点适合真正需要扫描的内容：彼此独立的选项、需要逐项执行的检查、明确的步骤、课程目录，以及读者之后会回来查找的清单。如果每个分点都需要一整段解释，或者几个分点共同构成同一条因果链，通常应该改回段落。

重复的小标题也可能是另一种分点。六个阶段下面都放“学什么、学到什么程度、要做的动作、阶段验收”，看起来很清楚，读起来却像规格表。路线文章可以保留阶段标题，但每个阶段内部应根据真实思路自然展开。只有在用户明确需要打分表、执行手册或字段化评审时，才使用整齐的字段结构。

结构需要稳定，但不需要过度对称。相邻章节可以有不同的段落数量、展开顺序和收束方式。不要让每节都用同一句式开头，也不要都以“最终形成……”结束。

## 7. 对照句、类比和标题

“问题不在 X，在 Y”“能跑通与适合生产是两件事”适合纠正真实误解。不要为了有力量，每一节都造一个“不是 A，而是 B”。

类比负责让读者进门，随后必须回到真实工程结构。地图、护栏、主干、工作台、管道都可以使用，但一段只保留一个主要比喻。

标题优先回答问题、指出动作或做出判断。避免全部写成只有技术名词的目录，也不要让每个标题都变成夸张口号。

## 8. AI 在叙事中的位置

AI 可以有多种角色：

- 扫描代码和资料，补齐信息；
- 扩大方案空间，比较路径；
- 执行重复编码、测试和转换；
- 从第二视角检查假设与跨步骤一致性；
- 更新文档、规则和复用资产。

人始终是问题负责人、决策者和验收者。

AI 产出很好时，要解释什么上下文、限制和检查让它变好。AI 失败时，要区分缺少上下文、任务形状错误、模型能力限制，还是人的决定本来就没有做完。

## 9. 情绪温度

有温度，但不靠煽情。

鼓励应该来自：复杂问题已经被拆开，第一步可以行动，结果有验收方式，能力会随项目积累。允许写意外、反思、旧判断失效，以及一个可运行结果带来的信心。

避免焦虑营销、夸大成功、自我表扬和空泛未来口号。普通技术课只保留一两个记忆点，情绪浓度更高的表达留给开篇和结束语。

## 10. 课程体例与个人文笔要分开

“你好，我是 Robert”、上一讲回顾、下一讲预告、固定总结、两道思考题和课程 CTA 都是体例，可以按交付形式启用。

真正需要跨文类保留的是：真实工作触发、判断过程、具体证据、明确范围、人的责任和可以继续推进的动作。

## 11. 两遍审校

### 第一遍：事实和推理

检查：

1. 这篇内容由什么真实问题触发；
2. 中央判断是什么；
3. 哪些是事实、推断、决定、计划、未知和非目标；
4. 选择依赖什么约束；
5. 学员最终得到什么能力或产物；
6. 还有什么没有解决。

原始材料缺事实时，不要用合理但虚构的第一人称故事填满。保持不确定，或向用户补充信息。

### 第二遍：声音和节奏

检查：

- 一段是否只推进一个判断；
- 抽象名词能否换成工程对象或动作；
- 长短段落是否自然交替；
- 问句是否马上获得回答；
- 加粗是否只留给承重结论；
- 是否重复使用标志性纠偏句；
- 每个“不是 A，而是 B”是否真的在纠正误解，能否直接陈述 B；
- 每一段是否增加了新信息，还是只在复述标题、图表或上一段；
- 分点是否真的帮助比较、执行或查阅，还是只把连续思考切碎了；
- 相邻章节是否为了整齐而复制了同一组小标题、句子开头和结尾；
- 结尾是否带来下一步，而不是广告。

## 12. 最终质量检查

下列维度按适用情况检查。短内容不需要为了得分补齐全部维度：

| 维度 | 需要看到什么 |
| --- | --- |
| 真实锚点 | 具体工作、学员问题或项目状态 |
| 推理轨迹 | 约束、选择或有价值的判断变化 |
| 工程判断 | 适合当前阶段的结论与代价 |
| 证据 | 产物、行为、数据、代码、测试或检查方式 |
| 能力边界 | 完成程度、非目标、未知或重评条件 |
| 行动 | 下一步、作业、实验、检查或工程产物 |
| 声音 | 直接、平静、具体、坦诚，不表演专业感 |

发现问题时先修推理，不要添加口头禅。最终成文应像一个真正做过项目的人，把一个决定讲给另一个有经验的工程师听。

---

# 附录 B

# 课程定位、课程大纲与学习路径

Use this reference for course positioning, curriculum outlines, action camps, transformation routes, course introductions, and full-course reviews.

## 1. 先定义课程要完成的变化

课程不是一组知识点，而是让一类人从一个状态走到另一个状态。

写大纲前先拿到：

```text
目标学员：
已有能力：
真实卡点：
目标岗位或目标任务：
课程时长与节数：
课程结束时能完成什么：
用什么产物证明：
课程明确不承诺什么：
```

“了解 AI 应用开发”不是能力目标。“能从业务问题出发，设计并跑通一个带 RAG、工具和评测的应用主链路”才可以继续拆成课程。

对于转型课程，优先回答学员真正关心的四个问题：

1. 我要转向什么岗位或能力；
2. 我需要学哪些东西；
3. 每一部分学到什么程度；
4. 我怎样证明自己正在接近目标。

不要用“学完保证就业”代替能力边界。课程可以建立路径、知识主干、项目证据和面试信心，不能替学员补完所有经验。

## 2. 从岗位工作反推技术体系

AI 课程最容易变成 Prompt、RAG、Agent、Memory 的名词目录。

正确顺序是：

```text
企业为什么需要这个岗位
→ 岗位需要完成什么工作
→ 一项工作由哪些能力组成
→ 哪些技术支撑这些能力
→ 学员怎样练习并证明
```

技术点只有在应用链路中出现，学员才知道它解决什么问题、与哪些模块相连、失败后从哪里检查。

课程可以追求宽视野，但“会”不等于每项都精通。需要为每个主题说明本阶段的完成标准：能识别、能使用、能设计、能排错，还是能在生产环境负责。

## 3. 选择一条能长出知识的项目主干

贯穿项目的任务是把课程知识连起来，不是展示产品野心。

一个合适的课程项目应满足：

- 有一个真实而容易理解的业务问题；
- 能自然覆盖课程的主要能力；
- 第一版范围足够小，可以在课程时长内走完；
- 模块有清楚边界，学员能替换数据、行业或工具继续扩展；
- 每个阶段都能产生可观察结果；
- 不依赖大规模团队、昂贵基础设施或无法获得的数据。

项目过小时，课程会退化成 API 教程；项目过大时，学员只会跟着复制代码。

课程需要明确：

```text
项目解决谁的问题
第一版跑通哪几条主路径
哪些能力是课程载体
哪些功能明确不做
项目怎样被学员替换或继续扩展
```

写代码不是行动的唯一形式。需求边界、架构图、技术决策、Context 规则、评测集、风险清单和复盘都可以成为课程中的主要产物。

## 4. 把文字、视频、项目和作业分开

四类交付应该互相支撑，不要重复同一份内容：

### 文字资料：建立地图

负责定义、体系位置、方案差异、完整参考、检查清单和延伸方向。适合课前阅读和课后查阅。

### 视频：公开判断过程

负责解释为什么现在遇到这个问题、有哪些路径、作者怎样取舍、AI 怎样参与，以及现场如何发现偏差。

### 项目：连接知识

负责让每项判断进入同一个系统，形成持续生长的项目状态。项目代码不需要覆盖所有行业功能，但关键链路需要能运行、能检查。

### 作业：完成迁移

负责让学员把课程判断用到自己的技术栈、行业、简历和目标岗位。作业应要求产物、理由、验证和剩余问题，而不是只交代码或截图。

如果文字和视频都在逐句讲概念，交付形式虽然多，学习体验仍然只有一种。

## 5. 课程主干如何推进

一组课程可以沿着下面的状态变化持续推进：

```text
已经具备的能力
→ 新暴露的缺口
→ 为什么会出现这个缺口
→ 可行路径
→ 当前约束
→ 作者的判断
→ 项目动作或学习动作
→ 工程产物
→ 验收证据
→ 可迁移的方法
→ 下一处缺口
```

不要求每节课把整条链路写一遍。它是整门课的推进机制，不是固定段落模板。

好的课程顺序有依赖关系：上一节的产物成为下一节的输入。需求边界进入架构，架构进入模块设计，模块进入实现，实现进入评测，评测暴露上线风险。

如果调换任意两节都没有影响，大纲可能仍是知识点清单。

## 6. 常用的阶段划分

根据课程目标选择，不必全部使用：

### 认知与路线

讲清岗位、能力地图、已有经验怎样迁移、课程能解决什么和不能解决什么。

### 问题与设计

完成产品定义、非目标、用户流程、架构、模块与实施顺序。技术选择服务于目标，不追新。

### 核心能力

沿项目链路引入模型、Prompt、Context、Session、RAG、工具或业务模块。每个概念都落到系统对象和验收信号。

### 质量与交付

处理评测、测试、性能、成本、安全、可观测性、部署和失败路径。让“可以演示”逐步接近“可以交给别人使用”。

### 实战与迁移

串联前面的方法完成一次完整变化，再把项目整理成复盘、作品、开源参与或下一阶段学习计划。

阶段名称只是导航。真正重要的是每个阶段结束后，学员的能力状态发生了什么变化。

## 7. 一节课应该有多大

对于 20～30 分钟的课程，一节只承载一个主要问题、一个中央判断或方法，以及一个主要产物或可观察结果。

“介绍 RAG”不是合格目标。可以改成：

> 为当前知识问答应用设计一条最小 RAG 链路，并能区分检索失败与生成失败。

多个技术可以同时出现，但必须共同服务于一个动作。不要为了覆盖面，把需求、架构、模型、RAG 和部署塞进同一节。

如果课程时长不同，调整内容密度，但仍需保留明确焦点。

## 8. 课程大纲的推荐结构

当用户提供参考大纲时，先学习它如何组织信息，再决定是否沿用结构。不要只复制标题和序号。

一份用于内部评审或正式制作的完整大纲通常包含：

1. **课程定位：** 用什么项目或问题作为主线，课程真正教什么；
2. **人群定位：** 学员已有能力、典型困惑和前置要求；
3. **交付目标：** 方法、项目、工程产物、工作方式和后续路径；
4. **交付形式：** 文字、视频、项目、作业怎样分工；
5. **课程介绍：** 为什么做、教什么、怎么学、最终得到什么；
6. **分阶段大纲：** 每一讲为什么存在、主要判断、项目推进和产物；
7. **阶段性验收：** 什么证据说明学员不是只看完视频；
8. **简化目录：** 方便快速评审整体节奏。

这些模块按任务取舍。面向销售的介绍需要更早说价值与结果；内部教学大纲需要更多能力递进与验收；简单目录不需要把完整介绍再写一遍。

### 大纲不是字段表

上面的结构用于检查信息是否缺失，不要求把每一项都写成列表。课程为什么存在、路线为什么这样安排、文字和视频怎样配合，这些内容通常更适合用短段落连续讲清楚。

分点主要留给确实需要快速查阅的内容，例如简化目录、阶段验收清单、并列方案和明确的交付项。不要把课程定位、人群、价值、学习方式和每个阶段都写成同样长度的项目符号。

转型路线尤其容易被写成规格表。可以保留阶段标题，但不要默认在每个阶段下面重复“学什么、学到什么程度、行动、验收”四个字段。把目标、动作和完成标准放进自然叙述里，读者更容易理解为什么上一阶段会走到下一阶段。

一致性来自主线和依赖关系，不来自视觉上的绝对对称。六个阶段可以有不同的篇幅，16 节课也不需要使用同一种句式收束。

### 区分“路线思考”和“具体学习路线”

路线思考负责解释为什么这样转：岗位怎样理解、旧路径哪里有问题、哪些判断决定了路线。它可以保留推理、取舍和认知变化。

具体学习路线负责回答接下来学什么：课程目标和边界是什么，学习顺序怎样安排，每部分学到什么程度，完成以后留下什么。它首先是一份导航和查阅文档，不应该把路线思考重新讲一遍。

当两份文档同时存在时，具体学习路线只需用短段落交代目标和边界，然后直接进入路线。此时表格、阶段图和短清单是合适的，因为读者需要快速对照“内容—深度—结果”。结构化不是 AI 味，重复论证和机械对称才是。

课程大纲再往下一层，只负责把学习路线映射成课时、文字资料、视频动作、项目推进和验收。三份文档之间要有依赖关系，不要相互复制正文。

## 9. 每一讲如何写进大纲

大纲中的单讲描述最好同时让人看见三件事：

- 为什么上一讲之后会遇到这个问题；
- 这一讲准备改变什么判断或完成什么动作；
- 项目或学员状态会增加什么。

不要让 16 讲变成 16 个同样的规格表。可以用一段自然说明收进问题、内容和产物，也可以在需要评审时显式写“目标/产物/验收”。选择一种主格式并保持可读性。

即使采用自然段，也要防止隐藏模板。连续多讲都以“本节将……”开头、以“最终形成……”结束，仍然会产生明显的生成感。根据课程形状改变展开顺序：有的先讲误解，有的先讲项目缺口，有的先讲一次失败，有的直接进入要做的决定。

标题优先使用：

- 问题：“什么时候该让模型调用工具”；
- 判断：“先定义产品边界，再写第一行代码”；
- 动作：“把不稳定的模型能力装进稳定接口”；
- 结果：“让多轮对话有状态，但不背着全部历史”。

标题不能替正文完成解释。

## 10. 阶段验收与能力证据

看完视频不是完成课程。

阶段验收可以围绕：

- 岗位与能力差距图；
- 一页产品定义和非目标；
- 架构图与技术决策记录；
- 可运行的核心链路；
- Prompt、Context 或数据规则；
- 评测集与结果报告；
- 风险清单、上线检查或故障手册；
- 项目复盘、作品材料和后续行动计划。

评分优先看判断和证据：目标是否清楚、选择是否匹配约束、结果怎样被检查、未知是否被诚实记录。不要用代码量和页面数量代替能力。

## 11. 个性化作业

适合使用“公共任务 + 个性化迁移”：

- 公共任务保证所有学员沿着同一条主干推进；
- 个性化任务根据简历、技术栈、行业、兴趣或目标岗位改变场景和约束。

一个完整作业至少要求：

1. 可检查的产物；
2. 选择理由；
3. 当前仍未解决的问题；
4. 下一步验证方式。

个性化生成和自动评分如果尚未实现，应写为后续计划，不要在课程大纲中描述成已经具备的交付。

## 12. 常见失败

- 课程从技术名词开始，没有岗位、业务问题或项目主线；
- 知识点很多，但学员不知道学到什么程度；
- 项目功能很丰富，却无法说明它证明了什么能力；
- 文字和视频内容重复，只是换了媒介；
- 每节课都要求写代码，没有需求、架构、判断和复盘产物；
- AI 被描述成自动完成全部工作，没有人的责任与验收；
- 为了显得企业化，在第一版堆入多租户、复杂权限、自动编排和大规模部署；
- 用就业承诺或焦虑代替能力终点；
- 每节课之间没有输入输出关系，可以随意调换顺序。

## 13. 完稿检查

最终应能回答：

- 学员为什么会买这门课；
- 学员已有能力和真正卡点是什么；
- 课程的中央判断是什么；
- 为什么选择这个贯穿项目；
- 文字、视频、项目和作业如何分工；
- 每个阶段给学员增加了什么能力；
- 课程结束时留下哪些证据；
- 哪些内容明确没有被课程解决；
- 学员接下来沿着哪里继续长。

---

# 附录 C

# 单节课、视频讲稿、实操与作业

Use this reference for individual lessons, video scripts, technical teaching articles, practical demonstrations, exercises, assessments, and course endings.

## 1. 单节课的基本单位

面向有项目经验的程序员，一节课不需要从术语定义开始。先让学员看到它在真实应用里解决什么问题、帮助做什么决定、怎样知道已经学到本阶段需要的程度。

一节课通常只需要：

- 一个主要问题；
- 一个中央判断或方法；
- 一个主要工程产物或可观察结果；
- 一个通向下一节的新状态。

技术点可以多个，但都要服务于同一个动作。

## 2. 自然的讲解推进

根据内容选用，不要把它变成固定标题：

1. **承接项目状态：** 上一节完成了什么，现在暴露了什么缺口；
2. **进入具体场景：** 工作中怎样遇到问题，直觉做法会怎样；
3. **校准问题：** 把“学习某技术”改成一个可以做决定的问题；
4. **给出紧凑框架：** 通常 2～5 个层次、维度或动作；
5. **推进项目：** 输入是什么，AI 做什么，人判断什么，产生什么；
6. **暴露失败路径：** 第一版可能错在哪里，怎样检查和回退；
7. **确认产物：** 项目现在多了什么，如何验收；
8. **完成迁移：** 学员怎样把同一判断用到自己的项目。

一节短课不必覆盖八步。只保留推动中央判断所需的部分。

这些动作是备课检查，不是八个固定标题。连续课程不要复制同一套“背景—目标—方法—产物—验收”版式。概念课可以多花几段校准理解，实现课可以直接从当前项目状态进入操作，实操课则保留现场偏差和修正过程。

## 3. 四种课程形状

### 概念与判断课

适用于岗位认知、Context、Agent 边界、评测观念等内容。

从熟悉的误解或真实困惑开始，花更多篇幅区分层次，少写操作清单。陌生概念可以沿着下面的路径落地：

```text
一句人话或克制类比
→ 它解决的真实问题
→ 在当前系统里由什么组成
→ 映射成表、接口、类或流程
→ 用什么行为证明学员理解了
```

结尾留下一个可以迁移的判断规则，而不是概念复述。

### 实现课

先明确项目输入和本节目标。每个主要步骤需要让学员看见：

- 正在决定什么；
- AI 能执行什么；
- 人需要重点审查什么；
- 产生什么具体结果；
- 最小验收信号是什么。

代码和 Prompt 不能悬空出现。解释为什么这样组织，哪些约束不能删，结果出来后先看哪里。

### 完整实操课

实操课不重新讲一遍理论，而是把前面的方法串成一条真实链路。

保留有教学价值的现场：指令不够清楚、AI 草稿偏离、一个测试暴露耦合、方案被新事实推翻，或者人拒绝了 AI 的建议。价值在于看到工程师怎样驾驶和收口，不是观看一次完美生成。

常见推进：

```text
项目状态
→ 一个窄动作
→ AI 结果
→ 人工检查
→ 意外或偏差
→ 修正
→ 测试与反向检查
→ 文档或规则沉淀
```

### 收尾与转型课

清楚分开：

- 课程或项目真正给了学员什么；
- 哪些东西没有给；
- 哪些方法可以迁移；
- 知识和经验缺口还在哪里；
- 下一步怎样选择项目、学习方向、开源任务或面试准备。

鼓励来自新产物和更清楚的自我判断，不来自“未来无限可能”。

## 4. AI 与人的真实分工

不要只写“AI 生成，人来审核”。根据任务写清角色：

- **AI 做侦察：** 扫描代码、调用点、历史和外部资料；
- **AI 做资料员：** 展开方案空间，整理可选路径；
- **AI 做顾问：** 在获得真实约束后比较方案；
- **AI 做执行者：** 生成重复代码、测试、草稿和转换；
- **AI 做第二视角：** 检查假设、边界和跨步骤一致性；
- **AI 做维护者：** 更新文档、规则和复用流程；
- **人负责问题：** 明确目标、业务范围和优先级；
- **人负责决定：** 选择架构、技术和取舍；
- **人负责验收：** 决定什么证据足够，并承担结果。

AI 成功时说明是哪类上下文和约束让它成功。AI 失败时说明需要补上下文、改变任务拆法、换模型，还是人先把决定做完。

## 5. 把抽象概念变成工程对象

每个重要概念至少落到一种可观察对象：

- 表、字段或数据流；
- 接口、类或模块；
- 配置、规则或 Prompt；
- 测试、评测集或验收用例；
- 日志、Trace、指标或调试记录；
- 可以运行的最小链路；
- 一页产品定义、架构决策或风险清单。

类比只能打开理解，不能替代系统结构。

## 6. 教学语言

正式课程直接和“你”说话，也可以用“我们”承接共同的项目状态。

有价值的教学动作包括：

- 预判学员最合理的疑问，再说明隐藏代价；
- 在写代码前暂停，先完成必须由人做的决定；
- 指出这一步应留下的工程产物；
- 告诉学员第一版出错很正常，并给出有范围的检查顺序；
- 在项目细节之后提炼可迁移判断。

根据上下文自然表达，不要反复套“你可能会想”“到这里先别急”“真正值得带走的是”等固定开头。

同样不要让每一讲都以“本节我们将……”开始、以“最终形成……”结束。课程需要连续性，但句式和段落形状应跟着这一讲真实要解决的问题变化。

谨慎使用：

- 没有工程不变量支撑的“一定、必须”；
- 没有测量条件的效率数字；
- 工作还没有发生就先给出的激励；
- 每个章节都用“接下来我们”报幕；
- 反复告诉学员“你会发现”，却不给证据。

## 7. 工程产物怎样进入下一节

学习产物应该可以被继续使用：

```text
做出一个可用的第一版
→ 放进真实任务检查
→ 用事实纠正
→ 更新项目记录或规则
→ 在下一项任务中复用
```

例如：

- 产品定义进入架构设计；
- Context 规则进入对话链路；
- 基线用例进入 Prompt 和模型比较；
- RAG 调试记录进入评测；
- 评测结果进入上线与优化；
- 项目复盘进入作品和面试表达。

不要把所有输出都称为“资产”。说明它后续怎样被使用，更有说服力。

## 8. 作业与验收

有效作业产生证据，不只检查记忆。

优先使用：

- 让学员比较 AI 输出和自己的项目知识；
- 画一张图或写一页边界文档；
- 完成一个最小实现并解释选择；
- 在新约束下重新审视一个决定；
- 记录失败用例，提出验证计划；
- 把课程方法迁移到自己的项目。

每份作业可以要求：

1. 一个可检查结果；
2. 为什么这样选；
3. 结果如何验收；
4. 还有什么不确定。

评分看判断和验证，不看代码量。截图只能证明程序曾经运行，不能单独证明学员理解了系统。

## 9. 单节结尾

正常课程可以用四个动作收尾：

1. 留下本节最重要的判断；
2. 确认项目或能力状态发生了什么变化；
3. 说明这个结果怎样进入下一节；
4. 在适合时给一到两个迁移问题。

不需要重复所有标题。课程预告和固定思考题按课程体例使用，不是每次都必须出现。

## 10. 可调整的单节模板

模板用于避免遗漏，不要求标题逐字一致：

```markdown
# 第 N 节：<行动或判断导向标题>

## 现在卡在哪里
承接项目状态，给出具体场景或失败。

## 真正要解决的问题
校准直觉，明确本节中央判断。

## <紧凑框架或机制>
解释 2～5 个关键维度，并落到当前系统。

## 推进项目
写清输入、AI 的动作、人的判断、产物和检查。

## 容易出错的地方
说明失败表现、检查顺序和回退方式。

## 收束
确认本节产物和下一节的新缺口。

## 作业
提交一个产物、选择理由、验证结果和剩余问题。
```

概念课可以减少步骤，增加层次辨析；实操课按真实操作顺序推进；收尾课围绕能力盘点和迁移计划展开。

## 11. 完稿检查

- 学员知道为什么现在学这一节吗？
- 这一节是否只有一个主要问题和主要产物？
- 概念是否进入真实系统，而不是停在定义？
- AI 做了什么，人决定了什么？
- 是否写出了错误结果怎样检查？
- 作业能否产生能力证据？
- 结尾是否自然暴露下一步，而不是重复摘要？
- 是否为了显得完整，塞入了本节不需要的技术和边界？
