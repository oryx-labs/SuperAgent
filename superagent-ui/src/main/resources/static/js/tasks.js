// SuperAgent UI — Tasks: list, create, detail (prompt / run / session), steering.
// Classic script sharing one global scope with the others; index.html loads them in order.
"use strict";

// ---------- tasks ----------
Object.assign(api, {
  listTasks: (page, status, narrow, size) => fetch("/api/v1/tasks?page=" + page + "&size=" + (size || TASK_PAGE_SIZE)
    + (status ? "&status=" + encodeURIComponent(status) : "")
    + ["project", "tag", "agent", "source"].map(k => narrow && narrow[k] ? "&" + k + "=" + encodeURIComponent(narrow[k]) : "").join(""))
    .then(r => r.json()),
  taskFacets: () => fetch("/api/v1/tasks/facets").then(kbJson),
  taskRelations: id => fetch("/api/v1/tasks/" + id + "/relations").then(kbJson),
  setTaskTags: (id, tags) => fetch("/api/v1/tasks/" + id + "/tags", {
    method: "PUT", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ tags }),
  }).then(kbJson),
  createTask: body => fetch("/api/v1/tasks", {
    method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body),
  }).then(kbJson),
  taskDetail: id => fetch("/api/v1/tasks/" + id).then(r => r.json()),
  taskAgents: () => fetch("/api/v1/tasks/agents").then(r => r.json()),
  taskStatus: id => fetch("/api/v1/tasks/" + id + "/status").then(r => r.json()),
  taskModelChoices: id => fetch("/api/v1/tasks/" + id + "/model-choices").then(r => r.json()),
  taskEvents: (id, offset) => fetch("/api/v1/tasks/" + id + "/events?offset=" + offset).then(r => r.json()),
  taskFiles: id => fetch("/api/v1/files?task=" + encodeURIComponent(id)).then(kbJson),
  taskMessage: (id, content, images, fileIds) => fetch("/api/v1/tasks/" + id + "/messages", {
    method: "POST", headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ content, images: images || [], fileIds: fileIds || [] }),
  }),
  stopTask: id => fetch("/api/v1/tasks/" + id + "/stop", { method: "POST" }),
  runTaskNow: id => fetch("/api/v1/tasks/" + id + "/run-now", { method: "POST" }).then(kbJson),
  deleteTask: id => fetch("/api/v1/tasks/" + id, { method: "DELETE" }),
});

const TASK_STATUS_LABEL = {
  pending: L("status.pending"), running: L("status.running"), needs_confirmation: L("status.needs_confirmation"),
  waiting: L("status.waiting"),
  done: L("status.done"), error: L("status.error"), stopped: L("status.stopped"),
};
const TASK_SOURCE_LABEL = {
  web_chat: L("tasks.srcWebChat"), task_form: L("tasks.srcTaskForm"), automation: L("tasks.srcAutomation"),
  inbox: L("tasks.srcInbox"),
};

const tel = {
  listView: document.getElementById("taskListView"),
  detailView: document.getElementById("taskDetailView"),
  rows: document.getElementById("taskRows"),
  empty: document.getElementById("tasksEmpty"),
  pager: document.getElementById("taskPager"),
  pagerInfo: document.getElementById("taskPagerInfo"),
  pagerPrev: document.getElementById("taskPagerPrev"),
  pagerNext: document.getElementById("taskPagerNext"),
  back: document.getElementById("taskBackBtn"),
  dTitle: document.getElementById("taskDetailTitle"),
  dMeta: document.getElementById("taskDetailMeta"),
  dStatus: document.getElementById("taskDetailStatus"),
  stopBtn: document.getElementById("taskStopBtn"),
  deleteBtn: document.getElementById("taskDeleteBtn"),
  stream: document.getElementById("taskStream"),
  promptBody: document.getElementById("taskPromptBody"),
  tabs: document.getElementById("taskTabs"),
  runPane: document.getElementById("taskRunPane"),
  live: document.getElementById("taskLive"),
  sessionInfo: document.getElementById("taskSessionInfo"),
  files: document.getElementById("taskFiles"),
  filesEmpty: document.getElementById("taskFilesEmpty"),
  progress: document.querySelector("#taskDetailView .progress"),
  progressBar: document.getElementById("taskProgressBar"),
  progressPct: document.getElementById("taskProgressPct"),
  facts: document.getElementById("taskFacts"),
  phasesPanel: document.getElementById("taskPhasesPanel"),
  phases: document.getElementById("taskPhases"),
  steerForm: document.getElementById("taskSteerForm"),
  confirmBar: document.getElementById("taskConfirmBar"),
  confirmGo: document.getElementById("taskConfirmGo"),
  confirmEdit: document.getElementById("taskConfirmEdit"),
  steerInput: document.getElementById("taskSteerInput"),
  steerBtn: document.getElementById("taskSteerBtn"),
  subtasksTab: document.getElementById("taskSubtasksTab"),
  subtasks: document.getElementById("taskSubtasks"),
  subtasksSum: document.getElementById("taskSubtasksSum"),
  rel: document.getElementById("taskDetailRel"),
  tags: document.getElementById("taskTags"),
  followBtn: document.getElementById("taskFollowBtn"),
  followList: document.getElementById("taskFollowList"),
  followUps: document.getElementById("taskFollowUps"),
};

const task = { id: null, renderedEvents: 0, mergeEl: null, mergeType: null, toolGroup: null, pollTimer: null };

// "waiting": a complex task whose subtasks are running; it carries on by itself.
function isActiveStatus(s) { return s === "running" || s === "pending" || s === "waiting"; }

function showTaskList() {
  stopTaskPolling();
  task.id = null;
  taskBack.length = 0;
  enteredTasksFrom = null;
  tel.detailView.hidden = true;
  tel.listView.hidden = false;
}

const TASK_PAGE_SIZE = 20;
let taskPage = 1;
let agentOptions = [];  // [{key, name, models:[ProviderModelDto]}] from /tasks/agents

// Tasks are only ever created by agents and automations, so the list just pages through them.
function renderTaskPager(page) {
  const pages = page ? Math.max(1, Math.ceil(page.total / page.size)) : 1;
  tel.pager.hidden = !page || pages <= 1;
  if (!page) return;
  tel.pagerInfo.textContent = L("tasks.pageInfo", { total: page.total, page: page.page, pages });
  tel.pagerPrev.disabled = page.page <= 1;
  tel.pagerNext.disabled = page.page >= pages;
}

async function loadTasks() {
  if (!agentOptions.length) await loadAgentOptions();
  let list = [];
  let page = null;
  loadTaskFacets();
  renderWorkSide();
  if (!taskStatus) { await renderTaskSections(); return; }
  try { const res = await api.listTasks(taskPage, taskStatus, taskNarrow); page = res && res.data; } catch (e) { /* ignore */ }
  list = (page && page.items) || [];
  tfEl.sum.textContent = page && taskNarrow.tag
    ? L("tasks.filterSum", { n: page.total, spend: "$" + Number(page.spend || 0).toFixed(2) }) : "";
  if (page) taskPage = page.page; // the server clamps a page past the end (e.g. after deletes)
  renderTaskPager(page);
  tel.rows.innerHTML = "";
  tel.empty.hidden = list.length > 0;
  // Who made it matters only when the page holds more than one person's tasks (a project's, say).
  tel.rows.closest("table").classList.toggle("is-solo", new Set(list.map(t => t.createdBy)).size <= 1);
  list.forEach(t => {
    tel.rows.appendChild(taskRow(t));
    if (taskExpanded.has(t.id)) showSubtaskRows(t); // kept open across reloads
  });
}

// ---------- the side panel: views by status, and projects opening onto their latest tasks ----------
// Outline icons (stroke = currentColor) so the panel stays monochrome.
const WS_ICONS = {
  all: '<path d="M9 4h6v3H9z"/><path d="M7 5H5v15h14V5h-2"/><path d="m9 13 2 2 4-4"/>',
  active: '<circle cx="12" cy="12" r="8"/><path d="M12 7v5l3 2"/>',
  confirm: '<path d="M12 3 2.5 20h19z"/><path d="M12 10v4M12 17v.5"/>',
  done: '<circle cx="12" cy="12" r="8"/><path d="m8.5 12 2.5 2.5 4.5-5"/>',
  folder: '<path d="M3 6.5A1.5 1.5 0 0 1 4.5 5H9l2 2.5h8.5A1.5 1.5 0 0 1 21 9v9.5a1.5 1.5 0 0 1-1.5 1.5h-15A1.5 1.5 0 0 1 3 18.5z"/>',
};
const WORK_VIEWS = [
  { key: "", label: "work.viewAll", icon: "all" },
  { key: "active", label: "work.viewActive", icon: "active" },
  { key: "needs_confirmation", label: "work.viewConfirm", icon: "confirm" },
  { key: "done", label: "work.viewDone", icon: "done" },
];
const WORK_PREVIEW = 5;
let taskStatus = "";
const workOpenProjects = new Set();
const workSide = document.getElementById("workSide");

function wsIcon(name) {
  const span = document.createElement("span");
  span.className = "ws-icon";
  span.innerHTML = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" '
    + 'stroke-linejoin="round" aria-hidden="true">' + WS_ICONS[name] + "</svg>";
  return span;
}

