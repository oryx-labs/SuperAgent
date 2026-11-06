// SuperAgent UI — Connectors: how the company links to outside systems, each employee's own authorization,
// toasts and the OAuth return. Classic script sharing one global scope with the others; index.html loads them in order.
"use strict";

// ---------- connectors ----------
// Administrators add and edit connectors; everyone authorizes the ones that act as them (a personal token,
// their own GitHub account, their Feishu account). Agents only get a connector once that is in place.
const connSend = (method, url, body) => fetch(url, {
  method, headers: { "Content-Type": "application/json" }, body: body === undefined ? undefined : JSON.stringify(body),
}).then(kbJson);

Object.assign(api, {
  listConnectors: () => fetch("/api/v1/connectors").then(kbJson),
  createConnector: body => connSend("POST", "/api/v1/connectors", body),
  updateConnector: (id, body) => connSend("PUT", "/api/v1/connectors/" + id, body),
  deleteConnector: id => connSend("DELETE", "/api/v1/connectors/" + id),
  saveConnectorToken: (id, token) => connSend("PUT", "/api/v1/connectors/" + id + "/token", { token }),
  revokeConnector: id => connSend("DELETE", "/api/v1/connectors/" + id + "/token"),
  connectorBindCode: id => connSend("POST", "/api/v1/connectors/" + id + "/bind-code"),
});

const cel = {
  rows: document.getElementById("connectorRows"),
  empty: document.getElementById("connectorsEmpty"),
  modal: document.getElementById("connectorModal"),
  form: document.getElementById("connectorForm"),
  title: document.getElementById("connectorModalTitle"),
  name: document.getElementById("cfName"),
  description: document.getElementById("cfDescription"),
  transport: document.getElementById("cfTransport"),
  authMethod: document.getElementById("cfAuthMethod"),
  endpoint: document.getElementById("cfEndpoint"),
  token: document.getElementById("cfToken"),
  tokenField: document.getElementById("cfTokenField"),
  tokenEnv: document.getElementById("cfTokenEnv"),
  tokenEnvField: document.getElementById("cfTokenEnvField"),
  secretKept: document.getElementById("cfSecretKept"),
  enabled: document.getElementById("cfEnabled"),
  error: document.getElementById("cfError"),
  submit: document.getElementById("cfSubmit"),
  tokenModal: document.getElementById("connTokenModal"),
  tokenForm: document.getElementById("connTokenForm"),
  tokenTitle: document.getElementById("connTokenTitle"),
  tokenInput: document.getElementById("connTokenInput"),
  tokenError: document.getElementById("connTokenError"),
};
let editing = null;
let tokenFor = null;

async function loadConnectors() {
  let list = [];
  try { list = (await api.listConnectors()) || []; } catch (e) { showToast(e.message, true); }
  cel.rows.innerHTML = "";
  cel.empty.hidden = list.length > 0;
  list.forEach(c => cel.rows.appendChild(connectorRow(c)));
}

