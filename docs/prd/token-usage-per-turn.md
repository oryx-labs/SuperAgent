# 用量只记本轮：口径实测与交付说明

问题：Claude Code 用 `--resume` 续跑会话时，result 里的 `modelUsage` / `total_cost_usd` 是整个会话的累计值，平台每轮都把它当本轮用量记一次，同一段历史被反复计算。本文记录实测口径、修复方式和各 runner 的结论。历史数据不处理。

## 实测环境

- Claude Code 2.1.318，`--model sonnet`（解析为 `claude-sonnet-5-5`）；codex-cli 0.167.0。
- 在 `/tmp/usage-probe` 隔离目录里跑，用的是本机已有的 CLI 登录，没有读取或打印任何凭据。
- 录下的 stream-json 裁剪掉对话内容、只留用量字段，放在 `superagent-runtime/src/test/resources/usage/`，由 `RecordedUsageTest` 回放。

| 文件 | 内容 |
|---|---|
| `claude-turn1-new.jsonl` | 新会话第一轮（`--session-id`） |
| `claude-turn2-resume.jsonl` | 同一会话第二轮（`--resume c7b19a0e…`），调用了一次 Bash，共 2 次 API 调用 |
| `claude-kept-resumed-2turns.jsonl` | 常驻进程（`--resume` + `--input-format stream-json`）在同一会话上连续两轮 |
| `claude-repeated-message.jsonl` | 一次调用同时产出 text 和 tool_use 两个内容块 |
| `codex-turn1-new.jsonl` / `codex-turn2-resume.jsonl` / `codex-rollout-token-counts.jsonl` | Codex 新线程第一轮、`exec resume` 第二轮，以及该线程 rollout 文件里的 token_count |

## 结论 1：第二轮 result 的 `modelUsage` 和 `total_cost_usd` 是累计值

第一轮 result（新会话）：

```json
{"type":"result","session_id":"c7b19a0e-…","total_cost_usd":0.0441568,
 "modelUsage":{"claude-sonnet-5-5":{"inputTokens":8,"outputTokens":3,"cacheReadInputTokens":10908,"cacheCreationInputTokens":17208,"costUSD":0.0441568}}}
```

第二轮（`--resume`），本轮的两次 API 调用和 result：

```json
{"type":"assistant","message":{"id":"msg_011ChCVyE8FSPMvasYuwpWUi","model":"claude-sonnet-5-5","content":[{"type":"tool_use"}],"usage":{"input_tokens":8,"cache_read_input_tokens":28116,"cache_creation_input_tokens":32,"output_tokens":74}}}
{"type":"assistant","message":{"id":"msg_01IcoogNh5gNJkSgUXy0b1rQ","model":"MaaS_Cl_Sonnet_5.5_20260928_ULT","content":[{"type":"text"}],"usage":{"input_tokens":2,"cache_read_input_tokens":11148,"cache_creation_input_tokens":17250,"output_tokens":3}}}
{"type":"result","session_id":"c7b19a0e-…","total_cost_usd":0.0920782,
 "modelUsage":{"claude-sonnet-5-5":{"inputTokens":18,"outputTokens":80,"cacheReadInputTokens":50172,"cacheCreationInputTokens":34490,"costUSD":0.0920782}},
 "model_usage_since_last_result":{"claude-sonnet-5-5":{"inputTokens":10,"outputTokens":77,"cacheReadInputTokens":39264,"cacheCreationInputTokens":17282,"costUSD":0.0479214}}}
```

对照（输入 / 缓存读 / 缓存写 / 输出，花费）：

| | 输入 | 缓存读 | 缓存写 | 输出 | 花费 |
|---|---|---|---|---|---|
| 第一轮记录 | 8 | 10908 | 17208 | 3 | 0.0441568 |
| 第二轮记录（修复后） | 10 | 39264 | 17282 | 77 | 0.0479214 |
| **两轮之和** | **18** | **50172** | **34490** | **80** | **0.0920782** |
| CLI 第二轮报的累计 `modelUsage` / `total_cost_usd` | 18 | 50172 | 34490 | 80 | 0.0920782 |
| 修复前第二轮会记的（累计值） | 18 | 50172 | 34490 | 80 | 0.0920782 |