async function renderWorkSide() {
  if (!projectList.length) await refreshProjects();
  workSide.innerHTML = "";
  workSide.appendChild(wsHead(L("work.views")));
  WORK_VIEWS.forEach(v => workSide.appendChild(
    wsItem(L(v.label), v.icon, !taskNarrow.project && taskStatus === v.key, () => pickWorkView(v.key))));
  const live = projectList.filter(p => p.status !== "archived");
  if (!live.length) return;
  workSide.appendChild(wsHead(L("work.projects")));
  live.forEach(p => {
    const open = workOpenProjects.has(p.id);
    const row = wsItem(p.name, "folder", taskNarrow.project === p.id && !taskStatus, () => pickWorkProject(p.id));
    const caret = document.createElement("button");
    caret.type = "button";
    caret.className = "ws-caret" + (open ? " is-open" : "");
    caret.title = L(open ? "work.collapse" : "work.expand");
    caret.setAttribute("aria-label", caret.title);
    caret.setAttribute("aria-expanded", String(open));
    caret.addEventListener("click", e => {
      e.stopPropagation();
      if (open) workOpenProjects.delete(p.id); else workOpenProjects.add(p.id);
      renderWorkSide();
    });
    row.appendChild(caret);
    workSide.appendChild(row);
    if (open) workSide.appendChild(wsPreview(p.id, row));
  });
}

function wsHead(text) {
  const h = document.createElement("div");
  h.className = "ws-head";
  h.textContent = text;
  return h;
}

function wsItem(text, icon, active, onClick) {
  const b = document.createElement("div");
  b.className = "ws-item" + (active ? " is-active" : "");
  b.setAttribute("role", "button");
  b.tabIndex = 0;
  const label = document.createElement("span");
  label.className = "ws-item__label";
  label.textContent = text;
  label.title = text;
  b.append(wsIcon(icon), label);
  b.addEventListener("click", onClick);
  b.addEventListener("keydown", e => { if (e.key === "Enter") onClick(); });
  return b;
}

// Under an open project: its tasks by status, each a filter for the list. Empty statuses are left out.
function wsPreview(projectId, row) {
  const ul = document.createElement("ul");
  ul.className = "ws-tasks";
  Promise.all(TASK_SECTIONS.map(sec => api.listTasks(1, sec.key, { project: projectId }, 1)
    .then(res => (res && res.data && res.data.total) || 0).catch(() => 0))).then(counts => {
    const total = counts.reduce((a, b) => a + b, 0);
    const count = document.createElement("span");
    count.className = "ws-item__count";
    count.textContent = total;
    row.insertBefore(count, row.querySelector(".ws-caret"));
    if (!total) {
      const li = document.createElement("li");
      li.className = "ws-tasks__empty";
      li.textContent = L("work.noTasks");
      ul.appendChild(li);
    }
    TASK_SECTIONS.forEach((sec, i) => {
      if (!counts[i]) return;
      const li = document.createElement("li");
      li.className = "ws-task" + (taskNarrow.project === projectId && taskStatus === sec.key ? " is-active" : "");
      const dot = document.createElement("span");
      dot.className = "ws-dot is-" + (sec.key === "active" ? "running" : sec.key);
      const name = document.createElement("span");
      name.className = "ws-task__title";
      name.textContent = L(sec.label);
      const n = document.createElement("span");
      n.className = "ws-item__count";
      n.textContent = counts[i];
      li.append(dot, name, n);
      li.addEventListener("click", () => pickWorkProject(projectId, sec.key));
      ul.appendChild(li);
    });
  });
  return ul;
}

function pickWorkView(key) {
  taskStatus = key;
  taskNarrow.project = "";
  taskPage = 1;
  loadTasks();
}

function pickWorkProject(id, status) {
  taskNarrow.project = id;
  taskStatus = status || "";
  workOpenProjects.add(id);
  taskPage = 1;
  loadTasks();
}

// ---------- the list in sections by status: what needs the user first, finished work last ----------
// Each section asks for its own tasks, so paging doesn't split a section across pages.
const TASK_SECTIONS = [
  { key: "needs_confirmation", label: "work.secConfirm", limit: 20 },
  { key: "active", label: "work.secActive", limit: 20 },
  { key: "error", label: "work.secError", limit: 20 },
  { key: "done", label: "work.secDone", limit: 10 },
  { key: "stopped", label: "work.secStopped", limit: 10, folded: true },
];
const sectionLimit = {};
const sectionFolded = new Set(TASK_SECTIONS.filter(x => x.folded).map(x => x.key));
let sectionsToken = 0;

async function renderTaskSections() {
  const token = ++sectionsToken;
  const pages = await Promise.all(TASK_SECTIONS.map(sec =>
    api.listTasks(1, sec.key, taskNarrow, sectionLimit[sec.key] || sec.limit)
      .then(res => (res && res.data) || null).catch(() => null)));
  if (token !== sectionsToken) return; // a newer load is drawing the list
  tel.pager.hidden = true;
  tel.rows.innerHTML = "";
  const total = pages.reduce((n, p) => n + (p ? p.total : 0), 0);
  tfEl.sum.textContent = taskNarrow.tag ? L("tasks.filterSum", { n: total,
    spend: "$" + pages.reduce((n, p) => n + Number((p && p.spend) || 0), 0).toFixed(2) }) : "";
  tel.empty.hidden = total > 0;
  const all = pages.flatMap(p => (p && p.items) || []);
  tel.rows.closest("table").classList.toggle("is-solo", new Set(all.map(t => t.createdBy)).size <= 1);
  TASK_SECTIONS.forEach((sec, i) => {
    const page = pages[i];
    if (!page || !page.total) return;
    const folded = sectionFolded.has(sec.key);
    tel.rows.appendChild(sectionHead(sec, page.total, folded));
    if (folded) return;
    page.items.forEach(t => {
      tel.rows.appendChild(taskRow(t));
      if (taskExpanded.has(t.id)) showSubtaskRows(t);
    });
    if (page.total > page.items.length) tel.rows.appendChild(sectionMore(sec, page.total - page.items.length));
  });
}

function sectionHead(sec, count, folded) {
  const tr = document.createElement("tr");
  tr.className = "t-section is-" + sec.key + (folded ? " is-folded" : "");
  const td = document.createElement("td");
  td.colSpan = 9;
  const caret = document.createElement("span");
  caret.className = "t-section__caret";
  const dot = document.createElement("span");
  dot.className = "ws-dot is-" + (sec.key === "active" ? "running" : sec.key);
  const name = document.createElement("span");
  name.className = "t-section__name";
  name.textContent = L(sec.label);
  const n = document.createElement("span");
  n.className = "t-section__count";
  n.textContent = count;
  td.append(caret, dot, name, n);
  tr.appendChild(td);
  tr.title = L(folded ? "work.expand" : "work.collapse");
  tr.addEventListener("click", () => {
    if (folded) sectionFolded.delete(sec.key); else sectionFolded.add(sec.key);
    renderTaskSections();
  });
  return tr;
}

function sectionMore(sec, left) {
  const tr = document.createElement("tr");
  tr.className = "t-section-more";
  const td = document.createElement("td");
  td.colSpan = 9;
  const b = document.createElement("button");
  b.type = "button";
  b.className = "link-btn";
  b.textContent = L("work.showMore", { n: left });
  b.addEventListener("click", () => {
    sectionLimit[sec.key] = (sectionLimit[sec.key] || sec.limit) + 30;
    renderTaskSections();
  });
  td.appendChild(b);
  tr.appendChild(td);
  return tr;
}

// ---------- a complex task's subtasks, as a tree under it in the list ----------
const taskExpanded = new Set();

function toggleSubtaskRows(t) {
  if (taskExpanded.has(t.id)) {
    taskExpanded.delete(t.id);
    tel.rows.querySelectorAll('tr[data-parent="' + CSS.escape(t.id) + '"]').forEach(r => r.remove());
    setRowOpen(t.id, false);
  } else {
    taskExpanded.add(t.id);
    showSubtaskRows(t);
  }
}

async function showSubtaskRows(t) {
  setRowOpen(t.id, true);
  let r = null;
  try { r = await api.taskRelations(t.id); } catch (e) { return; }
  const parentRow = tel.rows.querySelector('tr[data-id="' + CSS.escape(t.id) + '"]');
  if (!parentRow || !taskExpanded.has(t.id)) return; // the list was redrawn or the row closed meanwhile
  tel.rows.querySelectorAll('tr[data-parent="' + CSS.escape(t.id) + '"]').forEach(x => x.remove());
  const subs = (r && r.subtasks) || [];
  let after = parentRow;
  subs.forEach((s, i) => {
    const row = taskRow(Object.assign({ parentId: t.id }, s), { child: true, last: i === subs.length - 1 });
    after.after(row);
    after = row;
  });
}

function setRowOpen(id, open) {
  const row = tel.rows.querySelector('tr[data-id="' + CSS.escape(id) + '"]');
  if (!row) return;
  row.classList.toggle("is-open", open);
  const btn = row.querySelector(".t-tree");
  if (btn) btn.setAttribute("aria-expanded", String(open));
}

// ---------- narrowing the list by topic tag: one chip per tag, "All" first ----------
const taskNarrow = { project: "", tag: "", agent: "", source: "" };
const tfEl = {
  box: document.getElementById("taskFilter"),
  bar: document.getElementById("taskTagBar"),
  sum: document.getElementById("taskFilterSum"),
};

