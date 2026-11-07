// SuperAgent UI — My usage: what my work used, the raw cost being tokens. This month by default, then the detail in two
// kinds: agents (chatting and thinking) and tasks. Admins can see everyone's. A task's own runs show in its "Cost"
// tab. Dollar figures are estimates from list prices.
// Classic script sharing one global scope with the others; index.html loads them in order.
"use strict";

Object.assign(api, {
  usage: (scope, period) => fetch((scope === "all" ? "/api/v1/admin/usage" : "/api/v1/usage") + "?period=" + period
    + "&tz=" + encodeURIComponent(Intl.DateTimeFormat().resolvedOptions().timeZone || "")).then(kbJson),
  taskUsage: id => fetch("/api/v1/tasks/" + encodeURIComponent(id) + "/usage").then(kbJson),
});

const usEl = {
  title: document.getElementById("usTitle"),
  sub: document.getElementById("usSub"),
  period: document.getElementById("usPeriod"),
  chartBox: document.getElementById("usChartBox"),
  peopleBox: document.getElementById("usPeopleBox"),
  hero: document.getElementById("usHero"),
  cards: document.getElementById("usCards"),
  chart: document.getElementById("usChart"),
  kinds: document.getElementById("usKinds"),
  agentsCount: document.getElementById("usAgentsCount"),
  tasksCount: document.getElementById("usTasksCount"),
  detail: document.getElementById("usDetail"),
  people: document.getElementById("usPeople"),
};
let usScope = "me"; // "all" on the admin's company page (set by setView)
let usKind = "agents";
let usLast = null; // the summary on screen
const US_SOURCES = ["task", "chat", "superagent", "superagent_background", "model_choice", "memory", "file_tags"];

const usNum = n => Number(n || 0).toLocaleString();
/** A token count short enough for a card: 1.2M, 35.3K. */
const usShort = n => n >= 1e6 ? (n / 1e6).toFixed(n >= 1e7 ? 0 : 1) + "M" : n >= 1e3 ? (n / 1e3).toFixed(n >= 1e4 ? 0 : 1) + "K" : String(n);
/** An estimate in USD; small amounts keep their digits; * when some model had no price. */
function usCost(t) {
  if (!t.total) return "$0";
  if (t.costUsd == null) return "—";
  const v = t.costUsd;
  return "$" + (v >= 100 ? v.toFixed(0) : v >= 1 ? v.toFixed(2) : v.toFixed(4)) + (t.costPartial ? "*" : "");
}
const usSource = s => US_SOURCES.includes(s) ? L("us.src." + s) : s;

async function loadUsage() {
  if (!isAdmin) usScope = "me";
  const company = usScope === "all";
  usEl.title.textContent = L(company ? "us.companyTitle" : "us.title");
  usEl.sub.textContent = L(company ? "us.companySub" : "us.sub");
  let s;
  try { s = await api.usage(usScope, usEl.period.value); } catch (e) { showToast(e.message, true); return; }
  usLast = s;
  const t = s.total;
  // What the period cost, up front: the estimate and the tokens behind it.
  usEl.hero.replaceChildren();
  const what = document.createElement("div");
  what.className = "us-hero__what";
  what.textContent = L(usEl.period.value === "month" ? "us.heroMonth" : "us.heroPeriod",
    { period: usEl.period.selectedOptions[0].textContent });
  const big = document.createElement("div");
  big.className = "us-hero__cost";
  big.textContent = t.total ? usCost(t) : "$0";
  const info = document.createElement("span");
  info.className = "us-info";
  info.textContent = "ⓘ";
  info.title = L("us.note") + (t.costPartial ? "\n" + L("us.partial") : "");
  big.appendChild(info);
  const tokens = document.createElement("div");
  tokens.className = "us-hero__tokens";
  tokens.textContent = L("us.heroTokens", { n: usNum(t.total) });
  usEl.hero.append(what, big, tokens);
  const card = (value, label, sub) => {
    const c = document.createElement("div");
    c.className = "stat-card";
    c.innerHTML = '<span class="stat-card__value"></span><span class="stat-card__label"></span>' + (sub ? '<span class="stat-card__sub"></span>' : "");
    c.querySelector(".stat-card__value").textContent = value;
    c.querySelector(".stat-card__label").textContent = label;
    if (sub) c.querySelector(".stat-card__sub").textContent = sub;
    return c;
  };
  const inputAll = t.input + t.cacheRead + t.cacheWrite;
  usEl.cards.replaceChildren(
    card(usShort(s.agentTotal.total), L("us.kindAgents"), usCost(s.agentTotal) + " · " + L("us.runs", { n: usNum(s.agentTotal.runs) })),
    card(usShort(s.taskTotal.total), L("us.kindTasks"), usCost(s.taskTotal) + " · " + L("us.runs", { n: usNum(s.taskTotal.runs) })),
    card(usShort(inputAll), L("us.inputAll"), inputAll ? L("us.hitRate", { pct: Math.round(100 * t.cacheRead / inputAll) }) : ""),
    card(usShort(t.output), L("us.output"), t.reasoning ? L("us.reasoningOf", { n: usShort(t.reasoning) }) : ""),
    ...(company ? [card(usShort(s.systemTotal.total), L("us.kindSystem"), usCost(s.systemTotal) + " · " + L("us.runs", { n: usNum(s.systemTotal.runs) }))] : []));
  usEl.chartBox.hidden = !s.byDay.length; // nothing to draw: no empty chart
  usRenderChart(s.byDay);
  usEl.agentsCount.textContent = s.agents.length;
  usEl.tasksCount.textContent = s.tasks.length;
  document.getElementById("usSystemTab").hidden = !company;
  document.getElementById("usSystemCount").textContent = (s.system || []).length;
  if (!company && usKind === "system") usKind = "agents";
  usRenderDetail();
  usEl.peopleBox.hidden = !company;
  usEl.people.replaceChildren(...(company ? [usTable(null, s.byUser, {
    head: [L("us.colPerson"), L("us.input"), L("us.cacheRead"), L("us.cacheWrite"), L("us.output"), L("us.reasoning"),
      L("us.colTotal"), L("us.cost"), L("us.colRuns")],
    cells: r => [r.label || r.key || "—", usNum(r.input), usNum(r.cacheRead), usNum(r.cacheWrite), usNum(r.output),
      usNum(r.reasoning), usNum(r.total), usCost(r), usNum(r.runs)],
  })] : []));
}

