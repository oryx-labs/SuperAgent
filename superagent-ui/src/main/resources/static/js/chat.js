// SuperAgent UI — Agent chat: stored messages with inline edit, streaming bubbles, the agent sidebar and side panel.
// Classic script sharing one global scope with the others; index.html loads them in order.
"use strict";

// ---------- stored messages (with inline edit) ----------
function renderStoredMessage(role, content, id, images, sessionId) {
  clearEmptyState();
  const msg = document.createElement("div");
  msg.className = "msg " + (role === "user" ? "msg--user" : role === "system" ? "msg--system" : "msg--agent");
  if (id) msg.dataset.mid = id;
  if (sessionId) msg.dataset.sid = sessionId;
  if (role === "system") { // SuperAgent inbox notes: task reports, plan approvals
    const note = document.createElement("div");
    note.className = "msg__note";
    note.textContent = content;
    msg.appendChild(note);
    el.messages.appendChild(msg);
    return msg;
  }
  if (images && images.length) msg.appendChild(imageStrip(images));
  const bubble = document.createElement("div");
  bubble.className = "bubble";
  setBubbleContent(bubble, role, content);
  bubble.hidden = !content && !!(images && images.length); // image-only message: no empty bubble
  msg.appendChild(bubble);
  if (id) {
    const edit = document.createElement("button");
    edit.className = "msg__edit";
    edit.title = L("common.edit");
    edit.textContent = "✎";
    edit.addEventListener("click", () => beginEdit(msg, id));
    msg.appendChild(edit);
  }
  el.messages.appendChild(msg);
  return msg;
}

function beginEdit(msgEl, mid) {
  if (streaming) return;
  const role = msgEl.classList.contains("msg--user") ? "user" : "agent";
  const bubble = msgEl.querySelector(".bubble");
  const original = bubble.dataset.md != null ? bubble.dataset.md : bubble.textContent;
  const ta = document.createElement("textarea");
  ta.className = "edit-area";
  ta.value = original;
  const row = document.createElement("div");
  row.className = "edit-actions";
  const save = document.createElement("button");
  save.className = "edit-save";
  save.textContent = L("common.save");
  const cancel = document.createElement("button");
  cancel.className = "edit-cancel";
  cancel.textContent = L("common.cancel");
  row.append(save, cancel);
  bubble.replaceChildren(ta, row);
  ta.focus();
  ta.style.height = Math.min(ta.scrollHeight, 220) + "px";

  cancel.addEventListener("click", () => setBubbleContent(bubble, role, original));
  save.addEventListener("click", async () => {
    const val = ta.value;
    save.disabled = true;
    try {
      await api.editMessage(msgEl.dataset.sid || activeSessionId, mid, val);
      setBubbleContent(bubble, role, val);
    } catch (e) {
      setBubbleContent(bubble, role, original);
    }
  });
}

// ---------- streaming bubbles ----------
function addUserBubble(text, imageUrls) {
  clearEmptyState();
  const msg = document.createElement("div");
  msg.className = "msg msg--user";
  if (imageUrls && imageUrls.length) msg.appendChild(imageStrip(imageUrls));
  const bubble = document.createElement("div");
  bubble.className = "bubble";
  bubble.textContent = text;
  bubble.hidden = !text;
  msg.appendChild(bubble);
  if (isShared(activeAgent)) addAuthor(msg, L("co.me"));
  el.messages.appendChild(msg);
  scrollToBottom();
  return msg;
}

function addAssistantBubble() {
  clearEmptyState();
  const msg = document.createElement("div");
  msg.className = "msg msg--agent";
  const bubble = document.createElement("div");
  bubble.className = "bubble";
  const thinking = document.createElement("details");
  thinking.className = "thinking";
  thinking.hidden = true;
  const summary = document.createElement("summary");
  summary.textContent = L("chat.thinking");
  const thinkingBody = document.createElement("div");
  thinkingBody.className = "thinking__body";
  thinking.append(summary, thinkingBody);
  const tools = document.createElement("div");
  tools.className = "tools";
  tools.hidden = true;
  const text = document.createElement("div");
  text.className = "text is-streaming";
  bubble.append(thinking, tools, text);
  msg.appendChild(bubble);
  el.messages.appendChild(msg);
  scrollToBottom();
  return { thinking, thinkingBody, tools, text };
}