// A recognisable mark per known service (matched on kind, name or command); anything else gets its initial.
const SVG_GITHUB = '<svg viewBox="0 0 16 16" aria-hidden="true"><path fill="currentColor" d="M8 0C3.58 0 0 3.58 0 8c0 3.54 2.29 6.53 5.47 7.59.4.07.55-.17.55-.38 0-.19-.01-.82-.01-1.49-2.01.37-2.53-.49-2.69-.94-.09-.23-.48-.94-.82-1.13-.28-.15-.68-.52-.01-.53.63-.01 1.08.58 1.23.82.72 1.21 1.87.87 2.33.66.07-.52.28-.87.51-1.07-1.78-.2-3.64-.89-3.64-3.95 0-.87.31-1.59.82-2.15-.08-.2-.36-1.02.08-2.12 0 0 .67-.21 2.2.82.64-.18 1.32-.27 2-.27.68 0 1.36.09 2 .27 1.53-1.04 2.2-.82 2.2-.82.44 1.1.16 1.92.08 2.12.51.56.82 1.27.82 2.15 0 3.07-1.87 3.75-3.65 3.95.29.25.54.73.54 1.48 0 1.07-.01 1.93-.01 2.2 0 .21.15.46.55.38A8.013 8.013 0 0016 8c0-4.42-3.58-8-8-8z"/></svg>';
const SVG_PLUG = '<svg viewBox="0 0 24 24" aria-hidden="true" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M9 2v6M15 2v6M6 8h12v3a6 6 0 01-12 0V8zM12 17v5"/></svg>';
const CONNECTOR_MARKS = [
  { test: c => c.kind === "builtin", cls: "superagent", img: "/superagent-mark.png" },
  { test: c => /github/i.test(c.name + " " + (c.endpoint || "")), cls: "github", svg: SVG_GITHUB },
  { test: c => c.id === "gitee" || /gitee|码云/i.test(c.name + " " + (c.endpoint || "")), cls: "gitee", text: "G" },
  { test: c => c.kind === "feishu" || /feishu|lark|飞书/i.test(c.name), cls: "feishu", text: "飞" },
  { test: c => /gitlab/i.test(c.name + " " + (c.endpoint || "")), cls: "gitlab", text: "GL" },
  { test: c => /jira|atlassian|confluence/i.test(c.name + " " + (c.endpoint || "")), cls: "atlassian", text: "J" },
  { test: c => /notion/i.test(c.name + " " + (c.endpoint || "")), cls: "notion", text: "N" },
  { test: c => /slack/i.test(c.name + " " + (c.endpoint || "")), cls: "slack", text: "#" },
  { test: c => /postgres|mysql|sqlite|database|数据库/i.test(c.name + " " + (c.endpoint || "")), cls: "db", text: "DB" },
];

function connectorIcon(c) {
  const mark = CONNECTOR_MARKS.find(m => m.test(c));
  const icon = document.createElement("span");
  icon.className = "c-icon c-icon--" + (mark ? mark.cls : "generic");
  if (mark && mark.img) {
    const img = document.createElement("img");
    img.src = mark.img;
    img.alt = "";
    icon.appendChild(img);
  } else if (mark && mark.svg) icon.innerHTML = mark.svg; // our own constant markup, never data from the server
  else if (mark) icon.textContent = mark.text;
  else if (c.name && c.name.trim()) icon.textContent = c.name.trim().charAt(0).toUpperCase();
  else icon.innerHTML = SVG_PLUG;
  return icon;
}

function connectorRow(c) {
  const tr = document.createElement("tr");
  tr.dataset.id = c.id;

  const name = document.createElement("td");
  name.className = "c-name c-name--icon";
  const text = document.createElement("div");
  text.className = "c-name__text";
  text.appendChild(document.createTextNode(c.name));
  const builtin = c.kind === "builtin"; // SuperAgent's own MCP: shown, never edited
  if (builtin) {
    const tag = document.createElement("span");
    tag.className = "pill pill--method conn-builtin";
    tag.textContent = L("conn.builtin");
    text.appendChild(tag);
  }
  const desc = builtin ? L("conn.builtinDesc") : c.description;
  if (desc) { const d = document.createElement("small"); d.textContent = desc; text.appendChild(d); }
  const config = c.endpoint; // admins only: null for everyone else
  if (config) {
    const wrap = document.createElement("small");
    const code = document.createElement("code");
    code.textContent = config;
    wrap.appendChild(code);
    text.appendChild(wrap);
  }
  const nameCell = document.createElement("div");
  nameCell.className = "c-name__cell";
  nameCell.append(connectorIcon(c), text);
  name.appendChild(nameCell);

  const use = document.createElement("td");
  use.textContent = L("conn.use." + c.kind);

  const status = document.createElement("td");
  const state = connState(c);
  const st = document.createElement("span");
  st.className = "status" + (state === "connected" || state === "ready" ? " is-connected" : "") + (state === "error" ? " is-error" : "");
  const dot = document.createElement("span");
  dot.className = "status__dot";
  st.append(dot, document.createTextNode(L("conn.status." + state)));
  if (c.lastError) st.title = c.lastError;
  status.appendChild(st);
  if (isAdmin && c.needsAuthorization) {
    const n = document.createElement("small");
    n.className = "conn-count";
    n.textContent = L(c.kind === "feishu" ? "conn.linkedCount" : "conn.authorizedCount", { n: c.authorizedCount });
    status.appendChild(n);
  }

  const mine = document.createElement("td");
  mine.appendChild(myAuthorization(c));

  const actions = document.createElement("td");
  const cell = document.createElement("div");
  cell.className = "cell-actions";
  actions.appendChild(cell);
  if (isAdmin && c.kind === "mcp") { // SuperAgent's own and Feishu are set up elsewhere
    const edit = linkBtn("✎", () => openConnectorModal(c), "link-btn--muted");
    edit.title = L("common.edit");
    const del = linkBtn("×", () => deleteConnector(c), "link-btn--danger");
    del.title = L("common.delete");
    cell.append(edit, del);
  }
  tr.append(name, use, status, mine, actions);
  return tr;
}