async function loadTaskFacets() {
  let f = null;
  try { f = await api.taskFacets(); } catch (e) { f = {}; }
  const tags = f.tags || [];
  // Keep a chosen tag even once it has no tasks left, so the filter doesn't silently drop.
  if (taskNarrow.tag && !tags.some(t => t.key === taskNarrow.tag)) tags.push({ key: taskNarrow.tag, label: taskNarrow.tag, count: 0 });
  // Always there, with "All" even before any task is tagged: the list keeps its shape.
  const TOP = 8;
  const sorted = [...tags].sort((a, b) => b.count - a.count);
  const shown = sorted.slice(0, TOP);
  const chosen = sorted.find(t => t.key === taskNarrow.tag);
  if (chosen && !shown.includes(chosen)) shown.push(chosen); // the tag in use always shows
  const rest = sorted.filter(t => !shown.includes(t));
  const chips = [tagBarChip(L("tasks.filterAll"), null, ""), ...shown.map(t => tagBarChip("#" + t.label, t.count, t.key))];
  if (rest.length) {
    const more = document.createElement("button");
    more.type = "button";
    more.className = "tag-bar__chip tag-bar__more";
    more.textContent = (taskTagsOpen ? L("tasks.tagsLess") : L("tasks.tagsMore", { n: rest.length }));
    more.addEventListener("click", () => { taskTagsOpen = !taskTagsOpen; loadTaskFacets(); });
    if (taskTagsOpen) chips.push(...rest.map(t => tagBarChip("#" + t.label, t.count, t.key)));
    chips.push(more);
  }
  tfEl.bar.replaceChildren(...chips);
}
let taskTagsOpen = false;

function tagBarChip(label, count, key) {
  const b = document.createElement("button");
  b.type = "button";
  b.className = "tag-bar__chip" + (taskNarrow.tag === key ? " is-active" : "");
  b.setAttribute("role", "tab");
  b.setAttribute("aria-selected", String(taskNarrow.tag === key));
  const name = document.createElement("span");
  name.textContent = label;
  b.appendChild(name);
  if (count != null) {
    const n = document.createElement("span");
    n.className = "tag-bar__count";
    n.textContent = count;
    b.appendChild(n);
  }
  b.addEventListener("click", () => {
    taskNarrow.tag = taskNarrow.tag === key ? "" : key;
    taskPage = 1;
    loadTasks();
  });
  return b;
}

/** Narrows the list to one tag (from a tag chip). */
function filterByTag(tag) {
  taskNarrow.tag = tag;
  taskPage = 1;
  showTaskList();
  loadTasks();
}

/** general | coding | complex | article | subtask (a complex task's part). */
function taskKind(t) {
  if (t.parentId) return "subtask";
  return t.flow === "coding" || t.flow === "complex" || t.flow === "article" ? t.flow : "general";
}

/** "Waiting: A, B to finish", or why it can't start on its own; "" for a task that waits for nothing. */
function waitText(t) {
  const w = t.waitingFor || [];
  if (!w.length) return "";
  if (t.blocked) return L("tasks.blockedBy", { reason: t.blocked });
  return L("tasks.waitingFor", { names: w.map(r => r.title).join(L("tasks.waitSep")) });
}

/** "Run now" for a task stuck on one that failed or was stopped: it starts on the scheduler's next pass. */
function runNowButton(t, className) {
  const run = document.createElement("button");
  run.type = "button";
  run.className = className;
  run.textContent = L("tasks.runNow");
  run.title = L("tasks.runNowHint");
  run.addEventListener("click", async e => {
    e.stopPropagation();
    if (!window.confirm(L("tasks.runNowConfirm"))) return;
    run.disabled = true;
    try {
      await api.runTaskNow(t.id);
      if (task.id === t.id) openTask(t.id); else loadTasks();
    } catch (err) {
      run.disabled = false;
      window.alert(err && err.message ? err.message : String(err));
    }
  });
  return run;
}

/** Under the title in the detail view: what the task waits for; once it is stuck, why, and a "run it now" button. */
function renderWait(t) {
  let box = document.getElementById("taskDetailWait");
  if (!box) {
    box = document.createElement("div");
    box.id = "taskDetailWait";
    box.className = "task-detail__wait";
    tel.rel.after(box);
  }
  const w = t.waitingFor || [];
  box.hidden = !w.length;
  box.classList.toggle("is-blocked", !!t.blocked);
  if (!w.length) { box.replaceChildren(); return; }
  const text = document.createElement("span");
  text.append(document.createTextNode(L(t.blocked ? "tasks.waitBlockedLead" : "tasks.waitLead") + " "));
  w.forEach((r, i) => {
    if (i) text.append(document.createTextNode(L("tasks.waitSep")));
    const a = document.createElement("button");
    a.type = "button";
    a.className = "link-btn";
    a.textContent = r.title;
    a.addEventListener("click", () => openTask(r.id));
    text.appendChild(a);
  });
  text.append(document.createTextNode(" " + L("tasks.waitTail")));
  const parts = [text];
  if (t.blocked) {
    const why = document.createElement("span");
    why.className = "task-detail__wait-why";
    why.textContent = L("tasks.blockedBy", { reason: t.blocked });
    parts.push(why);
  }
  if (t.blocked) parts.push(runNowButton(t, "btn btn--sm btn--primary"));
  box.replaceChildren(...parts);
}

/** {@code opts.child}: a subtask drawn under its complex task ({@code opts.last}: the last of them). */
function taskRow(t, opts = {}) {
  const tr = document.createElement("tr");
  tr.dataset.id = t.id;
  if (opts.child) {
    tr.dataset.parent = t.parentId;
    tr.className = "t-row--child" + (opts.last ? " is-last" : "");
  }
  tr.addEventListener("click", () => openTask(t.id));

  const tdTask = document.createElement("td");
  tdTask.className = "t-task";
  const title = document.createElement("div");
  title.className = "t-title";
  const kind = taskKind(t);
  if (kind === "complex" && t.subtasks) {
    const tree = document.createElement("button");
    tree.type = "button";
    tree.className = "t-tree";
    tree.setAttribute("aria-expanded", "false");
    tree.title = L("tasks.showSubtasks");
    tree.addEventListener("click", e => { e.stopPropagation(); toggleSubtaskRows(t); });
    title.appendChild(tree);
  } else if (!opts.child) {
    const pad = document.createElement("span");
    pad.className = "t-tree-pad";
    title.appendChild(pad);
  }
  const name = document.createElement("span");
  name.className = "t-title__text";
  name.textContent = t.title;
  name.title = t.title;
  title.appendChild(name);
  // Chat is where tasks usually come from: only another source is marked.
  if (t.source && t.source !== "web_chat") {
    const src = document.createElement("span");
    src.className = "t-src";
    src.textContent = TASK_SOURCE_LABEL[t.source] || t.source;
    title.appendChild(src);
  }
  title.appendChild(taskNumberEl(t));
  tdTask.appendChild(title);
  const wait = waitText(t);
  if (wait) {
    const w = document.createElement("span");
    w.className = "t-chip t-chip--wait" + (t.blocked ? " is-blocked" : "");
    w.textContent = wait;
    w.title = t.blocked ? L("tasks.blockedHint") : L("tasks.waitHint");
    tdTask.appendChild(w);
    if (t.blocked) tdTask.appendChild(runNowButton(t, "t-chip t-chip--run"));
  }

  const tdKind = document.createElement("td");
  const kindPill = document.createElement("span");
  kindPill.className = "t-kind is-" + kind;
  kindPill.textContent = L("tasks.kind_" + kind);
  kindPill.title = L("tasks.kindHint_" + kind);
  // A complex task: how many of its subtasks are over, in the same pill.
  if (kind === "complex" && t.subtasks) {
    const n = document.createElement("b");
    n.textContent = t.subtasksFinished + "/" + t.subtasks;
    kindPill.appendChild(n);
    kindPill.title += " · " + L("tasks.colSubtasks") + " " + t.subtasksFinished + "/" + t.subtasks;
  }
  tdKind.appendChild(kindPill);

  const tdAgent = document.createElement("td");
  tdAgent.className = "t-agent";
  const agentName = document.createElement("div");
  // The agent it ran as, when an agent set it off; else the CLI it ran in.
  agentName.textContent = t.agentName || agentLabel(t.agentType);
  if (t.agentName) agentName.title = agentLabel(t.agentType);
  tdAgent.appendChild(agentName);

  const tdModel = document.createElement("td");
  tdModel.className = "t-model";
  tdModel.textContent = taskModelLabel(t);

  const tdCreated = document.createElement("td");
  tdCreated.className = "t-created";
  tdCreated.textContent = shortWhen(t.createdAt);
  tdCreated.title = formatDateTime(t.createdAt);

  const tdBy = document.createElement("td");
  tdBy.className = "t-by t-col-by";
  tdBy.textContent = t.createdBy ? personName(t.createdBy) : "—";

  const tdSpend = document.createElement("td");
  tdSpend.className = "t-spend";
  // A complex task's spend includes its subtasks'.
  tdSpend.textContent = "$" + Number(t.totalSpend != null ? t.totalSpend : t.spend || 0).toFixed(2);
  if (t.subtasks && Number(t.totalSpend) !== Number(t.spend)) tdSpend.title = L("tasks.spendTotalHint");

  const tdStatus = document.createElement("td");
  const cell = document.createElement("div");
  cell.className = "t-status-cell";
  const top = document.createElement("div");
  top.className = "t-status-top";
  top.appendChild(listStatusBadge(t));
  const elapsed = document.createElement("span");
  elapsed.className = "t-elapsed";
  elapsed.textContent = shortElapsed(t);
  elapsed.title = formatElapsed(t);
  top.appendChild(elapsed);
  const bar = document.createElement("div");
  bar.className = "progress" + (t.status === "error" ? " is-error" : isActiveStatus(t.status) ? " is-running" : "");
  const fill = document.createElement("div");
  fill.className = "progress__bar";
  fill.style.width = (t.progress || 0) + "%";
  bar.appendChild(fill);
  // Only a task under way needs a bar: a full green one says what "Done" already does.
  cell.appendChild(top);
  if (isActiveStatus(t.status)) cell.appendChild(bar);
  tdStatus.appendChild(cell);

  const tdActions = document.createElement("td");
  const del = document.createElement("button");
  del.className = "link-btn link-btn--danger";
  del.textContent = "×";
  del.title = L("common.delete");
  del.addEventListener("click", e => { e.stopPropagation(); deleteTask(t); });
  const wrap = document.createElement("div");
  wrap.className = "t-row-actions";
  wrap.appendChild(del);
  tdActions.appendChild(wrap);

  if (opts.child) del.hidden = true; // a subtask goes with its complex task
  tr.append(tdTask, tdKind, tdAgent, tdModel, tdBy, tdCreated, tdSpend, tdStatus, tdActions);
  return tr;
}