// ---------- agents (sidebar) ----------
// Phase 1 is 1 agent : 1 session; each agent carries its owning sessionId, which
// is what we actually open to chat. The agent is the named role; the session is plumbing.
async function loadAgents(autoOpen) {
  const res = await api.listAgents();
  agents = res.data || [];
  renderThreads(agents);
  if (autoOpen && !activeAgentId) {
    const first = agents.find(a => a.sessionId);
    if (first) selectAgent(first);
  }
}

// An agent with collaborators (whoever owns it) is a shared one: listed apart, marked with its head count.
const isShared = a => !!(a && a.members && a.members.length > 1);
const isOwner = a => !a || a.role !== "collaborator";

function renderThreads(list) {
  el.threadList.innerHTML = "";
  const supers = list.filter(a => a.kind === "super");
  const mine = list.filter(a => a.kind !== "super" && !isShared(a));
  const shared = list.filter(a => a.kind !== "super" && isShared(a));
  supers.forEach(a => el.threadList.append(superThread(a), threadSeparator()));
  // Both groups always show, so it's clear where shared agents will appear even before there are any.
  el.threadList.appendChild(threadGroup(L("co.myAgents"), mine.length));
  if (mine.length) mine.forEach(renderThread);
  else el.threadList.appendChild(threadEmpty(L("co.myEmpty")));
  el.threadList.appendChild(threadGroup(L("co.sharedAgents"), shared.length));
  if (shared.length) shared.forEach(renderThread);
  else el.threadList.appendChild(threadEmpty(L("co.sharedEmpty")));
}

function threadGroup(label, count) {
  const li = document.createElement("li");
  li.className = "thread-group";
  const name = document.createElement("span");
  name.textContent = label;
  const n = document.createElement("span");
  n.className = "thread-group__count";
  n.textContent = count;
  li.append(name, n);
  return li;
}

function threadEmpty(text) {
  const li = document.createElement("li");
  li.className = "thread-empty";
  li.textContent = text;
  return li;
}

function renderThread(a) {
  {
    const li = document.createElement("li");
    li.className = "thread" + (a.id === activeAgentId ? " is-active" : "");
    li.dataset.id = a.id;

    const avatar = document.createElement("span");
    avatar.className = "agent-avatar";
    avatar.setAttribute("aria-hidden", "true");
    avatar.textContent = a.avatar || "🙂";

    const name = document.createElement("span");
    name.className = "thread__name";
    name.textContent = a.name || "";

    if (a.managedBy) {
      const tag = document.createElement("span");
      tag.className = "thread__tag";
      tag.textContent = L("super.teamTag");
      tag.title = L("super.teamHint");
      name.appendChild(tag);
    }
    if (isShared(a)) {
      const tag = document.createElement("span");
      tag.className = "thread__shared";
      tag.textContent = "👥 " + a.members.length;
      tag.title = a.members.map(m => m.displayName).join("、");
      name.appendChild(tag);
    }

    const time = document.createElement("span");
    time.className = "thread__time";
    time.textContent = relativeTime(a.updatedAt);

    const actions = document.createElement("span");
    actions.className = "thread__actions";
    const rename = document.createElement("button");
    rename.className = "thread__act";
    rename.title = L("common.edit");
    rename.textContent = "✎";
    rename.addEventListener("click", e => { e.stopPropagation(); openAgentModal(a); });
    const del = document.createElement("button");
    del.className = "thread__act";
    del.title = L("common.delete");
    del.textContent = "×";
    del.addEventListener("click", e => { e.stopPropagation(); deleteThread(li, a); });
    if (isOwner(a)) actions.append(rename, del); // collaborators can't edit or delete someone else's agent

    li.append(avatar, name, time, actions);
    li.addEventListener("click", () => selectAgent(a));
    el.threadList.appendChild(li);
  }
}

