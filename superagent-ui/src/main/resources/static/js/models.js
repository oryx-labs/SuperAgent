// SuperAgent UI — Model management: vendor CLIs, model lists, inference checks.
// Classic script sharing one global scope with the others; index.html loads them in order.
"use strict";

// ---------- models (vendor CLIs) ----------
Object.assign(api, {
  listProviders: () => fetch("/api/v1/providers").then(r => r.json()),
  providerCatalog: () => fetch("/api/v1/providers/catalog").then(r => r.json()),
  addProvider: key => fetch("/api/v1/providers", {
    method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ key }),
  }).then(r => r.json()),
  providerDetail: id => fetch("/api/v1/providers/" + id).then(r => r.json()),
  removeProvider: id => fetch("/api/v1/providers/" + id, { method: "DELETE" }),
  installProvider: id => fetch("/api/v1/providers/" + id + "/install", { method: "POST" }).then(r => r.json()),
  upgradeProvider: id => fetch("/api/v1/providers/" + id + "/upgrade", { method: "POST" }).then(r => r.json()),
  refreshProviderModels: id => fetch("/api/v1/providers/" + id + "/models/refresh", { method: "POST" }).then(r => r.json()),
  nodeRuntime: () => fetch("/api/v1/providers/node").then(r => r.json()),
  checkLatest: id => fetch("/api/v1/providers/" + id + "/check-latest", { method: "POST" }).then(r => r.json()),
  credentials: id => fetch("/api/v1/providers/" + id + "/credentials").then(kbJson),
  saveCredential: (id, cid, body) => fetch("/api/v1/providers/" + id + "/credentials" + (cid ? "/" + cid : ""), {
    method: cid ? "PUT" : "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body),
  }).then(kbJson),
  deleteCredential: (id, cid) => fetch("/api/v1/providers/" + id + "/credentials/" + cid, { method: "DELETE" }).then(kbJson),
  checkCredential: (id, cid) => fetch("/api/v1/providers/" + id + "/credentials/" + cid + "/check", { method: "POST" }).then(kbJson),
  orderCredentials: (id, ids) => fetch("/api/v1/providers/" + id + "/credentials/order", {
    method: "PUT", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ ids }),
  }).then(kbJson),
  installNode: () => fetch("/api/v1/providers/node/install", { method: "POST" }).then(r => r.json()),
  installLog: id => fetch("/api/v1/providers/" + id + "/install-log").then(kbJson),
  nodeInstallLog: () => fetch("/api/v1/providers/node/install-log").then(kbJson),
});

const mel = {
  listView: document.getElementById("modelListView"),
  detailView: document.getElementById("modelDetailView"),
  rows: document.getElementById("modelRows"),
  empty: document.getElementById("modelsEmpty"),
  catalog: document.getElementById("modelCatalog"),
  addBtn: document.getElementById("modelAddBtn"),
  back: document.getElementById("modelBackBtn"),
  title: document.getElementById("modelDetailTitle"),
  meta: document.getElementById("modelDetailMeta"),
  status: document.getElementById("modelDetailStatus"),
  tabs: document.getElementById("modelTabs"),
  info: document.getElementById("modelInfo"),
  models: document.getElementById("modelModels"),
  modelsPanel: document.getElementById("modelModelsPanel"),
  modelsMeta: document.getElementById("modelModelsMeta"),
  modelsError: document.getElementById("modelModelsError"),
  modelsEmpty: document.getElementById("modelModelsEmpty"),
  refreshBtn: document.getElementById("modelRefreshBtn"),
};

function startModelPolling() {
  stopModelPolling();
  refreshModels();
  modelPollTimer = setInterval(refreshModels, 3000);
}
function stopModelPolling() {
  if (modelPollTimer) { clearInterval(modelPollTimer); modelPollTimer = null; }
}

function refreshModels() {
  if (modelDetailId) loadModelDetail(modelDetailId); else loadModels();
}