function statusBadge(status) {
  const b = document.createElement("span");
  b.className = "status-badge is-" + status;
  b.textContent = TASK_STATUS_LABEL[status] || status;
  return b;
}

/** A complex task whose subtasks are all over and that is at work again: it is writing the summary. */
const summingUp = t => t.flow === "complex" && t.subtasks > 0 && t.subtasksFinished >= t.subtasks
  && (t.status === "running" || t.status === "pending");

function listStatusBadge(t) {
  const b = statusBadge(t.status);
  if (summingUp(t)) b.textContent = L("tasks.summingUp");
  return b;
}

async function deleteTask(t) {
  if (!window.confirm(L("tasks.deleteConfirm", { name: t.title }))) return;
  try { await api.deleteTask(t.id); } catch (e) { /* ignore */ }
  if (task.id === t.id) showTaskList();
  await loadTasks();
}

// Where the back button goes: the task (and tab) shown before this one, or the page the task was opened from.
const taskBack = []; // [{ task, tab } | { view }]
const currentTaskTab = () => (tel.tabs.querySelector(".model-tab.is-active") || {}).dataset?.tab || null;

async function openTask(id, opts = {}) {
  if (!opts.back) {
    if (enteredTasksFrom) { // opened from a chat, to-do, projects…: back returns there
      taskBack.length = 0;
      taskBack.push({ view: enteredTasksFrom });
      enteredTasksFrom = null;
    } else if (task.id && task.id !== id && !tel.detailView.hidden) {
      taskBack.push({ task: task.id, tab: currentTaskTab() });
    }
  }
  task.id = id;
  task.renderedEvents = 0;
  task.mergeEl = null;
  task.mergeType = null;
  task.toolGroup = null;
  tel.listView.hidden = true;
  tel.detailView.hidden = false;
  tel.stream.innerHTML = "";
  let res;
  try { res = await api.taskDetail(id); } catch (e) { showTaskList(); return; }
  const d = res && res.data;
  if (!d) { showTaskList(); return; }
  renderDetail(d);
}

function renderDetail(d) {
  const t = d.task;
  task.general = !!(d.harness && d.harness.general); // before the run log is drawn: its tool calls start folded
  tel.dTitle.replaceChildren(t.title, taskNumberEl(t));
  const meta = [L("tasks.kind_" + taskKind(t)), TASK_SOURCE_LABEL[t.source] || t.source, t.repo, t.branch].filter(Boolean).join(" · ");
  tel.dMeta.textContent = meta;
  if (t.source === "automation") linkScheduledTask(t.id);

  // Interleave agent events with the user's steering messages by time.
  const timeline = [];
  (d.events || []).forEach(e => timeline.push({ kind: e.type, payload: e.payload, at: e.createdAt }));
  (d.messages || []).forEach(m => {
    if (m.role === "user") timeline.push({ kind: "cmd", payload: m.content, images: m.images, at: m.createdAt });
  });
  timeline.sort((a, b) => new Date(a.at || 0) - new Date(b.at || 0));
  tel.stream.innerHTML = "";
  task.mergeEl = null;
  task.mergeType = null;
  task.toolGroup = null;
  coalesce(timeline).forEach(appendStreamItem);
  task.renderedEvents = (d.events || []).length;
  if (timeline.length === 0) {
    const empty = document.createElement("p");
    empty.className = "task-stream__empty";
    empty.textContent = L("tasks.streamWaiting");
    tel.stream.appendChild(empty);
  }

  updateStatusUi(t);
  renderWait(t);
  renderFacts(t);
  renderSessionInfo(t);
  renderTaskTags(t);
  task.current = t;
  task.relKey = null;
  tel.subtasksTab.hidden = t.flow !== "complex";
  tel.subtasksTab.textContent = L("tasks.tabSubtasks", { n: t.subtasks || 0 });
  loadTaskRelations(t);
  task.harness = !!d.harness;
  renderPhases(d.harness ? [] : d.phases); // a harness-run task shows its steps in the pipeline above instead
  task.hasPhases = !!(d.phases && d.phases.length);
  // Only a task with a flow of its own (coding) has phases; the rest just run.
  tel.tabs.querySelector('[data-tab="run"]').textContent = task.hasPhases
    ? L("tasks.tabRunPhased", { n: d.phases.length }) : L("tasks.tabRun");
  if (task.harnessFor !== t.id) { hnOpenFiles = new Set(); task.harnessFor = t.id; }
  renderHarness(d.harness, t);
  if (tel.promptBody) tel.promptBody.innerHTML = renderMarkdown(d.brief || t.title || "");
  // A finished task opens on what it came up with; one still at work on its run log.
  const last = [...(d.events || [])].reverse().find(e => e.type === "result");
  renderTaskOutput(task.general ? generalNote(d) : last ? last.payload : null, t);
  // A complex task still at work opens on its subtasks: that is where its progress is. A general task always opens
  // on what it produced.
  const answered = last || (d.harness && d.harness.delivery);
  // A writing task opens on its final draft once delivered, on its run while at work.
  setTaskTab(d.harness && d.harness.harness === "subtitle" ? "ssub"
    : d.harness && d.harness.harness === "article" ? (t.status === "done" ? "afinal" : "run")
    : task.general && !(t.flow === "complex" && t.subtasks && isActiveStatus(t.status)) ? "output"
    : answered && t.status === "done" ? "output" : t.flow === "complex" && t.subtasks ? "subtasks" : "run");
  loadTaskFiles(t.id);
  task.choiceKey = t.status + "|" + t.model;
  loadModelChoices(t);
  loadTaskTiming(t.id);
  loadTaskUsage(t.id);
  task.lastPhaseKey = t.status + "|" + t.progress;
  if (isActiveStatus(t.status)) startTaskPolling(); else stopTaskPolling();
}

// The "model choice" tab: before the task runs, how hard it was judged, what it could have run on and what was
// picked, and why; then one more entry each time it failed and moved to a dearer model.
const CHOICE_BY = ["judge", "user", "fallback", "escalation", "default", "fixed", "reviewer"];

async function loadModelChoices(t) {
  const box = document.getElementById("taskModelChoices");
  let list = [];
  try { list = ((await api.taskModelChoices(t.id)) || {}).data || []; } catch (e) { /* keep what's shown */ return; }
  if (task.id !== t.id) return;
  box.replaceChildren();
  if (!list.length) {
    const p = document.createElement("p");
    p.className = "model-choices__empty";
    p.textContent = t.status === "pending" ? L("tasks.choicePending") : L("tasks.choiceNone", { model: t.model || L("tasks.modelDefault") });
    box.appendChild(p);
    return;
  }
  list.forEach(c => box.appendChild(modelChoiceCard(c)));
}

function modelChoiceCard(c) {
  const card = document.createElement("section");
  card.className = "model-choice is-" + c.by;
  const head = document.createElement("div");
  head.className = "model-choice__head";
  const by = document.createElement("span");
  by.className = "model-choice__by";
  by.textContent = CHOICE_BY.includes(c.by) ? L("tasks.choiceBy." + c.by) : c.by;
  const when = document.createElement("span");
  when.className = "model-choice__when";
  when.textContent = formatDateTime(c.createdAt);
  head.append(by, when);
  const facts = document.createElement("dl");
  facts.className = "model-choice__facts";
  const row = (k, v) => { if (!v) return; const dt = document.createElement("dt"); dt.textContent = k;
    const dd = document.createElement("dd"); dd.textContent = v; facts.append(dt, dd); };
  row(L("tasks.choiceDifficulty"), c.difficulty ? L("tasks.difficulty." + c.difficulty) : null);
  row(L("tasks.choiceModel"), [c.model, c.cli].filter(Boolean).join(" · "));
  row(L("tasks.choiceJudge"), c.judge);
  card.append(head, facts);
  if (c.reasoning) {
    const why = document.createElement("p");
    why.className = "model-choice__why";
    why.textContent = c.reasoning;
    card.appendChild(why);
  }
  if (c.candidates) {
    const more = document.createElement("details");
    more.className = "model-choice__candidates";
    const sum = document.createElement("summary");
    sum.textContent = L("tasks.choiceCandidates", { n: c.candidates.split("\n").length });
    const pre = document.createElement("pre");
    pre.textContent = c.candidates;
    more.append(sum, pre);
    card.appendChild(more);
  }
  return card;
}

