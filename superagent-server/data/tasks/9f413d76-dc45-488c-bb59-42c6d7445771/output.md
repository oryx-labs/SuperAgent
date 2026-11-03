All green. Everything is implemented and verified.

## 交付总结

实现了「agent 通过 MCP 创建任务后，聊天流里立即出现可点击任务链接」的体验，前端为主 + 少量后端改动。

### 改动文件

**后端（把 tool_result 透出到流）**
- `superagent-worker/.../agent/ClaudeStreamParser.java`：新增 `case "user"` 分支与 `parseUser`/`toolResultText` 方法。**tool_result 的解析位置**：Claude stream-json 中工具返回值出现在 `type == "user"` 的消息里，其 `message.content` 数组中 `type == "tool_result"` 的 block；`block.content` 可能是纯字符串或 `[{type:text,text:...}]` 数组，两种都取出文本，产出 `AgentEventType.TOOL_RESULT` 事件（payload = 工具返回文本）。未命中（如 `message:{}`）仍返回空，不破坏既有 TEXT/TOOL_USE/RESULT 解析。
- `superagent-worker/.../agent/ClaudeStreamParserTest.java`：新增 `surfacesToolResultText` 单测，覆盖数组形与字符串形两种 content。
- ChatService 无需改动：`runTurn` 的 sink 对所有事件统一 `send`（type = `tool_result`），`TOOL_RESULT` 落在 switch 的 `default` 分支被忽略，不进 textAccumulator/resultText —— 符合要求。`AgentEventType.TOOL_RESULT` 枚举本已存在。

**前端（渲染可点链接）**
- `app.js`：`handleFrame` 新增 `case "tool_result"`；新增 `parseCreatedTask`（正则 `/已创建任务\s+([0-9a-fA-F-]{36})（([^）]+)）/`，只匹配 create_task 的成功文案，非创建类结果不渲染）与 `appendTaskLink`（在 assistant 气泡内追加按钮式任务卡片，标题用 `textContent` 设置避免 XSS，点击 `setView("tasks") + openTask(id)` 跳转详情，不轮询、不等执行完成）。`/task` 斜杠命令链路保持原样。
- `i18n.js`：新增 `chat.openTask`（中/英）。
- `app.css`：新增 `.task-link` 样式（黑白灰底 + 品牌蓝 `--accent` 点缀，与现有风格一致）。

### 验证
- `node --check app.js` / `i18n.js`：通过
- `mvn -pl superagent-worker -am -DskipTests compile`：通过
- `mvn -pl superagent-worker test`：Tests run 3, Failures 0, Errors 0, BUILD SUCCESS

### 验收对照
1. ✅ 创建成功即在 assistant 消息内出现带任务名的可点击链接，点击跳详情
2. ✅ 不等执行完成（create_task 的 tool_result 一到就渲染）
3. ✅ 非创建类 tool_result 不污染气泡（仅匹配「已创建任务」前缀）
4. ✅ 任务标题用 `textContent`，无 XSS
5. ✅ 后端编译 + 单测通过；前端 `node --check` 通过

注：在你已有的 `renderMarkdown` 等未提交改动基础上做的增量修改，未回退它们。