// SuperAgent UI — Inbox: messages the user's Feishu bot received, extracted to-dos, bot settings.
// Classic script sharing one global scope with the others; index.html loads them in order.
"use strict";

// ---------- inbox ----------
// Messages the user's own Feishu bot receives for them, the to-dos extracted from them, and the bot settings.
Object.assign(api, {
  ibSource: () => fetch("/api/v1/inbox/source").then(kbJson),
  ibSave: body => fetch("/api/v1/inbox/source", {
    method: "PUT", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body),
  }).then(kbJson),
  ibBindCode: () => kbPost("/api/v1/inbox/source/bind-code", {}),
  ibEvents: () => fetch("/api/v1/inbox/events?limit=200").then(kbJson),
  ibTodos: () => fetch("/api/v1/inbox/todos").then(kbJson),
  ibTodoStatus: (id, status) => fetch("/api/v1/inbox/todos/" + id, {
    method: "PUT", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ status }),
  }).then(kbJson),
  ibTodoTask: id => kbPost("/api/v1/inbox/todos/" + id + "/task", {}),
});

const ibPane = document.querySelector('[data-pane="inbox"]');
const ibel = {
  status: document.getElementById("ibStatus"),
  tabs: document.getElementById("ibTabs"),
  todos: document.getElementById("ibTodos"),
  todosEmpty: document.getElementById("ibTodosEmpty"),
  showClosed: document.getElementById("ibShowClosed"),
  events: document.getElementById("ibEvents"),
  eventsEmpty: document.getElementById("ibEventsEmpty"),
  form: document.getElementById("ibForm"),
  appId: document.getElementById("ibAppId"),
  appSecret: document.getElementById("ibAppSecret"),
  secretHint: document.getElementById("ibSecretHint"),
  bind: document.getElementById("ibBind"),
  vip: document.getElementById("ibVip"),
  keywords: document.getElementById("ibKeywords"),
  digest: document.getElementById("ibDigest"),
  timezone: document.getElementById("ibTimezone"),
  enabled: document.getElementById("ibEnabled"),
  error: document.getElementById("ibError"),
};
let ibTab = "todos";
let ibTodoList = [];

function showIbTab(tab) {
  ibTab = tab;
  ibel.tabs.querySelectorAll(".model-tab").forEach(b => b.classList.toggle("is-active", b.dataset.tab === tab));
  ibPane.querySelectorAll("[data-ib]").forEach(p => { p.hidden = p.dataset.ib !== tab; });
  if (tab === "todos") loadIbTodos();
  if (tab === "events") loadIbEvents();
  if (tab === "settings") loadIbSource();
}
ibel.tabs.querySelectorAll(".model-tab").forEach(b => b.addEventListener("click", () => showIbTab(b.dataset.tab)));

async function loadInbox() {
  loadIbSource(true);
  showIbTab(ibTab);
}

function renderIbStatus(src) {
  const configured = src && src.configured;
  ibel.status.hidden = !configured;
  if (!configured) return;
  const state = !src.enabled ? "off" : src.status || "connecting";
  ibel.status.className = "status-badge is-ib-" + state;
  ibel.status.textContent = L("ib.status." + state);
  ibel.status.title = src.lastError || "";
}

async function loadIbSource(statusOnly) {
  let src;
  try { src = await api.ibSource(); } catch (e) { showToast(e.message, true); return; }
  renderIbStatus(src);
  if (statusOnly) return;
  ibel.appId.value = src.appId || "";
  ibel.appSecret.value = "";
  ibel.appSecret.required = !src.hasSecret;
  ibel.appSecret.placeholder = src.hasSecret ? "••••••••" : "";
  ibel.secretHint.hidden = !src.hasSecret;
  ibel.vip.value = src.vipNames || "";
  ibel.keywords.value = src.urgentKeywords || "";
  ibel.digest.value = src.digestTime || "09:00";
  ibel.timezone.value = src.timezone || "Asia/Shanghai";
  ibel.enabled.checked = src.configured ? src.enabled : true;
  ibel.error.hidden = !src.lastError;
  ibel.error.textContent = src.lastError || "";
  renderIbBind(src);
}

function renderIbBind(src) {
  ibel.bind.hidden = !src.configured;
  ibel.bind.innerHTML = "";
  if (!src.configured) return;
  const p = document.createElement("div");
  if (src.bound) {
    p.textContent = L("ib.bound");
  } else if (!src.bindCode) {
    p.textContent = L("ib.codeBurned");
  } else {
    p.append(document.createTextNode(L("ib.bindStep")));
    const code = document.createElement("code");
    code.textContent = L("ib.bindWord") + " " + (src.bindCode || "");
    p.append(code);
  }
  const again = document.createElement("button");
  again.type = "button";
  again.className = "link-btn";
  again.textContent = L(src.bound ? "ib.rebind" : "ib.newCode");
  again.addEventListener("click", async () => {
    try { renderIbBind(await api.ibBindCode()); } catch (e) { showToast(e.message, true); }
  });
  p.append(document.createTextNode(" "), again);
  ibel.bind.appendChild(p);
}

