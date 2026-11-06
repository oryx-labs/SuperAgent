// SuperAgent UI — The user's SuperAgent: inbox mode, plans, and its team's work history.
// Classic script sharing one global scope with the others; index.html loads them in order.
"use strict";

// ---------- SuperAgent chat ----------
// The SuperAgent chats live like any agent (sendMessage / the live view). Its background works through task
// results and received messages on its own and posts what the user needs to know into this conversation, so
// while the chat is open the view polls for those posts, the plan card and the background's state — never while
// a reply is streaming in.
const BACKGROUND_AUTHOR = "superagent:background";
let superMode = false;
let superTimer = null;
let superFingerprint = null;
const composerPlaceholder = el.input.getAttribute("placeholder");

function setSuperMode(on) {
  superMode = on;
  clearInterval(superTimer);
  // Only while the conversation is actually on screen (tab visible, chat view shown).
  superTimer = on ? setInterval(() => {
    if (!document.hidden && el.messages.offsetParent !== null) refreshSuper(false);
  }, 3000) : null;
  el.input.setAttribute("placeholder", on ? L("super.placeholder") : composerPlaceholder);
  document.getElementById("superWorkPanel").hidden = !on;
  if (on) loadSuperRecent();
  el.messages.classList.toggle("messages--super", on);
}

/** {@code force}: re-render even if nothing changed (opening the chat, a turn just ended). */
async function refreshSuper(force) {
  if (!superMode || !activeSessionId) return;
  if (!force && (streaming || liveWatch)) return; // a reply is coming in: leave it alone
  const agentId = activeAgentId;
  const [stateRes, historyRes] = await Promise.all([
    api.superState().catch(() => null), api.agentHistory(agentId).catch(() => null)]);
  if (!superMode || activeAgentId !== agentId || (!force && (streaming || liveWatch))) return;
  const state = stateRes && stateRes.data;
  const items = flattenHistory((historyRes && historyRes.data) || []);
  if (!state) return;
  if (state.sessionId) activeSessionId = state.sessionId;
  renderBackgroundState(state);
  // Re-render only on change, so polling doesn't reset scroll or flicker.
  const last = items[items.length - 1];
  const fp = [items.length, last && last.id, state.plan && state.plan.id, state.plan && state.plan.status].join("|");
  if (!force && fp === superFingerprint) return;
  superFingerprint = fp;
  loadSuperRecent();
  const nearBottom = el.messages.scrollHeight - el.messages.scrollTop - el.messages.clientHeight < 80;
  el.messages.innerHTML = "";
  if (!items.length) {
    el.messages.innerHTML = '<div class="empty-state"><p>' + escapeHtml(L("super.welcome")) + "</p></div>";
  }
  // The plan sits in the conversation where it was made: right under the first reply after it was proposed
  // (until that reply exists, at the end).
  const planAt = state.plan && state.plan.proposedAt ? new Date(state.plan.proposedAt) : null;
  let planShown = !state.plan;
  items.forEach(m => {
    const msgEl = renderHistoryItem(m);
    if (m.role === "assistant" && m.author === BACKGROUND_AUTHOR) markBackground(msgEl);
    if (!planShown && planAt && m.role === "assistant" && new Date(m.createdAt) >= planAt) {
      el.messages.appendChild(planCard(state.plan));
      planShown = true;
    }
  });
  if (!planShown) el.messages.appendChild(planCard(state.plan));
  if (activeAgent) attachFileCards(activeAgent);
  if (force || nearBottom || !last || last.role !== "assistant") scrollToBottom();
}

// ---------- the background ----------
const bgel = {
  state: document.getElementById("superBgState"),
  drawer: document.getElementById("superBgDrawer"),
  list: document.getElementById("superBgList"),
  empty: document.getElementById("superBgEmpty"),
};

function renderBackgroundState(state) {
  bgel.state.textContent = state.running ? L("super.bgRunning") : L("super.bgIdle");
  bgel.state.classList.toggle("is-running", !!state.running);
}

