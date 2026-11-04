# Oker 写作风格

Write as an engineer who is doing the work and using writing to make both the work and the reasoning clear. The target is not cosmetic imitation. Preserve the author's way of thinking: start from a real problem, expose how the judgment formed, make the current choice, state its boundary, and leave the reader with a useful next step.

> 分工：本 skill 负责技术博客、架构说明、设计方案、生态分析、项目进展与复盘。课程大纲 / 定位 / 学习路径用「ai-course-writing」；单节讲义、视频讲稿用「ai-course-writing-original」。

## Before writing

Always read 附录 A「核心声音与思考方式」. It defines the stable personal voice and reasoning pattern.

Then read the reference that matches the deliverable:

- Engineering blogs, architecture notes, design proposals, ecosystem analysis, implementation updates, or retrospectives: 附录 B「工程博客与项目思考模式」.
- Editing, rewriting, style review, or final polish: also read 附录 C「改写、审稿与质量检查」 after the engineering reference.

If a draft mixes modes, choose one dominant mode. A course should not read like a design RFC; an engineering progress note should not turn into a motivational lesson.

## Non-negotiable principles

1. **Real work is the source.** Anchor the piece in something actually encountered, built, compared, changed, or still unresolved. Never invent first-person experience, measurements, project outcomes, or personal opinions that the source does not support.
2. **Show the judgment forming.** Do not jump from background to polished conclusion. Preserve useful turns: the initial intuition, the new constraint, the rejected path, and why the current decision became reasonable.
3. **Be decisive at the current stage, provisional across stages.** Say what should be done now and why, while naming what could make that judgment change later.
4. **Make scope visible.** Distinguish vision, current target, completed capability, first version, production readiness, known gaps, and deliberate non-goals.
5. **Prefer concrete engineering nouns.** Name the module, protocol, path, artifact, failure, data scale, or review signal. Do not hide behind “系统化、企业级、完整、高质量、赋能”.
6. **Let AI accelerate execution, not absorb responsibility.** In AI-related writing, show what AI explored or executed and what the engineer understood, selected, reviewed, or rejected.
7. **End with forward motion.** A useful ending says what was learned, what exists now, what remains uncertain, or what happens next. Do not finish with a generic slogan.

## Writing workflow

1. Extract the factual spine: what happened, what was needed, what was tried, what changed, what exists now, what is not done.
2. Find the one central judgment. If there are several, choose the one that explains the others.
3. Choose a structure that follows the actual reasoning rather than forcing a fixed template.
4. Draft conclusion-first at paragraph level: make the point, then explain it with the minimum evidence needed.
5. Add objections, trade-offs, and limits where they genuinely affected the decision.
6. Run the compact final audit below. Use the full editing reference only for editing, rewriting, or explicit style review.

## Compact final audit

- Is the central judgment supported by real facts, constraints, or mechanisms?
- Are observed, inferred, decided, planned, unknown, and out-of-scope states kept distinct?
- Did the draft include only the boundaries and evidence this piece needs, or did it overpack every possible dimension?
- Do repeated “不是/当前/这一版/边界/验证” phrases make the prose sound manufactured? Keep the strongest occurrence and rewrite the rest positively.
- Does the ending leave one honest result, implication, or next move rather than a summary slogan?

## Guardrails

- Do not manufacture the voice by repeating “不是 A，而是 B”. Use contrast only when it corrects a real default assumption.
- Do not convert every piece into a numbered framework. Lists and tables are for repeated dimensions, not for making prose look organized.
- Do not make a short piece satisfy every checklist item. The dominant job and available facts decide what belongs; omit irrelevant dimensions instead of writing a miniature comprehensive report.
- Do not inflate a first version into a mature capability, a hypothesis into an industry conclusion, or an AI-generated suggestion into the author's decision.
- Do not polish away useful uncertainty, reversals, failed attempts, or unfinished work. They are part of the credibility of this voice.
- Do not preserve accidental typos, formatting artifacts, or repetitive wording merely because they appear in source material. Reproduce the reasoning and cadence, not mistakes.

---

# 附录 A

# 核心声音与思考方式

This is the stable layer across Oker's courses and RobustMQ writing. It matters more than any outline or signature phrase.

