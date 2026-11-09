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
  agentModel: id => fetch("/api/v1/agents/" + id + "/model").then(r => r.json()),
  superState: () => fetch("/api/v1/superagent").then(r => r.json()),
  superBackground: () => fetch("/api/v1/superagent/background?limit=300").then(kbJson),
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
  return tidyTables(window.DOMPurify.sanitize(window.marked.parse(raw)));
}

/**
 * Tables in replies: each in a scroll box (a wide one scrolls instead of squeezing), and short cells kept on one line
 * so labels like "任务标题" or "✅ 已确定" don't break into a column of single characters.
 */
function tidyTables(html) {
  if (html.indexOf("<table") < 0) return html;
  const box = document.createElement("div");
  box.innerHTML = html;
  box.querySelectorAll("table").forEach(t => {
    const wrap = document.createElement("div");
    wrap.className = "md-table";
    t.parentNode.insertBefore(wrap, t);
    wrap.appendChild(t);
    t.querySelectorAll("th, td").forEach(c => { if (c.textContent.trim().length <= 10) c.classList.add("is-short"); });
  });
  return box.innerHTML;
}

const isUserRole = role => role === "user";

// Fill a bubble with a message body, keeping the raw markdown in dataset.md so
// inline edit can recover the source. User text stays plain; agent text is markdown.
function setBubbleContent(bubble, role, content) {
  bubble.dataset.md = content == null ? "" : String(content);
  if (isUserRole(role)) { bubble.textContent = content; return; }
  // A failed turn keeps its error after the reply (ChatService.ERROR_MARK): show it as an error, not as Markdown.
  const raw = content == null ? "" : String(content);
  const at = raw.indexOf(REPLY_ERROR_MARK);
  if (at < 0) { bubble.innerHTML = renderMarkdown(raw); return; }
  bubble.innerHTML = at > 0 ? renderMarkdown(raw.slice(0, at)) : "";
  const err = document.createElement("div");
  err.className = "bubble__error";
  err.textContent = L("chat.errored", { msg: raw.slice(at + REPLY_ERROR_MARK.length).trim() || L("chat.errGeneric") });
  bubble.appendChild(err);
}
const REPLY_ERROR_MARK = "⚠️ 出错了：";

// ---------- shared request helpers and the small dialog ----------
// Server errors come back as {code, message}; surface the message.
async function kbJson(r) {
  const body = await r.json().catch(() => null);
  if (!r.ok) throw new Error((body && body.message) || ("HTTP " + r.status));
  return body ? body.data : null;
}
function kbPost(url, body) {
  return fetch(url, { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body) }).then(kbJson);
}
function kbFormatSize(n) {
  if (n < 1024) return n + " B";
  if (n < 1024 * 1024) return (n / 1024).toFixed(1) + " KB";
  return (n / 1024 / 1024).toFixed(1) + " MB";
}

// One small dialog for a name, a confirmation or a choice; action() does the request and may throw.
const kel = {
  modal: document.getElementById("kbModal"),
  form: document.getElementById("kbForm"),
  modalTitle: document.getElementById("kbModalTitle"),
  modalMsg: document.getElementById("kbModalMsg"),
  nameField: document.getElementById("kbNameField"),
  nameInput: document.getElementById("kbNameInput"),
  targetField: document.getElementById("kbTargetField"),
  targetSelect: document.getElementById("kbTargetSelect"),
  modalError: document.getElementById("kbModalError"),
  modalSubmit: document.getElementById("kbModalSubmit"),
};
let kbModalAction = null;

function kbOpenModal({ title, submit, danger, message, name, nameLabel, targets, action }) {
  kbModalAction = action;
  kel.modalTitle.textContent = title;
  kel.modalSubmit.textContent = submit;
  kel.modalSubmit.classList.toggle("btn--danger", !!danger);
  kel.modalMsg.hidden = !message;
  kel.modalMsg.textContent = message || "";
  kel.nameField.hidden = name === undefined;
  kel.nameField.querySelector("span").textContent = nameLabel || L("common.name");
  kel.nameInput.value = name || "";
  kel.targetField.hidden = !targets;
  kel.targetSelect.innerHTML = "";
  (targets || []).forEach(t => {
    const o = document.createElement("option");
    o.value = t;
    o.textContent = t;
    kel.targetSelect.appendChild(o);
  });
  kel.modalError.hidden = true;
  kel.modal.hidden = false;
  if (name !== undefined) { kel.nameInput.focus(); kel.nameInput.select(); }
}
const kbCloseModal = () => { kel.modal.hidden = true; kbModalAction = null; };
kel.modal.querySelectorAll("[data-close]").forEach(x => x.addEventListener("click", kbCloseModal));
kel.form.addEventListener("submit", async e => {
  e.preventDefault();
  if (!kbModalAction) return;
  kel.modalSubmit.disabled = true;
  try {
    await kbModalAction();
    kbCloseModal();
  } catch (err) {
    kel.modalError.textContent = err.message;
    kel.modalError.hidden = false;
  } finally {
    kel.modalSubmit.disabled = false;
  }
});