结论：
- 第二轮的 `modelUsage` 和 `total_cost_usd` 正好是两轮之和，是累计值。
- 修复后两轮各记一份，不重复，相加等于 CLI 报的最终累计值。
- 新版 CLI 额外给出 `model_usage_since_last_result`，它就是本轮的量（包括 CLI 自己的副调用），与本轮 assistant 消息之和完全一致（8+2=10，28116+11148=39264，32+17250=17282，74+3=77）。

## 结论 2：assistant 事件的 `message.usage` 是这次调用的最终值

- **单内容块的长回复**：一次调用输出 332 tokens，assistant 事件里的 `output_tokens` 是 332，与 result 的 332 一致。录制时加了 `--include-partial-messages`，这个版本的 CLI 只把 `message_start` / `message_delta` 发给每轮最后一次调用，且两者的 usage 与 assistant 事件相同。所以不需要用 `stream_event` 去补最终值。
- **一次调用有多个内容块**（`claude-repeated-message.jsonl`）：同一个 message id 会出现两次（text、tool_use），两次的 usage 都是最终值 91：

```json
{"type":"assistant","message":{"id":"msg_01qBMxpe3fYlZmpAeFGZuum1","content":[{"type":"text"}],"usage":{"input_tokens":2,"cache_creation_input_tokens":28457,"output_tokens":91}}}
{"type":"assistant","message":{"id":"msg_01qBMxpe3fYlZmpAeFGZuum1","content":[{"type":"tool_use"}],"usage":{"input_tokens":2,"cache_creation_input_tokens":28457,"output_tokens":91}}}
{"type":"assistant","message":{"id":"msg_01Y75O2G6o58XeIcZ68IEqHF","content":[{"type":"text"}],"usage":{"input_tokens":2,"cache_read_input_tokens":28457,"cache_creation_input_tokens":98,"output_tokens":3}}}
{"type":"result", … "model_usage_since_last_result":{"claude-sonnet-5-5":{"inputTokens":4,"outputTokens":94,…}}}
```

  按 message id 去重后为 91+3=94，与 result 一致；不去重会算成 185。

- **注意**：走中转时，assistant 里的模型名可能是中转自己的叫法（`MaaS_Cl_Sonnet_5.5_…`），按这个名字查不到价格。result 里只有一个模型时，平台把这些调用记到 result 的模型名下。

## 结论 3：常驻进程多轮

常驻进程以 `--resume c7b19a0e…` 启动，连续两轮（`claude-kept-resumed-2turns.jsonl`）：

| | 本轮调用（assistant） | `model_usage_since_last_result` | 累计 `modelUsage` 的输出 | `total_cost_usd` |
|---|---|---|---|---|
| 进程第 1 轮 | 2 / 28398 / 47 / 3 | 2 / 28398 / 47 / 3，$0.0029913 | 83（= 之前两轮 80 + 3） | 0.0950695 |
| 进程第 2 轮 | 8 / 0 / 28291 / 3 | 8 / 0 / 28291 / 3，$0.0707735 | 86 | 0.16584300000000002 |

结论：常驻进程的累计值从会话开头算起，而不是从进程启动算起。原来按进程做差值的写法，在进程第一轮会把之前的历史全部记进去（这一轮会记成 20/78570/34537/83）。修复后两轮分别记 2/28398/47/3 和 8/0/28291/3。

## 修复方式

改动集中在 superagent-runtime，下游不变。

- **`ClaudeStreamParser`**：result 事件只交出原始数据：`model_usage_since_last_result`（有的话）、累计 `modelUsage` / `total_cost_usd`、session id、本轮 assistant 调用之和（按 message id 去重）。
- **`ClaudeUsageLedger`**：决定本轮记多少，普通运行和常驻进程两条路径共用：
  1. CLI 给了 `model_usage_since_last_result`：直接用，花费取其中 costUSD 之和；
  2. 否则，已知这个会话上一次的累计值：本轮 = 累计 − 上一次（新会话第一轮减 0），花费同样做差；
  3. 否则（例如服务重启后的第一轮 resume）：记本轮 assistant 调用之和，不发 USAGE。