/**
 * The record reads as rounds: the inputs that reached the queue (task results, received messages), then how the
 * round handled them. Inputs that haven't been worked yet come last, marked as waiting.
 */
async function openBackgroundRecord() {
  bgel.drawer.hidden = false;
  bgel.list.replaceChildren();
  const items = (await api.superBackground().catch(() => null)) || [];
  const rounds = [];
  let inputs = [];
  items.forEach(m => {
    if (m.role === "assistant") { rounds.push({ inputs, reply: m }); inputs = []; } else inputs.push(m);
  });
  if (inputs.length) rounds.push({ inputs, reply: null });
  bgel.empty.hidden = rounds.length > 0;
  rounds.reverse().forEach(r => bgel.list.appendChild(backgroundRound(r)));
}

function backgroundRound(r) {
  const li = document.createElement("li");
  li.className = "bg-round";
  const head = document.createElement("div");
  head.className = "bg-round__head";
  const when = document.createElement("time");
  when.textContent = formatDateTime((r.reply || r.inputs[r.inputs.length - 1]).createdAt);
  const what = document.createElement("span");
  what.textContent = r.reply ? L("super.bgRound", { n: r.inputs.length }) : L("super.bgWaiting", { n: r.inputs.length });
  head.append(when, what);
  const ins = document.createElement("details");
  ins.className = "bg-round__inputs";
  const sum = document.createElement("summary");
  sum.textContent = L("super.bgInputs", { n: r.inputs.length });
  ins.appendChild(sum);
  r.inputs.forEach(m => {
    const p = document.createElement("pre");
    p.textContent = m.content;
    ins.appendChild(p);
  });
  li.append(head, ins);
  if (r.reply) {
    const reply = document.createElement("div");
    reply.className = "bg-round__reply md";
    reply.innerHTML = renderMarkdown(r.reply.content || "");
    li.appendChild(reply);
  }
  return li;
}

document.getElementById("superBgBtn").addEventListener("click", openBackgroundRecord);
bgel.drawer.querySelectorAll("[data-close]").forEach(x => x.addEventListener("click", () => { bgel.drawer.hidden = true; }));

/** What the background posted into the chat: labelled, so it doesn't read as an answer to the last message. */
function markBackground(msgEl) {
  msgEl.classList.add("msg--background");
  const tag = document.createElement("div");
  tag.className = "msg__bg-tag";
  tag.textContent = L("super.bgTag");
  msgEl.prepend(tag);
}

