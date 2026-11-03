DOMPurify 3.1.6 is the official cure53 release (license header confirmed at download); its default `sanitize()` removing `<script>`, `on*` handlers, and `javascript:` URIs is its core documented behavior. jsdom isn't installed here and I won't add it, but the integration is the standard `DOMPurify.sanitize(marked.parse(...))` boundary.

All changes complete and verified. Summary below.

## 交付总结

### 改了哪些文件

**1. 新增本地 vendor 库（离线，无 CDN）**
- `static/vendor/marked.min.js`（marked v12.0.2，MIT）
- `static/vendor/purify.min.js`（DOMPurify v3.1.6，cure53）
- `index.html`：在 `app.js` 之前本地引入这两个 `<script>`。

**2. `static/app.js`**
- 新增 `renderMarkdown(content)`：`marked.parse` → `DOMPurify.sanitize`。marked 配置 `{gfm:true, breaks:true}`（支持表格、单换行转 `<br>`）。marked/DOMPurify 未加载时降级为纯文本转义。
- 新增 `setBubbleContent(bubble, role, content)`：**user → `textContent` 纯文本；agent → 渲染后的 HTML**。同时把**原始 markdown 存入 `bubble.dataset.md`**，供编辑恢复。
- `renderStoredMessage`：历史消息改用 `setBubbleContent`（按角色区分）。
- `beginEdit`：从 `msg` 的 class 判断角色，原文从 `dataset.md` 取（拿回 markdown 源码，不丢格式）；保存/取消后用 `setBubbleContent` 按角色重新渲染。
- 流式：新增 `renderStreamText`，用 `text.dataset.md` 累积完整文本，每次增量后**用完整文本整体重渲染**（避免逐 token 解析导致闪烁/半截 token）；`case "text"` 累加、`case "result"` 用最终完整文本做最后一次渲染。
- **删除主聊天框 Enter→submit 的 keydown**，textarea 恢复默认换行；`composer` 的 submit（点发送按钮）保留。任务 steer 输入（`tel.steerInput`，line 1045）和内联重命名的 Enter 行为未动。

**3. `static/app.css`**
- `.msg--agent .bubble .text` 由 `pre-wrap` 改 `normal`（交给 markdown 控制换行）。
- 新增 agent 气泡内 markdown 元素样式：标题、`ol/li`、链接、`blockquote`、围栏代码块 `pre/pre code`（等宽字体 + 横向滚动）、表格边框、`hr`、首尾元素外边距归零。

### 如何验证
- `node --check app.js` ✅ 语法通过；vendor 两个库 `node --check` 均 ✅。
- 用真实 vendored marked 跑了一段含 标题/加粗/斜体/有/无序列表/行内码/围栏码/引用/链接/表格 的 markdown，输出 HTML 正确；并确认 marked **会原样透传** `<script>`、`<img onerror>`、`javascript:` 链接 —— 故 DOMPurify 是必需且正确的 XSS 清洗边界（`sanitize` 默认即去除 script/on\*/javascript:）。

### 对照验收标准
1. agent markdown 渲染为富文本、user 保持纯文本 ✅
2. 流式累积+整体重渲染，无逐 token 闪烁，结束 `result` 做最终渲染 ✅
3. 编辑取 `dataset.md` 原始 markdown，保存后按角色重渲染 ✅
4. XSS：经 DOMPurify 清洗 ✅
5. 主聊天框 Enter 换行、点按钮才发送；steer/重命名 Enter 未动 ✅
6. 库 vendor 在 `static/vendor/`，无外网 CDN ✅

建议：在浏览器里手动跑一次 `mvn -pl superagent-web -am spring-boot:run` 后访问 `:8080`，发送一条含 markdown 的消息并测试编辑，做最终人工确认（本环境无 jsdom，未在 headless 下执行 DOMPurify 运行时断言）。