// The SuperAgent isn't one more row: it leads the list as an orb with its role under the name.
function superThread(a) {
  const li = document.createElement("li");
  li.className = "thread thread--super" + (a.id === activeAgentId ? " is-active" : "");
  li.dataset.id = a.id;
  const orb = document.createElement("span");
  orb.className = "super-orb";
  orb.setAttribute("aria-hidden", "true");
  const body = document.createElement("span");
  body.className = "super-body";
  const name = document.createElement("span");
  name.className = "thread__name super-name";
  name.textContent = a.name || "SuperAgent";
  const role = document.createElement("span");
  role.className = "super-role";
  role.textContent = L("super.role");
  body.append(name, role);
  li.append(orb, body);
  li.addEventListener("click", () => selectAgent(a));
  return li;
}

function threadSeparator() {
  const li = document.createElement("li");
  li.className = "thread-sep";
  li.setAttribute("aria-hidden", "true");
  return li;
}

function beginRename(li, agent, nameEl) {
  if (streaming) return;
  const original = nameEl.textContent;
  const input = document.createElement("input");
  input.className = "thread__rename-input";
  input.value = original;
  nameEl.replaceWith(input);
  input.focus();
  input.select();

  let done = false;
  const commit = async save => {
    if (done) return;
    done = true;
    const span = document.createElement("span");
    span.className = "thread__name";
    const value = input.value.trim();
    if (save && value && value !== original) {
      try {
        await api.updateAgent(agent.id, {
          name: value, avatar: agent.avatar, character: agent.character, persona: agent.persona, model: agent.model,
        });
        agent.name = value;
        span.textContent = value;
      } catch (e) { span.textContent = original; }
    } else {
      span.textContent = original;
    }
    input.replaceWith(span);
  };
  input.addEventListener("keydown", e => {
    if (e.key === "Enter") { e.preventDefault(); commit(true); }
    else if (e.key === "Escape") { commit(false); }
  });
  input.addEventListener("blur", () => commit(true));
  input.addEventListener("click", e => e.stopPropagation());
}

async function deleteThread(li, agent) {
  if (streaming) return;
  if (!window.confirm(L("agent.deleteConfirm", { name: agent.name || "" }))) return;
  try { await api.deleteAgent(agent.id); } catch (e) { /* ignore */ }
  li.remove();
  agents = agents.filter(a => a.id !== agent.id);
  if (activeAgentId === agent.id) {
    activeAgentId = null;
    activeSessionId = null;
    el.messages.innerHTML = '<div class="empty-state"><p>' + L("agent.deletedState") + '</p></div>';
  }
}

function markActiveThread() {
  el.threadList.querySelectorAll(".thread").forEach(t =>
    t.classList.toggle("is-active", t.dataset.id === activeAgentId));
}

async function selectAgent(agent) {
  if (streaming) return;
  if (liveRunning) { // was watching another member's turn on the previous agent
    liveRunning = false;
    liveBubble = null;
    el.input.disabled = false;
    el.input.placeholder = L("chat.composer");
  }
  activeAgentId = agent.id;
  activeAgent = agent;
  renderAgentPanel(agent);
  markActiveThread();
  setSuperMode(agent.kind === "super");
  if (!agent.sessionId) {
    activeSessionId = null;
    el.messages.innerHTML = '<div class="empty-state"><p>' + L("agent.emptyChat") + '</p></div>';
    return;
  }
  await selectSession(agent.sessionId);
}