function planCard(plan) {
  const card = document.createElement("div");
  card.className = "plan-card" + (plan.status === "approved" ? " is-approved" : "");
  const head = document.createElement("div");
  head.className = "plan-card__head";
  const title = document.createElement("span");
  title.className = "plan-card__title";
  title.textContent = L("super.planTitle") + " #" + plan.id;
  const badge = document.createElement("span");
  badge.className = "status-badge " + (plan.status === "approved" ? "is-done" : "is-needs_confirmation");
  badge.textContent = L(plan.status === "approved" ? "super.approved" : "super.awaiting");
  head.append(title, badge);
  const summary = document.createElement("p");
  summary.className = "plan-card__summary";
  summary.textContent = plan.summary;
  card.append(head, summary);

  const section = (label, items) => {
    if (!items.length) return;
    const h = document.createElement("div");
    h.className = "plan-card__label";
    h.textContent = label;
    const ul = document.createElement("ul");
    ul.className = "plan-card__list";
    items.forEach(node => { const li = document.createElement("li"); li.append(...node); ul.appendChild(li); });
    card.append(h, ul);
  };
  const strong = t => { const b = document.createElement("b"); b.textContent = t; return b; };
  const muted = t => { const m = document.createElement("span"); m.className = "plan-card__muted"; m.textContent = t; return m; };
  section(L("super.planMembers"), (plan.members || []).map(m => [
    strong(m.name), muted(" · " + L(m.agentId ? "super.planReuse" : "super.planNew")),
    document.createTextNode(m.specialty ? " — " + m.specialty : "")]));
  section(L("super.planTasks"), (plan.tasks || []).map(t => [
    strong(t.title), muted(t.assignee ? " → " + t.assignee : ""),
    document.createTextNode(t.brief ? " — " + t.brief : "")]));

  if (plan.status === "proposed") {
    const foot = document.createElement("div");
    foot.className = "plan-card__foot";
    const hint = muted(L("super.approveHint"));
    const btn = document.createElement("button");
    btn.type = "button";
    btn.className = "btn btn--primary";
    btn.textContent = L("super.approve");
    btn.addEventListener("click", async () => {
      btn.disabled = true;
      const res = await api.superApprove(plan.id).catch(() => null);
      if (!res || !res.data) {
        btn.disabled = false;
        showToast((res && res.message) || L("super.approveFailed"), true);
        return;
      }
      superFingerprint = null;
      if (streaming || liveWatch) { refreshSuper(true); return; }
      // Approval carries on in the chat: the SuperAgent builds the team and assigns the work, live.
      sendMessage(L("super.approvedMsg", { id: plan.id }), [], [], [], []);
    });
    foot.append(hint, btn);
    card.appendChild(foot);
  }
  return card;
}

