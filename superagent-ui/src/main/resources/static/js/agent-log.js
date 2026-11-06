// SuperAgent UI — An agent's run log drawer.
// Classic script sharing one global scope with the others; index.html loads them in order.
"use strict";

// ---------- agent run log ----------
// What happened beneath the conversation: sessions, each turn's outcome, memory, a SuperAgent's moves.
const lel = {
  drawer: document.getElementById("agentLogDrawer"),
  title: document.getElementById("agentLogTitle"),
  tabs: document.getElementById("agentLogTabs"),
  superTab: document.getElementById("agentLogSuperTab"),
  list: document.getElementById("agentLogList"),
  empty: document.getElementById("agentLogEmpty"),
};
let logAgent = null;
let logCategory = "";

function openAgentLog() {
  if (!activeAgent) return;
  logAgent = activeAgent;
  logCategory = "";
  lel.title.textContent = L("log.title", { name: logAgent.name });
  lel.superTab.hidden = logAgent.kind !== "super";
  lel.drawer.hidden = false;
  loadAgentLog();
}

async function loadAgentLog() {
  lel.tabs.querySelectorAll(".auto-tab").forEach(t => t.classList.toggle("is-active", t.dataset.cat === logCategory));
  const res = await fetch("/api/v1/agents/" + logAgent.id + "/logs?limit=500"
    + (logCategory ? "&category=" + logCategory : "")).then(r => r.json()).catch(() => null);
  if (!res || !res.data) {
    lel.list.replaceChildren();
    lel.empty.hidden = false;
    lel.empty.textContent = (res && res.message) || L("log.loadFailed");
    return;
  }
  lel.list.replaceChildren(...res.data.map(logLine));
  lel.empty.hidden = res.data.length > 0;
  lel.empty.textContent = L("log.empty");
}

function logLine(e) {
  const li = document.createElement("li");
  const line = document.createElement("button");
  line.type = "button";
  line.className = "log-line is-" + e.level + (e.detail ? " has-detail" : "");
  const dot = document.createElement("span");
  dot.className = "log-dot";
  const time = document.createElement("time");
  time.className = "log-time";
  time.textContent = formatDateTime(e.at).slice(5); // MM/DD HH:mm:ss
  const cat = document.createElement("span");
  cat.className = "log-cat";
  cat.textContent = L("log.cat." + e.category);
  const summary = document.createElement("span");
  summary.className = "log-summary";
  summary.textContent = e.summary;
  line.append(dot, time, cat, summary);
  li.appendChild(line);
  if (e.detail) {
    const detail = document.createElement("pre");
    detail.className = "log-detail";
    detail.textContent = e.detail;
    detail.hidden = true;
    line.addEventListener("click", () => { detail.hidden = !detail.hidden; });
    li.appendChild(detail);
  }
  return li;
}

document.getElementById("agentLogBtn").addEventListener("click", openAgentLog);
document.getElementById("superLogBtn").addEventListener("click", openAgentLog);
document.getElementById("agentLogRefresh").addEventListener("click", loadAgentLog);
lel.tabs.addEventListener("click", e => {
  const t = e.target.closest(".auto-tab");
  if (t) { logCategory = t.dataset.cat; loadAgentLog(); }
});
lel.drawer.querySelectorAll("[data-close]").forEach(x => x.addEventListener("click", () => { lel.drawer.hidden = true; }));