/** disabled · connected / connecting / error (Feishu's live connection) · noToken (company token missing) · ready */
function connState(c) {
  if (!c.enabled) return "disabled";
  if (c.authMethod === "oauth" && !c.oauthConfigured) return "notSetUp";
  if (c.kind === "feishu" || c.kind === "builtin") return c.status || "connecting";
  if (c.authMethod === "shared_token" && !c.authorized) return "noToken";
  return "ready";
}

/** The viewer's own authorization for one connector, with what they can do about it. */
function myAuthorization(c) {
  const box = document.createElement("div");
  box.className = "conn-mine";
  const text = (t, muted) => {
    const s = document.createElement("span");
    s.className = muted ? "conn-mine__muted" : "conn-mine__ok";
    s.textContent = t;
    box.appendChild(s);
  };
  if (!c.needsAuthorization || !c.enabled) {
    text(c.enabled ? L("conn.notNeeded") : "—", true);
    return box;
  }
  if (c.kind === "feishu") {
    if (c.authorized) { text(L("conn.feishuLinked")); return box; }
    if (c.bindCode) {
      const hint = document.createElement("span");
      hint.className = "conn-bind";
      hint.append(document.createTextNode(L("conn.bindHint") + " "));
      const code = document.createElement("code");
      code.textContent = L("conn.bindWord") + " " + c.bindCode;
      hint.appendChild(code);
      box.append(hint, linkBtn(L("conn.newCode"), () => newBindCode(c)));
    } else {
      text(L("conn.autoLink"), true);
      box.appendChild(linkBtn(L("conn.getCode"), () => newBindCode(c)));
    }
    return box;
  }
  if (c.authMethod === "oauth") {
    if (!c.oauthConfigured) {
      // The company app isn't set up yet: nobody can authorize until an administrator does.
      text(L("conn.notSetUp"), true);
      if (isAdmin) box.appendChild(linkBtn(L("conn.goSetUp"), () => setView("integrations")));
      return box;
    }
    if (c.authorized) {
      text(c.account ? "@" + c.account : L("conn.tokenSet"));
      box.appendChild(linkBtn(L("conn.revoke"), () => revoke(c), "link-btn--muted"));
    } else {
      box.appendChild(primaryBtn(L("conn.authorize"), () => oauthConnect(c)));
    }
    return box;
  }
  if (c.authorized) {
    text(L("conn.tokenSet"));
    box.append(linkBtn(L("conn.changeToken"), () => openTokenModal(c)), linkBtn(L("conn.revoke"), () => revoke(c), "link-btn--muted"));
  } else {
    box.appendChild(primaryBtn(L("conn.enterToken"), () => openTokenModal(c)));
  }
  return box;
}

function linkBtn(text, onClick, extra) {
  const b = document.createElement("button");
  b.type = "button";
  b.className = "link-btn" + (extra ? " " + extra : "");
  b.textContent = text;
  b.addEventListener("click", onClick);
  return b;
}

function primaryBtn(text, onClick) {
  const b = document.createElement("button");
  b.type = "button";
  b.className = "btn-connect";
  b.textContent = text;
  b.addEventListener("click", onClick);
  return b;
}

async function connectorAction(fn) {
  try { await fn(); } catch (e) { showToast(e.message, true); }
  await loadConnectors();
}

const newBindCode = c => connectorAction(() => api.connectorBindCode(c.id));
const revoke = c => connectorAction(() => api.revokeConnector(c.id));

async function deleteConnector(c) {
  if (!window.confirm(L("conn.deleteConfirm", { name: c.name }))) return;
  await connectorAction(() => api.deleteConnector(c.id));
}

