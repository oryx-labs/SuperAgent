// SuperAgent UI — Shared agents: members, inviting colleagues, and a live view of turns other members started.
// Classic script sharing one global scope with the others; index.html loads them in order.
"use strict";

// ---------- shared agents: members, invite, live view of others' turns ----------
function renderMembers(agent) {
  const block = document.getElementById("agentMembersBlock");
  const list = document.getElementById("agentMembers");
  const shareable = agent.shareable && agent.kind !== "super";
  block.hidden = !shareable && !isShared(agent);
  if (block.hidden) return;
  list.innerHTML = "";
  (agent.members || []).forEach(m => {
    const li = document.createElement("li");
    const initial = document.createElement("span");
    initial.className = "member-initial";
    initial.textContent = (m.displayName || m.username || "?").slice(0, 1).toUpperCase();
    const name = document.createElement("span");
    name.textContent = m.username === currentUsername ? m.displayName + "（" + L("co.me") + "）" : m.displayName;
    const role = document.createElement("span");
    role.className = "member-role";
    role.textContent = L(m.role === "owner" ? "co.owner" : "co.collaborator");
    li.append(initial, name, role);
    if (isOwner(agent) && m.role !== "owner") {
      const rm = document.createElement("button");
      rm.type = "button";
      rm.className = "link-btn link-btn--muted";
      rm.textContent = "×";
      rm.title = L("co.remove");
      rm.addEventListener("click", async () => {
        try { await api.removeMember(agent.id, m.username); await refreshActiveAgent(); }
        catch (e) { showToast(e.message, true); }
      });
      li.appendChild(rm);
    }
    list.appendChild(li);
  });
  document.getElementById("agentInviteBtn").hidden = !isOwner(agent) || !shareable;
  document.getElementById("agentLeaveBtn").hidden = isOwner(agent);
}

Object.assign(api, {
  directory: () => fetch("/api/v1/directory/users").then(kbJson),
  inviteMembers: (id, usernames) => kbPost("/api/v1/agents/" + id + "/members", { usernames }),
  removeMember: (id, username) => fetch("/api/v1/agents/" + id + "/members/" + encodeURIComponent(username),
    { method: "DELETE" }).then(kbJson),
});

async function refreshActiveAgent() {
  await loadAgents();
  const fresh = agents.find(a => a.id === activeAgentId);
  if (fresh) { activeAgent = fresh; renderAgentPanel(fresh); }
}

const inv = {
  modal: document.getElementById("inviteModal"),
  form: document.getElementById("inviteForm"),
  title: document.getElementById("inviteTitle"),
  search: document.getElementById("inviteSearch"),
  list: document.getElementById("inviteList"),
};
let inviteCandidates = [];
const inviteChosen = new Set();

document.getElementById("agentInviteBtn").addEventListener("click", async () => {
  const agent = activeAgent;
  if (!agent) return;
  let people = [];
  try { people = (await api.directory()) || []; } catch (e) { showToast(e.message, true); return; }
  const already = new Set((agent.members || []).map(m => m.username));
  inviteCandidates = people.filter(p => !already.has(p.username));
  inviteChosen.clear();
  inv.title.textContent = L("co.inviteTitle", { name: agent.name });
  inv.search.value = "";
  renderInviteList();
  inv.modal.hidden = false;
  inv.search.focus();
});
inv.search.addEventListener("input", renderInviteList);
inv.modal.querySelectorAll("[data-close]").forEach(x => x.addEventListener("click", () => { inv.modal.hidden = true; }));

function renderInviteList() {
  const q = inv.search.value.trim().toLowerCase();
  const shown = inviteCandidates.filter(p => !q || p.displayName.toLowerCase().includes(q) || p.username.toLowerCase().includes(q));
  inv.list.innerHTML = "";
  if (!shown.length) {
    inv.list.innerHTML = '<p class="kb-empty">' + L(inviteCandidates.length ? "sk.noMatch" : "co.noOneElse") + "</p>";
    return;
  }
  shown.forEach(p => {
    const label = document.createElement("label");
    const cb = document.createElement("input");
    cb.type = "checkbox";
    cb.checked = inviteChosen.has(p.username);
    cb.addEventListener("change", () => { if (cb.checked) inviteChosen.add(p.username); else inviteChosen.delete(p.username); });
    const user = document.createElement("span");
    user.className = "invite-user";
    user.textContent = p.username;
    label.append(cb, document.createTextNode(p.displayName), user);
    inv.list.appendChild(label);
  });
}