// The task's output: its final reply (what it came up with). Not a file: files are what it saved on purpose.
function renderTaskOutput(text, t) {
  const box = document.getElementById("taskOutput");
  task.outputText = text;
  // A coding task's answer is its delivery, not the reviewer's last words.
  if (task.harness && renderHarnessOutput(box, text, t)) return;
  if (text) { box.innerHTML = renderMarkdown(text); return; }
  box.innerHTML = "";
  const p = document.createElement("p");
  p.className = "task-output__empty";
  p.textContent = L(t && (t.status === "error" || t.status === "stopped") ? "tasks.outputNone" : "tasks.outputWaiting");
  box.appendChild(p);
}

/**
 * What a general task's Output shows under its files: once delivered, what it delivered (its last message); before
 * that, the agent's own summary of its last round — never the checker's verdict or the question it waits on, which
 * are messages too.
 */
function generalNote(d) {
  const delivered = d.harness.steps.every(s => s.status === "done");
  if (!delivered) return d.harness.general.reply || null;
  const last = [...(d.messages || [])].reverse().find(m => m.role === "assistant" && m.content);
  return last ? last.content : d.harness.general.reply || null;
}

// Detail tabs: Prompt / Model choice / Conversation (run log + coding phases) / Output / Session.
function setTaskTab(tab) {
  tel.tabs.querySelectorAll(".model-tab").forEach(b => b.classList.toggle("is-active", b.dataset.tab === tab));
  tel.detailView.querySelectorAll("[data-pane-tab]").forEach(p => { p.hidden = p.dataset.paneTab !== tab; });
  if (tab === "run") scrollRunToEnd();
  if (tab === "usage" && task.id) loadTaskUsage(task.id); // a running task keeps adding up
}
tel.tabs.addEventListener("click", e => {
  const b = e.target.closest(".model-tab");
  if (b) setTaskTab(b.dataset.tab);
});

// After a full render the pane's height is only final once layout runs (markdown, phases),
// so scroll on the next frame as well as now.
function scrollRunToEnd() {
  scrollStream();
  requestAnimationFrame(scrollStream);
}

/**
 * The agent's reply is stored as it streamed, a few characters per event: a long task has thousands. Rendering each
 * one re-parsed the whole reply as Markdown every time (quadratic: one task here parsed 55 million characters on
 * open and froze the page). Runs of text or thinking are joined first, so each block renders once.
 */
function coalesce(items) {
  const out = [];
  items.forEach(item => {
    const prev = out[out.length - 1];
    if (prev && (item.kind === "text" || item.kind === "thinking") && prev.kind === item.kind) {
      prev.payload = (prev.payload || "") + (item.payload || "");
    } else {
      out.push({ ...item });
    }
  });
  return out;
}

// What the task produced, from Files: its delivery and anything its agent saved. A click opens the preview.
async function loadTaskFiles(id) {
  const list = await api.taskFiles(id).catch(() => []);
  if (task.id !== id) return; // another task was opened meanwhile
  task.filesList = list || [];
  if (task.general) renderTaskOutput(task.outputText, task.current); // its Output tab shows them as cards
  tel.files.replaceChildren(...(list || []).map(f => {
    const li = document.createElement("li");
    const btn = document.createElement("button");
    btn.type = "button";
    btn.className = "task-file";
    btn.title = f.name;
    const icon = document.createElement("span");
    icon.className = "task-file__icon";
    icon.textContent = fileIcon(f);
    const name = document.createElement("span");
    name.className = "task-file__name";
    name.textContent = f.name;
    const size = document.createElement("span");
    size.className = "task-file__size";
    size.textContent = f.kind === "link" ? "" : formatBytes(f.size);
    btn.append(icon, name, size);
    btn.addEventListener("click", () => openFile(f));
    li.appendChild(btn);
    return li;
  }));
  tel.filesEmpty.hidden = (list || []).length > 0;
}

function formatBytes(n) {
  if (n < 1024) return n + " B";
  if (n < 1024 * 1024) return (n / 1024).toFixed(1) + " KB";
  return (n / 1024 / 1024).toFixed(1) + " MB";
}

function formatDateTime(iso) {
  if (!iso) return "—";
  const d = new Date(iso);
  if (isNaN(d.getTime())) return "—";
  const p = n => String(n).padStart(2, "0");
  return d.getFullYear() + "/" + p(d.getMonth() + 1) + "/" + p(d.getDate())
    + " " + p(d.getHours()) + ":" + p(d.getMinutes()) + ":" + p(d.getSeconds());
}

/** The task's number ("#12"), what people quote; a click copies it. Before numbering existed: the short uuid. */
function taskNumberEl(t) {
  const label = t.number ? "#" + t.number : t.id.slice(0, 8);
  const el = document.createElement("button");
  el.type = "button";
  el.className = "t-id";
  el.textContent = label;
  el.title = label + " · " + t.id + "\n" + L("hn.clickCopy");
  el.addEventListener("click", e => {
    e.stopPropagation();
    navigator.clipboard.writeText(label).then(() => showToast(L("hn.copied"))).catch(() => {});
  });
  return el;
}

/** "14:15" today, "11/11 13:31" this year, "2025/11/11" before. */
function shortWhen(iso) {
  const d = new Date(iso);
  if (isNaN(d)) return "—";
  const now = new Date();
  const hm = String(d.getHours()).padStart(2, "0") + ":" + String(d.getMinutes()).padStart(2, "0");
  if (d.toDateString() === now.toDateString()) return hm;
  if (d.getFullYear() === now.getFullYear()) return (d.getMonth() + 1) + "/" + d.getDate() + " " + hm;
  return d.getFullYear() + "/" + (d.getMonth() + 1) + "/" + d.getDate();
}

/** The list's elapsed time: the two largest units ("1h 30m", "21m 40s"). */
function shortElapsed(task) {
  const full = formatElapsed(task);
  return full.split(" ").slice(0, 2).join(" ");
}

function formatElapsed(task) {
  if (!task || !task.startedAt) return "—";
  const start = new Date(task.startedAt).getTime();
  if (isNaN(start)) return "—";
  const end = task.finishedAt ? new Date(task.finishedAt).getTime() : Date.now();
  let s = Math.max(0, Math.floor((end - start) / 1000));
  const h = Math.floor(s / 3600); s -= h * 3600;
  const m = Math.floor(s / 60); s -= m * 60;
  if (h) return h + "h " + m + "m " + s + "s";
  if (m) return m + "m " + s + "s";
  return s + "s";
}

// text/result are agent natural-language output → render markdown. Everything
// else (thinking/tool/cmd/error) is technical text and stays plain.
const MD_KINDS = { text: 1, result: 1 };

// Fill an event body. Markdown kinds keep the full source in dataset.md and
// re-render sanitized HTML each tick (like renderStreamText) to avoid flicker;
// other kinds stay plain text.
// How a task agent marks the question it stops on: the platform reads it, people needn't see it.
const ASK_MARKER = /^[ \t]*NEEDS_CONFIRMATION[ \t]*[:：][ \t]*/gm;

function setEvBody(bodyEl, kind, fullRaw) {
  if (MD_KINDS[kind]) {
    bodyEl.dataset.raw = fullRaw;
    const shown = fullRaw.replace(ASK_MARKER, "");
    if (shown !== fullRaw) bodyEl.dataset.ask = "1"; else delete bodyEl.dataset.ask;
    bodyEl.dataset.md = shown;
    bodyEl.innerHTML = renderMarkdown(shown);
  } else {
    bodyEl.textContent = fullRaw;
  }
}

function appendStreamItem(item) {
  task.lastEventAt = item.at ? Math.min(Date.now(), new Date(item.at).getTime()) : Date.now(); // replayed: when it happened
  const type = item.kind;
  // The CLI's own system events carry just its session id: noise. SuperAgent's notes (moving to a stronger
  // model after a failure) are worth showing.
  if (type === "system" && (!item.payload || /^[0-9a-f-]{36}$/i.test(item.payload.trim()))) return;

  // A run of tool calls folds into one compact, collapsible block (one row per call).
  if (type === "tool_use" || type === "tool_result") { appendToolEvent(item); return; }
  // Any non-tool event ends the current tool group, so the next call starts a fresh one. The agent has moved on, so a
  // call still showing as running is done (MCP tools often send no result event of their own).
  if (task.toolGroup && task.toolGroup.pendingRow) finishToolRow(task.toolGroup.pendingRow, "");
  task.toolGroup = null;

  const mergeable = type === "text" || type === "thinking";
  if (mergeable && task.mergeType === type && task.mergeEl) {
    const bodyEl = task.mergeEl.querySelector(".ev__body");
    const prev = MD_KINDS[type] ? (bodyEl.dataset.raw || "") : bodyEl.textContent;
    setEvBody(bodyEl, type, prev + (item.payload || ""));
    if (bodyEl.dataset.ask) refreshConfirmChoices();
    scrollStream();
    return;
  }
  // The final answer comes twice (as the text streamed in, then as the run's result): show it once.
  if (type === "result") {
    const said = [...tel.stream.querySelectorAll(".ev--text .ev__body")].pop();
    if (said && (said.dataset.raw || "").trim() === (item.payload || "").trim()) {
      task.mergeEl = null;
      task.mergeType = null;
      return;
    }
  }
  const placeholder = tel.stream.querySelector(".task-stream__empty");
  if (placeholder) placeholder.remove();

  const ev = document.createElement("div");
  const label = EV_LABEL[type];
  const kind = type === "thinking" ? "think" : (type === "tool_use" || type === "tool_result") ? "tool" : type;
  ev.className = "ev ev--" + kind;
  ev.dataset.type = type === "thinking" ? "think" : (type === "tool_use" || type === "tool_result") ? "tool"
    : type === "result" ? "result" : type; // filter group
  if (label) {
    const lab = document.createElement("div");
    lab.className = "ev__label";
    lab.textContent = label;
    ev.appendChild(lab);
  }
  if (item.images && item.images.length) ev.appendChild(imageStrip(item.images));
  if (item.files && item.files.length) ev.appendChild(fileNameChips(item.files));
  const body = document.createElement("div");
  body.className = "ev__body";
  setEvBody(body, type, item.payload || "");
  body.hidden = type === "cmd" && !item.payload && !!((item.images && item.images.length) || (item.files && item.files.length));
  ev.appendChild(body);
  // A harness's step heading ("── 执行 · 第 2 轮 ──") opens a section of the log; a click folds it.
  if (type === "system" && /^── .+ ──$/.test((item.payload || "").trim())) ev.classList.add("ev--step");
  tel.stream.appendChild(ev);

  if (mergeable) { task.mergeEl = ev; task.mergeType = type; }
  else { task.mergeEl = null; task.mergeType = null; }
  if (body.dataset.ask) refreshConfirmChoices();
  scrollStream();
}

