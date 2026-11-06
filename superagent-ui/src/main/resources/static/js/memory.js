// SuperAgent UI — An agent's long-term memory drawer.
// Classic script sharing one global scope with the others; index.html loads them in order.
"use strict";

// ---------- agent memory ----------
// What the agent carries into every new conversation, distilled from its chats. Members read it; its owner can
// edit it, clear it, or have the latest conversation folded in now.
Object.assign(api, {
  agentMemory: id => fetch("/api/v1/agents/" + encodeURIComponent(id) + "/memory").then(kbJson),
  saveAgentMemory: (id, memory) => fetch("/api/v1/agents/" + encodeURIComponent(id) + "/memory", {
    method: "PUT", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ memory }),
  }).then(kbJson),
  distillAgentMemory: id => kbPost("/api/v1/agents/" + encodeURIComponent(id) + "/memory/distill", {}),
});

const memEl = {
  drawer: document.getElementById("memDrawer"),
  title: document.getElementById("memTitle"),
  meta: document.getElementById("memMeta"),
  view: document.getElementById("memView"),
  form: document.getElementById("memForm"),
  text: document.getElementById("memText"),
  edit: document.getElementById("memEdit"),
  distill: document.getElementById("memDistill"),
  clear: document.getElementById("memClear"),
  cancel: document.getElementById("memCancel"),
};
let memAgent = null;
let memState = null;
let memPoll = null;

async function openMemory() {
  if (!activeAgent) return;
  memAgent = activeAgent;
  memEl.title.textContent = L("mem.title", { name: memAgent.name });
  memEl.form.hidden = true;
  memEl.view.hidden = false;
  memEl.drawer.hidden = false;
  await loadMemory();
}

async function loadMemory() {
  const agent = memAgent;
  let state;
  try { state = await api.agentMemory(agent.id); } catch (e) { showToast(e.message, true); return; }
  if (memAgent !== agent) return;
  memState = state;
  renderMemory();
  // While a run is folding conversation in, check back until it's done.
  clearTimeout(memPoll);
  if (state.distilling && !memEl.drawer.hidden) memPoll = setTimeout(loadMemory, 3000);
}

function renderMemory() {
  const s = memState;
  memEl.meta.textContent = s.distilling ? L("mem.distilling")
    : s.updatedAt ? L("mem.updated", { time: formatDateTime(s.updatedAt) }) : "";
  memEl.edit.hidden = memEl.distill.hidden = !s.canEdit || !memEl.form.hidden;
  memEl.distill.disabled = s.distilling;
  if (s.memory) {
    memEl.view.innerHTML = renderMarkdown(s.memory);
  } else {
    memEl.view.innerHTML = "";
    const p = document.createElement("p");
    p.className = "kb-empty";
    p.textContent = L("mem.empty");
    memEl.view.appendChild(p);
  }
}

function closeMemory() {
  memEl.drawer.hidden = true;
  clearTimeout(memPoll);
}

function editMemory(on) {
  memEl.form.hidden = !on;
  memEl.view.hidden = on;
  if (on) { memEl.text.value = memState.memory || ""; memEl.text.focus(); }
  renderMemory();
}

async function saveMemory(text) {
  try { memState = await api.saveAgentMemory(memAgent.id, text); } catch (e) { showToast(e.message, true); return; }
  editMemory(false);
  showToast(L(text ? "mem.saved" : "mem.cleared"), false);
}

document.getElementById("agentMemoryBtn").addEventListener("click", openMemory);
memEl.drawer.querySelectorAll("[data-close]").forEach(x => x.addEventListener("click", closeMemory));
memEl.edit.addEventListener("click", () => editMemory(true));
memEl.cancel.addEventListener("click", () => editMemory(false));
memEl.form.addEventListener("submit", e => { e.preventDefault(); saveMemory(memEl.text.value.trim()); });
memEl.clear.addEventListener("click", () => kbOpenModal({
  title: L("mem.clear"), submit: L("mem.clear"), danger: true, message: L("mem.clearConfirm"),
  action: () => saveMemory(""),
}));
memEl.distill.addEventListener("click", async () => {
  try { memState = await api.distillAgentMemory(memAgent.id); } catch (e) { showToast(e.message, true); return; }
  if (!memState.distilling) showToast(L("mem.nothingNew"), false);
  loadMemory();
});
