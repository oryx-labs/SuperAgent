// SuperAgent UI — Data sources: where the user's SuperAgent hears from the outside world, and what it heard.
// Classic script sharing one global scope with the others; index.html loads them in order.
"use strict";

// ---------- data sources ----------
// Each source (v1: the user's own Feishu bot) delivers what it receives to SuperAgent, which decides what to do
// with each message. The message stream shows every message and what SuperAgent did about it.
Object.assign(api, {
  ibSources: () => fetch("/api/v1/inbox/sources").then(kbJson),
  ibSummary: () => fetch("/api/v1/inbox/summary").then(kbJson),
  ibSource: () => fetch("/api/v1/inbox/source").then(kbJson),
  ibSave: body => fetch("/api/v1/inbox/source", {
    method: "PUT", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body),
  }).then(kbJson),
  ibBindCode: () => kbPost("/api/v1/inbox/source/bind-code", {}),
  ibEvents: () => fetch("/api/v1/inbox/events?limit=500").then(kbJson),
});

// The kinds of source; only Feishu exists so far, the rest show what's coming.
const IB_TYPES = [
  { type: "feishu", icon: "飞", label: "ib.type.feishu", ready: true },
  { type: "email", icon: "✉", label: "ib.type.email" },
  { type: "calendar", icon: "▦", label: "ib.type.calendar" },
  { type: "github", icon: "⌥", label: "ib.type.github" },
  { type: "webhook", icon: "⇄", label: "ib.type.webhook" },
];
const IB_HANDLINGS = ["delivered", "notified", "todo", "task", "read", "ignored"];

const ibPane = document.querySelector('[data-pane="inbox"]');
const ibel = {
  tabs: document.getElementById("ibTabs"),
  cards: document.getElementById("ibCards"),
  add: document.getElementById("ibAddBtn"),
  picker: document.getElementById("ibPicker"),
  form: document.getElementById("ibForm"),
  appId: document.getElementById("ibAppId"),
  appSecret: document.getElementById("ibAppSecret"),
  secretHint: document.getElementById("ibSecretHint"),
  bind: document.getElementById("ibBind"),
  enabled: document.getElementById("ibEnabled"),
  error: document.getElementById("ibError"),
  cancel: document.getElementById("ibCancel"),
  events: document.getElementById("ibEvents"),
  eventsEmpty: document.getElementById("ibEventsEmpty"),
  fSource: document.getElementById("ibFilterSource"),
  fPriority: document.getElementById("ibFilterPriority"),
  fHandling: document.getElementById("ibFilterHandling"),
};
let ibTab = "sources";
let ibSourceList = [];
let ibEventList = [];

function showIbTab(tab) {
  ibTab = tab;
  ibel.tabs.querySelectorAll(".model-tab").forEach(b => b.classList.toggle("is-active", b.dataset.tab === tab));
  ibPane.querySelectorAll("[data-ib]").forEach(p => { p.hidden = p.dataset.ib !== tab; });
  if (tab === "sources") loadIbSources();
  if (tab === "events") loadIbEvents();
}
ibel.tabs.querySelectorAll(".model-tab").forEach(b => b.addEventListener("click", () => showIbTab(b.dataset.tab)));

async function loadInbox() {
  closeIbForm();
  showIbTab(ibTab);
}

const ibType = type => IB_TYPES.find(t => t.type === type) || { type, icon: "•", label: type };
const ibState = src => !src.enabled ? "off" : src.status || "connecting";

// ---------- sources ----------

async function loadIbSources() {
  try { ibSourceList = (await api.ibSources()) || []; } catch (e) { ibSourceList = []; showToast(e.message, true); }
  renderIbCards();
}

function renderIbCards() {
  ibel.cards.innerHTML = "";
  if (!ibSourceList.length) {
    ibel.cards.innerHTML = '<p class="kb-empty">' + escapeHtml(L("ib.noSources")) + "</p>";
  }
  ibSourceList.forEach(src => ibel.cards.appendChild(ibCard(src)));
}