const EV_LABEL = {
  thinking: L("ev.think"), tool_use: L("ev.tool"), tool_result: L("ev.toolResult"),
  result: L("ev.result"), error: L("ev.error"), cmd: "",
};

// Consecutive tool calls collapse into one block: a "N tool calls" header over one row per call
// (✓ Used tool: name, or a spinner + Using tool while it runs). Clicking a row reveals its result.
function ensureToolGroup() {
  if (task.toolGroup) return task.toolGroup;
  const placeholder = tel.stream.querySelector(".task-stream__empty");
  if (placeholder) placeholder.remove();
  const el = document.createElement("div");
  el.className = "ev ev--tools";
  el.dataset.type = "tool";
  const head = document.createElement("button");
  head.type = "button";
  head.className = "evtools__head";
  head.setAttribute("aria-expanded", String(!task.general)); // a general task's calls start folded
  const chev = document.createElement("span");
  chev.className = "evtools__chev";
  const count = document.createElement("span");
  count.className = "evtools__count";
  head.append(chev, count);
  const list = document.createElement("div");
  list.className = "evtools__list";
  head.addEventListener("click", () => {
    const open = head.getAttribute("aria-expanded") === "true";
    head.setAttribute("aria-expanded", String(!open));
    list.hidden = open;
  });
  list.hidden = !!task.general;
  el.append(head, list);
  tel.stream.appendChild(el);
  task.mergeEl = null;
  task.mergeType = null;
  task.toolGroup = { list, count, n: 0, pendingRow: null };
  return task.toolGroup;
}

function appendToolEvent(item) {
  const g = ensureToolGroup();
  if (item.kind === "tool_use") {
    g.pendingRow = addToolRow(g, "using", item.payload || "", "");
  } else { // tool_result: fill in the call it belongs to, or stand alone if orphaned
    if (g.pendingRow) finishToolRow(g.pendingRow, item.payload || "");
    else addToolRow(g, "done", "", item.payload || "");
    g.pendingRow = null;
  }
  scrollStream();
}

function addToolRow(g, state, name, result) {
  const row = document.createElement("div");
  row.className = "evtool is-" + state;
  const btn = document.createElement("button");
  btn.type = "button";
  btn.className = "evtool__row";
  const icon = document.createElement("span");
  icon.className = "evtool__icon";
  const text = document.createElement("span");
  text.className = "evtool__text";
  const lead = document.createElement("span");
  lead.className = "evtool__lead";
  lead.textContent = L(state === "using" ? "ev.usingTool" : "ev.usedTool");
  const nameEl = document.createElement("b");
  nameEl.textContent = name;
  text.append(lead, nameEl);
  const chev = document.createElement("span");
  chev.className = "evtool__chev";
  btn.append(icon, text, chev);
  const detail = document.createElement("div");
  detail.className = "evtool__detail";
  detail.hidden = true;
  const body = document.createElement("div");
  body.className = "ev__body";
  detail.appendChild(body);
  btn.addEventListener("click", () => {
    if (row.dataset.hasDetail !== "1") return;
    const open = row.dataset.open === "1";
    row.dataset.open = open ? "0" : "1";
    detail.hidden = open;
  });
  row.append(btn, detail);
  g.list.appendChild(row);
  g.n += 1;
  g.count.textContent = L(g.n === 1 ? "ev.toolCall" : "ev.toolCalls", { n: g.n });
  const handle = { row, lead, body };
  if (result) finishToolRow(handle, result);
  return handle;
}

function finishToolRow(handle, result) {
  handle.row.className = "evtool is-done";
  handle.lead.textContent = L("ev.usedTool");
  if ((result || "").trim()) {
    handle.body.textContent = result;
    handle.row.dataset.hasDetail = "1";
  }
}

/** The run is over: nothing is still being called, whatever events went missing. */
function finishAllToolRows() {
  tel.stream.querySelectorAll(".evtool.is-using").forEach(row => {
    row.className = "evtool is-done";
    const lead = row.querySelector(".evtool__lead");
    if (lead) lead.textContent = L("ev.usedTool");
  });
  if (task.toolGroup) task.toolGroup.pendingRow = null;
}

// While a task runs, a line under its log says it's still at work and how long since the last thing it showed: some
// CLIs (Codex) only show a reply once it's written, so a long summary otherwise looks stuck.
setInterval(() => {
  const t = task.current;
  const on = !!t && t.status === "running" && !document.hidden;
  tel.live.hidden = !on;
  if (!on) return;
  const quiet = Math.max(0, Math.round((Date.now() - (task.lastEventAt || Date.now())) / 1000));
  const what = L(summingUp(t) ? "tasks.liveSumming" : "tasks.liveWorking");
  tel.live.textContent = quiet >= 10 ? L("tasks.liveQuiet", { what, d: tmDuration(quiet * 1000) }) : what;
}, 1000);

// The run pane is the scrolling container (the stream itself never overflows).
function scrollStream() { tel.runPane.scrollTop = tel.runPane.scrollHeight; }

function updateStatusUi(t) {
  tel.dStatus.className = "status-badge is-" + t.status;
  tel.dStatus.textContent = summingUp(t) ? L("tasks.summingUp") : TASK_STATUS_LABEL[t.status] || t.status;
  tel.progress.className = "progress" + (t.status === "error" ? " is-error" : isActiveStatus(t.status) ? " is-running" : "");
  tel.progressBar.style.width = (t.progress || 0) + "%";
  tel.progressPct.textContent = (t.progress || 0) + "%";
  const busy = isActiveStatus(t.status);
  if (!busy) finishAllToolRows();
  tel.steerInput.disabled = busy;
  tel.steerBtn.disabled = busy;
  taskImages.setDisabled(busy);
  // PENDING with a session = a steering message queued until that session is free (another task's
  // agent is running in it); say so instead of "running".
  const waiting = t.status === "pending" && !!t.sessionId;
  tel.steerInput.placeholder = waiting ? L("tasks.steerWaiting") : busy ? L("tasks.steerBusy") : L("tasks.steer");
  tel.stopBtn.disabled = !busy;
  // Carrying on: once it has come to something, and not for a subtask (its complex task carries on instead).
  tel.followBtn.hidden = !!t.parentId || !["done", "error", "stopped"].includes(t.status);
  renderConfirmBar(t);
}

// ---------- a decision the task is waiting for ----------
// The agent stops with a "NEEDS_CONFIRMATION:" question (the last message in the stream); small buttons in the
// reply box answer it in one click, and anything more goes in the box itself.
function renderConfirmBar(t) {
  const waiting = t.status === "needs_confirmation";
  tel.confirmBar.hidden = !waiting;
  tel.steerForm.classList.toggle("has-confirm", waiting);
  if (waiting) tel.confirmGo.disabled = false;
  refreshConfirmChoices();
}

// The answers the task's question offers ("👉 下一步：回复「A」或「B」") become the buttons; without any, the
// generic "confirm and continue".
function refreshConfirmChoices() {
  tel.confirmBar.querySelectorAll(".task-confirm__choice").forEach(b => b.remove());
  const asked = [...tel.stream.querySelectorAll('.ev__body[data-ask]')].pop();
  const replies = asked ? nextReplies(asked.dataset.raw) : [];
  tel.confirmGo.hidden = replies.length > 0;
  replies.forEach((reply, i) => {
    const b = document.createElement("button");
    b.type = "button";
    b.className = "task-confirm__btn task-confirm__choice" + (i === 0 ? " task-confirm__btn--go" : "");
    b.textContent = reply;
    b.addEventListener("click", () => sendConfirm(reply, b));
    tel.confirmBar.insertBefore(b, tel.confirmEdit);
  });
}

tel.confirmGo.addEventListener("click", () => sendConfirm(L("tasks.confirmReply"), tel.confirmGo));