function showModelList() {
  modelDetailId = null;
  mel.detailView.hidden = true;
  mel.listView.hidden = false;
  loadCatalog();
}

async function loadCatalog() {
  let list = [];
  try { const res = await api.providerCatalog(); list = (res && res.data) || []; } catch (e) { /* ignore */ }
  mel.catalog.innerHTML = "";
  list.forEach(c => {
    const opt = document.createElement("option");
    opt.value = c.key;
    const tag = c.supported ? "" : c.installable ? (c.preview ? " · " + L("models.preview") : "") : " · " + L("models.displayOnly");
    opt.textContent = c.name + tag + (c.added ? " ✓" : "");
    opt.disabled = c.added;
    mel.catalog.appendChild(opt);
  });
  const firstFree = list.find(c => !c.added);
  if (firstFree) mel.catalog.value = firstFree.key;
  mel.addBtn.disabled = !firstFree;
}

async function loadModels() {
  let list = [];
  try { const res = await api.listProviders(); list = (res && res.data) || []; } catch (e) { /* ignore */ }
  mel.rows.innerHTML = "";
  mel.empty.hidden = list.length > 0;
  list.forEach(p => mel.rows.appendChild(modelRow(p)));
  renderNodeRuntime(list);
}

// ---------- Node.js runtime (what the npm-based CLIs install and run on) ----------
const nel = {
  state: document.getElementById("nodeState"),
  hint: document.getElementById("nodeHint"),
  btn: document.getElementById("nodeInstallBtn"),
  logBtn: document.getElementById("nodeLogBtn"),
};

/** The highest Node any added CLI needs, so the card can say whether the current one is enough. */
function nodeNeeded(providers) {
  return providers.map(p => p.minNode).filter(Boolean)
    .sort((a, b) => semverCmp(b, a))[0] || null;
}

function semverCmp(a, b) {
  const pa = String(a).replace(/^v/, "").split(".").map(Number), pb = String(b).replace(/^v/, "").split(".").map(Number);
  for (let i = 0; i < 3; i++) { if ((pa[i] || 0) !== (pb[i] || 0)) return (pa[i] || 0) - (pb[i] || 0); }
  return 0;
}

async function renderNodeRuntime(providers) {
  let n;
  try { const res = await api.nodeRuntime(); n = res && res.data; } catch (e) { return; }
  if (!n) return;
  const need = nodeNeeded(providers);
  const tooOld = n.version && need && semverCmp(n.version, need) < 0;
  nel.state.className = "status-badge node-card__state " + (n.installing ? "is-model-installing"
    : !n.version || tooOld ? "is-model-no_auth" : "is-model-ok");
  nel.state.textContent = n.installing ? L("node.installing")
    : !n.version ? L("node.missing")
    : n.version + " · " + L(n.source === "private" ? "node.private" : "node.system");
  nel.hint.textContent = n.installing && n.step ? n.step
    : n.error ? L("node.failed", { msg: n.error })
    : !n.version ? L("node.missingHint")
    : tooOld ? L("node.tooOld", { need })
    : n.source === "private" ? L("node.privateHint") : L("node.systemHint");
  nel.hint.classList.toggle("is-error", !!n.error);
  // Offered when there's no Node or it's too old; a working system Node is left alone.
  nel.btn.hidden = n.installing || (n.version && !tooOld && n.source !== "private");
  nel.btn.textContent = L(n.source === "private" ? "node.update" : "node.install");
  nel.btn.disabled = n.installing;
  nel.logBtn.hidden = !n.hasLog;
}

nel.logBtn.addEventListener("click", () => openInstallLog("Node.js", api.nodeInstallLog));

// ---------- install / upgrade log ----------
const iel = {
  drawer: document.getElementById("instDrawer"),
  title: document.getElementById("instTitle"),
  meta: document.getElementById("instMeta"),
  error: document.getElementById("instError"),
  log: document.getElementById("instLog"),
};
let instTimer = null;
let instLoad = null;