/** The detail in the kind picked: one row per agent, or per task. */
function usRenderDetail() {
  const s = usLast;
  if (!s) return;
  usEl.kinds.querySelectorAll(".model-tab").forEach(b => b.classList.toggle("is-active", b.dataset.kind === usKind));
  if (usKind === "system") {
    // The background's own checks, per kind and model: what keeping the model statuses current costs.
    usEl.detail.replaceChildren(usTable(null, s.system || [], {
      textCols: 1,
      head: [L("us.colCheck"), L("us.colModel"), L("us.input"), L("us.cacheRead"), L("us.output"), L("us.colTotal"),
        L("us.cost"), L("us.colRuns"), L("us.colLast")],
      cells: r => { const [kind, model] = r.key.split("|"); return [L("us.src." + kind), model || "—", usNum(r.input),
        usNum(r.cacheRead), usNum(r.output), usNum(r.total), usCost(r), usNum(r.runs), r.lastAt ? relativeTime(r.lastAt) : "—"]; },
    }));
    return;
  }
  const agents = usKind === "agents";
  const rows = agents ? s.agents : s.tasks;
  usEl.detail.replaceChildren(usTable(null, rows, {
    textCols: 1,
    head: [L(agents ? "us.colAgent" : "us.colTask"), L("us.colModel"), L("us.input"), L("us.cacheRead"), L("us.cacheWrite"),
      L("us.output"), L("us.reasoning"), L("us.colTotal"), L("us.cost"), L("us.colRuns"), L("us.colLast")],
    cells: r => [usLink(r, agents), (r.models || []).join(", ") || "—", usNum(r.input), usNum(r.cacheRead),
      usNum(r.cacheWrite), usNum(r.output), usNum(r.reasoning), usNum(r.total), usCost(r), usNum(r.runs),
      r.lastAt ? relativeTime(r.lastAt) : "—"],
  }));
}

/** The row's name, opening the agent's chat or the task when there's one to open. */
function usLink(r, agent) {
  const name = agent ? (r.key ? r.label || L("us.goneAgent") : L("us.system")) : (r.label || L("us.goneTask"));
  const target = agent ? (r.key && typeof agents !== "undefined" ? agents.find(a => a.id === r.key) : null) : r.key;
  if (!target || (!agent && !r.label)) return name;
  const b = document.createElement("button");
  b.type = "button";
  b.className = "us-link";
  b.textContent = name;
  b.addEventListener("click", () => {
    if (agent) { setView("chat"); selectAgent(target); } else { setView("tasks"); openTask(target); }
  });
  return b;
}