function escapeHtml(s) {
  return String(s).replace(/[&<>"']/g, c => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
}

// ---------- SuperAgent work history ----------
// What the SuperAgent actually got done: every task it assigned, who ran it, and how it ended.
const wel = {
  recent: document.getElementById("superWorkList"),
  recentEmpty: document.getElementById("superWorkEmpty"),
  drawer: document.getElementById("superWorkDrawer"),
  list: document.getElementById("workList"),
  empty: document.getElementById("workEmpty"),
  count: document.getElementById("workCount"),
  status: document.getElementById("workStatusFilter"),
  member: document.getElementById("workMemberFilter"),
};

async function loadSuperRecent() {
  const res = await api.superWork("finished", null, 10).catch(() => null);
  const items = (res && res.data) || [];
  wel.recent.replaceChildren(...items.map(recentRow));
  wel.recentEmpty.hidden = items.length > 0;
}

// Side-panel row: a status dot, the title, then who ran it and how long ago (exact time on hover).
function recentRow(w) {
  const li = document.createElement("li");
  const btn = document.createElement("button");
  btn.type = "button";
  btn.className = "sw-row";
  btn.title = (TASK_STATUS_LABEL[w.status] || L("status." + w.status)) + " · " + formatDateTime(w.finishedAt);
  const dot = document.createElement("span");
  dot.className = "sw-dot is-" + w.status;
  const body = document.createElement("span");
  body.className = "sw-body";
  const title = document.createElement("span");
  title.className = "sw-title";
  title.textContent = w.title;
  const meta = document.createElement("span");
  meta.className = "sw-meta";
  const who = document.createElement("span");
  who.className = "sw-who";
  who.textContent = (w.memberAvatar ? w.memberAvatar + " " : "") + (w.memberName || "—");
  const when = document.createElement("span");
  when.className = "sw-when";
  when.textContent = w.finishedAt ? relativeTime(w.finishedAt) : "";
  meta.append(who, when);
  body.append(title, meta);
  btn.append(dot, body);
  btn.addEventListener("click", () => openTaskFromWork(w.taskId));
  li.appendChild(btn);
  return li;
}

function workBadge(status) {
  const b = document.createElement("span");
  b.className = "status-badge is-" + status;
  b.textContent = TASK_STATUS_LABEL[status] || L("status." + status);
  return b;
}

// One work item: status, title, who ran it and when; the drawer also shows the outcome summary.
function workItem(w, withSummary, onClick) {
  const btn = document.createElement("button");
  btn.type = "button";
  btn.className = "work-item";
  const top = document.createElement("div");
  top.className = "work-item__top";
  const title = document.createElement("span");
  title.className = "work-item__title";
  title.textContent = w.title;
  title.title = w.title;
  top.append(workBadge(w.status), title);
  const meta = document.createElement("div");
  meta.className = "work-item__meta";
  const who = document.createElement("span");
  who.textContent = (w.memberAvatar ? w.memberAvatar + " " : "") + (w.memberName || "—");
  const when = document.createElement("span");
  when.textContent = w.finishedAt ? L("work.finished", { time: formatDateTime(w.finishedAt) })
    : L("work.assigned", { time: formatDateTime(w.assignedAt) });
  meta.append(who, when);
  btn.append(top, meta);
  if (withSummary && w.summary) {
    const sum = document.createElement("div");
    sum.className = "work-item__summary";
    sum.textContent = w.summary;
    btn.appendChild(sum);
  }
  btn.addEventListener("click", onClick);
  return btn;
}

async function openTaskFromWork(taskId) {
  wel.drawer.hidden = true;
  setView("tasks");
  await openTask(taskId);
}

function openWorkDrawer() {
  // Members to filter by: the user's agents other than the SuperAgent (its hires and the user's own alike).
  const current = wel.member.value;
  wel.member.replaceChildren();
  const all = document.createElement("option");
  all.value = "";
  all.textContent = L("work.fMembers");
  wel.member.appendChild(all);
  agents.filter(a => a.kind !== "super").forEach(a => {
    const o = document.createElement("option");
    o.value = a.id;
    o.textContent = (a.avatar ? a.avatar + " " : "") + a.name;
    wel.member.appendChild(o);
  });
  wel.member.value = [...wel.member.options].some(o => o.value === current) ? current : "";
  wel.drawer.hidden = false;
  loadWorkList();
}

async function loadWorkList() {
  const res = await api.superWork(wel.status.value, wel.member.value, 500).catch(() => null);
  const items = (res && res.data) || [];
  wel.count.textContent = L("work.count", { n: items.length });
  wel.list.replaceChildren(...items.map(workRow));
  wel.empty.hidden = items.length > 0;
}

// A row expands in place to the item's timeline, with a way into the task itself.
function workRow(w) {
  const li = document.createElement("li");
  li.className = "work-row";
  const detail = document.createElement("div");
  detail.className = "work-row__detail";
  detail.hidden = true;
  li.append(workItem(w, true, async () => {
    if (!detail.hidden) { detail.hidden = true; return; }
    detail.hidden = false;
    detail.replaceChildren();
    const res = await api.superWorkEvents(w.taskId).catch(() => null);
    const ul = document.createElement("ul");
    ul.className = "work-events";
    ((res && res.data) || []).forEach(e => {
      const item = document.createElement("li");
      const t = document.createElement("time");
      t.textContent = formatDateTime(e.at);
      const k = document.createElement("b");
      k.textContent = L("work.ev." + e.kind);
      item.append(t, k, document.createTextNode(e.detail || ""));
      ul.appendChild(item);
    });
    const open = document.createElement("button");
    open.type = "button";
    open.className = "btn";
    open.textContent = L("work.openTask");
    open.addEventListener("click", () => openTaskFromWork(w.taskId));
    detail.append(ul);
    if (!w.planId) {
      const note = document.createElement("p");
      note.className = "super-work__empty";
      note.textContent = L("work.backfilled");
      detail.appendChild(note);
    }
    detail.appendChild(open);
  }), detail);
  return li;
}

document.getElementById("superWorkAllBtn").addEventListener("click", openWorkDrawer);
wel.drawer.querySelectorAll("[data-close]").forEach(x => x.addEventListener("click", () => { wel.drawer.hidden = true; }));
[wel.status, wel.member].forEach(x => x.addEventListener("change", loadWorkList));