function openInstallLog(name, load) {
  instLoad = load;
  iel.title.textContent = L("inst.title", { name });
  iel.meta.textContent = "";
  iel.error.hidden = true;
  iel.log.textContent = "";
  iel.drawer.hidden = false;
  renderInstallLog();
}

function closeInstallLog() {
  iel.drawer.hidden = true;
  instLoad = null;
  if (instTimer) { clearTimeout(instTimer); instTimer = null; }
}

async function renderInstallLog() {
  if (instTimer) { clearTimeout(instTimer); instTimer = null; }
  const load = instLoad;
  if (!load) return;
  let j;
  try { j = await load(); } catch (e) { iel.meta.textContent = e.message; return; }
  if (load !== instLoad) return; // closed or switched meanwhile
  if (!j) { iel.meta.textContent = L("inst.none"); return; }
  const end = j.finishedAt ? new Date(j.finishedAt) : new Date();
  const secs = Math.max(0, Math.round((end - new Date(j.startedAt)) / 1000));
  const took = secs >= 60 ? L("inst.minSec", { m: Math.floor(secs / 60), s: secs % 60 }) : L("inst.sec", { s: secs });
  iel.meta.textContent = [L("inst.action." + j.action), new Date(j.startedAt).toLocaleString(),
    j.running ? L("inst.running", { t: took }) : j.error ? L("inst.failed", { t: took }) : L("inst.done", { t: took })].join(" · ");
  iel.error.hidden = !j.error;
  iel.error.textContent = j.error || "";
  // Follow the end while the reader is there; leave the scroll alone when they've scrolled up to read.
  const atEnd = iel.log.scrollHeight - iel.log.scrollTop - iel.log.clientHeight < 40;
  iel.log.textContent = (j.lines || []).join("\n");
  if (atEnd) iel.log.scrollTop = iel.log.scrollHeight;
  if (j.running) instTimer = setTimeout(renderInstallLog, 1500);
}

iel.drawer.querySelectorAll("[data-close]").forEach(x => x.addEventListener("click", closeInstallLog));

nel.btn.addEventListener("click", async () => {
  nel.btn.disabled = true;
  try { await api.installNode(); } catch (e) { showToast(e.message, true); }
  loadModels(); // the 3s poll keeps the card current while it installs
  openInstallLog("Node.js", api.nodeInstallLog);
});

function yesNo(v, supported) {
  if (!supported) return "—";
  return v ? "✓" : "✗";
}

function modelStatusBadge(p) {
  const b = document.createElement("span");
  b.className = "status-badge is-model-" + p.status;
  b.textContent = L("models.status." + p.status);
  return b;
}