/** One stacked bar per day (input, cache read, cache write, output), scaled to the busiest day. */
function usRenderChart(days) {
  const max = Math.max(1, ...days.map(d => d.total));
  usEl.chart.replaceChildren(...days.map(d => {
    const col = document.createElement("div");
    col.className = "us-day";
    col.title = d.key + "\n" + L("us.input") + " " + usNum(d.input) + "\n" + L("us.cacheRead") + " " + usNum(d.cacheRead)
      + "\n" + L("us.cacheWrite") + " " + usNum(d.cacheWrite) + "\n" + L("us.output") + " " + usNum(d.output) + "\n" + usCost(d);
    const bar = document.createElement("div");
    bar.className = "us-bar";
    bar.style.height = Math.max(2, Math.round(100 * d.total / max)) + "%";
    [["is-output", d.output], ["is-cache-write", d.cacheWrite], ["is-cache-read", d.cacheRead], ["is-input", d.input]]
      .forEach(([cls, n]) => {
        if (!n) return;
        const seg = document.createElement("i");
        seg.className = cls;
        seg.style.flexGrow = String(n);
        bar.appendChild(seg);
      });
    const label = document.createElement("span");
    label.className = "us-day__label";
    label.textContent = d.key.slice(5);
    col.append(bar, label);
    return col;
  }));
  if (!days.length) {
    const p = document.createElement("p");
    p.className = "kb-empty";
    p.textContent = L("us.empty");
    usEl.chart.appendChild(p);
  }
}

/** Rows as a table; a cell may be text or an element. {@code title} null: no heading. */
function usTable(title, rows, opts = {}) {
  const box = document.createElement("section");
  box.className = "us-table";
  if (title) {
    const h = document.createElement("h3");
    h.className = "panel__title panel__title--spaced";
    h.textContent = title;
    box.appendChild(h);
  }
  const scroll = document.createElement("div");
  scroll.className = "us-scroll";
  const table = document.createElement("table");
  table.className = "ctable us-ctable";
  const head = opts.head || [L("us.colModel"), L("us.input"), L("us.cacheRead"), L("us.cacheWrite"), L("us.output"),
    L("us.colTotal"), L("us.cost"), L("us.colRuns")];
  const thead = document.createElement("thead");
  const htr = document.createElement("tr");
  head.forEach((x, i) => {
    const th = document.createElement("th");
    if (i > (opts.textCols || 0)) th.className = "num";
    th.textContent = x;
    htr.appendChild(th);
  });
  thead.appendChild(htr);
  const body = document.createElement("tbody");
  rows.forEach(r => {
    const tr = document.createElement("tr");
    const cells = opts.cells ? opts.cells(r) : [r.label || r.key || "—", usNum(r.input), usNum(r.cacheRead),
      usNum(r.cacheWrite), usNum(r.output), usNum(r.total), usCost(r), usNum(r.runs)];
    cells.forEach((c, i) => {
      const td = document.createElement("td");
      if (i > (opts.textCols || 0)) td.className = "num";
      if (c instanceof window.Node) td.appendChild(c); else td.textContent = c;
      tr.appendChild(td);
    });
    body.appendChild(tr);
  });
  if (!rows.length) {
    const tr = document.createElement("tr");
    const td = document.createElement("td");
    td.colSpan = head.length;
    td.className = "kb-empty";
    td.textContent = L("us.empty");
    tr.appendChild(td);
    body.appendChild(tr);
  }
  table.append(thead, body);
  scroll.appendChild(table);
  box.appendChild(scroll);
  return box;
}

usEl.period.addEventListener("change", loadUsage);
usEl.kinds.addEventListener("click", e => {
  const b = e.target.closest(".model-tab");
  if (!b) return;
  usKind = b.dataset.kind;
  usRenderDetail();
});

// ---------- a task's cost tab ----------

async function loadTaskUsage(taskId) {
  const box = document.getElementById("taskUsage");
  let u;
  try { u = await api.taskUsage(taskId); } catch (e) { return; }
  if (task.id !== taskId) return; // another task was opened meanwhile
  box.replaceChildren();
  if (!u.rows.length) {
    const p = document.createElement("p");
    p.className = "kb-empty";
    p.textContent = L("us.taskEmpty");
    box.appendChild(p);
    return;
  }
  const t = u.total;
  const sum = document.createElement("p");
  sum.className = "task-usage__sum";
  sum.textContent = L("us.taskSum", { total: usNum(t.total), input: usNum(t.input), read: usNum(t.cacheRead),
    write: usNum(t.cacheWrite), output: usNum(t.output), cost: usCost(t) });
  const info = document.createElement("span");
  info.className = "us-info";
  info.textContent = " ⓘ";
  info.title = L("us.note") + (t.costPartial ? "\n" + L("us.partial") : "");
  sum.appendChild(info);
  box.append(sum, usTable(L("us.byModel"), u.byModel));
  // Every run, oldest first: when, what for, which model, its tokens and cost.
  box.appendChild(usTable(L("us.runsTitle"), u.rows, {
    textCols: 2,
    head: [L("us.colWhen"), L("us.colSource"), L("us.colModel"), L("us.input"), L("us.cacheRead"), L("us.cacheWrite"),
      L("us.output"), L("us.cost")],
    cells: r => [formatDateTime(r.at), usSource(r.source), r.model || "—", usNum(r.input), usNum(r.cacheRead),
      usNum(r.cacheWrite), usNum(r.output), usCost(r)],
  }));
}
