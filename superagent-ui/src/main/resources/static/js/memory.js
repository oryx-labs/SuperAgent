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

const mel = {
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
  mel.title.textContent = L("mem.title", { name: memAgent.name });
  mel.form.hidden = true;
  mel.view.hidden = false;
  mel.drawer.hidden = false;
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
  if (state.distilling && !mel.drawer.hidden) memPoll = setTimeout(loadMemory, 3000);
}

function renderMemory() {
  const s = memState;
  mel.meta.textContent = s.distilling ? L("mem.distilling")
    : s.updatedAt ? L("mem.updated", { time: formatDateTime(s.updatedAt) }) : "";
  mel.edit.hidden = mel.distill.hidden = !s.canEdit || !mel.form.hidden;
  mel.distill.disabled = s.distilling;
  if (s.memory) {
    mel.view.innerHTML = renderMarkdown(s.memory);
  } else {
    mel.view.innerHTML = "";
    const p = document.createElement("p");
    p.className = "kb-empty";
    p.textContent = L("mem.empty");
    mel.view.appendChild(p);
  }
}

function closeMemory() {
  mel.drawer.hidden = true;
  clearTimeout(memPoll);
}

function editMemory(on) {
  mel.form.hidden = !on;
  mel.view.hidden = on;
  if (on) { mel.text.value = memState.memory || ""; mel.text.focus(); }
  renderMemory();
}

async function saveMemory(text) {
  try { memState = await api.saveAgentMemory(memAgent.id, text); } catch (e) { showToast(e.message, true); return; }
  editMemory(false);
  showToast(L(text ? "mem.saved" : "mem.cleared"), false);
}

document.getElementById("agentMemoryBtn").addEventListener("click", openMemory);
mel.drawer.querySelectorAll("[data-close]").forEach(x => x.addEventListener("click", closeMemory));
mel.edit.addEventListener("click", () => editMemory(true));
mel.cancel.addEventListener("click", () => editMemory(false));
mel.form.addEventListener("submit", e => { e.preventDefault(); saveMemory(mel.text.value.trim()); });
mel.clear.addEventListener("click", () => kbOpenModal({
  title: L("mem.clear"), submit: L("mem.clear"), danger: true, message: L("mem.clearConfirm"),
  action: () => saveMemory(""),
}));
mel.distill.addEventListener("click", async () => {
  try { memState = await api.distillAgentMemory(memAgent.id); } catch (e) { showToast(e.message, true); return; }
  if (!memState.distilling) showToast(L("mem.nothingNew"), false);
  loadMemory();
});
