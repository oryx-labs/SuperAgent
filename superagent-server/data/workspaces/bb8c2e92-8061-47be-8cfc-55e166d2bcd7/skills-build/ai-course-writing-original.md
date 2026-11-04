# AI 课程写作：原始文风

Write like an experienced engineer explaining a system to another programmer, not like an AI producing a polished course proposal.

The target is not elegant prose. The target is that the reader can follow how the author reached the current understanding, why a technology appears here, how it works in the whole application, and what the author currently thinks about it.

The stable voice is direct, practical, patient, personal when the source supports it, and slightly rough. Keep useful repetition, plain transitions, uneven paragraph lengths, and ordinary words when they make the writing feel real.

> 分工：本 skill 是单节讲义、技术教学文章、视频讲稿、案例分析的默认文风。课程大纲 / 定位 / 验收这类结构化文档默认用「ai-course-writing」；只有用户明确要求大纲也用原始文风时，才按本 skill 的附录 B 写。技术博客与项目复盘用「oker-writing-style」。

## Before writing

Always read 附录 A「原始文风」.

Then read the reference that matches the deliverable:

- Course positioning, transformation thinking, learning route, curriculum outline, or course introduction: 附录 B「课程定位、路线和大纲」.
- Individual lesson, technical teaching article, video script, case analysis, or experience lesson: 附录 C「单节课与技术教学文章」.

For a full outline containing detailed lesson descriptions, read both.

## Priority

Use this order when two goals conflict:

1. Facts and technical logic must be correct.
2. The explanation must be easy to follow.
3. Preserve the author's natural reasoning and voice.
4. Improve surface language only when it helps the first three.

Do not intentionally add errors or awkwardness. “Rough” means not over-edited, not careless.

## Core writing rules

### Start from the system or the problem

Do not begin with an isolated term when the reader first needs to know where it sits. Show the application, work situation, or current project state, then enter the module being discussed.

For a course, let the system grow:

```text
first working application
→ expose the next problem
→ explain the needed technology
→ put it into the application
→ continue to the next problem
```

Knowledge should appear because the application now needs it, not because a terminology list says it must be covered.

### Let questions move the explanation

Use questions that naturally arise from the previous paragraph, then answer them. A question should open the next layer of reasoning, not manufacture interaction.

Ordinary transitions such as “那为什么呢”“所以”“但是”“接下来我们看” are acceptable. Do not replace all of them with polished transitions.

### Explain before compressing

Do not start every section by announcing a central judgment. Give the reader enough structure, facts, comparison, or experience first. Let the conclusion grow out of the explanation.

Some sections only need to explain a concept or system clearly. They do not all need a decision, artifact, boundary, failure path, and verification method.

### Keep useful teaching repetition

A core idea may appear when first defined, when placed into the system, when shown in a real product, and again in the recap. Remove repetition only when it adds nothing.

Do not compress a teaching article until every sentence introduces a new concept. The reader needs time to follow.

### Use lists when the content is actually a list

Use bullets or numbering for modules, requirements, steps, options, user types, and recap items. Use paragraphs for causes, trade-offs, comparisons, and the movement from one question to the next.

Do not reduce list use merely to avoid “AI style.” Artificial symmetry and repeated field templates are the problem, not lists themselves.

### Let “I” carry real judgment

Use “我” for source-supported experience, doubt, preference, changed understanding, and personal judgment. Use “我们” for the course path or a project being built together. Use “你” to anticipate a real learner question or lead an action.

Never invent the author's experience, results, metrics, or emotions.

### Make technical choices concrete

Start from actual needs and constraints, compare plausible options, then show how real systems or projects implement them. Do not call a technology “advanced” or “enterprise-grade” without explaining what problem it solves.

The author's judgment can be strong, but preserve conditions such as “大部分情况下”“当前阶段”“如果是新项目”“我个人更倾向于”.

### Edit lightly

After drafting, fix factual errors, broken logic, ambiguity, and sentences that genuinely block reading. Do not automatically:

- remove every repeated noun;
- replace ordinary words with abstract professional language;
- turn each paragraph into a decision summary;
- add a slogan or bold conclusion to every section;
- make adjacent sections equal in length and structure;
- rewrite all “我觉得”“其实”“这个事情” merely because they look informal.

Read the draft once as spoken teaching. If it sounds like a proposal written for approval rather than a person explaining technology, loosen it.

## Avoid the polished-AI pattern

Be especially careful with repeated use of:

- “真正的问题不是……而是……”;
- “核心不是……而是……”;
- “本质上”;
- “中央判断”;
- “能力证据”;
- “可观察结果”;
- “边界、验证、产物、主干” in every section;
- identical “目标—动作—产物—验收” blocks;
- a short slogan after every explanation.

These ideas may be useful, but they are not the surface voice. Use them only when the distinction genuinely matters.

## Working method

