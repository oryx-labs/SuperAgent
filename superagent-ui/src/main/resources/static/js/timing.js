// SuperAgent UI — where time went: a line under each chat reply (opening its breakdown), and the task's "Time" tab.
// Classic script sharing one global scope with the others; index.html loads them in order.
"use strict";

Object.assign(api, {
  agentTimings: id => fetch("/api/v1/agents/" + encodeURIComponent(id) + "/timings").then(kbJson),
  taskTiming: id => fetch("/api/v1/tasks/" + encodeURIComponent(id) + "/timing").then(kbJson),
});

// The stages of a turn, in the order they happen; they add up to the whole.
const TM_STAGES = ["prep", "retry", "startup", "model", "tools", "output", "save"];

function tmDuration(ms) {
  if (ms < 1000) return L("tm.s", { n: (ms / 1000).toFixed(1) });
  if (ms < 60000) return L("tm.s", { n: (ms / 1000).toFixed(ms < 10000 ? 1 : 0) });
  const m = Math.floor(ms / 60000);
  const s = Math.round((ms % 60000) / 1000);
  return m >= 60 ? L("tm.hm", { h: Math.floor(m / 60), m: m % 60 }) : L("tm.ms", { m, s });
}

const tmStageMs = t => ({ prep: t.prepMs, retry: t.retryMs, startup: t.startupMs, model: t.modelMs, tools: t.toolsMs,
  output: t.outputMs, save: t.saveMs });

// One stacked bar: each stage's share of the turn.
function tmBar(t) {
  const bar = document.createElement("div");
  bar.className = "tm-bar";
  const ms = tmStageMs(t);
  const total = Math.max(1, t.totalMs);
  TM_STAGES.forEach(k => {
    if (!ms[k]) return;
    const seg = document.createElement("span");
    seg.className = "tm-bar__seg tm-" + k;
    seg.style.width = (ms[k] / total * 100) + "%";
    seg.title = L("tm.stage." + k) + " " + tmDuration(ms[k]);
    bar.appendChild(seg);
  });
  return bar;
}

// The breakdown: every stage with its share, then each tool with its calls.
function tmDetail(t) {
  const box = document.createElement("div");
  box.className = "tm-detail";
  box.appendChild(tmBar(t));
  const list = document.createElement("dl");
  list.className = "tm-stages";
  const ms = tmStageMs(t);
  TM_STAGES.forEach(k => {
    if (!ms[k] && k !== "model") return;
    const dt = document.createElement("dt");
    const dot = document.createElement("i");
    dot.className = "tm-dot tm-" + k;
    dt.append(dot, document.createTextNode(L("tm.stage." + k)));
    const dd = document.createElement("dd");
    dd.textContent = tmDuration(ms[k]) + " · " + Math.round(ms[k] / Math.max(1, t.totalMs) * 100) + "%";
    list.append(dt, dd);
  });
  box.appendChild(list);
  if ((t.tools || []).length) {
    const tools = document.createElement("ul");
    tools.className = "tm-toollist";
    t.tools.forEach(x => {
      const li = document.createElement("li");
      const name = document.createElement("span");
      name.textContent = x.name.replace(/^mcp__superagent__/, "");
      name.title = x.name;
      const v = document.createElement("span");
      v.textContent = L("tm.toolCalls", { n: x.count }) + " · " + tmDuration(x.ms);
      li.append(name, v);
      tools.appendChild(li);
    });
    box.appendChild(tools);
  }
  if (t.attempts > 1) {
    const p = document.createElement("p");
    p.className = "tm-note";
    p.textContent = L("tm.retried", { n: t.attempts - 1 });
    box.appendChild(p);
  }
  return box;
}

// The one-line summary: the total, then the stages that took real time, biggest first.
function tmSummary(t) {
  const ms = tmStageMs(t);
  const parts = TM_STAGES.filter(k => ms[k] >= 500).sort((a, b) => ms[b] - ms[a]).slice(0, 4)
    .map(k => k === "tools" && t.tools.length
      ? L("tm.toolsShort", { n: t.tools.reduce((s, x) => s + x.count, 0), d: tmDuration(ms[k]) })
      : L("tm.stage." + k) + " " + tmDuration(ms[k]));
  return [L("tm.total", { d: tmDuration(t.totalMs) }), ...parts].join(" · ");
}

// The line under a reply: the total, a small bar, and the biggest stages keyed to the bar's colours.
function tmCompact(t) {
  const ms = tmStageMs(t);
  const total = document.createElement("b");
  total.className = "tm-line__total";
  total.textContent = "⏱ " + tmDuration(t.totalMs);
  const bar = tmBar(t);
  bar.classList.add("tm-bar--mini");
  const parts = TM_STAGES.filter(k => ms[k] >= 500).sort((a, b) => ms[b] - ms[a]).slice(0, 3).map(k => {
    const part = document.createElement("span");
    part.className = "tm-line__part";
    const dot = document.createElement("i");
    dot.className = "tm-dot tm-" + k;
    const label = k === "tools" && t.tools.length
      ? L("tm.toolsShort", { n: t.tools.reduce((n, x) => n + x.count, 0), d: tmDuration(ms[k]) })
      : L("tm.stage." + k) + " " + tmDuration(ms[k]);
    part.append(dot, document.createTextNode(label));
    return part;
  });
  return [total, bar, ...parts];
}