function ibCard(src) {
  const t = ibType(src.type);
  const card = document.createElement("div");
  card.className = "ib-card";
  const icon = document.createElement("span");
  icon.className = "ib-card__icon ib-card__icon--" + src.type;
  icon.textContent = t.icon;
  const main = document.createElement("div");
  main.className = "ib-card__main";
  const head = document.createElement("div");
  head.className = "ib-card__head";
  const name = document.createElement("b");
  name.textContent = L(t.label);
  const state = ibState(src);
  const status = document.createElement("span");
  status.className = "ib-status is-" + state;
  status.textContent = L("ib.status." + state);
  status.title = src.lastError || "";
  head.append(name, status);
  const meta = document.createElement("div");
  meta.className = "ib-card__meta";
  meta.textContent = [L(src.bound ? "ib.cardBound" : "ib.cardUnbound"), L("ib.cardToday", { n: src.today }),
    src.lastMessageAt ? L("ib.cardLast", { time: formatDateTime(src.lastMessageAt) }) : null].filter(Boolean).join(" · ");
  main.append(head, meta);
  if (state === "error" && src.lastError) {
    const err = document.createElement("div");
    err.className = "ib-card__error";
    err.textContent = src.lastError;
    main.appendChild(err);
  }
  const actions = document.createElement("div");
  actions.className = "ib-card__actions";
  const btn = (label, fn) => {
    const b = document.createElement("button");
    b.type = "button";
    b.className = "link-btn";
    b.textContent = label;
    b.addEventListener("click", fn);
    actions.appendChild(b);
  };
  btn(L("ib.settings"), () => openIbForm());
  btn(L("ib.viewMessages"), () => { ibel.fSource.value = src.type; showIbTab("events"); });
  btn(L(src.enabled ? "ib.pause" : "ib.resume"), async () => {
    try { await api.ibSave({ appId: src.appId, appSecret: "", enabled: !src.enabled }); } catch (e) { showToast(e.message, true); }
    loadIbSources();
  });
  card.append(icon, main, actions);
  return card;
}

// "+ Add": pick a kind; the ones not built yet are listed, disabled.
ibel.add.addEventListener("click", () => {
  ibel.picker.hidden = !ibel.picker.hidden;
  if (ibel.picker.hidden) return;
  closeIbForm();
  ibel.picker.innerHTML = "";
  IB_TYPES.forEach(t => {
    const added = ibSourceList.some(s => s.type === t.type);
    const b = document.createElement("button");
    b.type = "button";
    b.className = "ib-pick";
    b.disabled = !t.ready || added;
    const icon = document.createElement("span");
    icon.className = "ib-card__icon ib-card__icon--" + t.type;
    icon.textContent = t.icon;
    const label = document.createElement("span");
    label.textContent = L(t.label);
    const note = document.createElement("small");
    note.textContent = added ? L("ib.added") : t.ready ? "" : L("ib.soon");
    b.append(icon, label, note);
    b.addEventListener("click", () => { ibel.picker.hidden = true; openIbForm(); });
    ibel.picker.appendChild(b);
  });
});

async function openIbForm() {
  ibel.picker.hidden = true;
  let src;
  try { src = await api.ibSource(); } catch (e) { showToast(e.message, true); return; }
  ibel.appId.value = src.appId || "";
  ibel.appSecret.value = "";
  ibel.appSecret.required = !src.hasSecret;
  ibel.appSecret.placeholder = src.hasSecret ? "••••••••" : "";
  ibel.secretHint.hidden = !src.hasSecret;
  ibel.enabled.checked = src.configured ? src.enabled : true;
  ibel.error.hidden = !src.lastError;
  ibel.error.textContent = src.lastError || "";
  renderIbBind(src);
  ibel.form.hidden = false;
  ibel.add.hidden = true;
  ibel.appId.focus();
}

function closeIbForm() {
  ibel.form.hidden = true;
  ibel.picker.hidden = true;
  ibel.add.hidden = false;
}
ibel.cancel.addEventListener("click", closeIbForm);

function renderIbBind(src) {
  ibel.bind.innerHTML = "";
  const p = document.createElement("div");
  if (!src.configured) {
    p.textContent = L("ib.bindAfterSave");
    ibel.bind.appendChild(p);
    return;
  }
  if (src.bound) {
    p.textContent = L("ib.bound");
  } else if (!src.bindCode) {
    p.textContent = L("ib.codeBurned");
  } else {
    p.append(document.createTextNode(L("ib.bindStep")));
    const code = document.createElement("code");
    code.textContent = L("ib.bindWord") + " " + src.bindCode;
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
    const src = await api.ibSave({ appId: ibel.appId.value.trim(), appSecret: ibel.appSecret.value, enabled: ibel.enabled.checked });
    showToast(L("ib.saved"), false);
    renderIbBind(src); // step 3: the bind code to send
    ibel.appSecret.value = "";
    ibel.appSecret.required = false;
    ibel.secretHint.hidden = false;
    loadIbSources();
    setTimeout(() => { if (!ibPane.hidden) loadIbSources(); }, 4000); // the connection status settles
  } catch (err) {
    ibel.error.textContent = err.message;
    ibel.error.hidden = false;
  }
});

