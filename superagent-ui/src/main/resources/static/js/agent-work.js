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
const ACTIVE_TASK = new Set(["pending", "running", "needs_confirmation"]);

async function renderAgentWork(agent) {
  awel.wrap.hidden = !agent || agent.kind === "super";
  if (awel.wrap.hidden) return;
  let items, made;
  try {
    [items, made] = await Promise.all([api.agentTasks(agent.id), api.listFiles(agent.id)]);
  } catch (e) { return; } // keep the last lists
  if (activeAgent !== agent) return; // switched agents while loading
  items = (items || []).slice().sort((a, b) => ACTIVE_TASK.has(b.status) - ACTIVE_TASK.has(a.status));
  awel.list.replaceChildren(...items.map(agentWorkRow));
  awel.empty.hidden = items.length > 0;
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
  who.textContent = f.taskTitle ? L("fl.fromTask", { title: f.taskTitle }) : (f.ownerName || f.owner || "");
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

// Tasks move on their own: keep the list current while the agent's chat is open.
setInterval(() => {
  if (activeAgent && activeAgent.kind !== "super" && !document.querySelector('[data-pane="chat"]').hidden) {
    renderAgentWork(activeAgent);
  }
}, 15000);