function renderAgentPanel(agent) {
  const panel = document.getElementById("agentInfoPanel");
  if (!panel) return;
  if (!agent) { panel.hidden = true; return; }
  panel.hidden = false;
  document.getElementById("agentInfoName").textContent = agent.name || "Agent";
  document.getElementById("agentInfoAvatar").textContent = agent.avatar || "🙂";
  const ch = characterByKey(agent.character);
  document.getElementById("agentInfoCharacter").textContent = agent.kind === "super" ? L("super.role")
    : ch ? ch.emoji + " " + characterName(ch) : L("agent.characterCustom");
  const editBtn = document.getElementById("agentEditBtn");
  if (editBtn) editBtn.hidden = agent.kind === "super" || !isOwner(agent);
  document.getElementById("agentLogBtn").hidden = !isOwner(agent); // the run log is the owner's only
  composerModel.disabled = !isOwner(agent);
  composerModel.title = isOwner(agent) ? "" : L("co.modelOwnerOnly");
  renderMembers(agent);
  panel.classList.toggle("agent-info--super", agent.kind === "super");
  document.getElementById("agentInfoDetails").hidden = agent.kind === "super";
  document.getElementById("superIntro").hidden = agent.kind !== "super";
  const persona = document.getElementById("agentInfoPersona");
  persona.textContent = agent.persona || "";
  persona.hidden = !agent.persona;
  if (!characterChoices.length) loadCharacters().then(() => { if (activeAgent === agent && agent.character) renderAgentPanel(agent); });
  const model = agent.model || systemSettings.agentDefaultModel || "default";
  document.getElementById("agentInfoModel").textContent = model;
  renderComposerModel(agent);
  renderAgentSkills(agent);
}

// Composer model picker: the agent's model override, limited to Claude models the background probe saw answer.
const composerModel = document.getElementById("statusHint");

async function renderComposerModel(agent) {
  const { groups } = await loadChatModels();
  if (activeAgent !== agent) return; // switched agents while loading
  composerModel.innerHTML = "";
  const def = document.createElement("option");
  def.value = "";
  def.textContent = L("chat.modelDefault", { model: systemSettings.agentDefaultModel || L("settings.modelCliDefault") });
  composerModel.appendChild(def);
  fillModelGroups(composerModel, groups, m => m.id);
  // Show the truth when the saved model has since failed its probe, rather than silently displaying another.
  if (agent.model && !groups.some(g => g.models.some(m => m.id === agent.model))) {
    fillModelGroups(composerModel, [{ label: L("settings.modelCurrent"), models: [{ id: agent.model }] }], m => m.id);
  }
  composerModel.value = agent.model || "";
}

// Chat models across every runnable CLI (the backend runs a turn under the CLI that owns the chosen
// model), keeping only those the background probe saw answer: being listed isn't enough.
const CLI_LABELS = { claude: "Claude Code", codex: "Codex" };
async function loadChatModels() {
  let options = [];
  try { const res = await api.taskAgents(); options = (res && res.data) || []; } catch (e) { /* keep empty */ }
  let listed = 0;
  const groups = options.map(o => {
    const models = o.models || [];
    listed += models.length;
    return { label: CLI_LABELS[o.key] || o.name || o.key, models: models.filter(m => m.inferStatus === "ok") };
  }).filter(g => g.models.length);
  return { groups, listed };
}

function fillModelGroups(select, groups, label) {
  groups.forEach(gr => {
    const g = document.createElement("optgroup");
    g.label = gr.label;
    gr.models.forEach(m => {
      const o = document.createElement("option");
      o.value = m.id;
      o.textContent = label(m);
      g.appendChild(o);
    });
    select.appendChild(g);
  });
}

composerModel.addEventListener("change", async () => {
  const agent = activeAgent;
  if (!agent) return;
  try {
    const res = await api.updateAgent(agent.id, {
      name: agent.name, avatar: agent.avatar, character: agent.character, persona: agent.persona,
      model: composerModel.value || null,
    });
    activeAgent = res.data;
    renderAgentPanel(res.data);
  } catch (e) {
    showToast(L("chat.modelSaveFailed"), true);
    composerModel.value = agent.model || "";
  }
});