async function sendConfirm(text, btn) {
  if (!task.id) return;
  btn.disabled = true;
  const res = await api.taskMessage(task.id, text, [], []).catch(() => null);
  if (!res || !res.ok) {
    const body = res && await res.json().catch(() => null);
    showToast((body && body.message) || L("tasks.steerFailed"), true);
    btn.disabled = false;
    return;
  }
  tel.confirmBar.hidden = true;
  tel.steerForm.classList.remove("has-confirm");
  setTaskTab("run");
  appendStreamItem({ kind: "cmd", payload: text });
  startTaskPolling();
}
tel.confirmEdit.addEventListener("click", () => {
  tel.steerInput.focus();
  tel.steerInput.placeholder = L("tasks.confirmEditPh");
});

// The model a task ran on (chosen for it before it started).
function taskModelLabel(t) {
  const model = t.model || L("tasks.modelDefault");
  return model;
}

function renderFacts(t) {
  fillFacts(tel.facts, factsOf(t));
}

function factsOf(t) {
  const facts = [
    [L("tasks.kind"), L("tasks.kind_" + taskKind(t)), L("tasks.kindHint_" + taskKind(t))],
    [L("tasks.agent"), agentLabel(t.agentType)],
    [L("tasks.model"), taskModelLabel(t)],
    // No effort setting exists yet: tasks run at the CLI's default.
    [L("tasks.effort"), L("tasks.effortDefault"), L("tasks.effortHint")],
    [L("tasks.session"), t.sessionId ? t.sessionId.slice(0, 8) : "—", t.sessionId || ""],
    [L("tasks.created"), formatDateTime(t.createdAt)],
    [L("tasks.by"), personName(t.createdBy) || "—"],
    [L("tasks.spend"), "$" + Number(t.spend || 0).toFixed(2)],
    [L("tasks.elapsed"), formatElapsed(t)],
  ];
  // A complex task costs what it spent plus what its subtasks did (from its relations, fetched alongside).
  if (t.subtasks && task.relTotal && task.relTotal.id === t.id) {
    facts.push([L("tasks.spendTotal"), "$" + Number(task.relTotal.spend || 0).toFixed(2), L("tasks.spendTotalHint")]);
  }
  if (t.error) facts.push([L("tasks.error"), t.error]);
  return facts;
}

function renderSessionInfo(t) {
  const state = t.sessionState ? L("tasks.sessionState." + t.sessionState) : "—";
  fillFacts(tel.sessionInfo, [
    [L("tasks.sessionId"), t.sessionId || L("tasks.sessionNone")],
    [L("tasks.sessionState"), state],
    [L("tasks.workspace"), t.workspaceDir || "—"],
    [L("tasks.agentType"), t.agentType ? agentLabel(t.agentType) + " (" + t.agentType + ")" : "—"],
    [L("tasks.model"), taskModelLabel(t)],
    [L("tasks.effort"), L("tasks.effortDefault"), L("tasks.effortHint")],
  ]);
}

/** Renders [label, value, title?] rows into a <dl>. */
function fillFacts(dl, rows) {
  dl.innerHTML = "";
  rows.forEach(([k, v, title]) => {
    const dt = document.createElement("dt"); dt.textContent = k;
    const dd = document.createElement("dd"); dd.textContent = v;
    if (title) dd.title = title;
    dl.append(dt, dd);
  });
}

// The coding flow's three steps: a status chip per step, plus the PRD markdown
// and the acceptance evidence inline. Hidden entirely for non-coding tasks.
function renderPhases(phases) {
  if (!tel.phasesPanel || !tel.phases) return;
  if (!phases || !phases.length) { tel.phasesPanel.hidden = true; tel.phases.innerHTML = ""; return; }
  tel.phasesPanel.hidden = false;
  tel.phases.innerHTML = "";
  phases.forEach(p => {
    const li = document.createElement("li");
    li.className = "phase phase--" + p.status;
    const head = document.createElement("div");
    head.className = "phase__head";
    const name = document.createElement("span");
    name.className = "phase__name";
    name.textContent = p.name;
    const chip = document.createElement("span");
    chip.className = "phase__status";
    chip.textContent = L("tasks.phaseStatus." + p.status) || p.status;
    head.append(name, chip);
    li.appendChild(head);
    if (p.content) {
      const body = document.createElement("div");
      body.className = "phase__doc";
      body.innerHTML = renderMarkdown(p.content);
      li.appendChild(body);
    }
    if (p.evidence) {
      const ev = document.createElement("div");
      ev.className = "phase__doc";
      ev.innerHTML = renderMarkdown(p.evidence);
      li.appendChild(ev);
    }
    tel.phases.appendChild(li);
  });
}

// One poll at a time: the next is scheduled when this one is done, so a slow answer never overlaps the next
// (overlapping polls read the same offset and drew the same events twice).
function startTaskPolling() {
  stopTaskPolling();
  task.polling = true;
  task.pollTimer = setTimeout(pollLoop, 1500);
}
function stopTaskPolling() {
  task.polling = false;
  if (task.pollTimer) { clearTimeout(task.pollTimer); task.pollTimer = null; }
}
async function pollLoop() {
  await pollTask();
  if (task.polling) task.pollTimer = setTimeout(pollLoop, 1500);
}

async function pollTask() {
  const id = task.id;
  if (!id) { stopTaskPolling(); return; }
  try {
    const [evRes, stRes] = await Promise.all([api.taskEvents(id, task.renderedEvents), api.taskStatus(id)]);
    if (task.id !== id) return; // another task was opened meanwhile
    const newEvents = (evRes && evRes.data) || [];
    coalesce(newEvents.map(e => ({ kind: e.type, payload: e.payload, at: e.createdAt }))).forEach(appendStreamItem);
    task.renderedEvents += newEvents.length;
    const result = [...newEvents].reverse().find(e => e.type === "result");
    // A general task's Output comes from its record (refetched below), not whatever turn answered last (the checker).
    if (result && !task.general) renderTaskOutput(result.payload, null);
    const t = stRes && stRes.data;
    if (!t) return;
    updateStatusUi(t);
    renderWait(t);
    renderFacts(t);
    renderSessionInfo(t);
    task.current = t;
    loadTaskRelations(t); // refetched only when its subtasks or its status moved on
    // The model is decided before the task starts, and again whenever it moves to a dearer one after failing.
    if (task.choiceKey !== t.status + "|" + t.model) { task.choiceKey = t.status + "|" + t.model; loadModelChoices(t); loadTaskUsage(t.id); }
    // Phases only change when the task moves on: refetch the (large) detail then, not every poll. A harness-run task
    // also records each check as it runs, so new events mean its checks may have moved on.
    const phaseKey = t.status + "|" + t.progress;
    const harnessMoved = task.harness && newEvents.length && Date.now() - (task.harnessAt || 0) > 4000;
    if (task.hasPhases && (phaseKey !== task.lastPhaseKey || harnessMoved)) {
      task.lastPhaseKey = phaseKey;
      task.harnessAt = Date.now();
      try {
        const dRes = await api.taskDetail(id);
        if (dRes && dRes.data && task.id === id) {
          renderPhases(dRes.data.harness ? [] : dRes.data.phases);
          renderHarness(dRes.data.harness, t);
          if (task.general) renderTaskOutput(generalNote(dRes.data), t);
        }
      } catch (e) { /* ignore */ }
    }
    if (task.timingKey !== t.status) { task.timingKey = t.status; loadTaskTiming(id); } // a turn ended or the task moved on
    if (!isActiveStatus(t.status)) { stopTaskPolling(); loadTaskFiles(id); }
  } catch (e) { /* ignore transient poll errors */ }
}

// steering: always resumes this task's own session (see TaskService.sendMessage)
const taskImages = imageAttacher({
  button: document.getElementById("taskAttachBtn"),
  fileInput: document.getElementById("taskImageInput"),
  tray: document.getElementById("taskImageTray"),
  pasteTarget: tel.steerInput,
  dropZone: tel.steerForm,
});

tel.steerForm.addEventListener("submit", async e => {
  e.preventDefault();
  const text = tel.steerInput.value.trim();
  if ((!text && taskImages.count() === 0) || !task.id) return;
  let res;
  try {
    // Attachments go to Files first (under this task); the message then carries their ids.
    const fileIds = taskImages.count() ? await taskImages.upload({ task: task.id }) : [];
    res = await api.taskMessage(task.id, text, [], fileIds);
  } catch (err) { showToast(err.message || L("tasks.steerFailed"), true); return; }
  if (!res || !res.ok) {
    const body = res && await res.json().catch(() => null);
    showToast((body && body.message) || L("tasks.steerFailed"), true); // keep the text and files so they can be resent
    return;
  }
  const urls = taskImages.previewUrls();
  const names = taskImages.fileNames();
  tel.steerInput.value = "";
  autoGrowSteer();
  taskImages.clear();
  setTaskTab("run");
  appendStreamItem({ kind: "cmd", payload: text, images: urls, files: names });
  startTaskPolling();
});
tel.steerInput.addEventListener("keydown", e => {
  if (e.key === "Enter" && !e.shiftKey) { e.preventDefault(); tel.steerForm.requestSubmit(); }
});
tel.steerInput.addEventListener("input", autoGrowSteer);
function autoGrowSteer() {
  tel.steerInput.style.height = "auto";
  tel.steerInput.style.height = Math.min(tel.steerInput.scrollHeight, 160) + "px";
}

