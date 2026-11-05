// SuperAgent UI — Connectors (MCP) admin, toasts and the OAuth return.
// Classic script sharing one global scope with the others; index.html loads them in order.
"use strict";

// ---------- connectors ----------
Object.assign(api, {
  listConnectors: () => fetch("/api/v1/connectors").then(r => r.json()),
  createConnector: body => fetch("/api/v1/connectors", {
    method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body),
  }).then(r => r.json()),
  updateConnector: (id, body) => fetch("/api/v1/connectors/" + id, {
    method: "PUT", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body),
  }).then(r => r.json()),
  deleteConnector: id => fetch("/api/v1/connectors/" + id, { method: "DELETE" }),
  connectConnector: id => fetch("/api/v1/connectors/" + id + "/connect", { method: "POST" }),
  disconnectConnector: id => fetch("/api/v1/connectors/" + id + "/disconnect", { method: "POST" }),
});

const TYPE_LABEL = { required: L("conn.typeRequired"), optional: L("conn.typeOptional") };
const METHOD_LABEL = { system_oauth: L("conn.methodSystem"), personal_token: L("conn.methodToken") };
const STATUS_LABEL = { connected: L("conn.connected"), not_connected: L("conn.notConnected") };

const cel = {
  rows: document.getElementById("connectorRows"),
  empty: document.getElementById("connectorsEmpty"),
  modal: document.getElementById("connectorModal"),
  form: document.getElementById("connectorForm"),
  title: document.getElementById("connectorModalTitle"),
  name: document.getElementById("cfName"),
  endpoint: document.getElementById("cfEndpoint"),
  description: document.getElementById("cfDescription"),
  transport: document.getElementById("cfTransport"),
  type: document.getElementById("cfType"),
  authMethod: document.getElementById("cfAuthMethod"),
  submit: document.getElementById("cfSubmit"),
};
let editingId = null;

async function loadConnectors() {
  let list = [];
  try { const res = await api.listConnectors(); list = (res && res.data) || []; } catch (e) { /* ignore */ }
  cel.rows.innerHTML = "";
  cel.empty.hidden = list.length > 0;
  list.forEach(c => cel.rows.appendChild(connectorRow(c)));
}

function connectorRow(c) {
  const tr = document.createElement("tr");
  tr.dataset.id = c.id;

  const name = document.createElement("td");
  name.className = "c-name";
  name.appendChild(document.createTextNode(c.name));
  if (c.description) { const d = document.createElement("small"); d.textContent = c.description; name.appendChild(d); }
  if (c.endpoint) {
    const wrap = document.createElement("small");
    const code = document.createElement("code");
    code.textContent = c.endpoint;
    wrap.appendChild(code);
    name.appendChild(wrap);
  }

  const type = document.createElement("td");
  type.textContent = TYPE_LABEL[c.type] || c.type;

  const status = document.createElement("td");
  const st = document.createElement("span");
  st.className = "status" + (c.status === "connected" ? " is-connected" : "");
  const dot = document.createElement("span");
  dot.className = "status__dot";
  st.append(dot, document.createTextNode(STATUS_LABEL[c.status] || c.status));
  status.appendChild(st);
  if (c.status === "connected" && c.account) {
    const acct = document.createElement("span");
    acct.className = "status__account";
    acct.textContent = "@" + c.account;
    status.appendChild(acct);
  }

  const method = document.createElement("td");
  const pill = document.createElement("span");
  pill.className = "pill pill--method";
  pill.textContent = METHOD_LABEL[c.authMethod] || c.authMethod;
  method.appendChild(pill);

  const actions = document.createElement("td");
  const cell = document.createElement("div");
  cell.className = "cell-actions";
  actions.appendChild(cell);
  tr.append(name, type, status, method, actions);
  // Connections are shared by the whole platform, so only admins (dis)connect, edit or delete.
  if (!isAdmin) return tr;
  const reconnect = c.oauthProvider
    ? () => oauthConnect(c)
    : () => connectorAction(api.connectConnector, c.id);
  if (c.status === "connected") {
    cell.append(
      linkBtn(L("conn.reconnect"), reconnect),
      linkBtn(L("conn.disconnect"), () => connectorAction(api.disconnectConnector, c.id), "link-btn--muted"));
  } else {
    const connect = document.createElement("button");
    connect.className = "btn-connect";
    connect.textContent = L("conn.connect");
    connect.addEventListener("click", reconnect);
    cell.append(connect);
  }
  const edit = linkBtn("✎", () => openConnectorModal(c), "link-btn--muted");
  edit.title = L("common.edit");
  const del = linkBtn("×", () => deleteConnector(c), "link-btn--danger");
  del.title = L("common.delete");
  cell.append(edit, del);
  return tr;
}