function modelRow(p) {
  const tr = document.createElement("tr");

  const tdName = document.createElement("td");
  tdName.className = "c-name";
  tdName.appendChild(document.createTextNode(p.name));
  const sub = document.createElement("small");
  const code = document.createElement("code");
  code.textContent = p.cli || "—";
  sub.appendChild(code);
  if (!p.installable) sub.appendChild(document.createTextNode("  · " + L("models.displayOnly")));
  tdName.appendChild(sub);
  // Say what the newer CLIs are (GLM isn't a CLI of its own) and whether tasks run on them yet.
  const note = L("models.note." + p.key);
  const hasNote = note !== "models.note." + p.key;
  if (p.installable && (!p.supported || hasNote)) {
    const n = document.createElement("small");
    n.className = "model-note";
    if (p.preview) {
      const tag = document.createElement("span");
      tag.className = "model-tag";
      tag.textContent = L("models.preview");
      n.appendChild(tag);
    }
    n.appendChild(document.createTextNode(hasNote ? note : L("models.installOnly")));
    tdName.appendChild(n);
  }

  const cell = text => { const td = document.createElement("td"); td.textContent = text; return td; };

  const tdStatus = document.createElement("td");
  tdStatus.appendChild(modelStatusBadge(p));
  if (p.installStep) {
    const st = document.createElement("small");
    st.className = "model-step";
    st.textContent = p.installStep;
    st.title = p.installStep;
    tdStatus.appendChild(st);
  }
  if (p.message) {
    const m = document.createElement("small");
    m.className = "model-error";
    m.textContent = p.message;
    tdStatus.appendChild(m);
  }

  const tdActions = document.createElement("td");
  const wrap = document.createElement("div");
  wrap.className = "cell-actions";
  if (p.installable && !p.installed && p.status !== "installing") {
    const b = document.createElement("button");
    b.className = "btn-connect";
    b.textContent = L("models.install");
    b.addEventListener("click", () => providerAction(p, "install"));
    wrap.appendChild(b);
  }
  if (p.installable && p.installed && p.upgradeAvailable && p.status !== "installing") {
    const b = document.createElement("button");
    b.className = "btn-connect";
    b.textContent = L("models.upgrade");
    b.addEventListener("click", () => providerAction(p, "upgrade"));
    wrap.appendChild(b);
  }
  if (p.installable && p.installed && p.status !== "installing") {
    // The hourly sweep finds new releases; this asks the registry now.
    const check = linkBtn(L("models.checkLatest"), async () => {
      let res;
      try { res = await api.checkLatest(p.id); } catch (e) { showToast(e.message, true); return; }
      const d = res && res.data;
      if (d && !d.upgradeAvailable) showToast(L("models.upToDate", { version: d.currentVersion || "" }), false);
      loadModels();
    }, "link-btn--muted");
    // Already on the latest known release: nothing to check. (Unknown latest stays clickable, to go and find out.)
    if (p.latestVersion && p.currentVersion === p.latestVersion) {
      check.disabled = true;
      check.title = L("models.upToDate", { version: p.currentVersion });
    }
    wrap.appendChild(check);
  }
  if (p.hasInstallLog) {
    wrap.appendChild(linkBtn(L("inst.log"), () => openInstallLog(p.name, () => api.installLog(p.id)),
      p.message ? "link-btn--danger" : "link-btn--muted"));
  }
  wrap.append(
    linkBtn(L("models.details"), () => openModelDetail(p.id), "link-btn--muted"),
    linkBtn("×", () => removeProvider(p), "link-btn--danger"));
  wrap.lastChild.title = L("common.delete");
  tdActions.appendChild(wrap);

  tr.append(tdName,
    cell(yesNo(p.installed, p.installable)),
    cell(yesNo(p.canInfer, p.supported)),
    cell(p.currentVersion || "—"),
    cell(p.latestVersion || "—"),
    modelCountCell(p),
    tdStatus, tdActions);
  return tr;
}

// How many models the CLI lists, and how many answered the last inference check ("not checked" counts apart).
function modelCountCell(p) {
  const td = document.createElement("td");
  td.className = "model-count";
  const models = p.models || [];
  if (!p.supported || !models.length) { td.textContent = "—"; return td; }
  const ok = models.filter(m => m.inferStatus === "ok").length;
  const bad = models.filter(m => m.inferStatus === "error").length;
  const total = document.createElement("span");
  total.className = "model-count__total";
  total.textContent = L("models.countTotal", { n: models.length });
  const parts = [total];
  const chip = (n, cls, key) => {
    const s = document.createElement("span");
    s.className = "model-count__chip " + cls;
    s.textContent = L(key, { n });
    return s;
  };
  parts.push(chip(ok, "is-ok", "models.countOk"), chip(bad, "is-bad", "models.countBad"));
  const pending = models.length - ok - bad;
  if (pending) parts.push(chip(pending, "is-pending", "models.countPending"));
  td.append(...parts);
  return td;
}

async function providerAction(p, action) {
  const cmd = action === "install" ? p.installCommand : p.upgradeCommand;
  if (!window.confirm(L("models.confirm" + (action === "install" ? "Install" : "Upgrade"), { name: p.name, cmd }))) return;
  try {
    await (action === "install" ? api.installProvider(p.id) : api.upgradeProvider(p.id));
  } catch (e) { /* ignore */ }
  refreshModels();
  openInstallLog(p.name, () => api.installLog(p.id)); // show what it's doing rather than a status that sits still
}