// ---------- message stream ----------

function fillIbFilters() {
  const fill = (sel, allLabel, options) => {
    const cur = sel.value;
    sel.innerHTML = "";
    [["", L(allLabel)], ...options].forEach(([v, label]) => {
      const o = document.createElement("option");
      o.value = v;
      o.textContent = label;
      sel.appendChild(o);
    });
    sel.value = options.some(([v]) => v === cur) ? cur : "";
  };
  const types = Array.from(new Set(ibEventList.map(e => e.source).concat(ibSourceList.map(s => s.type))));
  fill(ibel.fSource, "ib.allSources", types.map(t => [t, L(ibType(t).label)]));
  fill(ibel.fPriority, "ib.allPriorities", ["P0", "P1", "P2", "IGNORE"].map(p => [p, L("ib.prio." + p)]));
  fill(ibel.fHandling, "ib.allHandlings", [["waiting", L("ib.handling.waiting")], ...IB_HANDLINGS.map(h => [h, L("ib.handling." + h)])]);
}

async function loadIbEvents() {
  const wantSource = ibel.fSource.value;
  try { ibEventList = (await api.ibEvents()) || []; } catch (e) { ibEventList = []; showToast(e.message, true); }
  fillIbFilters();
  if (wantSource) ibel.fSource.value = wantSource;
  renderIbEvents();
}
[ibel.fSource, ibel.fPriority, ibel.fHandling].forEach(s => s.addEventListener("change", renderIbEvents));

function renderIbEvents() {
  const src = ibel.fSource.value, prio = ibel.fPriority.value, handling = ibel.fHandling.value;
  const shown = ibEventList.filter(ev => (!src || ev.source === src) && (!prio || ev.priority === prio)
    && (!handling || (ev.handling || "waiting") === handling));
  ibel.events.innerHTML = "";
  ibel.eventsEmpty.hidden = shown.length > 0;
  ibel.eventsEmpty.textContent = L(ibEventList.length ? "ib.noMatch" : "ib.eventsEmpty");
  shown.forEach(ev => {
    const tr = document.createElement("tr");
    const source = document.createElement("td");
    source.className = "kb-list__meta";
    source.textContent = L(ibType(ev.source).label);
    const from = document.createElement("td");
    from.textContent = (ev.senderName || "—") + " · " + (ev.chatName || "—");
    const text = document.createElement("td");
    text.className = "ib-text";
    text.textContent = ev.text || "";
    const prioCell = document.createElement("td");
    const badge = document.createElement("span");
    badge.className = "ib-prio ib-prio--" + ev.priority;
    badge.textContent = L("ib.prio." + ev.priority);
    prioCell.appendChild(badge);
    if (ev.triageReason) {
      const r = document.createElement("span");
      r.className = "ib-reason";
      r.textContent = ev.triageReason;
      prioCell.appendChild(r);
    }
    const handled = document.createElement("td");
    const h = ev.handling || "waiting";
    const hb = document.createElement("span");
    hb.className = "ib-handling is-" + h;
    hb.textContent = L("ib.handling." + h);
    handled.appendChild(hb);
    const when = document.createElement("td");
    when.className = "kb-list__meta";
    when.textContent = formatDateTime(ev.receivedAt);
    tr.append(source, from, text, prioCell, handled, when);
    ibel.events.appendChild(tr);
  });
}

// ---------- the SuperAgent panel's line about its sources ----------

async function loadSuperSources() {
  const link = document.getElementById("superSources");
  const res = await api.ibSummary().catch(() => null);
  if (!res) { link.hidden = true; return; }
  link.hidden = false;
  link.textContent = res.sources
    ? L("ib.superLine", { n: res.sources, today: res.today, handled: res.handled })
    : L("ib.superNone");
}