inv.form.addEventListener("submit", async e => {
  e.preventDefault();
  if (!inviteChosen.size || !activeAgent) { inv.modal.hidden = true; return; }
  try {
    await api.inviteMembers(activeAgent.id, Array.from(inviteChosen));
    inv.modal.hidden = true;
    showToast(L("co.invited"), false);
    await refreshActiveAgent();
    await selectSession(activeSessionId); // author labels appear now that the agent is shared
  } catch (err) { showToast(err.message, true); }
});

document.getElementById("agentLeaveBtn").addEventListener("click", async () => {
  const agent = activeAgent;
  if (!agent || !window.confirm(L("co.leaveConfirm", { name: agent.name }))) return;
  try {
    await api.removeMember(agent.id, currentUsername);
    activeAgentId = null;
    activeAgent = null;
    renderAgentPanel(null);
    el.messages.innerHTML = '<div class="empty-state"><p>' + L("co.left") + "</p></div>";
    await loadAgents(true);
  } catch (e) { showToast(e.message, true); }
});

// Members who didn't start the running turn poll for it: whose it is and the text so far. When it ends,
// the conversation reloads with the new messages. Only while a shared agent is open in the chat view.
let liveBubble = null;
let liveRunning = false;
async function pollLive() {
  const agent = activeAgent;
  if (!agent || !isShared(agent) || streaming || !activeSessionId || superMode
      || document.querySelector('[data-pane="chat"]').hidden) return;
  let live;
  try { live = await fetch("/api/v1/sessions/" + activeSessionId + "/live").then(kbJson); } catch (e) { return; }
  if (activeAgent !== agent || streaming) return;
  if (live.running && live.by !== currentUsername) {
    if (!liveRunning) {
      liveRunning = true;
      await selectSession(activeSessionId); // shows the member's message that started the turn
      liveBubble = addAssistantBubble();
      const head = document.createElement("div");
      head.className = "live-head";
      liveBubble.text.closest(".bubble").prepend(head);
      liveBubble.head = head;
    }
    liveBubble.head.textContent = L("co.liveBy", { name: live.byName }) + (live.activity ? " · ⚙ " + live.activity : "");
    renderStreamText(liveBubble.text, live.text || "");
    el.input.disabled = true;
    el.input.placeholder = L("co.waitFor", { name: live.byName });
    scrollToBottom();
  } else if (liveRunning) {
    liveRunning = false;
    liveBubble = null;
    el.input.disabled = false;
    el.input.placeholder = L("chat.composer");
    await selectSession(activeSessionId);
    loadAgents();
  }
}
setInterval(pollLive, 2500);

async function selectSession(id) {
  if (superMode) { activeSessionId = id; superFingerprint = null; await refreshSuper(); return; }
  activeSessionId = id;
  el.messages.innerHTML = "";
  let messages;
  if (activeAgent) {
    const res = await api.agentHistory(activeAgent.id);
    const segments = (res && res.data) || [];
    // The next turn continues the newest session.
    if (segments.length) activeSessionId = segments[segments.length - 1].sessionId;
    messages = flattenHistory(segments);
  } else {
    const res = await api.getSession(id);
    messages = (res.data && res.data.messages) || [];
  }
  if (!messages.length) {
    el.messages.innerHTML = '<div class="empty-state"><p>' + L("chat.startState") + '</p></div>';
  }
  messages.forEach(renderHistoryItem);
  scrollToBottom();
}

// An agent has many sessions over time (a new model, or a session that kept failing, starts a fresh
// one). Users only ever see the agent, so its sessions read as one continuous conversation.
function flattenHistory(segments) {
  const out = [];
  segments.forEach(seg => seg.messages.forEach(m => out.push(Object.assign({ sessionId: seg.sessionId }, m))));
  return out;
}

function renderHistoryItem(m) {
  const msg = renderStoredMessage(m.role, m.content, m.role === "system" ? null : m.id, m.images, m.sessionId);
  if (m.role === "user" && isShared(activeAgent) && m.authorName) addAuthor(msg, m.author === currentUsername ? L("co.me") : m.authorName);
  return msg;
}

function addAuthor(msg, name) {
  const who = document.createElement("div");
  who.className = "msg__author";
  who.textContent = name;
  msg.prepend(who);
}