// Under each reply on screen; a click opens the breakdown.
async function attachTimings(agentId) {
  if (!agentId) return;
  const list = await api.agentTimings(agentId).catch(() => null);
  if (!list || activeAgentId !== agentId) return;
  list.forEach(t => {
    const msg = el.messages.querySelector('.msg[data-mid="' + CSS.escape(t.messageId) + '"]');
    if (!msg || msg.querySelector(".tm-line")) return;
    const line = document.createElement("div");
    line.className = "tm-line";
    const btn = document.createElement("button");
    btn.type = "button";
    btn.className = "tm-line__btn";
    btn.setAttribute("aria-expanded", "false");
    btn.append(...tmCompact(t));
    btn.title = L("tm.open");
    let detail = null;
    btn.addEventListener("click", () => {
      if (detail) { detail.remove(); detail = null; btn.setAttribute("aria-expanded", "false"); return; }
      detail = tmDetail(t);
      line.appendChild(detail);
      btn.setAttribute("aria-expanded", "true");
    });
    line.appendChild(btn);
    msg.appendChild(line);
  });
}

// ---------- the task's "Time" tab ----------
const TM_WAITS = { wait_start: "tm.waitStart", wait_user: "tm.waitUser", wait_queue: "tm.waitQueue", running: "tm.running" };

async function loadTaskTiming(id) {
  const box = document.getElementById("taskTiming");
  const d = await api.taskTiming(id).catch(() => null);
  if (!d || task.id !== id) return;
  const turns = d.segments.filter(s => s.kind === "turn");
  const sum = kind => d.segments.filter(s => s.kind === kind).reduce((n, s) => n + s.ms, 0);
  const total = d.segments.reduce((n, s) => n + s.ms, 0);
  const machine = sum("turn") + sum("running");

  const head = document.createElement("div");
  head.className = "tm-task__head";
  const stat = (label, ms) => {
    const c = document.createElement("div");
    c.className = "tm-task__stat";
    const b = document.createElement("b");
    b.textContent = tmDuration(ms);
    const s = document.createElement("span");
    s.textContent = label;
    c.append(b, s);
    return c;
  };
  head.append(stat(L(d.open ? "tm.soFar" : "tm.whole"), total), stat(L("tm.machine"), machine),
    stat(L("tm.waitStart"), sum("wait_start") + sum("wait_queue")), stat(L("tm.waitUser"), sum("wait_user")));

  // All turns together: what the runs themselves spent their time on.
  const parts = { prepMs: 0, retryMs: 0, startupMs: 0, modelMs: 0, toolsMs: 0, outputMs: 0, saveMs: 0, totalMs: 0 };
  const tools = new Map();
  turns.forEach(s => {
    Object.keys(parts).forEach(k => { parts[k] += s.turn[k] || 0; });
    (s.turn.tools || []).forEach(x => {
      const a = tools.get(x.name) || { name: x.name, count: 0, ms: 0 };
      a.count += x.count; a.ms += x.ms; tools.set(x.name, a);
    });
  });
  const all = Object.assign({}, parts, { tools: [...tools.values()].sort((a, b) => b.ms - a.ms),
    attempts: turns.reduce((n, s) => n + s.turn.attempts, 0) - turns.length + 1 });

  const flow = document.createElement("ol");
  flow.className = "tm-flow";
  let n = 0;
  d.segments.forEach(s => {
    const li = document.createElement("li");
    li.className = "tm-flow__item is-" + s.kind;
    const title = document.createElement("div");
    title.className = "tm-flow__title";
    const name = document.createElement("span");
    const when = document.createElement("span");
    when.className = "tm-flow__when";
    when.textContent = formatDateTime(s.startedAt);
    const dur = document.createElement("b");
    dur.textContent = tmDuration(s.ms);
    if (s.kind === "turn") {
      n++;
      name.textContent = L("tm.turn", { n }) + (s.turn.label ? " · " + (L("tm.phase." + s.turn.label) !== "tm.phase." + s.turn.label
        ? L("tm.phase." + s.turn.label) : s.turn.label) : "");
      title.append(name, when, dur);
      li.append(title, tmBar(s.turn));
      const more = document.createElement("button");
      more.type = "button";
      more.className = "tm-line__btn";
      more.textContent = tmSummary(s.turn).split(" · ").slice(1).join(" · ") || L("tm.open");
      let detail = null;
      more.addEventListener("click", () => {
        if (detail) { detail.remove(); detail = null; return; }
        detail = tmDetail(s.turn);
        li.appendChild(detail);
      });
      li.appendChild(more);
    } else {
      name.textContent = L(TM_WAITS[s.kind] || s.kind);
      title.append(name, when, dur);
      li.appendChild(title);
    }
    flow.appendChild(li);
  });

  const sections = [head];
  if (turns.length) {
    const h = document.createElement("h3");
    h.className = "task-section__title";
    h.textContent = L("tm.runsTitle");
    sections.push(h, tmDetail(all));
  }
  const h2 = document.createElement("h3");
  h2.className = "task-section__title";
  h2.textContent = L("tm.flowTitle");
  sections.push(h2, flow);
  if (!d.segments.length) {
    const p = document.createElement("p");
    p.className = "tm-note";
    p.textContent = L("tm.none");
    sections.push(p);
  }
  box.replaceChildren(...sections);
}