// ---------- the admin's add / edit form ----------

function openConnectorModal(c) {
  editing = c;
  cel.title.textContent = L(c ? "conn.modalEdit" : "conn.modalAdd");
  cel.name.value = c ? c.name : "";
  cel.description.value = c && c.description ? c.description : "";
  cel.transport.value = c ? c.transport : "stdio";
  // OAuth exists only for the built-in GitHub connector, so it's offered only while editing that one.
  const oauthOpt = cel.authMethod.querySelector('option[value="oauth"]');
  oauthOpt.hidden = !(c && c.authMethod === "oauth");
  cel.authMethod.value = c ? c.authMethod : "none";
  cel.endpoint.value = c && c.endpoint ? c.endpoint : "";
  cel.tokenEnv.value = c && c.tokenEnv ? c.tokenEnv : "";
  cel.token.value = "";
  cel.secretKept.hidden = !(c && c.companySecretSet);
  cel.enabled.checked = c ? c.enabled : true;
  cel.error.hidden = true;
  syncConnectorForm();
  cel.modal.hidden = false;
  cel.name.focus();
}

/** Shows the fields that apply to the chosen transport and authorization. */
function syncConnectorForm() {
  const auth = cel.authMethod.value;
  cel.tokenField.hidden = auth !== "shared_token";
  cel.tokenEnvField.hidden = cel.transport.value !== "stdio" || auth === "none";
}
[cel.transport, cel.authMethod].forEach(s => s.addEventListener("change", syncConnectorForm));

function closeConnectorModal() { cel.modal.hidden = true; }

cel.form.addEventListener("submit", async e => {
  e.preventDefault();
  const body = { name: cel.name.value.trim(), description: cel.description.value.trim(), transport: cel.transport.value,
    authMethod: cel.authMethod.value, endpoint: cel.endpoint.value.trim(), tokenEnv: cel.tokenEnv.value.trim(),
    secret: cel.token.value, enabled: cel.enabled.checked };
  cel.submit.disabled = true;
  cel.error.hidden = true;
  try {
    if (editing) await api.updateConnector(editing.id, body);
    else await api.createConnector(body);
    closeConnectorModal();
    showToast(L("conn.saved"), false);
    await loadConnectors();
  } catch (err) {
    cel.error.textContent = err.message;
    cel.error.hidden = false;
  } finally {
    cel.submit.disabled = false;
  }
});

document.getElementById("addConnectorBtn").addEventListener("click", () => openConnectorModal(null));

// ---------- an employee's own token ----------

function openTokenModal(c) {
  tokenFor = c;
  cel.tokenTitle.textContent = L("conn.tokenTitle", { name: c.name });
  cel.tokenInput.value = "";
  cel.tokenError.hidden = true;
  cel.tokenModal.hidden = false;
  cel.tokenInput.focus();
}

cel.tokenForm.addEventListener("submit", async e => {
  e.preventDefault();
  try {
    await api.saveConnectorToken(tokenFor.id, cel.tokenInput.value);
    cel.tokenModal.hidden = true;
    await loadConnectors();
  } catch (err) {
    cel.tokenError.textContent = err.message;
    cel.tokenError.hidden = false;
  }
});

[cel.modal, cel.tokenModal].forEach(m => m.querySelectorAll("[data-close]")
  .forEach(x => x.addEventListener("click", () => { m.hidden = true; })));
document.addEventListener("keydown", e => {
  if (e.key !== "Escape") return;
  if (!cel.modal.hidden) closeConnectorModal();
  if (!cel.tokenModal.hidden) cel.tokenModal.hidden = true;
});

// Full-page navigation to the provider's authorize endpoint (the built-in OAuth connectors' id is the provider:
// github, gitee); the server 302s to the provider and back.
function oauthConnect(c) {
  if (!c.oauthConfigured) {
    showToast(L("conn.oauthNotConfigured", { name: c.name }), true);
    return;
  }
  window.location.href = "/api/v1/oauth/" + encodeURIComponent(c.id) + "/authorize?connectorId=" + encodeURIComponent(c.id);
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
  not_configured: "oauth.err.not_configured",
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
