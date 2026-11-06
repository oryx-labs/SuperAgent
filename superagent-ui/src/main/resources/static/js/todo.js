// SuperAgent UI — To-do home page: what needs the user (decisions, things to do) and what agents finished.
// Classic script sharing one global scope with the others; index.html loads them in order.
"use strict";

Object.assign(api, {
  listNotices: () => fetch("/api/v1/notices").then(kbJson),
  noticeAction: (id, action) => kbPost("/api/v1/notices/" + id + "/" + action, {}),
  noticesReadAll: () => kbPost("/api/v1/notices/read-all", {}),
  todoStatus: (id, status) => fetch("/api/v1/inbox/todos/" + id, {
    method: "PUT", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ status }),
  }).then(kbJson),
  todoToTask: id => kbPost("/api/v1/inbox/todos/" + id + "/task", {}),
});

const todoPane = document.querySelector('[data-pane="todo"]');
const tdel = {
  tabs: document.getElementById("todoTabs"),
  list: document.getElementById("todoList"),
  empty: document.getElementById("todoEmpty"),
  readAll: document.getElementById("todoReadAll"),
  badge: document.getElementById("todoBadge"),
  stats: document.getElementById("todoStats"),
};
let notices = [];
let todoFilter = "all";

const TODO_KIND = {
  confirm: { icon: "⚠️", label: "todo.kindConfirm" },
  action: { icon: "📌", label: "todo.kindAction" },
  done: { icon: "✅", label: "todo.kindDone" },
  notice: { icon: "🔔", label: "todo.kindNotice" },
};

async function loadTodos() {
  try { notices = (await api.listNotices()) || []; } catch (e) { notices = []; showToast(e.message, true); }
  renderTodos();
}

/** Open = still needs the user (or not yet read); the rest is history, kept for good. */
const isOpenNotice = n => n.state === "open";
const isIgnored = n => n.state === "ignored";

// One list per tab. "All" is what needs the user now; a kind tab also keeps that kind's handled items,
// dimmed below the open ones; "Ignored" is the only place ignored items show (and can be restored).
function todoTabItems(filter) {
  if (filter === "ignored") return notices.filter(isIgnored);
  if (filter === "all") return notices.filter(isOpenNotice);
  return notices.filter(n => n.kind === filter && !isIgnored(n));
}

function renderTodos() {
  tdel.tabs.querySelectorAll(".model-tab").forEach(t => {
    const f = t.dataset.filter;
    t.classList.toggle("is-active", f === todoFilter);
    const items = todoTabItems(f);
    t.querySelector("b").textContent = (f === "ignored" ? items : items.filter(isOpenNotice)).length || "";
  });
  // Open before handled; decisions first, then things to do, then finished work and notices; newest first.
  const order = { confirm: 0, action: 1, done: 2, notice: 3 };
  const items = todoTabItems(todoFilter).sort((a, b) =>
    isOpenNotice(b) - isOpenNotice(a) || order[a.kind] - order[b.kind] || new Date(b.updatedAt) - new Date(a.updatedAt));
  tdel.list.replaceChildren(...items.map(todoItem));
  tdel.empty.hidden = items.length > 0;
  tdel.empty.textContent = L(todoFilter === "all" ? "todo.emptyAll" : "todo.empty");
  tdel.readAll.hidden = !notices.some(n => isOpenNotice(n) && (n.kind === "done" || n.kind === "notice"));
  renderTodoStats();
  updateTodoBadge();
}

// At-a-glance numbers from the server: what is open now, and finished work over time (from the user's tasks,
// so it includes work from before the to-do page). Open-item cards filter the list.
async function renderTodoStats() {
  let st;
  try { st = await fetch("/api/v1/notices/stats").then(kbJson); } catch (e) { return; }
  if (!st) return;
  const groups = [
    { label: "todo.groupOpen", metrics: [
      { filter: "confirm", label: "todo.statConfirm", value: st.toConfirm, tone: "warn" },
      { filter: "action", label: "todo.statAction", value: st.toDo, tone: "ink" },
      { filter: "notice", label: "todo.statUnread", value: st.unreadNotices },
    ] },
    { label: "todo.groupDone", metrics: [
      { label: "todo.statDoneToday", value: st.doneToday, tone: "ok" },
      { label: "todo.statDoneWeek", value: st.doneThisWeek },
      { label: "todo.statDoneMonth", value: st.doneThisMonth },
      { label: "todo.statDoneTotal", value: st.doneTotal },
    ] },
  ];
  tdel.stats.replaceChildren(...groups.map(g => {
    const panel = document.createElement("section");
    panel.className = "todo-stats__panel";
    const head = document.createElement("div");
    head.className = "todo-stats__head";
    head.textContent = L(g.label);
    const row = document.createElement("div");
    row.className = "todo-stats__row";
    row.append(...g.metrics.map(m => {
      const el = document.createElement(m.filter ? "button" : "div");
      el.className = "todo-metric" + (m.tone && m.value ? " is-" + m.tone : "")
        + (m.filter ? " is-link" : "") + (m.filter && todoFilter === m.filter ? " is-active" : "");
      const v = document.createElement("span");
      v.className = "todo-metric__value";
      v.textContent = m.value;
      const l = document.createElement("span");
      l.className = "todo-metric__label";
      l.textContent = L(m.label);
      el.append(v, l);
      if (m.filter) {
        el.type = "button";
        el.addEventListener("click", () => { todoFilter = todoFilter === m.filter ? "all" : m.filter; renderTodos(); });
      }
      return el;
    }));
    panel.append(head, row);
    return panel;
  }));
}