tel.back.addEventListener("click", async () => {
  const prev = taskBack.pop();
  if (prev && prev.task) {
    await openTask(prev.task, { back: true });
    if (prev.tab && task.id === prev.task) setTaskTab(prev.tab);
  } else if (prev && prev.view) {
    const view = prev.view;
    showTaskList();
    setView(view);
  } else {
    showTaskList();
    loadTasks();
  }
});
tel.pagerPrev.addEventListener("click", () => { taskPage = Math.max(1, taskPage - 1); loadTasks(); });
tel.pagerNext.addEventListener("click", () => { taskPage += 1; loadTasks(); });
tel.stopBtn.addEventListener("click", async () => { if (task.id) { try { await api.stopTask(task.id); } catch (e) { /* ignore */ } } });
tel.deleteBtn.addEventListener("click", async () => {
  if (!task.id || !window.confirm(L("tasks.deleteConfirm2"))) return;
  const id = task.id;
  try { await api.deleteTask(id); } catch (e) { /* ignore */ }
  showTaskList();
  await loadTasks();
});

async function loadAgentOptions() {
  try { const res = await api.taskAgents(); agentOptions = (res && res.data) || []; } catch (e) { agentOptions = []; }
  return agentOptions;
}

function agentLabel(key) {
  const opt = agentOptions.find(a => a.key === key);
  return opt ? opt.name : (key || "—");
}

// A task a scheduled task started links back to it.
async function linkScheduledTask(taskId) {
  let a = null;
  try { a = (await fetch("/api/v1/automations/by-task/" + encodeURIComponent(taskId)).then(r => r.json())).data; }
  catch (e) { return; }
  if (!a || tel.detailView.hidden || task.id !== taskId) return; // the user moved on meanwhile
  const link = document.createElement("button");
  link.type = "button";
  link.className = "link-btn task-detail__origin";
  link.textContent = L("tasks.fromScheduled", { name: a.name });
  link.addEventListener("click", () => { setView("automation"); openAutomation(a.id); });
  tel.dMeta.append(" · ", link);
}

// ---------- how a task stands to others: its complex task, its subtasks, what it carries on ----------

/** Refetches only when something that changes them moved: the status, or how far the subtasks are. */
async function loadTaskRelations(t) {
  // While its subtasks run, look again every ten seconds so their own states show too.
  const key = t.status + "|" + (t.subtasks || 0) + "|" + (t.subtasksFinished || 0)
    + (t.status === "waiting" ? "|" + Math.floor(Date.now() / 10000) : "");
  if (task.relKey === key) return;
  task.relKey = key;
  let r = null;
  try { r = await api.taskRelations(t.id); } catch (e) { return; }
  if (task.id !== t.id) return;
  renderRelationLine(r);
  renderSubtasks(t, r);
  renderFollowUps(r);
  task.relTotal = { id: t.id, spend: r.totalSpend };
  renderFacts(task.current && task.current.id === t.id ? task.current : t);
}

/** Under the title: the complex task this is part of, or the task it carries on from, as links. */
function renderRelationLine(r) {
  const bits = [];
  const link = (label, other) => {
    const wrap = document.createElement("span");
    wrap.append(document.createTextNode(label + " "));
    const a = document.createElement("button");
    a.type = "button";
    a.className = "link-btn";
    a.textContent = other.title;
    a.addEventListener("click", () => openTask(other.id));
    wrap.appendChild(a);
    bits.push(wrap);
  };
  if (r.parent) link(L("tasks.relParent"), r.parent);
  if (r.continuesFrom) link(L("tasks.relFrom"), r.continuesFrom);
  tel.rel.replaceChildren(...bits);
  tel.rel.hidden = !bits.length;
}

function renderSubtasks(t, r) {
  const subs = r.subtasks || [];
  tel.subtasksTab.textContent = L("tasks.tabSubtasks", { n: subs.length });
  if (t.flow !== "complex") return;
  const finished = subs.filter(s => ["done", "error", "stopped"].includes(s.status)).length;
  const spent = subs.reduce((n, s) => n + Number(s.spend || 0), 0);
  tel.subtasksSum.textContent = subs.length
    ? L("tasks.subtasksSum", { done: finished, n: subs.length, spend: "$" + spent.toFixed(2) })
    : L(t.status === "done" ? "tasks.subtasksNone" : "tasks.subtasksPlanning");
  tel.subtasks.replaceChildren(...subs.map(subtaskRow));
}

function subtaskRow(s) {
  const row = document.createElement("button");
  row.type = "button";
  row.className = "subtask";
  row.addEventListener("click", () => openTask(s.id));
  const title = document.createElement("span");
  title.className = "subtask__title";
  title.textContent = s.title;
  const model = document.createElement("span");
  model.className = "subtask__meta";
  model.textContent = taskModelLabel(s);
  const spend = document.createElement("span");
  spend.className = "subtask__meta subtask__num";
  spend.textContent = "$" + Number(s.spend || 0).toFixed(2);
  const time = document.createElement("span");
  time.className = "subtask__meta subtask__num";
  time.textContent = formatElapsed(s);
  row.append(statusBadge(s.status), title, model, spend, time);
  if (s.error) row.title = s.error;
  return row;
}

function renderFollowUps(r) {
  const list = r.followUps || [];
  tel.followList.hidden = !list.length;
  tel.followUps.replaceChildren(...list.map(f => {
    const li = document.createElement("li");
    const a = document.createElement("button");
    a.type = "button";
    a.className = "link-btn";
    a.textContent = f.title;
    a.addEventListener("click", () => openTask(f.id));
    li.append(statusBadge(f.status), a);
    return li;
  }));
}

// ---------- tags: set automatically when a task is done; the owner can change them ----------

function renderTaskTags(t) {
  const box = tel.tags;
  box.replaceChildren();
  (t.tags || []).forEach(tag => {
    const chip = document.createElement("button");
    chip.type = "button";
    chip.className = "t-chip t-chip--tag";
    chip.textContent = "#" + tag;
    chip.title = L("tasks.tagFilterHint");
    chip.addEventListener("click", () => filterByTag(tag));
    box.appendChild(chip);
  });
  if (!(t.tags || []).length) {
    const none = document.createElement("span");
    none.className = "task-tags__none";
    none.textContent = L(t.parentId ? "tasks.tagsSubtask" : t.status === "done" ? "tasks.tagsNone" : "tasks.tagsLater");
    box.appendChild(none);
  }
  if (!t.parentId) {
    const edit = document.createElement("button");
    edit.type = "button";
    edit.className = "link-btn link-btn--muted";
    edit.textContent = L("common.edit");
    edit.addEventListener("click", () => editTaskTags(t));
    box.appendChild(edit);
  }
}

/** The tags as one line of text, separated by spaces or commas. */
function editTaskTags(t) {
  const box = tel.tags;
  const input = document.createElement("input");
  input.type = "text";
  input.className = "task-tags__input";
  input.value = (t.tags || []).join(" ");
  input.placeholder = L("tasks.tagsPh");
  input.setAttribute("aria-label", L("tasks.tags"));
  const save = async () => {
    const tags = input.value.split(/[\s,，、#]+/).filter(Boolean);
    try {
      const res = await api.setTaskTags(t.id, tags);
      t.tags = res.tags;
      if (task.current && task.current.id === t.id) task.current.tags = res.tags;
    } catch (e) { showToast(e.message, true); }
    renderTaskTags(t);
  };
  input.addEventListener("keydown", e => {
    if (e.key === "Enter") { e.preventDefault(); save(); }
    else if (e.key === "Escape") renderTaskTags(t);
  });
  input.addEventListener("blur", save);
  box.replaceChildren(input);
  input.focus();
}

// ---------- carrying on: a new task that starts from this one's conclusion ----------
const tflEl = {
  modal: document.getElementById("taskFollowModal"),
  form: document.getElementById("taskFollowForm"),
  hint: document.getElementById("taskFollowHint"),
  name: document.getElementById("taskFollowName"),
  brief: document.getElementById("taskFollowBrief"),
  complex: document.getElementById("taskFollowComplex"),
  error: document.getElementById("taskFollowError"),
  submit: document.getElementById("taskFollowSubmit"),
};

tel.followBtn.addEventListener("click", () => {
  const t = task.current;
  if (!t) return;
  tflEl.hint.textContent = L("tasks.followUpHintFor", { name: t.title });
  tflEl.name.value = L("tasks.followUpDefaultName", { name: t.title });
  tflEl.brief.value = "";
  tflEl.complex.checked = false;
  tflEl.error.hidden = true;
  tflEl.modal.hidden = false;
  tflEl.brief.focus();
});

tflEl.form.addEventListener("submit", async e => {
  e.preventDefault();
  const from = task.current;
  if (!from) return;
  tflEl.submit.disabled = true;
  tflEl.error.hidden = true;
  try {
    const created = await api.createTask({ title: tflEl.name.value.trim(), brief: tflEl.brief.value.trim(),
      kind: tflEl.complex.checked ? "complex" : "general", continuesFrom: from.id });
    tflEl.modal.hidden = true;
    openTask(created.id);
  } catch (err) {
    tflEl.error.textContent = err.message;
    tflEl.error.hidden = false;
  } finally {
    tflEl.submit.disabled = false;
  }
});
tflEl.modal.querySelectorAll("[data-close]").forEach(x => x.addEventListener("click", () => { tflEl.modal.hidden = true; }));
document.addEventListener("keydown", e => { if (e.key === "Escape" && !tflEl.modal.hidden) tflEl.modal.hidden = true; });

tel.stream.addEventListener("click", e => {
  const head = e.target.closest(".ev--step");
  if (!head) return;
  const fold = !head.classList.contains("is-folded");
  head.classList.toggle("is-folded", fold);
  for (let n = head.nextElementSibling; n && !n.classList.contains("ev--step"); n = n.nextElementSibling) n.hidden = fold;
});
