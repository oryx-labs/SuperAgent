"""Draws SuperAgent's architecture and capability diagrams as SVG, in English and Chinese.

Run from the website directory:  python3 scripts/diagrams.py public/diagrams
then copy public/diagrams/architecture-en.svg to ../docs/images/superagent-architecture.svg (used by the README).
A box whose text may not fit is reported as "overflow"; widen the box or shorten the text."""
import sys, os
from xml.sax.saxutils import escape

OUT = sys.argv[1]
FONT = "-apple-system, 'Segoe UI', 'PingFang SC', 'Hiragino Sans GB', 'Microsoft YaHei', 'Noto Sans CJK SC', sans-serif"
INK, SUB, MUTED, BLUE, LINE, LANE, ACCENT_BG = "#10151f", "#51607a", "#8b97ac", "#2563eb", "#d7e2f5", "#f7f9fc", "#eaf1ff"

def width(text, size):
    return sum(size * (1.0 if ord(c) > 0x2E80 else 0.53) for c in text)

class Svg:
    def __init__(self, w, h):
        self.w, self.h, self.parts, self.warn = w, h, [], []
    def rect(self, x, y, w, h, fill="#fff", stroke=LINE, rx=12, sw=1.2, dash=None):
        d = f' stroke-dasharray="{dash}"' if dash else ""
        self.parts.append(f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="{rx}" fill="{fill}" stroke="{stroke}" stroke-width="{sw}"{d}/>')
    def text(self, x, y, s, size=14, color=INK, weight=400, anchor="start", limit=None):
        if limit and width(s, size) > limit:
            self.warn.append(f"overflow ({width(s, size):.0f}>{limit}): {s}")
        self.parts.append(f'<text x="{x}" y="{y}" font-size="{size}" fill="{color}" font-weight="{weight}" text-anchor="{anchor}">{escape(s)}</text>')
    def arrow(self, x1, y1, x2, y2):
        self.parts.append(f'<line x1="{x1}" y1="{y1}" x2="{x2}" y2="{y2}" stroke="{BLUE}" stroke-width="2" marker-end="url(#arr)"/>')
    def box(self, x, y, w, h, title, lines=(), accent=False, center=False):
        self.rect(x, y, w, h, ACCENT_BG if accent else "#fff", BLUE if accent else LINE, 10, 1.4 if accent else 1.2)
        tx = x + w / 2 if center else x + 14
        anchor = "middle" if center else "start"
        top = y + (h - 20 - 18 * len(lines)) / 2 + 16 if center else y + 26
        self.text(tx, top, title, 15, INK, 600, anchor, w - 24)
        for i, l in enumerate(lines):
            self.text(tx, top + 22 + i * 19, l, 12, SUB, 400, anchor, w - 20)
    def lane(self, x, y, w, h, label):
        self.rect(x, y, w, h, LANE, "#e2e8f2", 14, 1)
        self.text(x + 18, y + 26, label, 13, BLUE, 700)
    def save(self, path):
        head = (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {self.w} {self.h}" width="{self.w}" height="{self.h}" '
                f'font-family="{FONT}"><defs><marker id="arr" viewBox="0 0 10 10" refX="8" refY="5" markerWidth="7" '
                f'markerHeight="7" orient="auto-start-reverse"><path d="M0 0L10 5L0 10z" fill="{BLUE}"/></marker></defs>'
                f'<rect width="{self.w}" height="{self.h}" fill="#ffffff"/>')
        open(path, "w", encoding="utf-8").write(head + "".join(self.parts) + "</svg>\n")
        for w_ in self.warn:
            print(os.path.basename(path), w_)

T = {
 "en": dict(
   entry="Entry", work="Collaboration & execution", base="Runtime", data="Data", gov="Governance",
   e=[("Employees", ["Web console"]), ("Feishu", ["The company app"]), ("Schedules & API", ["Automations"])],
   sa=("SuperAgent", ["Plan → team → assign → report", "One for every employee"]),
   w=[("Agents", ["Persona · memory · skills", "Several people at once"]), ("Tasks", ["Session pool · watchdog", "Coding flow · approvals"]),
      ("Automations", ["Schedule · cron · API", "One-off runs"])],
   b=[("Model runners", ["Claude Code · Codex · DeepSeek", "Kimi · Qwen · GLM", "Key pool · failover · tiers"]),
      ("Tools (MCP)", ["SuperAgent's built-in MCP", "GitHub · Gitee", "The company's own MCP servers"]),
      ("Company assets", ["Files · folders · tags", "Agent memory · skills", "Run logs"])],
   d=[("Database", ["MySQL or ClickHouse"]), ("File storage", ["On the server or S3-compatible"])],
   g=["Identity & permissions", "People approve", "Encrypted secrets", "Per-run credentials", "Run logs", "Spend by task & model"],
   # capability map
   c_emp="For every employee", c_ext="Coming in from outside", c_adm="For administrators", c_base="Foundation",
   emp=[("To-do", "What needs you"), ("Agents", "Persona · memory · skills"), ("SuperAgent", "Plans and runs a team"),
        ("Collaboration", "Many people, one agent"), ("Tasks", "Requirement → delivery"), ("Automations", "Schedule · cron · API"),
        ("Files", "Folders · tags · search"), ("Skills", "Personal · company"), ("Connectors", "Authorize as yourself")],
   ext=[("Feishu", "Messages → SuperAgent"), ("GitHub · Gitee", "Each person's own account"), ("Custom MCP", "Any tool, any auth"),
        ("Message stream", "What SuperAgent did")],
   adm=[("Users & org", "Provisioned access"), ("Models", "CLIs · key pool · tiers"), ("Analytics", "Tasks · spend by model"),
        ("System settings", "Basic · integrations · files")],
   fnd=[("Model runners", "6 coding CLIs"), ("Built-in MCP", "Checked at startup"), ("Storage", "MySQL · ClickHouse · S3"),
        ("Security", "Approvals · encryption · logs")],
 ),
 "zh": dict(
   entry="入口", work="协作与执行", base="运行底座", data="数据", gov="治理",
   e=[("员工", ["Web 控制台"]), ("飞书", ["公司应用"]), ("定时与 API", ["自动化"])],
   sa=("SuperAgent", ["计划 → 组队 → 派活 → 回报", "每位员工一个"]),
   w=[("Agent", ["性格 · 记忆 · Skills", "多人同时协作"]), ("任务", ["会话池 · 看门狗", "编码流程 · 等人确认"]),
      ("自动化", ["定时 · Cron · API", "一次性运行"])],
   b=[("模型运行器", ["Claude Code · Codex · DeepSeek", "Kimi · Qwen · GLM", "Key 池 · 失败切换 · 档位"]),
      ("工具（MCP）", ["SuperAgent 内置 MCP", "GitHub · Gitee", "公司自定义 MCP 服务"]),
      ("公司资产", ["文件 · 文件夹 · 标签", "Agent 记忆 · Skills", "运行日志"])],
   d=[("数据库", ["MySQL 或 ClickHouse"]), ("文件存储", ["服务器本地或 S3 兼容存储"])],
   g=["身份与权限", "人来拍板", "密钥加密", "每次运行单独授权", "运行日志", "按任务、按模型看花费"],
   c_emp="员工使用", c_ext="外部接入", c_adm="管理后台", c_base="底座",
   emp=[("待办", "需要你处理的事"), ("Agent", "性格 · 记忆 · Skills"), ("SuperAgent", "做计划、带团队"),
        ("协作", "多人同一个 Agent"), ("任务", "从需求到交付"), ("自动化", "定时 · Cron · API"),
        ("文件", "文件夹 · 标签 · 查找"), ("Skills", "个人 · 公司"), ("连接", "以自己的身份授权")],
   ext=[("飞书", "消息 → SuperAgent"), ("GitHub · Gitee", "员工各自的账号"), ("自定义 MCP", "任何工具、任何授权"),
        ("消息流", "SuperAgent 的处理")],
   adm=[("用户与组织", "预先开通"), ("模型", "CLI · Key 池 · 档位"), ("分析", "任务 · 按模型花费"),
        ("系统设置", "基本 · 连接配置 · 存储")],
   fnd=[("模型运行器", "6 个编码 CLI"), ("内置 MCP", "启动时自检"), ("存储", "MySQL · ClickHouse · S3"),
        ("安全", "人工确认 · 加密 · 日志")],
 ),
}

def architecture(t, path):
    s = Svg(1200, 720)
    L, R = 30, 960              # main area; governance band to the right
    # entry
    s.lane(L, 20, R - L, 120, t["entry"])
    bw = (R - L - 40 - 2 * 20) / 3
    for i, (ti, li) in enumerate(t["e"]):
        s.box(L + 20 + i * (bw + 20), 56, bw, 66, ti, li, center=True)
    s.arrow((L + R) / 2, 142, (L + R) / 2, 168)
    # collaboration & execution
    s.lane(L, 172, R - L, 160, t["work"])
    s.box(L + 20, 208, 220, 106, t["sa"][0], t["sa"][1], accent=True)
    x0 = L + 20 + 220 + 40
    ww = (R - 20 - x0 - 2 * 16) / 3
    s.arrow(L + 20 + 220 + 4, 261, x0 - 6, 261)
    for i, (ti, li) in enumerate(t["w"]):
        s.box(x0 + i * (ww + 16), 208, ww, 106, ti, li)
    s.arrow((L + R) / 2, 334, (L + R) / 2, 360)
    # runtime
    s.lane(L, 364, R - L, 186, t["base"])
    bw = (R - L - 40 - 2 * 20) / 3
    for i, (ti, li) in enumerate(t["b"]):
        s.box(L + 20 + i * (bw + 20), 400, bw, 132, ti, li)
    s.arrow((L + R) / 2, 552, (L + R) / 2, 578)
    # data
    s.lane(L, 582, R - L, 118, t["data"])
    dw = (R - L - 40 - 20) / 2
    for i, (ti, li) in enumerate(t["d"]):
        s.box(L + 20 + i * (dw + 20), 618, dw, 64, ti, li, center=True)
    # governance band
    gx, gw = R + 20, 1200 - R - 20 - 30
    s.rect(gx, 20, gw, 680, ACCENT_BG, "#c7d6f5", 14, 1)
    s.text(gx + gw / 2, 50, t["gov"], 15, BLUE, 700, "middle")
    for i, g in enumerate(t["g"]):
        y = 76 + i * 102
        s.rect(gx + 14, y, gw - 28, 78, "#fff", LINE, 10)
        s.text(gx + gw / 2, y + 44, g, 13, INK, 600, "middle", gw - 28)
    s.save(path)

def capabilities(t, path):
    s = Svg(1200, 640)
    # three columns: employees (wide), outside, admin
    cols = [(30, 620, t["c_emp"], t["emp"], 3), (670, 240, t["c_ext"], t["ext"], 1), (930, 240, t["c_adm"], t["adm"], 1)]
    top, h = 20, 470
    for x, w, label, items, per_row in cols:
        s.lane(x, top, w, h, label)
        iw = (w - 40 - (per_row - 1) * 14) / per_row
        ih = 120 if per_row == 3 else 92
        gap = 14 if per_row == 3 else 12
        for i, (ti, sub) in enumerate(items):
            r, c = divmod(i, per_row)
            bx, by = x + 20 + c * (iw + 14), top + 46 + r * (ih + gap)
            accent = ti in ("SuperAgent",)
            s.box(bx, by, iw, ih, ti, [sub], accent=accent, center=True)
    # foundation
    s.lane(30, 510, 1140, 110, t["c_base"])
    fw = (1140 - 40 - 3 * 16) / 4
    for i, (ti, sub) in enumerate(t["fnd"]):
        s.box(50 + i * (fw + 16), 546, fw, 58, ti, [sub], center=True)
    s.save(path)

for lang, t in T.items():
    architecture(t, os.path.join(OUT, f"architecture-{lang}.svg"))
    capabilities(t, os.path.join(OUT, f"capabilities-{lang}.svg"))
print("written")