ibel.form.addEventListener("submit", async e => {
  e.preventDefault();
  ibel.error.hidden = true;
  try {
    const src = await api.ibSave({
      appId: ibel.appId.value.trim(), appSecret: ibel.appSecret.value, enabled: ibel.enabled.checked,
      vipNames: ibel.vip.value, urgentKeywords: ibel.keywords.value,
      digestTime: ibel.digest.value, timezone: ibel.timezone.value.trim(),
    });
    showToast(L("ib.saved"), false);
    renderIbStatus(src);
    renderIbBind(src);
    ibel.appSecret.value = "";
    ibel.secretHint.hidden = false;
    setTimeout(() => { if (!ibPane.hidden && ibTab === "settings") loadIbSource(); }, 4000); // connection status settles
  } catch (err) {
    ibel.error.textContent = err.message;
    ibel.error.hidden = false;
  }
});

async function loadIbTodos() {
  try { ibTodoList = (await api.ibTodos()) || []; } catch (e) { ibTodoList = []; showToast(e.message, true); }
  renderIbTodos();
}
ibel.showClosed.addEventListener("change", renderIbTodos);

function renderIbTodos() {
  const closed = t => t.status === "done" || t.status === "dismissed";
  const shown = ibTodoList.filter(t => ibel.showClosed.checked || !closed(t));
  ibel.todos.innerHTML = "";
  ibel.todosEmpty.hidden = shown.length > 0;
  shown.forEach(t => {
    const tr = document.createElement("tr");
    tr.className = "ib-todo" + (closed(t) ? " is-closed" : "");
    const main = document.createElement("td");
    const title = document.createElement("div");
    title.className = "ib-todo__title";
    title.textContent = t.title;
    const meta = document.createElement("div");
    meta.className = "ib-todo__meta";
    meta.textContent = [t.requester && L("ib.from", { who: t.requester }), t.due && L("ib.due", { due: t.due }),
      t.source, formatDateTime(t.createdAt), t.status !== "open" && L("ib.todo." + t.status)].filter(Boolean).join(" · ");
    main.append(title, meta);
    if (t.detail) {
      const d = document.createElement("div");
      d.className = "ib-todo__detail";
      d.textContent = t.detail;
      main.appendChild(d);
    }
    const actions = document.createElement("td");
    actions.className = "ib-actions";
    const btn = (label, fn, danger) => {
      const b = document.createElement("button");
      b.type = "button";
      b.className = "link-btn" + (danger ? " link-btn--muted" : "");
      b.textContent = label;
      b.addEventListener("click", async () => {
        b.disabled = true;
        try { await fn(); await loadIbTodos(); } catch (e) { showToast(e.message, true); b.disabled = false; }
      });
      actions.appendChild(b);
    };
    if (t.status === "tasked" && t.taskId) {
      btn(L("ib.openTask"), async () => { setView("tasks"); openTask(t.taskId); });
    } else if (t.status === "open") {
      btn(L("ib.toTask"), async () => { const r = await api.ibTodoTask(t.id); showToast(L("ib.tasked"), false); return r; });
      btn(L("ib.done"), () => api.ibTodoStatus(t.id, "done"));
      btn(L("ib.dismiss"), () => api.ibTodoStatus(t.id, "dismissed"), true);
    } else {
      btn(L("ib.reopen"), () => api.ibTodoStatus(t.id, "open"));
    }
    tr.append(main, actions);
    ibel.todos.appendChild(tr);
  });
}

async function loadIbEvents() {
  let list = [];
  try { list = (await api.ibEvents()) || []; } catch (e) { showToast(e.message, true); }
  ibel.events.innerHTML = "";
  ibel.eventsEmpty.hidden = list.length > 0;
  list.forEach(ev => {
    const tr = document.createElement("tr");
    const prio = document.createElement("td");
    const badge = document.createElement("span");
    badge.className = "ib-prio ib-prio--" + ev.priority;
    badge.textContent = ev.priority === "IGNORE" ? L("ib.ignored") : ev.priority;
    prio.appendChild(badge);
    if (!ev.processed) {
      const pend = document.createElement("span");
      pend.className = "ib-reason";
      pend.textContent = L("ib.pending");
      prio.appendChild(pend);
    }
    const from = document.createElement("td");
    from.textContent = (ev.senderName || "—") + " · " + (ev.chatName || "—");
    const text = document.createElement("td");
    text.className = "ib-text";
    text.textContent = ev.text || "";
    if (ev.triageReason) {
      const r = document.createElement("span");
      r.className = "ib-reason";
      r.textContent = ev.triageReason;
      text.appendChild(r);
    }
    const when = document.createElement("td");
    when.className = "kb-list__meta";
    when.textContent = formatDateTime(ev.receivedAt);
    tr.append(prio, from, text, when);
    ibel.events.appendChild(tr);
  });
}