- **中断的轮次**（没有 result）：记已出现调用之和，同时清掉这个会话的基线，防止下一轮做差值时把它又算一遍。
- **副调用**（CLI 内部用更便宜的模型起标题等，只出现在 `modelUsage` 里，不出现在 assistant 事件里）：
  - 第 1 种情况由 CLI 本身算进本轮；
  - 第 2 种情况：新会话第一轮全记，之后按差值记；
  - 第 3 种情况：无法和历史区分，这一轮不记（宁漏不重）。
  - 单测：`ClaudeUsageLedgerTest.aSideCallSeenOnlyInTheTotalsIsBilledInFullFirstThenByDifferenceAndNotWithoutABaseline`。
- **花费**：USAGE 只发本轮金额。任务 spend 有价格时用平台按 TOKENS 估算的金额，没有价格时才用 USAGE，两者现在都是本轮值；对话只按 TOKENS 记账。所以 TaskService 和 agent 模块不用改。
- **Codex**：`exec resume` 前从 `$CODEX_HOME/sessions/**/rollout-…-<thread>.jsonl` 读出该线程最后一次 `total_token_usage`，本轮报数减去它。这个基线存在文件里，服务重启后也能用。找不到 rollout 时这一轮不记，并打 warn。

## 各 runner 结论

| runner | 是否「累计当本轮」 | 依据 | 处理 |
|---|---|---|---|
| Claude Code（含 GLM，走 Claude Code） | 是 | 实测（见上） | 已修，`ClaudeUsageLedger` |
| Codex | 是：`exec resume` 的 `turn.completed.usage` 是线程累计（第二轮输出 10 = 5+5，输入 41825 = 20886+20939），与 rollout 的 `total_token_usage` 相同 | 实测（`codex-*.jsonl`） | 已修，`CodexRollouts`，单测 `RecordedUsageTest.aResumedCodexTurnIsBilledWhatItAddedToTheThread` |
| DeepSeek（dsh） | 否：每一步的 `status/step_end` 带本步 usage，平台在本次运行内累加 | 源码 | 不改 |
| Kimi | 否：stream-json 不带用量，平台只读本次运行在 wire.jsonl 里新追加的 assistant usage | 源码 | 不改 |
| Qwen Code | 否（推断）：读 result 的 `usage`。在 Claude Code 里这个字段实测是本轮主循环的量，Qwen 的结构照搬 Claude，按源码每次运行新建统计 | 源码，未实测（本机未安装） | 不改 |
| CodeBuddy | 否（推断）：同 Qwen，读 result 的 `usage`；实测 Claude Code 的这个字段是本轮值 | 格式与 Claude Code 一致，未实测（本机未安装） | 不改 |
| Gemini CLI | 否（推断）：读 result 的 `stats`，由进程内的统计生成，每次运行是一个新进程 | 源码，未实测（本机未安装） | 不改 |
| MiniMax（mcode） | 否（推断）：读本次运行 `exec.completed` 的 `result.usage` | 源码，未实测（本机未安装） | 不改 |
| Grok（ACP） | 否（推断）：只取 `turn_completed`（或单独的 `usage`）更新，每轮只取一次；`response_completed` 是单次调用的局部量，已忽略 | 源码，未实测（本机未安装） | 不改 |

## 验证

- 单测：
  - `RecordedUsageTest`：用真实录制回放，覆盖 resume 两轮之和 = CLI 累计、常驻进程以 resume 启动后多轮、重复消息只算一次、runner 端到端、Codex resume；
  - `ClaudeUsageLedgerTest`：新会话首轮、resume 差值、基线未知、副调用、中断后不重复、普通运行多轮的 USAGE；
  - `ClaudeWarmSessionsTest`：常驻进程以 resume 启动后连续 3 轮；
  - `TokenCountingTest`：Codex 差值。
- 命令：`mvn -pl superagent-runtime -am test`、`mvn -pl superagent-web -am test`。

## 遗留风险

- 服务器上的 CLI 如果比 2.1.318 老、没有 `model_usage_since_last_result`：重启后每个会话的第一轮不记花费，副调用也漏记；从第二轮起靠差值恢复正常。
- Claude 的基线只存在内存里，最多记 1 万个会话，按最近使用淘汰。
- 中断轮次里，如果 result 没出现、而调用的模型名是中转的叫法，按这个名字查不到价格，金额会缺失（token 照记）。
- 标「推断」的 runner 没有用真实 CLI 验证，装上之后可以按同样的方法录两轮确认。
- 历史数据没有处理。
