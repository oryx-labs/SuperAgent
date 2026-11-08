// SuperAgent UI — Message stream: what outside messages (Feishu, DingTalk) reached the user's SuperAgent, and what
// it did. The "Messages" tab of the to-do page (todo.js switches to it). Classic script sharing one global scope with the others; index.html loads
// them in order.
"use strict";

Object.assign(api, {
  ibSummary: () => fetch("/api/v1/inbox/summary").then(kbJson),
  ibEvents: () => fetch("/api/v1/inbox/events?limit=500").then(kbJson),
});

const IB_SOURCES = { feishu: "ib.source.feishu", dingtalk: "ib.source.dingtalk" };
const IB_HANDLINGS = ["delivered", "notified", "todo", "task", "read", "ignored"];

const ibel = {
  events: document.getElementById("ibEvents"),
  eventsEmpty: document.getElementById("ibEventsEmpty"),
  fPriority: document.getElementById("ibFilterPriority"),
  fHandling: document.getElementById("ibFilterHandling"),
};
let ibEventList = [];

async function loadInbox() {
  const [summary, list] = await Promise.all([
    api.ibSummary().catch(() => null),
    api.ibEvents().catch(e => { showToast(e.message, true); return []; }),
  ]);
  if (summary) setTodoMessagesCount(summary.today);
  ibEventList = list || [];
  fillIbFilters();
  renderIbEvents();
}

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
  fill(ibel.fPriority, "ib.allPriorities", ["P0", "P1", "P2", "IGNORE"].map(p => [p, L("ib.prio." + p)]));
  fill(ibel.fHandling, "ib.allHandlings", [["waiting", L("ib.handling.waiting")], ...IB_HANDLINGS.map(h => [h, L("ib.handling." + h)])]);
}
[ibel.fPriority, ibel.fHandling].forEach(s => s.addEventListener("change", renderIbEvents));

function renderIbEvents() {
  const prio = ibel.fPriority.value, handling = ibel.fHandling.value;
  ibel.fPriority.classList.toggle("is-set", !!prio);
  ibel.fHandling.classList.toggle("is-set", !!handling);
  const shown = ibEventList.filter(ev => (!prio || ev.priority === prio) && (!handling || (ev.handling || "waiting") === handling));
  ibel.events.innerHTML = "";
  ibel.eventsEmpty.hidden = shown.length > 0;
  ibel.eventsEmpty.textContent = L(ibEventList.length ? "ib.noMatch" : "ib.eventsEmpty");
  shown.forEach(ev => ibel.events.appendChild(ibRow(ev)));
}

/** One message: who and where (with the source's mark), the text, how urgent, what SuperAgent did, when. */
function ibRow(ev) {
  const tr = document.createElement("tr");
  const cell = (cls, ...kids) => {
    const td = document.createElement("td");
    if (cls) td.className = cls;
    td.append(...kids);
    return td;
  };
  const span = (cls, text) => {
    const s = document.createElement("span");
    s.className = cls;
    s.textContent = text;
    return s;
  };

  const mark = span("ib-src ib-src--" + ev.source, ev.source === "dingtalk" ? "钉" : ev.source === "feishu" ? "飞" : "·");
  mark.title = IB_SOURCES[ev.source] ? L(IB_SOURCES[ev.source]) : ev.source;
  const who = document.createElement("div");
  who.className = "ib-who";
  who.append(span("ib-who__name", ev.senderName || "—"), span("ib-who__chat", ev.chatName || "—"));
  const fromBox = document.createElement("div");
  fromBox.className = "ib-from";
  fromBox.append(mark, who);
  const from = cell("", fromBox);

  const text = span("ib-text", ev.text || "");
  text.title = ev.text || "";
  const msg = cell("", text);

  const prio = cell("", span("ib-prio ib-prio--" + ev.priority, L("ib.prio." + ev.priority)));
  if (ev.triageReason) {
    const r = span("ib-reason", ev.triageReason);
    r.title = ev.triageReason;
    prio.appendChild(r);
  }
  const h = ev.handling || "waiting";
  const handled = cell("", span("ib-handling is-" + h, L("ib.handling." + h)));
  const when = cell("ib-time", ibWhen(ev.receivedAt));
  when.title = formatDateTime(ev.receivedAt);
  tr.append(from, msg, prio, handled, when);
  return tr;
}

/** Today: the time; this year: month/day and time; older: the date. */
function ibWhen(iso) {
  const d = new Date(iso);
  if (isNaN(d.getTime())) return "—";
  const p = n => String(n).padStart(2, "0");
  const now = new Date();
  const hm = p(d.getHours()) + ":" + p(d.getMinutes());
  if (d.toDateString() === now.toDateString()) return hm;
  if (d.getFullYear() === now.getFullYear()) return (d.getMonth() + 1) + "/" + d.getDate() + " " + hm;
  return d.getFullYear() + "/" + (d.getMonth() + 1) + "/" + d.getDate();
}

// ---------- the SuperAgent panel's line about outside messages ----------
// Always opens the message stream (it says there how to link when that's still to do); the line under it gives
// today's count, or the nudge to link.

async function loadSuperSources() {
  const link = document.getElementById("superSources");
  const res = await api.ibSummary().catch(() => null);
  if (!res) { link.hidden = true; return; }
  link.hidden = false;
  link.href = "#messages";
  // The link lives in SuperAgent's details drawer: a title, and this line under it.
  document.getElementById("superSourcesHint").textContent = res.linked
    ? L("ib.superLine", { today: res.today, handled: res.handled }) : L("ib.superNone");
}