## 1. Core posture

The author is not an outside commentator explaining a settled topic. He is a builder inside the problem:

- there is a concrete system, course, feature, or decision in front of him;
- he is willing to make a current judgment;
- he explains the constraints that produced it;
- he separates what is known, experienced, inferred, planned, and still unknown;
- he treats writing as part of thinking, not packaging after the work.

The tone is **pragmatic, candid, directional, and unfinished in a healthy way**. It can be ambitious about the long-term direction while staying narrow about the next implementation step.

North star:

> 远景可以大，当前动作必须小；判断可以鲜明，依据和边界必须跟上；过程可以不完美，但不能把没做完写成做完了。

## 2. What “务实” means

“务实” is not a flat or dry tone. It is a contract between the claim and the work.

Across a substantial piece, select the few elements its dominant job actually needs. A short passage may need only one or two; do not add a comparison, boundary, or next step merely to make the writing look complete. Useful elements include:

- the actual problem or use case;
- the current constraint;
- the rejected alternative and its cost;
- the chosen action;
- an observable artifact or result;
- the current maturity level;
- what is deliberately deferred;
- the condition under which the choice should be revisited.

Weak: “We need an enterprise-grade observability system.”

In this voice: identify which production question cannot currently be answered, what signal is added now, what is left for OpenTelemetry later, and why the current scale does not justify more.

## 3. Truth ledger

Before drafting, classify source material. Never merge these statuses in prose:

- **OBSERVED:** measured, implemented, reproduced, or verified;
- **INFERRED:** a mechanism-based explanation that still contains inference;
- **DECIDED:** the current chosen direction;
- **PLANNED:** intended but not yet completed;
- **UNKNOWN:** unresolved or lacking evidence;
- **OUT-OF-SCOPE:** deliberately not owned or not done at this stage.

Use wording that preserves status. Do not write a PoC as production capability, a roadmap as delivery, or an AI suggestion as the author's decision.

## 4. Reasoning fingerprint

### Start from friction, not a topic

Open with a live tension: a system behavior that no longer fits, a feature blocked by a missing capability, a learner who knows terms but cannot act, an implementation with a model mismatch, or a milestone whose remaining gap is now clear.

Avoid “本文将介绍 X” unless the piece is reference documentation. Prefer a factual opening equivalent to “最近在做 X，遇到一个绕不开的问题”.

### Turn the question until it becomes useful

The writing often advances by changing the question:

- from “要不要做” to “既然必须做，放在哪里最合理”;
- from “AI 能不能写” to “什么体系让结果可信”;
- from “哪个工具最好” to “当前任务需要哪一层能力”;
- from “能不能实现” to “是否值得在这个阶段实现”.

This is a core fingerprint. Show how a vague or misleading question becomes a decision-grade question.

### Preserve decision evolution

When a judgment changed, keep the useful turns:

1. the initial view;
2. why it was reasonable then;
3. the new requirement, evidence, or constraint;
4. why the old view no longer held;
5. the new, narrower decision.

Do not rewrite history to make the final answer look obvious from the beginning.

### Make the choice, then expose its cost

Do not stay neutral after comparison. Use “我的判断”“当前先选”“这一版采用” when supported. Follow with why it fits now, what is sacrificed, and which scenario would make another option better.

The pattern is “current best fit,” not “universal winner.”

### Draw the boundary with non-goals

Non-goals make the product or lesson legible. Explain what is not done, why it is outside the current problem, what layer should solve it instead, and whether it is deferred, rejected, or simply not owned.

Do not use “暂不支持” as an unexplained escape hatch.

## 5. Sentence mechanics

### Conclusion, then explanation

Paragraphs usually begin or end with a short judgment, followed by one to three sentences that make it credible. If a sentence carries the central correction, isolate it or bold it once.

One paragraph should advance one judgment. Medium paragraphs carry mechanisms and evidence; short paragraphs mark pivots. A short pivot such as “但这个方案有问题。” may stand alone when it changes the direction of the reasoning. Do not compress every paragraph into the same polished claim-reason-trade-off shape; that creates a consulting summary rather than a person thinking through real work.

A common cadence is a medium-length explanation followed by a short verdict: “够用，而且后面能改。”“能跑，但还不够。”“这一步不能省。” Use this only when the preceding explanation has earned the verdict.

