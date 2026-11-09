// SuperAgent UI — A regular agent's side panel: the tasks it set off, the files it made, and its details drawer.
// Classic script sharing one global scope with the others; index.html loads them in order.
"use strict";

Object.assign(api, {
  agentTasks: id => fetch("/api/v1/agents/" + id + "/tasks").then(kbJson),
});

const awel = {
  wrap: document.getElementById("agentWorkWrap"),
  list: document.getElementById("agentWorkList"),
  empty: document.getElementById("agentWorkEmpty"),
  files: document.getElementById("agentFileList"),
  filesEmpty: document.getElementById("agentFileEmpty"),
  drawer: document.getElementById("agentDetailDrawer"),
};

// Tasks still under way come first (what needs watching), then the rest; newest first within each.
const ACTIVE_TASK = new Set(["pending", "running", "needs_confirmation", "waiting"]);

async function renderAgentWork(agent) {
  awel.wrap.hidden = !agent || agent.kind === "super";
  if (awel.wrap.hidden) return;
  let items, made;
  try {
    [items, made] = await Promise.all([api.agentTasks(agent.id), api.listFiles(agent.id)]);
  } catch (e) { return; } // keep the last lists
  if (activeAgent !== agent) return; // switched agents while loading
  noticeSettledTasks(agent, items || []);
  attachAgentTaskCards(items || []);
  // A complex task's subtasks sit under it, folded away; the list holds the work the agent set off.
  const all = items || [];
  const ids = new Set(all.map(t => t.id));
  const kids = new Map();
  all.filter(t => t.parentId && ids.has(t.parentId)).forEach(t => {
    if (!kids.has(t.parentId)) kids.set(t.parentId, []);
    kids.get(t.parentId).push(t);
  });
  const top = all.filter(t => !(t.parentId && ids.has(t.parentId)))
    .sort((a, b) => ACTIVE_TASK.has(b.status) - ACTIVE_TASK.has(a.status));
  awel.list.replaceChildren(...top.map(t => agentWorkItem(t, kids.get(t.id) || [])));
  awel.empty.hidden = top.length > 0;
  made = made || [];
  awel.files.replaceChildren(...made.map(agentFileRow));
  awel.filesEmpty.hidden = made.length > 0;
}

function agentFileRow(f) {
  const li = document.createElement("li");
  const btn = document.createElement("button");
  btn.type = "button";
  btn.className = "sw-row";
  btn.title = formatDateTime(f.updatedAt);
  const icon = document.createElement("span");
  icon.className = "sw-icon";
  icon.textContent = fileIcon(f);
  const body = document.createElement("span");
  body.className = "sw-body";
  const title = document.createElement("span");
  title.className = "sw-title";
  title.textContent = f.name;
  const meta = document.createElement("span");
  meta.className = "sw-meta";
  const who = document.createElement("span");
  who.className = "sw-who";
  who.textContent = f.taskTitle ? L("fl.fromTask", { title: f.taskTitle }) : (f.ownerName || personName(f.owner));
  const when = document.createElement("span");
  when.className = "sw-when";
  when.textContent = relativeTime(f.updatedAt);
  meta.append(who, when);
  body.append(title, meta);
  btn.append(icon, body);
  btn.addEventListener("click", () => openFile(f));
  li.appendChild(btn);
  return li;
}

// Complex tasks whose subtasks the user unfolded; kept across the panel's refreshes.
const agentWorkOpen = new Set();

/** A task, and under a complex one its subtasks: folded by default, with how many there are. */
function agentWorkItem(t, subs) {
  const li = agentWorkRow(t);
  const n = Math.max(subs.length, t.subtasks || 0);
  if (!n) return li;
  const done = subs.length ? subs.filter(s => !ACTIVE_TASK.has(s.status)).length : (t.subtasksFinished || 0);
  li.classList.add("sw-parent");
  const toggle = document.createElement("button");
  toggle.type = "button";
  toggle.className = "sw-toggle";
  const open = agentWorkOpen.has(t.id);
  toggle.setAttribute("aria-expanded", String(open));
  toggle.textContent = L("aw.subtasks", { done, n });
  toggle.title = L(open ? "aw.subtasksHide" : "aw.subtasksShow");
  const list = document.createElement("ul");
  list.className = "sw-sub";
  list.hidden = !open;
  subs.slice().sort((a, b) => new Date(a.createdAt) - new Date(b.createdAt))
    .forEach(s => list.appendChild(agentWorkRow(s)));
  toggle.addEventListener("click", () => {
    const now = !agentWorkOpen.has(t.id);
    if (now) agentWorkOpen.add(t.id); else agentWorkOpen.delete(t.id);
    list.hidden = !now;
    toggle.setAttribute("aria-expanded", String(now));
    toggle.title = L(now ? "aw.subtasksHide" : "aw.subtasksShow");
  });
  li.append(toggle, list);
  return li;
}

