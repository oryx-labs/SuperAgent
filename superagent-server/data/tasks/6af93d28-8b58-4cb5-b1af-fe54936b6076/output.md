The implementation is complete and consistent. All changes are in place.

## 交付总结

### 问题
task 详情页事件流 `appendStreamItem` 用 `textContent` 渲染，导致 agent 的 `text`/`result` 事件把 markdown 源码（`#`、```、列表、加粗等）直接显示为纯文本。

### 改动文件

**`superagent-web/src/main/resources/static/app.js`**（约 937–988 行）
- 新增 `MD_KINDS = { text: 1, result: 1 }` 标记需渲染 markdown 的 kind。
- 抽出小助手 `setEvBody(bodyEl, kind, fullRaw)`：markdown kind 把完整源码存 `dataset.md` 后用 `renderMarkdown` 整体重渲染 `innerHTML`（复用现成的 marked+DOMPurify，不绕过清洗）；其余 kind 走 `textContent`。
- 合并累积路径：从 `dataset.md`（markdown kind）或 `textContent`（其它）取已累积内容，追加新 payload 后统一经 `setEvBody` 重渲染——像 `renderStreamText` 一样整体重渲染，避免逐 token 闪烁。
- 新建事件路径同样走 `setEvBody`，两条路径逻辑统一。
- `thinking/tool_use/tool_result/cmd/error/system` 不在 `MD_KINDS` 里，仍为纯文本。

**`superagent-web/src/main/resources/static/app.css`**
- 把 markdown 元素样式选择器（`h1-h4`、`ul/ol/li`、`code`、`pre`、`blockquote`、`table`、`a`、`hr` 等）从 `.bubble ...` 扩展为同时覆盖 `.ev__body ...`。
- `pre` 已有 `overflow-x: auto`，窄面板内代码块可横向滚动。
- 新增 `.ev--text .ev__body, .ev--result .ev__body { white-space: normal; }`——因 `.ev__body` 默认 `pre-wrap` 会把渲染后 HTML 标签间的换行当空白，渲染 markdown 时需改为 normal；其它纯文本 kind 仍保留 `pre-wrap`。

### 验收对照
1. ✅ text/result 渲染为富文本（标题/列表/代码块/加粗/链接/表格）。
2. ✅ 增量追加时整体重渲染 `dataset.md`，无逐 token 闪烁。
3. ✅ thinking/tool/cmd/error/system 仍为纯文本。
4. ✅ `<script>`/`onerror` payload 经 `renderMarkdown` 的 DOMPurify 清洗，不会执行。
5. ✅ `node --check app.js` 通过。

作用域仅限 task 详情页事件流；聊天页、artifact、facts、status 未改动。

（提醒：需 `mvn -pl superagent-web install -DskipTests` 重新打包静态资源，由你手动执行。）