async function removeProvider(p) {
  if (!window.confirm(L("models.removeConfirm", { name: p.name }))) return;
  try { await api.removeProvider(p.id); } catch (e) { /* ignore */ }
  loadCatalog();
  loadModels();
}

mel.addBtn.addEventListener("click", async () => {
  const key = mel.catalog.value;
  if (!key) return;
  mel.addBtn.disabled = true;
  try { await api.addProvider(key); } catch (e) { /* ignore */ }
  await loadCatalog();
  await loadModels();
});

function openModelDetail(id) {
  modelDetailId = id;
  mel.listView.hidden = true;
  mel.detailView.hidden = false;
  closeCredentialForm();
  setModelTab("info");
  loadModelDetail(id);
}

async function loadModelDetail(id) {
  let p;
  try { const res = await api.providerDetail(id); p = res && res.data; } catch (e) { /* ignore */ }
  if (!p || modelDetailId !== id) return;
  mel.title.textContent = p.name;
  credProviderKey = p.key;
  cel2.tab.hidden = !p.installable; // every installable CLI keeps its keys here
  mel.meta.textContent = p.cli || "";
  mel.status.className = "status-badge is-model-" + p.status;
  mel.status.textContent = L("models.status." + p.status);

  const facts = [
    [L("models.colName"), p.cli || "—"],
    [L("models.colInstalled"), yesNo(p.installed, p.supported)],
    [L("models.colInfer"), yesNo(p.canInfer, p.supported)],
    [L("models.colVersion"), p.currentVersion || "—"],
    [L("models.colLatest"), p.latestVersion || "—"],
    [L("models.installCmd"), p.installCommand || "—"],
    [L("models.upgradeCmd"), p.upgradeCommand || "—"],
    [L("models.lastChecked"), formatDateTime(p.lastCheckedAt)],
  ];
  if (p.message) facts.push([L("models.lastError"), p.message]);
  mel.info.innerHTML = "";
  facts.forEach(([k, v]) => {
    const dt = document.createElement("dt"); dt.textContent = k;
    const dd = document.createElement("dd"); dd.textContent = v;
    mel.info.append(dt, dd);
  });

  mel.models.innerHTML = "";
  const models = p.models || [];
  const inferRank = m => ({ ok: 0, error: 2 }[m.inferStatus] ?? 1);
  models.slice().sort((a, b) => inferRank(a) - inferRank(b))
    .forEach(m => mel.models.appendChild(modelItem(m)));
  mel.modelsEmpty.hidden = models.length > 0;
  mel.modelsEmpty.textContent = p.supported ? L("models.noModels") : L("models.modelsComingSoon");
  mel.refreshBtn.hidden = !p.supported;
  const inferOk = models.filter(m => m.inferStatus === "ok").length;
  const inferError = models.filter(m => m.inferStatus === "error").length;
  mel.modelsMeta.textContent = p.supported
    ? L("models.modelsMeta", {
        count: models.length,
        source: p.modelsSource || "—",
        time: formatDateTime(p.modelsCheckedAt),
      }) + (inferOk || inferError ? L("models.inferSummary", { ok: inferOk, error: inferError }) : "")
    : "";
  mel.modelsError.hidden = !p.modelsError;
  mel.modelsError.textContent = p.modelsError ? L("models.modelsError", { msg: p.modelsError }) : "";
}

