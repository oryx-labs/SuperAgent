// SuperAgent UI — Shared agents: members, inviting colleagues, and a live view of turns other members started.
// Classic script sharing one global scope with the others; index.html loads them in order.
"use strict";

// ---------- shared agents: members, invite, live view of others' turns ----------
function renderMembers(agent) {
  const block = document.getElementById("agentMembersBlock");
  const list = document.getElementById("agentMembers");
  const shareable = agent.shareable && agent.kind !== "super";
  block.hidden = !shareable && !isShared(agent);
  renderPanelPeople(agent, shareable);
  if (block.hidden) return;
  list.innerHTML = "";
  (agent.members || []).forEach(m => {
    const li = document.createElement("li");
    const initial = document.createElement("span");
    initial.className = "member-initial";
    paintAvatar(initial, m.username, m.displayName);
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

// On the panel itself: who's in this agent at a glance, and the Invite button where it's easy to find.
const PANEL_FACES = 5;
function renderPanelPeople(agent, shareable) {
  const people = agent.members || [];
  const row = document.getElementById("agentPeople");
  row.hidden = !(shareable || isShared(agent)) || people.length < 2; // just the owner: nothing to show yet
  document.getElementById("agentPanelInviteBtn").hidden = !shareable || !isOwner(agent);
  if (row.hidden) return;
  const faces = document.getElementById("agentFaces");
  faces.replaceChildren(...people.slice(0, PANEL_FACES).map(m => {
    const face = document.createElement("span");
    face.className = "member-initial agent-card__face";
    face.title = m.displayName;
    paintAvatar(face, m.username, m.displayName);
    return face;
  }));
  if (people.length > PANEL_FACES) {
    const more = document.createElement("span");
    more.className = "member-initial agent-card__face agent-card__face--more";
    more.textContent = "+" + (people.length - PANEL_FACES);
    faces.appendChild(more);
  }
  document.getElementById("agentPeopleCount").textContent = L("co.peopleCount", { n: people.length });
}
document.getElementById("agentPanelInviteBtn").addEventListener("click", () => document.getElementById("agentInviteBtn").click());
document.getElementById("agentPeople").addEventListener("click", () => document.getElementById("agentDetailBtn").click());

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

// A turn keeps running on the server even if the page is closed or refreshed, so whoever opens the session
// watches it: a collaborator seeing another member's turn, or anyone returning to a turn they started before
// the stream was cut. The poll only notices that a turn is running; the turn itself comes over a stream of the
// same events the sender gets, rendered by the same code, so every viewer sees the same thing (thinking, tool
// calls, text, task and file cards). When it ends the conversation reloads with the stored messages. Only while
// a chat is open.
let liveWatch = null; // { agent, abort } while a turn is being watched
async function pollLive() {
  const agent = activeAgent;
  if (!agent || streaming || liveWatch || !activeSessionId
      || document.querySelector('[data-pane="chat"]').hidden) return;
  let live;
  try { live = await fetch("/api/v1/sessions/" + activeSessionId + "/live").then(kbJson); } catch (e) { return; }
  if (activeAgent !== agent || streaming || liveWatch || !live.running) return;
  watchLiveTurn(agent, live);
}

async function watchLiveTurn(agent, live) {
  const abort = new AbortController();
  liveWatch = { agent, abort };
  const mine = live.by === currentUsername;
  await selectSession(activeSessionId); // shows the message that started the turn
  if (activeAgent !== agent) { liveWatch = null; return; }
  const handles = addAssistantBubble();
  const head = document.createElement("div");
  head.className = "live-head";
  head.textContent = mine ? L("chat.resuming") : L("co.liveBy", { name: live.byName });
  handles.text.closest(".bubble").prepend(head);
  el.input.disabled = true;
  el.input.placeholder = mine ? L("chat.resumingPh") : L("co.waitFor", { name: live.byName });
  scrollToBottom();
  try {
    const resp = await fetch("/api/v1/sessions/" + activeSessionId + "/live/stream", { signal: abort.signal });
    if (resp.ok) await consumeSse(resp, handles);
  } catch (e) { /* switched away or the connection dropped: the poll picks the turn up again */ }
  handles.text.classList.remove("is-streaming");
  liveWatch = null;
  if (abort.signal.aborted || activeAgent !== agent) return;
  el.input.disabled = false;
  el.input.placeholder = L("chat.composer");
  await selectSession(activeSessionId);
  loadAgents();
  renderAgentWork(agent); // the turn may have set off a task
}

/** Stops watching when the user opens another agent. */
function stopLiveWatch() {
  if (!liveWatch) return;
  liveWatch.abort.abort();
  liveWatch = null;
  el.input.disabled = false;
  el.input.placeholder = L("chat.composer");
}
setInterval(pollLive, 2500);

async function selectSession(id) {
  if (superMode) { activeSessionId = id; superFingerprint = null; await refreshSuper(true); return; }
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
  if (activeAgent) attachFileCards(activeAgent); // each reply's files, under it
  if (activeAgent) attachInviteCards(activeAgent); // invitations the agent proposed, under the reply that did
  if (activeAgent) attachSkillDeletionCards(activeAgent.id); // and skill deletions waiting for a yes
  if (activeAgent) attachAgentTaskCards(); // and the tasks each reply set off
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
  if (m.role === "assistant" && m.author && m.author.startsWith("task:")) markTaskReport(msg, m.author.slice(5));
  return msg;
}

function addAuthor(msg, name) {
  const who = document.createElement("div");
  who.className = "msg__author";
  who.textContent = name;
  msg.prepend(who);
}

// ---------- invitations proposed in conversation ----------
// An agent can only propose adding someone; the owner confirms on this card (joining shows them everything).
Object.assign(api, {
  agentInvites: id => fetch("/api/v1/agents/" + encodeURIComponent(id) + "/invites").then(kbJson),
  inviteAction: (id, inviteId, action) =>
    kbPost("/api/v1/agents/" + encodeURIComponent(id) + "/invites/" + encodeURIComponent(inviteId) + "/" + action, {}),
});

async function attachInviteCards(agent) {
  if (!agent || agent.kind === "super") return;
  let list = [];
  try { list = (await api.agentInvites(agent.id)) || []; } catch (e) { return; }
  if (activeAgent !== agent) return;
  list.forEach(inv => {
    const msg = inv.messageId && el.messages.querySelector('.msg[data-mid="' + CSS.escape(inv.messageId) + '"]');
    if (!msg) return;
    const old = msg.querySelector('.invite-card[data-iid="' + CSS.escape(inv.id) + '"]');
    const card = inviteCard(agent, inv);
    if (old) old.replaceWith(card); else (msg.querySelector(".bubble") || msg).appendChild(card);
  });
}

/** {@code agent}: the agent the invitation is for; in the SuperAgent's chat it isn't the one on screen, so it's named. */
function inviteCard(agent, inv) {
  const card = document.createElement("div");
  card.className = "invite-card is-" + inv.status;
  card.dataset.iid = inv.id;
  const text = document.createElement("div");
  text.className = "invite-card__text";
  const title = document.createElement("b");
  const names = (inv.names || inv.usernames).join("、");
  title.textContent = activeAgent && activeAgent.kind === "super" && inv.agentName
    ? L("co.inviteCardTitleFor", { names, agent: inv.agentName }) : L("co.inviteCardTitle", { names });
  const hint = document.createElement("span");
  hint.textContent = L("co.inviteCard." + inv.status + (inv.status === "pending" && !isOwner(agent) ? "Member" : ""));
  text.append(title, hint);
  card.appendChild(text);
  if (inv.status === "pending" && isOwner(agent)) {
    const actions = document.createElement("div");
    actions.className = "invite-card__actions";
    const act = (label, action, primary) => {
      const b = document.createElement("button");
      b.type = "button";
      b.className = "btn btn--sm" + (primary ? " btn--primary" : "");
      b.textContent = L(label);
      b.addEventListener("click", async () => {
        actions.querySelectorAll("button").forEach(x => { x.disabled = true; });
        try {
          const done = await api.inviteAction(agent.id, inv.id, action);
          card.replaceWith(inviteCard(agent, done));
          if (action === "confirm") { showToast(L("co.inviteConfirmed"), false); await refreshActiveAgent(); }
        } catch (e) {
          showToast(e.message, true);
          actions.querySelectorAll("button").forEach(x => { x.disabled = false; });
        }
      });
      return b;
    };
    actions.append(act("co.inviteCancel", "cancel", false), act("co.inviteConfirm", "confirm", true));
    card.appendChild(actions);
  }
  return card;
}