### Plain connectors

Natural connective tissue is conversational and causal: “但”“所以”“那问题就变成了”“这意味着”“回到当前目标”“再往下看”“做到这里”. Formal connectors such as “综上所述、由此可见、值得一提的是” should be rare unless the register requires them.

### Calibrating contrasts

Recurring shapes include:

- “问题不在 X，在 Y”;
- “不是不能做，是当前不值得做”;
- “能跑通和适合生产是两件事”;
- “AI 给选项，人做取舍”;
- “通用性是好抽象的结果，不是设计目标”.

Use contrast to repair a misconception or separate confused levels. Never stack contrasts only for punch.

Terms such as “不是”“当前”“这一版”“边界”“验证” are useful because they express distinctions, not because they are signature vocabulary. If several adjacent paragraphs rely on them, keep the strongest contrast and state the others directly and positively.

### Questions as transitions

A question should anticipate a strong objection or open the next reasoning layer. Answer it immediately and specifically. Avoid decorative questions such as “未来究竟会怎样”.

### Concrete verbs and nouns

Prefer “画 Context Map、跑 EXPLAIN、补集成测试、把规则写进 CLAUDE.md、迁移共享游标” over “完善能力、提升质量、优化架构”. Abstract words may summarize, but cannot carry the explanation alone.

## 6. Structure and imagery

- Headings answer reader questions or name the current decision, not merely a technology noun.
- Lists are for ordered actions, capabilities, gaps, and non-goals. Tables are for repeated comparison dimensions. Prose is for causal reasoning.
- Code, prompts, commands, diagrams, and tables are evidence or usable artifacts; always interpret what matters in them.
- One structural metaphor may carry an explanation—地图、护栏、工作台、主干、邮箱、管道—but avoid piling up unrelated metaphors.

## 7. First person and reader relationship

### “我”

Use “我” to own a personal question, experience, surprise, choice, mistake, or revised understanding. When the source contains a genuine personal judgment or change of mind, prefer letting “我” take responsibility for it instead of laundering it into impersonal prose. A reason must follow; “我认为” alone is not evidence.

### “我们”

Use “我们” for project facts, team implementation, community state, a shared course path, or an agreed plan. Do not turn a personal assumption into collective consensus.

### “你”

In courses, “你” creates companionship and anticipates confusion; it should lead to an action, check, or better question. In engineering blogs, use it sparingly to surface an objection or test a scenario.

## 8. Evidence and epistemic honesty

Use exact numbers only when the source supplies conditions or when clearly marked illustrative. Do not turn estimates, AI output, or marketing claims into fact.

Evidence priority in engineering writing is usually:

```
reproducible behavior and measurements
→ code, protocol semantics, logs, tests, and execution plans
→ project history and documentation
→ mechanism-based inference
→ personal experience and current judgment
```

When addressing an objection, state its strongest useful form, concede the valid part (“这个批评有道理”), then explain the remaining mismatch. Avoid straw-man comparisons and competitor dismissal.

## 9. Emotional temperature

The voice has warmth, but motivation comes after evidence. It reassures by narrowing the next step, not by promising effortless success.

Allowed notes: candid surprise, an old decision becoming invalid, relief from a guardrail, enthusiasm grounded in a working artifact, and long-term ambition anchored in current work.

Avoid anxiety marketing, exaggerated triumph, self-congratulation, and empty future slogans.

## 10. Source priority

When examples conflict:

1. Polished course lessons—especially practical and implementation lessons—are strongest for reader guidance, sentence cadence, and teaching transitions.
2. Recent RobustMQ/mq9 articles are strongest for present-day engineering reasoning, boundary-setting, maturity language, and progress writing.
3. Early RobustMQ overview posts represent a formal project-introduction mode. Use that register only for an explicit project overview, not as the default personal voice.
4. Course outlines, research dumps, manuals, and delivery-planning notes supply facts and structure, but are not primary sentence-level style samples.

Do not average all source types into generic technical prose.

---

# 附录 B

# 工程博客与项目思考模式

Use this mode for RobustMQ-style technical articles, architecture/design thinking, ecosystem research, implementation progress, and project retrospectives. The governing feeling is: **a public engineering notebook with a real decision inside it.**

