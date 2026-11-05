// SuperAgent UI — Tasks: list, create, detail (prompt / run / session), steering.
// Classic script sharing one global scope with the others; index.html loads them in order.
"use strict";

// ---------- tasks ----------
Object.assign(api, {
  listTasks: page => fetch("/api/v1/tasks?page=" + page + "&size=" + TASK_PAGE_SIZE).then(r => r.json()),
  taskDetail: id => fetch("/api/v1/tasks/" + id).then(r => r.json()),
  taskAgents: () => fetch("/api/v1/tasks/agents").then(r => r.json()),
  taskStatus: id => fetch("/api/v1/tasks/" + id + "/status").then(r => r.json()),
  taskEvents: (id, offset) => fetch("/api/v1/tasks/" + id + "/events?offset=" + offset).then(r => r.json()),
  taskArtifacts: id => fetch("/api/v1/tasks/" + id + "/artifacts").then(r => r.json()),
  taskMessage: (id, content, images) => fetch("/api/v1/tasks/" + id + "/messages", {
    method: "POST", headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ content, images: images || [] }),
  }),
  stopTask: id => fetch("/api/v1/tasks/" + id + "/stop", { method: "POST" }),
  deleteTask: id => fetch("/api/v1/tasks/" + id, { method: "DELETE" }),
});

const TASK_STATUS_LABEL = {
  pending: L("status.pending"), running: L("status.running"), needs_confirmation: L("status.needs_confirmation"),
  done: L("status.done"), error: L("status.error"), stopped: L("status.stopped"),
};
const TASK_SOURCE_LABEL = {
  web_chat: L("tasks.srcWebChat"), task_form: L("tasks.srcTaskForm"), automation: L("tasks.srcAutomation"),
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
  sessionInfo: document.getElementById("taskSessionInfo"),
  artifacts: document.getElementById("taskArtifacts"),
  artifactsEmpty: document.getElementById("taskArtifactsEmpty"),
  progress: document.querySelector("#taskDetailView .progress"),
  progressBar: document.getElementById("taskProgressBar"),
  progressPct: document.getElementById("taskProgressPct"),
  facts: document.getElementById("taskFacts"),
  phasesPanel: document.getElementById("taskPhasesPanel"),
  phases: document.getElementById("taskPhases"),
  steerForm: document.getElementById("taskSteerForm"),
  steerInput: document.getElementById("taskSteerInput"),
  steerBtn: document.getElementById("taskSteerBtn"),
};

const task = { id: null, renderedEvents: 0, mergeEl: null, mergeType: null, pollTimer: null };

function isActiveStatus(s) { return s === "running" || s === "pending"; }

function showTaskList() {
  stopTaskPolling();
  task.id = null;
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
  try { const res = await api.listTasks(taskPage); page = res && res.data; } catch (e) { /* ignore */ }
  list = (page && page.items) || [];
  if (page) taskPage = page.page; // the server clamps a page past the end (e.g. after deletes)
  renderTaskPager(page);
  tel.rows.innerHTML = "";
  tel.empty.hidden = list.length > 0;
  list.forEach(t => tel.rows.appendChild(taskRow(t)));
}

function taskRow(t) {
  const tr = document.createElement("tr");
  tr.addEventListener("click", () => openTask(t.id));

  const tdTask = document.createElement("td");
  const title = document.createElement("div");
  title.className = "t-title";
  title.textContent = t.title;
  tdTask.appendChild(title);
  const metaBits = [t.repo, t.branch, t.flow].filter(Boolean);
  if (metaBits.length) {
    const sub = document.createElement("small");
    sub.className = "t-sub";
    const code = document.createElement("code");
    code.textContent = metaBits.join(" · ");
    sub.appendChild(code);
    tdTask.appendChild(sub);
  }
  // Short session id under the title: equal ids across rows mean the tasks shared (reused) a session.
  const sess = document.createElement("small");
  sess.className = "t-session";
  sess.textContent = t.sessionId ? "session " + t.sessionId.slice(0, 8) : L("tasks.sessionNone");
  if (t.sessionId) sess.title = t.sessionId;
  tdTask.appendChild(sess);

  const tdAgent = document.createElement("td");
  tdAgent.className = "t-agent";
  const agentName = document.createElement("div");
  agentName.textContent = agentLabel(t.agentType);
  const modelName = document.createElement("small");
  modelName.textContent = t.model || L("tasks.modelDefault");
  tdAgent.append(agentName, modelName);

  const tdSource = document.createElement("td");
  tdSource.textContent = TASK_SOURCE_LABEL[t.source] || t.source;

  const tdCreated = document.createElement("td");
  tdCreated.className = "t-created";
  tdCreated.textContent = formatDateTime(t.createdAt);
  if (t.createdBy) { const by = document.createElement("small"); by.textContent = t.createdBy; tdCreated.appendChild(by); }

  const tdSpend = document.createElement("td");
  tdSpend.className = "t-spend";
  tdSpend.textContent = "$" + Number(t.spend || 0).toFixed(2);

  const tdStatus = document.createElement("td");
  const cell = document.createElement("div");
  cell.className = "t-status-cell";
  const top = document.createElement("div");
  top.className = "t-status-top";
  top.appendChild(statusBadge(t.status));
  const elapsed = document.createElement("span");
  elapsed.className = "t-elapsed";
  elapsed.textContent = formatElapsed(t);
  top.appendChild(elapsed);
  const bar = document.createElement("div");
  bar.className = "progress" + (t.status === "error" ? " is-error" : isActiveStatus(t.status) ? " is-running" : "");
  const fill = document.createElement("div");
  fill.className = "progress__bar";
  fill.style.width = (t.progress || 0) + "%";
  bar.appendChild(fill);
  cell.append(top, bar);
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

  tr.append(tdTask, tdAgent, tdSource, tdCreated, tdSpend, tdStatus, tdActions);
  return tr;
}

