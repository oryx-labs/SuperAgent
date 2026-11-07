// SuperAgent UI — System Settings → Integrations: the company's own apps on outside systems (the GitHub OAuth app
// employees authorize through, the Feishu app). Administrators only. Classic script sharing one global scope with
// the others; index.html loads them in order.
"use strict";

Object.assign(api, {
  listIntegrations: () => fetch("/api/v1/admin/integrations").then(kbJson),
  saveIntegration: (key, body) => fetch("/api/v1/admin/integrations/" + key, {
    method: "PUT", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body),
  }).then(kbJson),
});

const intel = {
  rows: document.getElementById("intRows"),
  modal: document.getElementById("intModal"),
  form: document.getElementById("intForm"),
  title: document.getElementById("intModalTitle"),
  guideLink: document.getElementById("intGuideLink"),
  guide: document.getElementById("intGuide"),
  guideTitle: document.getElementById("intGuideTitle"),
  guideBody: document.getElementById("intGuideBody"),
  callbackField: document.getElementById("intCallbackField"),
  callback: document.getElementById("intCallback"),
  fields: document.getElementById("intFields"),
  enabled: document.getElementById("intEnabled"),
  error: document.getElementById("intError"),
  submit: document.getElementById("intSubmit"),
};
let intEditing = null;

async function loadIntegrations() {
  let list = [];
  try { list = (await api.listIntegrations()) || []; } catch (e) { showToast(e.message, true); }
  intel.rows.innerHTML = "";
  list.forEach(i => intel.rows.appendChild(integrationRow(i)));
}

/** off · incomplete (enabled but a field is missing) · connected / connecting / error (a live link) · ready */
function integrationState(i) {
  if (!i.enabled) return "off";
  if (!i.configured) return "incomplete";
  return i.status || "ready";
}

function integrationRow(i) {
  const tr = document.createElement("tr");
  const name = document.createElement("td");
  name.className = "c-name c-name--icon";
  const cell = document.createElement("div");
  cell.className = "c-name__cell";
  const text = document.createElement("div");
  text.className = "c-name__text";
  text.textContent = i.name;
  const desc = document.createElement("small");
  desc.textContent = L("int.desc." + i.key);
  text.appendChild(desc);
  cell.append(connectorIcon({ kind: i.key === "feishu" ? "feishu" : "mcp", name: i.name }), text);
  name.appendChild(cell);

  const status = document.createElement("td");
  const state = integrationState(i);
  const st = document.createElement("span");
  st.className = "status" + (state === "ready" || state === "connected" ? " is-connected" : "") + (state === "error" ? " is-error" : "");
  const dot = document.createElement("span");
  dot.className = "status__dot";
  st.append(dot, document.createTextNode(L("int.state." + state)));
  if (i.lastError) st.title = i.lastError;
  status.appendChild(st);
  if (state === "error" && i.lastError) {
    const err = document.createElement("small");
    err.className = "int-error";
    err.textContent = i.lastError;
    status.appendChild(err);
  }

  // What is filled in, without ever showing a secret.
  const config = document.createElement("td");
  config.className = "int-config";
  i.fields.forEach(f => {
    const line = document.createElement("div");
    const label = document.createElement("span");
    label.className = "int-config__label";
    label.textContent = f.label + "：";
    const value = document.createElement("span");
    value.className = f.set ? "" : "int-config__missing";
    value.textContent = !f.set ? L("int.notSet") : f.secret ? L("int.secretSet") : f.value;
    line.append(label, value);
    config.appendChild(line);
  });

  const actions = document.createElement("td");
  const act = document.createElement("div");
  act.className = "cell-actions";
  const edit = document.createElement("button");
  edit.type = "button";
  edit.className = "link-btn";
  edit.textContent = L("common.edit");
  edit.addEventListener("click", () => openIntegration(i));
  const guide = document.createElement("button");
  guide.type = "button";
  guide.className = "link-btn link-btn--muted";
  guide.textContent = L("int.guide");
  guide.addEventListener("click", () => openIntegrationGuide(i));
  act.append(guide, edit);
  actions.appendChild(act);

  tr.append(name, status, config, actions);
  return tr;
}

