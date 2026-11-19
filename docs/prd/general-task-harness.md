# 普通任务四步流程与任务详情页改版（PRD）

## 背景
普通任务（general）现在只跑一轮，提示词里写「交付前自己检查一遍」，Agent 说自己通过就算通过。详情页把运行日志平铺出来，看不出现在到了哪一步、交付了什么、有没有经过检查。编码任务已经有一套 harness（`com.superagent.task.harness`），普通任务照这个框架来做。

## 需求
- **R1 明确标准**：把确认表或任务说明里的「交付物」「验收标准」拆成可以逐条核对的清单（交付物带期望的文件类型）。这一步不请用户确认。标准写得含糊时，Agent 自己补全成具体条目，标上 `agentAdded`。
  验收：清单存进 HarnessState；Agent 补全的条目在「标准」标签里显示「由 Agent 补全」。
- **R2 执行**：沿用现有的普通任务提示词（NEEDS_CONFIRMATION 提问、read_link 读资料都保留），产出用 file_write 或 file_save 交付；第 2 轮起，提示词里带上没通过的条目。
  验收：Agent 提问时任务照旧停在需要确认；复杂任务的子任务不停下来。
- **R3 平台硬核对**（不调用模型）：
  - 声称交付的文件确实存在，并且不是空文件；
  - 文件类型和交付物要求一致；
  - 资料都读到了：说明里的每个链接都有成功的 read_link，附件在 uploads/ 里；
  - 有固定格式的文件能被解析：srt（序号和时间轴）、json、csv（列数一致）、md（非空）。
  验收：单测里空文件、类型不对、srt 写错时，核对都不通过。
- **R4 独立检查**：开一个新会话，只给它清单、产出内容（文本文件截断后附上）和最终回复，不给执行过程。模型由 `TaskModelSelector.checker` 选：用于任务的模型里最便宜的那个；开了「任务固定模型」时，从固定模型里选最便宜的。逐条给出 ✅/❌ 和依据，最后一行是 `VERDICT: PASS|FAIL`，由平台解析。缺了 VERDICT 行就当 FAIL。平台硬核对没通过时，这一轮不调用模型。
  验收：单测断言检查用的模型和 CLI 是选出的那个。
- **R5 退回**：没通过就带着失败的条目退回执行，最多退 2 轮（最多执行 3 次）。超过就停在需要确认（NEEDS_CONFIRMATION），把剩下的问题列出来。用户回复修改意见会再跑一轮；回复「确认交付」就照现在的样子交付。
- **R6 没有文件产出**：任务没有交付文件、清单里也没有文件类交付物时，检查对象是最终回复。
- **R7 交付**：输出里汇总产出文件和一段简短说明，作为任务结果，沿用现有机制发回原来的对话。
- **R8 复杂任务**：子任务（general）各自走 R1–R7。主任务汇总那一轮结束后，对汇总结果再做一次 R3/R4 检查，没通过就退回汇总，规则和 R5 一样；检查记录存进主任务的 harness 状态，详情页同样显示。
- **R9 详情页**：
  - 顶部卡片显示状态、模型（另外标出检查用的模型）、耗时、花费、当前步骤；
  - 步骤条：明确标准 → 执行 → 检查 → 交付，退回过的标出轮次，比如「执行 · 第 2 轮」；
  - 标签依次为：产出、标准、过程、检查，然后才是 Prompt、模型选择、成本、耗时、会话；
  - 打开任务时默认显示「产出」；
  - 「过程」按步骤和轮次分段，工具调用默认折叠；
  - 中英文 i18n 都要补；风格用黑白灰加品牌蓝。
- **R10 兼容**：没有 harness 数据的旧任务，详情页照现在的样子显示；编码任务的视图不变。

## 技术思路
- 新建 `harness/general/GeneralHarness`（kind=general，四个步骤：criteria/execute/check/deliver）、`GeneralPrompts`（各步提示词和解析）、`OutputChecks`（平台核对，纯函数，方便单测）。
- `HarnessState` 增加普通任务要记录的字段：`criteria` 加上 `agentAdded` 和 `kind`；新增 `deliverables` 和 `checkRounds`（每轮的平台核对、检查结论、问题、模型）；新增 `executeRounds`。
- 对应地扩展 `HarnessDto` 和 `HarnessViews`。
- `HarnessHost` 增加四个方法：
  - `turnOn(model, cli, …)`：用指定模型跑一轮；
  - `checkerModel(task)`：选检查用的模型；
  - `producedFiles(task)`：取任务产出的文件；
  - `readLinks(task)`：取运行事件里成功的 read_link。
- core 的 `TaskModelSelector` 增加 `default Optional<TaskModelChoice> checker(Task)`，由 cost 的 `TaskModelRouter` 实现。没有选择器时，用任务自己的模型。
- `TaskService`：
  - 把 general 交给 harness（只用于新建的任务；旧任务没有 harness 状态，仍走单轮执行）；
  - 复杂任务汇总那一轮结束后，调用 `GeneralHarness` 的检查。
- 前端：`task-harness.js` 按 `harness === "general"` 渲染新视图；`index.html` 调整标签顺序；`i18n.js` 补中英文。

## 样式稿
见文件「general-task-detail-mock.html」，可以直接在浏览器里打开，能切换三种场景：已完成、停下等确认、旧任务。