function statusBadge(status) {
  const b = document.createElement("span");
  b.className = "status-badge is-" + status;
  b.textContent = TASK_STATUS_LABEL[status] || status;
  return b;
}

async function deleteTask(t) {
  if (!window.confirm(L("tasks.deleteConfirm", { name: t.title }))) return;
  try { await api.deleteTask(t.id); } catch (e) { /* ignore */ }
  if (task.id === t.id) showTaskList();
  await loadTasks();
}

async function openTask(id) {
  task.id = id;
  task.renderedEvents = 0;
  task.mergeEl = null;
  task.mergeType = null;
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
  tel.dTitle.textContent = t.title;
  const meta = [TASK_SOURCE_LABEL[t.source] || t.source, t.repo, t.branch, t.flow].filter(Boolean).join(" · ");
  tel.dMeta.textContent = meta;

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
  timeline.forEach(appendStreamItem);
  task.renderedEvents = (d.events || []).length;
  if (timeline.length === 0) {
    const empty = document.createElement("p");
    empty.className = "task-stream__empty";
    empty.textContent = L("tasks.streamWaiting");
    tel.stream.appendChild(empty);
  }

  updateStatusUi(t);
  renderFacts(t);
  renderSessionInfo(t);
  renderPhases(d.phases);
  task.hasPhases = !!(d.phases && d.phases.length);
  if (tel.promptBody) tel.promptBody.innerHTML = renderMarkdown(d.brief || t.title || "");
  setTaskTab("run");
  loadArtifacts(t.id);
  if (isActiveStatus(t.status)) startTaskPolling(); else stopTaskPolling();
}