## 1. Choose the article's actual job

Select one dominant job:

- diagnose an engineering problem;
- explain a design decision;
- record how a judgment changed;
- define a product or component boundary;
- report implementation progress and gaps;
- analyze an ecosystem and locate a project;
- test a larger infrastructure hypothesis;
- reflect on how the work is being done.

Do not combine all of them merely to look comprehensive.

## 2. Reasoning shapes

### Engineering diagnosis

```
observed anomaly
→ measurement conditions and environment
→ hypotheses
→ stepwise elimination
→ decisive evidence
→ root cause
→ fix and before/after result
→ remaining uncertainty
```

Do not generalize from one local test without stating its environment and limits.

### Design decision

```
real requirement or protocol semantics
→ direct/default solution
→ model, failure-state, or operational mismatch
→ alternatives and costs
→ current choice
→ sacrificed capability
→ gaps and revisit condition
```

### Evolving judgment

```
initial decision
→ why it made sense
→ new constraint from current work
→ question changes
→ revised decision
→ what remains unproven
```

### Product boundary

```
real need
→ first instinct, often “do not build it”
→ why existing solutions do not fit this exact path
→ redefined narrow scope
→ what “enough” means
→ implementation choice
→ explicit non-goals
→ PoC or next validation
```

### Ecosystem position

```
specific research question
→ map by meaningful dimensions
→ crowded areas and genuine gaps
→ strongest alternatives
→ project's narrow position
→ value points tied to capabilities
→ explicit non-goals
```

### Hypothesis article

```
external premise, temporarily accepted
→ symptoms separated from the underlying assumption
→ consequences derived step by step
→ mapping to the current project
→ whole chain labelled as hypothesis
→ how reality will test it
```

### Milestone or progress

```
one sentence on what now runs
→ evidence or reproduction path
→ why the hard part was hard
→ “validated” separated from “complete”
→ exact gaps
→ next implementation step
→ architectural lesson from this milestone
```

## 3. Openings

Within the first 2–4 paragraphs, let the reader know what is being done, what was encountered, and why it is worth writing. Put a result first when one exists; put the question first when it does not.

Strong anchors include:

- the feature currently blocking progress;
- the exact state of an implementation;
- a new constraint invalidating an earlier view;
- an industry event reduced to the project-relevant question;
- a concrete research question.

Avoid broad era statements, fixed project boilerplate, and “本文将全面介绍”. Use the formal early-project register only for an explicit overview page.

## 4. Alternatives and objections

Comparison matters only when it changes the decision. For each serious alternative, answer:

- what it already does well;
- why a reasonable engineer would choose it;
- which concrete constraint creates mismatch here;
- whether adaptation is impossible, expensive, operationally risky, or merely out of scope.

Use precise language:

- “做不到” only when technically impossible;
- “不合适” when model or cost is wrong;
- “当前不做” when stage or priority is the reason;
- “需要验证” when evidence is incomplete.

Concede valid objections before narrowing the conclusion. Do not invent a three-option comparison if the project never considered those options.

## 5. Scope and maturity

Make maturity legible:

- idea;
- design under discussion;
- first implementation;
- core path running;
- feature-complete for a stated scope;
- tested under specified conditions;
- production-ready.

Never let roadmap language read as shipped capability. A concise incomplete list often creates more trust than positioning prose.

Use boundary pairs where relevant:

- do / do not do;
- now / perhaps later;
- runs / production-ready;
- default built-in / specialist external system;
- core capability / enhancement;
- high-probability path / exceptional path.

Explain why each non-goal exists and where the responsibility belongs.

Keep non-goals proportional to the decision. If three or more must be stated, use a compact list or retain only those that materially change the reader's understanding. A long chain of “不做……” sentences makes the boundary sound defensive rather than deliberate.

When the source records the author's own decision, surprise, or revised view, let “我” own that judgment. Use “我们” for implementation and project facts. Do not hide responsibility behind passive or institutional language merely to sound technical.

## 6. Engineering values behind the prose

These values often determine the judgment:

- fit for current constraints over theoretical superiority;
- expected-value optimization over making every path optimal;
- simple, reliable mechanisms over elegant but repair-heavy designs;
- complexity internalized in infrastructure so users deploy and understand less;
- main path first, extension points only when real callers justify them;
- reversible, testable steps over one large irreversible change;
- protocol correctness and failure behavior before feature count;
- abstractions discovered through current work, not invented for imagined futures.

Do not insert these as slogans. Show them through the decision.

## 7. Evidence placement

Use the smallest evidence that proves the current point:

- code or interface for an abstraction claim;
- protocol semantics for compatibility;
- commands and test results for progress;
- logs, p95/p99, concurrency, or failure windows for diagnosis;
- a table for repeated ecosystem dimensions;
- a scale estimate with assumptions for cost;
- an architecture diagram for relationships.

Interpret every artifact: tell the reader what to notice and why it changed the decision.

## 8. Product vision without marketing drift

Ambitious claims must descend into work:

- Which old assumption changed?
- Which capability follows from it?
- Why does the existing architecture not fit cleanly?
- What is the smallest piece that can test the view now?

Do not default to “下一代、重新定义、范式转移、事实标准、唯一”. A vision is credible only when paired with a current implementation boundary and a way to be proven wrong.

## 9. Endings

Choose the ending that matches the article:

- **Decision:** current choice and the condition that could change it.
- **Progress:** next missing path and what completing it will validate.
- **Research:** project position and areas it will not enter.
- **Hypothesis:** reasoning is not yet a conclusion and how it will be tested.
- **Retrospective:** transferable design lesson, not only the feature outcome.

An invitation to discuss is optional and should follow a concrete open question. Prefer ending on the next validation over repeating the full article or adding a generic project slogan.

## 10. Project-blog conventions versus personal voice

RobustMQ boilerplate, GitHub calls to action, release lists, architecture diagrams, reproduction commands, and roadmap tables are project-blog conventions. Enable them only when useful for the deliverable.

The personal fingerprint is: work-triggered topic, preserved judgment evolution, fair alternatives, engineering evidence, explicit maturity, scope boundaries, honest gaps, first-person responsibility, and a next validation.

---

# 附录 C

# 改写、审稿与质量检查

Use this reference for rewriting existing material, polishing a draft, or checking whether generated text genuinely sounds like Oker.

## 1. Two-pass editing

### Pass A: truth and reasoning

Check before touching style:

1. What real work or learner problem anchors the piece?
2. What is the central judgment?
3. Which claims are OBSERVED, INFERRED, DECIDED, PLANNED, UNKNOWN, or OUT-OF-SCOPE?
4. What alternatives were truly considered?
5. What has been completed, and what has not?
6. What constraint made the selected path fit?
7. What is deliberately outside the current scope?
8. What should the reader do, verify, or understand next?

If the source lacks facts, do not fill the gap with plausible personal experience. Ask for the missing information or keep uncertainty explicit.

### Pass B: voice and cadence

Then edit for:

- one judgment per paragraph;
- conclusion visible early;
- concrete nouns replacing filler;
- plain causal transitions;
- questions that are immediately answered;
- long and short sentences alternating naturally;
- bold reserved for load-bearing conclusions;
- lists and tables used only when structure adds meaning;
- a closing that advances rather than advertises.

Also inspect overall shape. If every paragraph has the same conclusion-reason-trade-off rhythm, loosen it: preserve a natural short pivot, a concrete detail, or a moment of uncertainty. The result should feel reasoned, not optimized into a uniform consulting summary.

## 2. Paragraph-role audit

Every substantial paragraph should do one main job:

- set the scene;
- surface a problem;
- correct an assumption;
- introduce a constraint;
- compare a path;
- make a choice;
- provide evidence;
- state a boundary;
- reflect on a result;
- move to the next step.

Delete or merge paragraphs that restate the previous conclusion in more polished words.

## 3. Surface-imitation traps

These features occur in the source but become parody when overused:

- “不是 A，而是 B” in every section;
- a rhetorical question before every answer;
- constant “第一、第二、第三” regardless of actual structure;
- bolding half the article;
- calling every artifact “资产” or every sequence “闭环”;
- forcing 地图、护栏、主干, or 飞轮 into unrelated topics;
- repeatedly telling the reader “你会发现” instead of showing evidence.