// One row per task: a status dot, the title, then its status and when it last moved (exact time on hover).
function agentWorkRow(t) {
  const li = document.createElement("li");
  const btn = document.createElement("button");
  btn.type = "button";
  btn.className = "sw-row";
  const at = t.finishedAt || t.startedAt || t.createdAt;
  btn.title = formatDateTime(at);
  const dot = document.createElement("span");
  dot.className = "sw-dot is-" + t.status;
  const body = document.createElement("span");
  body.className = "sw-body";
  const title = document.createElement("span");
  title.className = "sw-title";
  title.textContent = t.title;
  const meta = document.createElement("span");
  meta.className = "sw-meta";
  const status = document.createElement("span");
  status.className = "sw-who";
  status.textContent = (TASK_STATUS_LABEL[t.status] || t.status)
    + (t.status === "running" && t.progress ? " · " + t.progress + "%" : "");
  const when = document.createElement("span");
  when.className = "sw-when";
  when.textContent = at ? relativeTime(at) : "";
  meta.append(status, when);
  body.append(title, meta);
  btn.append(dot, body);
  btn.addEventListener("click", () => { setView("tasks"); openTask(t.id); });
  li.appendChild(btn);
  return li;
}

function closeAgentDetail() { awel.drawer.hidden = true; }
document.getElementById("agentDetailBtn").addEventListener("click", () => { if (activeAgent) awel.drawer.hidden = false; });
document.getElementById("agentEditBtn").addEventListener("click", closeAgentDetail); // the edit modal takes over
awel.drawer.querySelectorAll("[data-close]").forEach(x => x.addEventListener("click", closeAgentDetail));
document.addEventListener("keydown", e => {
  // The run log opens on top: Escape closes that first.
  if (e.key === "Escape" && !awel.drawer.hidden && document.getElementById("agentLogDrawer").hidden
    && document.getElementById("memDrawer").hidden) closeAgentDetail();
});

// The agent's chat talks, its tasks do the work: each reply that set off tasks gets a card of them (status, name,
// a link to the task), and a task that finishes, fails or needs a decision reports back into the conversation.
let agentTaskStatus = { agentId: null, byId: new Map(), items: [] };

/** Without {@code items}, the last list fetched for the open agent (the conversation was just re-rendered). */
function attachAgentTaskCards(items) {
  if (!items) items = activeAgent && agentTaskStatus.agentId === activeAgent.id ? agentTaskStatus.items : [];
  const byReply = new Map();
  items.filter(t => t.originMessageId).forEach(t => {
    if (!byReply.has(t.originMessageId)) byReply.set(t.originMessageId, []);
    byReply.get(t.originMessageId).unshift({ taskId: t.id, title: t.title, status: t.status, self: true }); // oldest first
  });
  byReply.forEach((list, messageId) => {
    const msg = el.messages.querySelector('.msg[data-mid="' + CSS.escape(messageId) + '"]');
    if (!msg) return;
    const card = dispatchCard(list);
    const old = msg.querySelector(".sa-dispatch");
    if (old) old.replaceWith(card); else (msg.querySelector(".bubble") || msg).appendChild(card);
  });
}

/** A task that just stopped running has posted its outcome: show it (never over a reply that's streaming in). */
function noticeSettledTasks(agent, items) {
  const before = agentTaskStatus.agentId === agent.id ? agentTaskStatus.byId : null;
  agentTaskStatus = { agentId: agent.id, byId: new Map(items.map(t => [t.id, t.status])), items };
  // Notes drawn before the statuses arrived get their colour now.
  el.messages.querySelectorAll(".msg--task-report").forEach(m => {
    const s = agentTaskStatus.byId.get(m.dataset.taskId);
    if (s) m.className = m.className.replace(/\bis-\S*/g, "").trim() + " is-" + s;
  });
  if (!before || streaming || liveWatch) return;
  // Moved since the last look — or new and already settled (it ran between two looks) — or handed to this agent
  // from elsewhere (the SuperAgent), with a note in this chat the page hasn't shown yet.
  const settled = items.some(t => before.get(t.id) !== t.status
    && (t.status === "done" || t.status === "error" || t.status === "needs_confirmation"));
  const arrived = items.some(t => !before.has(t.id) && t.originMessageId
    && !el.messages.querySelector('.msg[data-mid="' + CSS.escape(t.originMessageId) + '"]'));
  if (settled || arrived) selectSession(activeSessionId);
}

/** A note from a task in the chat (taken on, finished, failed, waiting): labelled as such, with a way to open it. */
function markTaskReport(msgEl, taskId) {
  // Coloured by how the task stands now (done / failed / waiting on someone).
  const status = agentTaskStatus.byId.get(taskId) || "";
  msgEl.classList.add("msg--background", "msg--task-report", "is-" + status);
  msgEl.dataset.taskId = taskId;
  const tag = document.createElement("button");
  tag.type = "button";
  tag.className = "msg__bg-tag msg__task-tag";
  tag.textContent = L("chat.taskReportTag") + " · " + L("chat.taskReportOpen");
  tag.title = L("super.dispatchOpenTask");
  tag.addEventListener("click", () => { setView("tasks"); openTask(taskId); });
  msgEl.prepend(tag);
}

// Tasks move on their own: keep the list current while the agent's chat is open — every 5 s while one is under
// way (its outcome then shows promptly), otherwise every 15 s.
let agentWorkTick = 0;
setInterval(() => {
  agentWorkTick++;
  const busy = [...agentTaskStatus.byId.values()].some(s => ACTIVE_TASK.has(s));
  if ((busy || agentWorkTick % 3 === 0) && activeAgent && activeAgent.kind !== "super"
      && !document.hidden && !document.querySelector('[data-pane="chat"]').hidden) {
    renderAgentWork(activeAgent);
  }
}, 5000);