function linkBtn(text, onClick, extra) {
  const b = document.createElement("button");
  b.className = "link-btn" + (extra ? " " + extra : "");
  b.textContent = text;
  b.addEventListener("click", onClick);
  return b;
}

async function connectorAction(fn, id) {
  try { await fn(id); } catch (e) { /* ignore */ }
  await loadConnectors();
}

async function deleteConnector(c) {
  if (!window.confirm(L("conn.deleteConfirm", { name: c.name }))) return;
  try { await api.deleteConnector(c.id); } catch (e) { /* ignore */ }
  await loadConnectors();
}

function openConnectorModal(c) {
  editingId = c ? c.id : null;
  cel.title.textContent = c ? L("conn.modalEdit") : L("conn.modalAdd");
  cel.name.value = c ? c.name : "";
  cel.endpoint.value = c && c.endpoint ? c.endpoint : "";
  cel.description.value = c && c.description ? c.description : "";
  cel.transport.value = c ? c.transport : "stdio";
  cel.type.value = c ? c.type : "optional";
  cel.authMethod.value = c ? c.authMethod : "system_oauth";
  cel.modal.hidden = false;
  cel.name.focus();
}

function closeConnectorModal() { cel.modal.hidden = true; }

cel.modal.querySelectorAll("[data-close]").forEach(e => e.addEventListener("click", closeConnectorModal));
document.addEventListener("keydown", e => {
  if (e.key === "Escape" && !cel.modal.hidden) closeConnectorModal();
});

cel.form.addEventListener("submit", async e => {
  e.preventDefault();
  const body = {
    name: cel.name.value.trim(),
    endpoint: cel.endpoint.value.trim(),
    description: cel.description.value.trim(),
    transport: cel.transport.value,
    type: cel.type.value,
    authMethod: cel.authMethod.value,
  };
  if (!body.name) return;
  cel.submit.disabled = true;
  try {
    if (editingId) await api.updateConnector(editingId, body);
    else await api.createConnector(body);
    closeConnectorModal();
    await loadConnectors();
  } catch (err) { /* ignore */ }
  finally { cel.submit.disabled = false; }
});

document.getElementById("addConnectorBtn").addEventListener("click", () => openConnectorModal(null));

// Full-page navigation to the provider's authorize endpoint; the server 302s to GitHub.
function oauthConnect(c) {
  if (!c.oauthConfigured) {
    showToast(L("conn.oauthNotConfigured"), true);
    return;
  }
  window.location.href = "/api/v1/oauth/" + c.oauthProvider + "/authorize?connectorId=" + encodeURIComponent(c.id);
}

// ---------- toast + OAuth return ----------
function showToast(message, isError) {
  const t = document.createElement("div");
  t.className = "toast" + (isError ? " toast--error" : "");
  t.textContent = message;
  document.body.appendChild(t);
  requestAnimationFrame(() => t.classList.add("is-show"));
  setTimeout(() => { t.classList.remove("is-show"); setTimeout(() => t.remove(), 300); }, 4000);
}

const OAUTH_ERRORS = {
  github_not_configured: "oauth.err.github_not_configured",
  invalid_state: "oauth.err.invalid_state",
  exchange_failed: "oauth.err.exchange_failed",
  missing_code: "oauth.err.missing_code",
  access_denied: "oauth.err.access_denied",
  no_oauth_provider: "oauth.err.no_oauth_provider",
  unknown_connector: "oauth.err.unknown_connector",
};

(function handleOAuthReturn() {
  const params = new URLSearchParams(window.location.search);
  if (!params.has("connected") && !params.has("connect_error")) return;
  setView("connectors");
  if (params.has("connected")) {
    showToast(L("conn.oauthConnected"), false);
  } else {
    const code = params.get("connect_error");
    showToast(OAUTH_ERRORS[code] ? L(OAUTH_ERRORS[code]) : L("oauth.err.generic", { code }), true);
  }
  history.replaceState({}, document.title, window.location.pathname + window.location.hash);
})();