/** The menu badge counts what needs the user: decisions and things to do. */
function updateTodoBadge() {
  const n = notices.filter(x => isOpenNotice(x) && (x.kind === "confirm" || x.kind === "action")).length;
  tdel.badge.hidden = n === 0;
  tdel.badge.textContent = n > 99 ? "99+" : String(n);
}

// One table row per item: type badge, title (+ detail), where it came from, when, actions.
function todoItem(n) {
  const kind = TODO_KIND[n.kind] || TODO_KIND.notice;
  const tr = document.createElement("tr");
  tr.className = "todo-row todo-row--" + n.kind + (isOpenNotice(n) ? "" : " is-closed");
  const tdKind = document.createElement("td");
  const badge = document.createElement("span");
  badge.className = "todo-kind todo-kind--" + n.kind;
  badge.textContent = L(kind.label);
  tdKind.appendChild(badge);
  const main = document.createElement("td");
  const title = document.createElement("div");
  title.className = "todo-row__title";
  title.textContent = n.title;
  main.appendChild(title);
  if (n.body) {
    const body = document.createElement("div");
    body.className = "todo-row__body";
    body.textContent = n.body;
    body.title = n.body;
    main.appendChild(body);
  }
  const from = document.createElement("td");
  from.className = "todo-row__meta";
  from.textContent = n.actor || "—";
  const when = document.createElement("td");
  when.className = "todo-row__meta";
  when.textContent = formatDateTime(n.updatedAt);
  const actions = document.createElement("td");
  actions.className = "ctable__actions-col todo-row__actions";
  todoActions(n).forEach(([label, fn, primary]) => {
    const b = document.createElement("button");
    b.type = "button";
    b.className = primary ? "btn btn--primary btn--sm" : "link-btn";
    b.textContent = label;
    b.addEventListener("click", async () => {
      b.disabled = true;
      try { await fn(); } catch (e) { showToast(e.message, true); }
      await loadTodos();
    });
    actions.appendChild(b);
  });
  tr.append(tdKind, main, from, when, actions);
  return tr;
}

/** [label, action, primary] per item: the main way to act on it first. */
function todoActions(n) {
  if (!isOpenNotice(n)) return n.state === "ignored" ? [[L("todo.restore"), () => api.noticeAction(n.id, "reopen")]] : [];
  const ignore = [L("todo.ignore"), () => api.noticeAction(n.id, "ignore")];
  const read = [L("todo.markRead"), () => api.noticeAction(n.id, "done")];
  switch (n.kind) {
    case "confirm":
      return n.linkType === "superagent"
        ? [[L("todo.review"), openSuperAgentPlan, true], ignore]
        : [[L("todo.handle"), () => openTaskFromTodo(n.linkId), true], ignore];
    case "action":
      return [[L("todo.toTask"), async () => { await api.todoToTask(n.linkId); showToast(L("ib.tasked"), false); }, true],
        [L("todo.markDone"), () => api.todoStatus(n.linkId, "done")], ignore];
    case "done":
      return [[L("todo.viewResult"), async () => { await api.noticeAction(n.id, "done"); openTaskFromTodo(n.linkId); }, true], read];
    default:
      return n.linkType === "agent"
        ? [[L("todo.open"), async () => { await api.noticeAction(n.id, "done"); await openAgentFromTodo(n.linkId); }, true], read]
        : [read];
  }
}

function openTaskFromTodo(taskId) {
  setView("tasks");
  openTask(taskId);
}

async function openSuperAgentPlan() {
  setView("chat");
  if (!agents.length) await loadAgents();
  const sa = agents.find(a => a.kind === "super");
  if (sa) selectAgent(sa);
}

async function openAgentFromTodo(agentId) {
  setView("chat");
  await loadAgents();
  const a = agents.find(x => x.id === agentId);
  if (a) selectAgent(a);
}

tdel.tabs.addEventListener("click", e => {
  const t = e.target.closest(".model-tab");
  if (t) { todoFilter = t.dataset.filter; renderTodos(); }
});
tdel.readAll.addEventListener("click", async () => {
  try { await api.noticesReadAll(); } catch (e) { showToast(e.message, true); }
  loadTodos();
});

// Keep the menu badge current while the user works elsewhere (agents finish work in the background).
setInterval(() => {
  if (!currentUsername) return;
  if (!todoPane.hidden) loadTodos();
  else api.listNotices().then(list => { notices = list || []; updateTodoBadge(); }).catch(() => {});
}, 60000);