// One table row per model: id (+ display name, non-native tag), effort levels, default level, context.
function modelItem(m) {
  const tr = document.createElement("tr");

  const tdModel = document.createElement("td");
  tdModel.className = "c-name";
  const id = document.createElement("code");
  id.className = "model-id";
  id.textContent = m.id;
  tdModel.appendChild(id);
  if (!m.nativeModel) {
    const tag = document.createElement("span");
    tag.className = "model-tag model-tag--warn";
    tag.textContent = L("models.nonNative");
    tag.title = L("models.nonNativeHint");
    tdModel.appendChild(tag);
  }
  if (m.displayName && m.displayName !== m.id) {
    const name = document.createElement("small");
    name.textContent = m.displayName;
    tdModel.appendChild(name);
  }

  const tdEfforts = document.createElement("td");
  if (m.efforts && m.efforts.length) {
    const chips = document.createElement("div");
    chips.className = "model-chips";
    m.efforts.forEach(e => {
      const chip = document.createElement("span");
      chip.className = "model-tag" + (e === m.defaultEffort ? " model-tag--default" : "");
      chip.textContent = e;
      chips.appendChild(chip);
    });
    tdEfforts.appendChild(chips);
    if (m.effortSource === "cli") {
      const note = document.createElement("small");
      note.className = "model-note";
      note.textContent = L("models.effortCli");
      tdEfforts.appendChild(note);
    }
  } else {
    tdEfforts.className = "model-note";
    tdEfforts.textContent = L("models.effortUnknown");
  }

  const tdDefault = document.createElement("td");
  tdDefault.textContent = m.defaultEffort || "—";
  const tdCtx = document.createElement("td");
  tdCtx.className = "t-spend";
  tdCtx.textContent = m.contextWindow ? formatContext(m.contextWindow) : "—";

  tr.append(tdModel, tdEfforts, tdDefault, tdCtx, inferenceCell(m));
  return tr;
}

// Background probe result: healthy / failing (with the reason) / not checked yet.
function inferenceCell(m) {
  const td = document.createElement("td");
  const state = m.inferStatus === "ok" ? "ok" : m.inferStatus === "error" ? "error" : "unchecked";
  const badge = document.createElement("span");
  badge.className = "status-badge is-infer-" + state;
  badge.textContent = L({ ok: "models.inferOk", error: "models.inferError", unchecked: "models.inferUnchecked" }[state]);
  if (m.inferError) {
    // The reason is long raw API text — keep it out of the row, show it on hover.
    const wrap = document.createElement("span");
    wrap.className = "infer-tip";
    const err = document.createElement("span");
    err.className = "infer-tip__body";
    err.textContent = m.inferError;
    wrap.append(badge, err);
    td.appendChild(wrap);
  } else {
    td.appendChild(badge);
  }
  if (m.inferCheckedAt) {
    const when = document.createElement("small");
    when.className = "model-note";
    when.textContent = L("models.inferCheckedAt", { time: formatDateTime(m.inferCheckedAt) });
    td.appendChild(when);
  }
  return td;
}


function formatContext(n) {
  return n >= 1000000 ? (n / 1000000).toFixed(n % 1000000 ? 1 : 0) + "M" : Math.round(n / 1000) + "K";
}

function setModelTab(tab) {
  mel.tabs.querySelectorAll(".model-tab").forEach(b => b.classList.toggle("is-active", b.dataset.tab === tab));
  mel.info.hidden = tab !== "info";
  mel.modelsPanel.hidden = tab !== "models";
  cel2.panel.hidden = tab !== "keys";
  if (tab === "keys") loadCredentials();
}

// ---------- API keys: a CLI's endpoints + keys, in the order runs try them ----------
const cel2 = {
  tab: document.getElementById("modelKeysTab"),
  panel: document.getElementById("modelKeysPanel"),
  rows: document.getElementById("credRows"),
  add: document.getElementById("credAddBtn"),
  form: document.getElementById("credForm"),
  label: document.getElementById("credLabel"),
  url: document.getElementById("credUrl"),
  key: document.getElementById("credKey"),
  bearer: document.getElementById("credBearer"),
  bearerField: document.getElementById("credBearerField"),
  error: document.getElementById("credError"),
  cancel: document.getElementById("credCancel"),
};
let credList = [];
let credEditing = null; // null = closed, "new", or an id
let credProviderKey = null;

