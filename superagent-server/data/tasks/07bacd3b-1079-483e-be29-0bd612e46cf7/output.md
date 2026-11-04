## 验收结论：通过 ✅

环境**没有无头浏览器**（Playwright 需联网下载 `playwright@1.64.1`，被离线环境拦截；jsdom 也未安装）。按要求**如实说明并降级**为「构建通过 + 关键 DOM 断言」，**未编造任何截图**。

### 证据

**1. JS 语法检查**
```
JS_SYNTAX_OK   (node --check app.js)
```

**2. Maven 构建 + 测试（superagent-web -am，离线）**
```
Tests run: 18, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```
现有 web/core 测试全绿，改动未破坏后端与 i18n 相关装配。

**3. 路由逻辑断言（加载真实 `app.js`，驱动真实 `setView`/`initialView`/`hashchange`/初始化恢复，非重写复制）**

用最小自动桩 DOM 在 Node 的 `vm` 里运行真实 `app.js`，断言导航高亮（`is-active`）与各 `[data-pane].hidden` 的真实状态：

| 场景 | 结果 |
|---|---|
| 空 hash 首次加载 → 默认 `chat`，并写入 `#chat` | PASS |
| `#tasks` 刷新 → 停在 tasks（非 Agent），tasks 面板可见、chat 隐藏 | PASS |
| 管理员 `#connectors` 刷新 → 停在 connectors | PASS |
| **非管理员** `#users` 刷新 → 回落 `chat` 并改写 hash 为 `#chat` | PASS |
| 非法 `#bogus` → 回落 `chat` | PASS |
| `#models` 加载后 `hashchange` 到 `#tasks`（模拟后退/前进）→ 视图切到 tasks | PASS |

```
ALL ASSERTIONS PASSED
```

这覆盖了验收标准三条：各主要页面刷新后停留原页、首次无状态进默认页、前进/后退一致；并额外验证了「非管理员访问管理员视图」的安全回落。临时 harness 文件已清理。

### 对照验收标准
- 每个主要页面刷新后停留原页 → 覆盖（tasks/connectors/models 用例 + hashchange）。
- 首次无状态访问进默认页 → 覆盖（空 hash / 非法 hash 均回落 chat）。
- 不破坏切换、i18n、样式 → 后端 18 测试通过；仅改 `app.js`，未动 `index.html`/`app.css`/`i18n.js`；语法校验通过。

说明：受限于离线环境无法做像素级截图，真实浏览器中的视觉确认建议在联网环境用 Playwright 对 `#tasks`/`#connectors` 刷新各截一张补充，但功能正确性已由上述加载真实代码的断言覆盖。