function openIntegration(i) {
  intEditing = i;
  intel.title.textContent = i.name;
  intel.callbackField.hidden = !i.callbackUrl;
  intel.callback.value = i.callbackUrl || "";
  intel.fields.innerHTML = "";
  i.fields.forEach(f => {
    const label = document.createElement("label");
    label.className = "field";
    const span = document.createElement("span");
    span.textContent = f.label;
    const input = document.createElement("input");
    input.type = f.secret ? "password" : "text";
    input.autocomplete = f.secret ? "new-password" : "off";
    input.dataset.key = f.key;
    input.value = f.secret ? "" : (f.value || "");
    // A stored secret is never sent to the browser; leaving the field empty keeps it.
    if (f.secret && f.set) input.placeholder = L("int.secretKeep");
    label.append(span, input);
    intel.fields.appendChild(label);
  });
  intel.enabled.checked = i.enabled;
  intel.error.hidden = true;
  intel.modal.hidden = false;
  const first = intel.fields.querySelector("input");
  if (first) first.focus();
}

intel.callback.addEventListener("focus", () => intel.callback.select());

intel.form.addEventListener("submit", async e => {
  e.preventDefault();
  const values = {};
  intel.fields.querySelectorAll("input[data-key]").forEach(inp => { values[inp.dataset.key] = inp.value.trim(); });
  intel.submit.disabled = true;
  intel.error.hidden = true;
  try {
    await api.saveIntegration(intEditing.key, { enabled: intel.enabled.checked, values });
    intel.modal.hidden = true;
    showToast(L("int.saved"), false);
    await loadIntegrations();
    // A live link (Feishu) reports in a few seconds.
    if (intEditing.key === "feishu") setTimeout(() => { if (!integrationsPane.hidden) loadIntegrations(); }, 5000);
  } catch (err) {
    intel.error.textContent = err.message;
    intel.error.hidden = false;
  } finally {
    intel.submit.disabled = false;
  }
});

intel.modal.querySelectorAll("[data-close]").forEach(x => x.addEventListener("click", () => { intel.modal.hidden = true; }));
document.addEventListener("keydown", e => { if (e.key === "Escape" && !intel.modal.hidden) intel.modal.hidden = true; });

// ---------- setup guide: how to create the app on the other side ----------
// Steps are i18n strings ("int.guide.<key>.<n>"), read until one is missing. The callback URL (when the system
// has one) is shown where a step needs it, with a copy button: it must be entered there exactly.

function openIntegrationGuide(i) {
  intel.guideTitle.textContent = L("int.guideTitle", { name: i.name });
  const body = intel.guideBody;
  body.replaceChildren();
  const intro = document.createElement("p");
  intro.className = "int-guide__intro";
  intro.textContent = L("int.guide." + i.key + ".intro");
  body.appendChild(intro);

  const ol = document.createElement("ol");
  ol.className = "int-guide__steps";
  for (let n = 1; ; n++) {
    const k = "int.guide." + i.key + "." + n;
    const text = L(k);
    if (text === k) break; // no such step
    const li = document.createElement("li");
    const p = document.createElement("p");
    p.textContent = text;
    li.appendChild(p);
    if (i.callbackUrl && L(k + ".callback") === "1") li.appendChild(guideCallback(i.callbackUrl));
    ol.appendChild(li);
  }
  body.appendChild(ol);

  const note = L("int.guide." + i.key + ".note");
  if (note !== "int.guide." + i.key + ".note") {
    const n = document.createElement("p");
    n.className = "int-guide__note";
    n.textContent = note;
    body.appendChild(n);
  }
  const link = L("int.guide." + i.key + ".url");
  const open = document.createElement("a");
  open.className = "btn btn--sm";
  open.href = link;
  open.target = "_blank";
  open.rel = "noopener noreferrer";
  open.textContent = L("int.guideGo", { name: i.name });
  body.appendChild(open);
  intel.guide.hidden = false;
}

/** The callback URL, copyable, with where to change it if it's wrong. */
function guideCallback(url) {
  const box = document.createElement("div");
  box.className = "int-guide__callback";
  const code = document.createElement("code");
  code.textContent = url;
  const copy = document.createElement("button");
  copy.type = "button";
  copy.className = "btn btn--sm";
  copy.textContent = L("int.copy");
  copy.addEventListener("click", async () => {
    try { await navigator.clipboard.writeText(url); showToast(L("int.copied"), false); }
    catch (e) { showToast(url, false); }
  });
  const hint = document.createElement("small");
  hint.textContent = L("int.callbackHint");
  box.append(code, copy, hint);
  return box;
}

intel.guideLink.addEventListener("click", () => { if (intEditing) openIntegrationGuide(intEditing); });
intel.guide.querySelectorAll("[data-close]").forEach(x => x.addEventListener("click", () => { intel.guide.hidden = true; }));
document.addEventListener("keydown", e => { if (e.key === "Escape" && !intel.guide.hidden) intel.guide.hidden = true; });

