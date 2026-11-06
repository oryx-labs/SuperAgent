// SuperAgent UI — Message stream: what outside messages (Feishu) reached the user's SuperAgent, and what it did.
// Reached from the SuperAgent panel. Classic script sharing one global scope with the others; index.html loads
// them in order.
"use strict";

Object.assign(api, {
  ibSummary: () => fetch("/api/v1/inbox/summary").then(kbJson),
  ibEvents: () => fetch("/api/v1/inbox/events?limit=500").then(kbJson),
});

const IB_SOURCES = { feishu: "ib.source.feishu" };
const IB_HANDLINGS = ["delivered", "notified", "todo", "task", "read", "ignored"];

const ibPane = document.querySelector('[data-pane="inbox"]');
const ibel = {
  notLinked: document.getElementById("ibNotLinked"),
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
  ibel.notLinked.hidden = !summary || summary.linked;
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
  const shown = ibEventList.filter(ev => (!prio || ev.priority === prio) && (!handling || (ev.handling || "waiting") === handling));
  ibel.events.innerHTML = "";
  ibel.eventsEmpty.hidden = shown.length > 0;
  ibel.eventsEmpty.textContent = L(ibEventList.length ? "ib.noMatch" : "ib.eventsEmpty");
  shown.forEach(ev => {
    const tr = document.createElement("tr");
    const source = document.createElement("td");
    source.className = "kb-list__meta";
    source.textContent = IB_SOURCES[ev.source] ? L(IB_SOURCES[ev.source]) : ev.source;
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

// ---------- the SuperAgent panel's line about outside messages ----------
// Linked: today's count, opening the message stream. Not linked: a nudge to the Connectors page.

async function loadSuperSources() {
  const link = document.getElementById("superSources");
  const res = await api.ibSummary().catch(() => null);
  if (!res) { link.hidden = true; return; }
  link.hidden = false;
  link.href = res.linked ? "#messages" : "#connectors";
  // The link lives in SuperAgent's details drawer: a title, and this line under it.
  document.getElementById("superSourcesHint").textContent = res.linked
    ? L("ib.superLine", { today: res.today, handled: res.handled }) : L("ib.superNone");
}
