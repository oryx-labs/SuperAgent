// SuperAgent UI — Shared state, element refs, the REST client, markdown rendering and small helpers.
// Classic script sharing one global scope with the others; index.html loads them in order.
"use strict";

const L = (k, v) => window.I18N.t(k, v);

const api = {
  listSessions: () => fetch("/api/v1/sessions").then(r => r.json()),
  createSession: () =>
    fetch("/api/v1/sessions", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ agentType: "claude" }),
    }).then(r => r.json()),
  getSession: id => fetch("/api/v1/sessions/" + id).then(r => r.json()),
  stop: id => fetch("/api/v1/sessions/" + id + "/stop", { method: "POST" }),
  rename: (id, title) =>
    fetch("/api/v1/sessions/" + id + "/title", {
      method: "PUT",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ title }),
    }).then(r => { if (!r.ok) throw new Error("rename failed"); return r.json(); }),
  remove: id => fetch("/api/v1/sessions/" + id, { method: "DELETE" }),
  editMessage: (sid, mid, content) =>
    fetch("/api/v1/sessions/" + sid + "/messages/" + mid, {
      method: "PUT",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ content }),
    }).then(r => { if (!r.ok) throw new Error("edit failed"); return r.json(); }),
  listAgents: () => fetch("/api/v1/agents").then(r => r.json()),
  agentHistory: id => fetch("/api/v1/agents/" + id + "/history").then(r => r.json()),
  superState: () => fetch("/api/v1/superagent").then(r => r.json()),
  superSubmit: body => fetch("/api/v1/superagent/messages", {
    method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body),
  }).then(r => r.json()),
  superWork: (status, memberId, limit) => fetch("/api/v1/superagent/work?status=" + encodeURIComponent(status)
    + (memberId ? "&memberId=" + encodeURIComponent(memberId) : "") + "&limit=" + limit).then(r => r.json()),
  superWorkEvents: taskId => fetch("/api/v1/superagent/work/" + encodeURIComponent(taskId) + "/events")
    .then(r => r.json()),
  superApprove: planId => fetch("/api/v1/superagent/plans/" + encodeURIComponent(planId) + "/approve",
    { method: "POST" }).then(r => r.json()),
  createAgent: body =>
    fetch("/api/v1/agents", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(body),
    }).then(r => r.json()),
  agentAvatars: () => fetch("/api/v1/agents/avatars").then(r => r.json()),
  agentCharacters: () => fetch("/api/v1/agents/characters").then(r => r.json()),
  updateAgent: (id, body) =>
    fetch("/api/v1/agents/" + id, {
      method: "PUT",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(body),
    }).then(r => { if (!r.ok) throw new Error("update failed"); return r.json(); }),
  deleteAgent: id => fetch("/api/v1/agents/" + id, { method: "DELETE" }),
};

const el = {
  threadList: document.getElementById("threadList"),
  messages: document.getElementById("messages"),
  emptyState: document.getElementById("emptyState"),
  composer: document.getElementById("composer"),
  input: document.getElementById("composerInput"),
  newChatBtn: document.getElementById("newChatBtn"),
  sendBtn: document.getElementById("sendBtn"),
};

let agents = [];
let activeAgentId = null;
let activeAgent = null;
let editingAgentId = null;
let editingAgentModel = null;  // legacy per-agent override; the form no longer edits it, so carry it through
let systemSettings = {};       // SystemSettingsDto from /settings
let activeSessionId = null;
let streaming = false;

// ---------- helpers ----------
function clearEmptyState() { if (el.emptyState) el.emptyState.hidden = true; }
function scrollToBottom() { el.messages.scrollTop = el.messages.scrollHeight; }

if (window.marked && window.marked.setOptions) window.marked.setOptions({ gfm: true, breaks: true });

// Render assistant/agent markdown to sanitized HTML. Raw HTML in the source is
// passed through by marked, so DOMPurify is the XSS boundary (no script/on*/javascript:).
function renderMarkdown(content) {
  const raw = content == null ? "" : String(content);
  if (!window.marked || !window.DOMPurify) {
    const d = document.createElement("div");
    d.textContent = raw;
    return d.innerHTML;
  }
  return window.DOMPurify.sanitize(window.marked.parse(raw));
}

const isUserRole = role => role === "user";

// Fill a bubble with a message body, keeping the raw markdown in dataset.md so
// inline edit can recover the source. User text stays plain; agent text is markdown.
function setBubbleContent(bubble, role, content) {
  bubble.dataset.md = content == null ? "" : String(content);
  if (isUserRole(role)) bubble.textContent = content;
  else bubble.innerHTML = renderMarkdown(content);
}