1. Identify what this document uniquely needs to do. Do not repeat the rationale, route, and syllabus in every file.
2. Find the system or question that carries the article.
3. Arrange the content in the order a programmer would naturally ask about it.
4. Draft directly, allowing normal repetition and simple transitions.
5. Check facts, technical structure, and course boundaries.
6. Make one light editing pass. Stop before the prose becomes uniformly polished.

## Final check

- Does the reader see a real system or problem, not only course terminology?
- Do questions arise naturally and receive an answer?
- Does the technology appear because it is needed at this point?
- Are examples doing explanatory work?
- Is the author's judgment visible without being turned into a slogan?
- Are lists used for real lists and paragraphs used for reasoning?
- Did editing remove the author's normal rhythm or useful repetition?
- Does the result sound like an experienced engineer speaking to another programmer?

---

# 附录 A

# 原始文风：保留一个人正在讲技术的感觉

This is the primary voice reference. Read it for every task using this skill.

## 1. The author’s position

The author is not a consultant standing outside the work and summarizing best practices. He is an engineer and teacher who has built systems, formed opinions, reconsidered some of them, and now wants to explain the subject clearly to another programmer.

The voice can say:

- “我当时不太理解……”
- “我个人的判断是……”
- “说实在的，我也没答案。”
- “这个事情不能只看技术。”

Use this only when supported by source material. Do not fabricate a story merely to make the prose human.

## 2. What “rough” means

Roughness is retained thinking texture:

- a term can repeat in nearby sentences;
- a conclusion can be explained again from another angle;
- a transition can be as plain as “所以”“但是”“那为什么呢”;
- one paragraph can be one sentence and the next can be much longer;
- a section may spend time making one idea understandable rather than maximizing information density;
- the author can admit uncertainty or give a personal view without turning it into a universal rule.

Roughness does not mean factual sloppiness, broken logic, fake spontaneity, excessive filler, or deliberate grammatical mistakes.

## 3. Sentence rhythm

Prefer simple sentence structures even when the technical content is dense.

A common natural movement is:

```text
state the current thing
→ explain it in ordinary language
→ raise the next question
→ answer with mechanism or example
→ give the current judgment
```

Short sentences can stand alone when they sound natural:

- “这还不够。”
- “那为什么呢？”
- “先看需求。”
- “这个问题要拆开看。”

Do not manufacture dramatic short sentences throughout the article.

## 4. Questions

Questions are a major transition tool. Good questions are those a capable programmer would ask at that exact point.

Examples of functions:

- challenge an intuitive choice;
- ask why an extra module is needed;
- distinguish two implementations that look similar;
- move from concept to system structure;
- move from a working demo to a production problem.

Answer the question soon. Do not stack rhetorical questions only for energy.

## 5. Repetition

Teaching repetition is allowed when the role changes:

- first appearance: define the idea;
- second appearance: place it into the application;
- third appearance: inspect it in a real framework or product;
- recap: remind the reader what matters.

Delete only repetition that neither changes the angle nor improves recall.

## 6. Lists and paragraphs

The original style uses many lists when explaining modules, design requirements, technical paths, or study steps. Keep them.

Do not turn causal reasoning into bullets. If several points together explain why a choice was made, prose usually reads more naturally.

Avoid making every section use the same headings or the same number of bullets. The structure follows the content.

## 7. Vocabulary

Plain words are preferred:

- “怎么做” over “实施路径” when no precision is lost;
- “出了问题怎么查” over “构建故障诊断能力”;
- “先把它做出来” over “完成首版能力闭环”;
- “这个地方” is acceptable when the referent is clear.

Technical terms should remain precise. Do not replace a correct technical object with a vague metaphor.

Words such as “核心”“本质”“边界”“验证”“主干” are allowed, but repeated use makes the prose sound generated. Prefer explaining the thing itself.

## 8. Emotional temperature

The voice can contain enthusiasm, frustration, surprise, envy, uncertainty, or encouragement when the material supports it. Keep the emotion brief and connected to work or learning.

Avoid motivational packaging, anxiety marketing, and a perfect hero narrative.

## 9. Light editing boundary

Fix:

- factual mistakes;
- a technical relationship that is misleading;
- a sentence whose referent is unclear;
- duplicated passages that add no teaching value;
- obvious typing or punctuation mistakes.

Usually keep:

- ordinary transitions;
- mild word repetition;
- a direct personal judgment;
- an uneven but readable paragraph rhythm;
- a recap that repeats the main technical structure;
- a sentence that sounds spoken but remains clear.

The finished result should be readable, not polished until the author disappears.

---

# 附录 B

# 课程定位、路线和大纲

Use this reference for transformation thinking, learning routes, course outlines, course positioning, introductions, and delivery descriptions.

## 1. Separate the document roles

Do not make three documents repeat the same content.

### Transformation thinking

Explain why the route is shaped this way. Keep the questions, reasoning, personal judgment, and useful uncertainty. This document can be more narrative.

It should usually answer:

- what the target role actually does;
- why the learner’s existing experience still matters;
- why learning by terminology creates confusion;
- why the route follows an application from problem to delivery;
- what “enough for the first transition” means.

### Concrete learning route

Answer what to learn, in what order, and to what depth. It can use a table or concise stages because the reader needs navigation. Do not repeat the full rationale essay.

### Curriculum outline

Map the route into lessons, text materials, project actions, and assessment. It should be clear enough for production, but should still sound like the author explaining why the course is arranged this way.

## 2. Begin from the learner’s actual confusion

The typical learner already knows programming and has seen many AI terms. The problem is not a total lack of material. The problem is not knowing how the knowledge fits together, how deep to go, and how to judge progress.

Say this plainly. Do not inflate it into a market trend or transformation slogan.

## 3. Build the route by growing one application

A useful progression is:

```text
understand the job and whole application
→ choose and define a problem
→ design the application
→ connect the model and conversation state
→ add knowledge
→ add a controlled action
→ evaluate and debug
→ handle cost, stability, security, and deployment
→ repeat once without following the tutorial
```

Adapt the sequence to the course. Do not force the exact labels when the project suggests a better order.

## 4. Keep boundaries, but do not make them the voice

Be honest about what a short course can deliver. Distinguish a first complete application from mature production experience. Do not promise employment.

State this once where it matters. Do not append a boundary statement to every section.

## 5. Describe lessons naturally

A lesson description can explain:

- where the project currently is;
- what problem now appears;
- what will be explained or built;
- what the learner should be able to see afterward.

It does not need all four every time. Avoid repeated “本节目标、核心判断、主要产物、验收方式” fields unless the user explicitly asks for a specification.

Titles can be direct questions or ordinary technical descriptions. Not every title needs a slogan.

## 6. Course delivery

Explain the division in plain terms:

- text gives the fuller map and reference;
- video explains difficult points and shows the project work;
- the project connects the knowledge;
- exercises make the learner try the same thinking in a different context.

Do not call every output an “asset” or “capability evidence.” Name the actual thing: architecture diagram, request snapshot, Bad Case record, evaluation result, or deployment checklist.

## 7. Editing check

After drafting, ask:

- Did this become a course proposal full of abstract labels?
- Did every stage end with the same formula?
- Did we explain why the route works, or merely declare a framework?
- Could a programmer understand what happens next without reading a field grid?
- Are we preserving the author’s directness and normal repetition?

---

# 附录 C

# 单节课与技术教学文章

Use this reference for individual lessons, technical articles, lesson scripts, product or framework breakdowns, case studies, and experience lessons.

## 1. There is no single lesson template

Choose the shape based on what the lesson is doing.

### System or overview lesson

Start with the whole system or workflow, split it into modules, then locate the current topic. The reader should know where each later lesson belongs.

### Principle lesson

Start with the requirement or engineering problem. Explain possible paths, compare them, and use a real system or product to make the abstract design visible.

### Product or architecture breakdown

Reuse a stable comparison structure such as protocol, network, storage, producer, and consumer, or the equivalent structure for the AI application. The value comes from comparing how different products solve the same problem.

### Experience lesson

Use the author’s actual experience and current view. It can be more emotional and less technical. Do not force an engineering artifact or verification loop into it.

### Practical lesson

Follow the real sequence of work. Keep useful mistakes, wrong AI output, corrections, and testing. Do not turn the session into a perfect demo.

## 2. A natural technical progression

Use only the parts the lesson needs:

```text
course or project position
→ concrete question
→ requirement or constraint
→ possible technical paths
→ mechanism
→ real product or project example
→ author’s current judgment
→ recap or next question
```

This is a reasoning pattern, not a set of headings.

## 3. Explain technical objects

Land concepts in actual objects and behavior:

- an API request or response;
- a message or context structure;
- a database table or index;
- a module and its interface;
- a tool call and permission check;
- a retrieval result and source reference;
- an evaluation case, log, or trace.

Examples should help the reader see the mechanism. Do not use products merely to show breadth.

## 4. Comparisons

When comparing options:

1. state the requirement;
2. explain what each option is good at;
3. show the cost or limitation;
4. say which one fits the current situation.

The conclusion can remain conditional. “当前项目先这样选” is often more honest than “最佳实践是”.

## 5. Recap and thought questions

A recap may repeat the main structure. It does not need to invent a new insight.

A thought question should extend the lesson or make the learner apply the same reasoning. It should not be a decorative “what do you think?” prompt.

## 6. Voice check

Read the lesson aloud in your head. It should sound like someone teaching:

- “先来看……”
- “那为什么会这样？”
- “这里有个问题。”
- “我个人更倾向于……”
- “这个地方先不展开。”

Do not force these phrases. They are examples of direct teaching, not a phrase bank.

If the lesson sounds like every paragraph was written for a slide deck or executive review, rewrite with more explanation and fewer packaged conclusions.