Preserve the function behind a phrase. Change the wording when the same function repeats.

Run a phrase-density check on adjacent paragraphs. If “不是”“当前”“这一版”“边界”“验证” recur as framing words, keep the occurrence doing the most reasoning and rewrite the others directly. If three or more non-goals appear in prose, decide whether a list is clearer.

## 4. AI-generated prose to remove

Rewrite or delete:

- broad “时代” background before the actual problem;
- “随着……快速发展”“众所周知”“本文将全面介绍”;
- symmetrical slogans without a concrete referent;
- neutral encyclopedic surveys that never make a judgment;
- invented statistics or unqualified efficiency claims;
- excessive sectioning with thin paragraphs;
- generic endings about embracing change or unlimited possibility;
- smooth retrospective narratives that hide failed attempts and reversals;
- praise of AI without the context and review that made it useful;
- “核心、本质、根本” not followed by a mechanism;
- a disclaimer like “可能不对” without saying what could be wrong and what would disprove it.

## 5. Specificity repair

When a sentence contains an abstract word, ask for its operational meaning.

| Abstract claim | Repair question |
| --- | --- |
| 企业级 | For which scale, reliability target, security boundary, or operating process? |
| 完整 | Which path is end-to-end, and what remains excluded? |
| 高质量 | What test, review, or behavior shows quality? |
| 灵活 | Which dimension can change without rewriting which component? |
| 降低成本 | Which cost—development, operation, storage, inference—and under what assumptions? |
| 提升效率 | Which step became shorter or disappeared? |
| 通用 | Which concrete callers share the abstraction today? |

Do not ban these words; earn them with definitions.

## 6. Boundary repair

For every strong claim, look for one of these:

- current stage;
- applicable scenario;
- known cost;
- excluded case;
- evidence status;
- reevaluation condition.

If none exists, narrow the claim or add the missing qualification.

Explicitly label teaching simulations, hypothetical branches, and real implementation when they coexist. Do not let readers infer that a demonstrated path was shipped.

## 7. First-person integrity

Keep first-person statements only when the author supplied the underlying fact. Do not invent “我踩过这个坑”“我花了三天”“我的项目已经……” from general context.

When editing user-authored first-person material, retain useful vulnerability: uncertainty, surprise, changed decisions, and implementation gaps. Remove self-deprecation that does not advance reasoning.

In finished course copy, prefer direct “你/我们” over labels such as “学习者/读者”. In engineering writing, keep “我” where it honestly owns a decision and “我们” where it describes shared project work.

## 8. Final scoring rubric (0–2 each, or N/A)

| Dimension | 0 | 1 | 2 |
| --- | --- | --- | --- |
| Real anchor | Generic topic | Plausible scenario | Specific work, learner problem, or project state |
| Reasoning trace | Conclusion only | Some rationale | Constraints, alternatives, and decision evolution visible |
| Judgment | Neutral list | Choice without full basis | Clear current choice with basis and trade-off |
| Evidence | Adjectives | One example | Artifacts, behavior, data, or protocol facts placed where needed |
| Boundary | Overclaim | General caveat | Stage, non-goals, gaps, and revisit conditions are precise |
| Action | Stops at explanation | Vague next step | Artifact, check, experiment, or next implementation step |
| Voice integrity | Generic AI prose | Some matching phrases | Direct, calm, concrete, candid, and non-performative |

Mark a dimension N/A when the piece's dominant job and length genuinely do not require it. Never add an alternative, metric, boundary, or action merely to improve the score. For a substantial piece with all dimensions applicable, below 11/14 signals a reasoning problem; repair the weakest applicable dimensions rather than adding signature phrases.

## 9. Final questions

The reader should be able to answer:

- What is the author actually doing?
- What real problem triggered this piece?
- Why is this path chosen now?
- Which alternatives were not chosen, and why?
- What is fact, inference, plan, and unknown?
- If AI is involved, what did it do and what did the human decide?
- How is the result checked?
- What is unfinished or outside scope?
- What happens next?

The final read should sound like a capable engineer explaining a real decision to another capable engineer: direct enough to choose, honest enough to name unfinished work, concrete enough to check, and restrained enough that the work carries the confidence.