// Detail tabs: Prompt / Conversation (run log + coding phases, the default) / Session.
function setTaskTab(tab) {
  tel.tabs.querySelectorAll(".model-tab").forEach(b => b.classList.toggle("is-active", b.dataset.tab === tab));
  tel.detailView.querySelectorAll("[data-pane-tab]").forEach(p => { p.hidden = p.dataset.paneTab !== tab; });
  if (tab === "run") scrollRunToEnd();
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

async function loadArtifacts(id) {
  let list = [];
  try { const res = await api.taskArtifacts(id); list = (res && res.data) || []; } catch (e) { /* ignore */ }
  tel.artifacts.innerHTML = "";
  tel.artifactsEmpty.hidden = list.length > 0;
  list.forEach(a => {
    const li = document.createElement("li");
    const link = document.createElement("a");
    link.href = "/api/v1/tasks/" + id + "/artifacts/" + encodeURIComponent(a.name);
    link.setAttribute("download", a.name);
    const name = document.createElement("span");
    name.textContent = a.name;
    const size = document.createElement("span");
    size.className = "artifact-size";
    size.textContent = formatBytes(a.size);
    link.append(name, size);
    const save = document.createElement("button");
    save.type = "button";
    save.className = "link-btn artifact-kb";
    save.textContent = L("tasks.saveToKb");
    save.addEventListener("click", () => saveArtifactToKb(id, a.name));
    li.append(link, save);
    tel.artifacts.appendChild(li);
  });
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
function setEvBody(bodyEl, kind, fullRaw) {
  if (MD_KINDS[kind]) {
    bodyEl.dataset.md = fullRaw;
    bodyEl.innerHTML = renderMarkdown(fullRaw);
  } else {
    bodyEl.textContent = fullRaw;
  }
}

function appendStreamItem(item) {
  const type = item.kind;
  if (type === "system") return; // bootstrap noise
  const mergeable = type === "text" || type === "thinking";
  if (mergeable && task.mergeType === type && task.mergeEl) {
    const bodyEl = task.mergeEl.querySelector(".ev__body");
    const prev = MD_KINDS[type] ? (bodyEl.dataset.md || "") : bodyEl.textContent;
    setEvBody(bodyEl, type, prev + (item.payload || ""));
    scrollStream();
    return;
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
  const body = document.createElement("div");
  body.className = "ev__body";
  setEvBody(body, type, item.payload || "");
  body.hidden = type === "cmd" && !item.payload && !!(item.images && item.images.length);
  ev.appendChild(body);
  tel.stream.appendChild(ev);

  if (mergeable) { task.mergeEl = ev; task.mergeType = type; }
  else { task.mergeEl = null; task.mergeType = null; }
  scrollStream();
}

const EV_LABEL = {
  thinking: L("ev.think"), tool_use: L("ev.tool"), tool_result: L("ev.toolResult"),
  result: L("ev.result"), error: L("ev.error"), cmd: "",
};

// The run pane is the scrolling container (the stream itself never overflows).
function scrollStream() { tel.runPane.scrollTop = tel.runPane.scrollHeight; }

function updateStatusUi(t) {
  tel.dStatus.className = "status-badge is-" + t.status;
  tel.dStatus.textContent = TASK_STATUS_LABEL[t.status] || t.status;
  tel.progress.className = "progress" + (t.status === "error" ? " is-error" : isActiveStatus(t.status) ? " is-running" : "");
  tel.progressBar.style.width = (t.progress || 0) + "%";
  tel.progressPct.textContent = (t.progress || 0) + "%";
  const busy = isActiveStatus(t.status);
  tel.steerInput.disabled = busy;
  tel.steerBtn.disabled = busy;
  taskImages.setDisabled(busy);
  // PENDING with a session = a steering message queued until that session is free (another task's
  // agent is running in it); say so instead of "running".
  const waiting = t.status === "pending" && !!t.sessionId;
  tel.steerInput.placeholder = waiting ? L("tasks.steerWaiting") : busy ? L("tasks.steerBusy") : L("tasks.steer");
  tel.stopBtn.disabled = !busy;
}

function renderFacts(t) {
  const facts = [
    [L("tasks.agent"), agentLabel(t.agentType)],
    [L("tasks.model"), t.model || L("tasks.modelDefault")],
    // No effort setting exists yet: tasks run at the CLI's default.
    [L("tasks.effort"), L("tasks.effortDefault"), L("tasks.effortHint")],
    [L("tasks.session"), t.sessionId ? t.sessionId.slice(0, 8) : "—", t.sessionId || ""],
    [L("tasks.created"), formatDateTime(t.createdAt)],
    [L("tasks.by"), t.createdBy || "—"],
    [L("tasks.spend"), "$" + Number(t.spend || 0).toFixed(2)],
    [L("tasks.elapsed"), formatElapsed(t)],
  ];
  if (t.error) facts.push([L("tasks.error"), t.error]);
  fillFacts(tel.facts, facts);
}

function renderSessionInfo(t) {
  const state = t.sessionState ? L("tasks.sessionState." + t.sessionState) : "—";
  fillFacts(tel.sessionInfo, [
    [L("tasks.sessionId"), t.sessionId || L("tasks.sessionNone")],
    [L("tasks.sessionState"), state],
    [L("tasks.workspace"), t.workspaceDir || "—"],
    [L("tasks.agentType"), t.agentType ? agentLabel(t.agentType) + " (" + t.agentType + ")" : "—"],
    [L("tasks.model"), t.model || L("tasks.modelDefault")],
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

function startTaskPolling() {
  stopTaskPolling();
  task.pollTimer = setInterval(pollTask, 1500);
}
function stopTaskPolling() {
  if (task.pollTimer) { clearInterval(task.pollTimer); task.pollTimer = null; }
}

async function pollTask() {
  const id = task.id;
  if (!id) { stopTaskPolling(); return; }
  try {
    const [evRes, stRes] = await Promise.all([api.taskEvents(id, task.renderedEvents), api.taskStatus(id)]);
    const newEvents = (evRes && evRes.data) || [];
    newEvents.forEach(e => appendStreamItem({ kind: e.type, payload: e.payload, at: e.createdAt }));
    task.renderedEvents += newEvents.length;
    const t = stRes && stRes.data;
    if (!t) return;
    updateStatusUi(t);
    renderFacts(t);
    renderSessionInfo(t);
    if (task.hasPhases) {
      try { const dRes = await api.taskDetail(id); if (dRes && dRes.data) renderPhases(dRes.data.phases); } catch (e) { /* ignore */ }
    }
    if (!isActiveStatus(t.status)) { stopTaskPolling(); loadArtifacts(id); }
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
    res = await api.taskMessage(task.id, text, await taskImages.payload());
  } catch (err) { res = null; }
  if (!res || !res.ok) {
    showToast(L("tasks.steerFailed"), true); // keep the text and images so they can be resent
    return;
  }
  const urls = taskImages.previewUrls();
  tel.steerInput.value = "";
  autoGrowSteer();
  taskImages.clear();
  setTaskTab("run");
  appendStreamItem({ kind: "cmd", payload: text, images: urls });
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

tel.back.addEventListener("click", () => { showTaskList(); loadTasks(); });
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