async function loadCredentials() {
  const id = modelDetailId;
  if (!id) return;
  try { credList = (await api.credentials(id)) || []; } catch (e) { showToast(e.message, true); return; }
  if (modelDetailId !== id) return;
  renderCredentials();
}

function renderCredentials() {
  const pool = credList.filter(c => !c.env);
  cel2.rows.replaceChildren(...credList.map((c, i) => credRow(c, i, pool.length)));
}

function credRow(c, index, poolSize) {
  const tr = document.createElement("tr");
  tr.className = "cred-row" + (c.current ? " is-current" : "") + (c.env ? " is-env" : "") + (c.enabled ? "" : " is-off");
  const cell = (text, cls) => { const td = document.createElement("td"); if (cls) td.className = cls; td.textContent = text; td.title = text; return td; };

  const order = document.createElement("td");
  order.className = "cred-col-order";
  if (c.env) {
    order.textContent = "—";
  } else {
    order.append(String(index + 1));
    const up = linkBtn("↑", () => moveCredential(index, -1), "link-btn--muted");
    const down = linkBtn("↓", () => moveCredential(index, 1), "link-btn--muted");
    up.disabled = index === 0;
    down.disabled = index === poolSize - 1;
    up.title = L("cred.up"); down.title = L("cred.down");
    order.append(up, down);
  }

  const name = document.createElement("td");
  name.className = "cred-name";
  name.textContent = c.env ? L("cred.envLabel") : c.label;
  if (c.current) {
    const badge = document.createElement("span");
    badge.className = "cred-current";
    badge.textContent = L("cred.current");
    name.appendChild(badge);
  }
  if (!c.env && c.source === "machine") {
    const badge = document.createElement("span");
    badge.className = "cred-source";
    badge.textContent = L("cred.fromMachine");
    badge.title = L("cred.fromMachineHint");
    name.appendChild(badge);
  }
  if (c.env) {
    const sub = document.createElement("small");
    sub.textContent = L("cred.envHint");
    name.appendChild(sub);
  }

  // No endpoint of its own = the vendor's official one, named so nobody has to guess where calls go.
  const url = c.env ? cell(L("cred.defaultUrl"), "cred-url")
    : cell(c.baseUrl || (c.defaultBaseUrl || "") + " · " + L("cred.official"), "cred-url");
  const key = cell(c.env ? "—" : c.keyHint + (credProviderKey === "claude" ? " · " + L(c.bearer ? "cred.bearer" : "cred.apiKey") : ""), "cred-key");

  const status = document.createElement("td");
  if (c.env) {
    status.textContent = L("cred.envStatus");
    status.className = "cred-muted";
  } else {
    const b = document.createElement("span");
    const st = c.enabled ? c.status : "off";
    b.className = "status-badge is-cred-" + st;
    b.textContent = L("cred.status." + st);
    status.appendChild(b);
    if (c.lastError && c.enabled) {
      // Why it failed (or why it couldn't be checked), right there: not only on hover.
      const why = document.createElement("small");
      why.className = "cred-reason" + (c.status === "error" ? " is-error" : "");
      why.textContent = c.lastError;
      status.appendChild(why);
    }
    if (c.checkedAt) {
      const when = document.createElement("small");
      when.className = "cred-when";
      when.textContent = L("cred.checkedAt", { time: formatDateTime(c.checkedAt) });
      status.appendChild(when);
    }
  }

  const actions = document.createElement("td");
  actions.className = "ctable__actions-col";
  if (!c.env) {
    const wrap = document.createElement("div");
    wrap.className = "cell-actions";
    wrap.append(
      linkBtn(L("cred.check"), async () => {
        try {
          const r = await api.checkCredential(modelDetailId, c.id);
          if (r && r.status === "ok") showToast(L("cred.checkOk"), false);
          else if (r) showToast(L("cred.checkFailed", { reason: r.lastError || L("cred.status." + r.status) }), true);
        } catch (e) { showToast(e.message, true); }
        loadCredentials();
      }, "link-btn--muted"),
      linkBtn(L("common.edit"), () => openCredentialForm(c), "link-btn--muted"),
      linkBtn(L(c.enabled ? "cred.disable" : "cred.enable"), async () => {
        try { await api.saveCredential(modelDetailId, c.id, { enabled: !c.enabled }); } catch (e) { showToast(e.message, true); }
        loadCredentials();
      }, "link-btn--muted"),
      linkBtn("×", () => kbOpenModal({
        title: L("cred.deleteTitle"), submit: L("common.delete"), danger: true,
        message: L("cred.deleteConfirm", { name: c.label }),
        action: async () => { await api.deleteCredential(modelDetailId, c.id); await loadCredentials(); },
      }), "link-btn--danger"));
    wrap.lastChild.title = L("common.delete");
    actions.appendChild(wrap);
  }
  tr.append(order, name, url, key, status, actions);
  return tr;
}

async function moveCredential(index, delta) {
  const ids = credList.filter(c => !c.env).map(c => c.id);
  const to = index + delta;
  if (to < 0 || to >= ids.length) return;
  [ids[index], ids[to]] = [ids[to], ids[index]];
  try { credList = (await api.orderCredentials(modelDetailId, ids)) || credList; } catch (e) { showToast(e.message, true); }
  renderCredentials();
}

function openCredentialForm(c) {
  credEditing = c ? c.id : "new";
  cel2.label.value = c ? c.label : "";
  cel2.url.value = c ? c.baseUrl || "" : "";
  const official = (credList.find(x => x.defaultBaseUrl) || {}).defaultBaseUrl;
  cel2.url.placeholder = official ? L("cred.fUrlPhDefault", { url: official }) : L("cred.fUrlPh");
  cel2.key.value = "";
  cel2.key.placeholder = c ? L("cred.keepKey", { hint: c.keyHint }) : "";
  cel2.key.required = !c;
  cel2.bearer.checked = c ? c.bearer : true;
  cel2.bearerField.hidden = credProviderKey !== "claude"; // the others' header is fixed by their API
  cel2.error.hidden = true;
  cel2.form.hidden = false;
  cel2.label.focus();
}

function closeCredentialForm() {
  credEditing = null;
  cel2.form.hidden = true;
  cel2.key.value = ""; // never keep a typed key around in the page
}

cel2.add.addEventListener("click", () => openCredentialForm(null));
cel2.cancel.addEventListener("click", closeCredentialForm);
cel2.form.addEventListener("submit", async e => {
  e.preventDefault();
  const body = { label: cel2.label.value.trim(), baseUrl: cel2.url.value.trim(), apiKey: cel2.key.value.trim(),
    bearer: cel2.bearer.checked };
  cel2.error.hidden = true;
  try {
    await api.saveCredential(modelDetailId, credEditing === "new" ? null : credEditing, body);
    closeCredentialForm();
    await loadCredentials();
    // The new/changed key is checked in the background: show its result shortly.
    setTimeout(loadCredentials, 4000);
  } catch (err) {
    cel2.error.textContent = err.message;
    cel2.error.hidden = false;
  }
});
mel.tabs.addEventListener("click", e => {
  const b = e.target.closest(".model-tab");
  if (b) setModelTab(b.dataset.tab);
});
mel.refreshBtn.addEventListener("click", async () => {
  const id = modelDetailId;
  if (!id) return;
  mel.refreshBtn.disabled = true;
  mel.refreshBtn.textContent = L("models.refreshing");
  try { await api.refreshProviderModels(id); } catch (e) { /* surfaced via modelsError */ }
  mel.refreshBtn.disabled = false;
  mel.refreshBtn.textContent = L("models.refresh");
  loadModelDetail(id);
});
mel.back.addEventListener("click", () => { showModelList(); loadModels(); });
