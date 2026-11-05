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
    opt.textContent = c.name + (c.supported ? "" : " · " + L("models.displayOnly")) + (c.added ? " ✓" : "");
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
}

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
  if (!p.supported) sub.appendChild(document.createTextNode("  · " + L("models.displayOnly")));
  tdName.appendChild(sub);

  const cell = text => { const td = document.createElement("td"); td.textContent = text; return td; };

  const tdStatus = document.createElement("td");
  tdStatus.appendChild(modelStatusBadge(p));
  if (p.message) {
    const m = document.createElement("small");
    m.className = "model-error";
    m.textContent = p.message;
    tdStatus.appendChild(m);
  }

  const tdActions = document.createElement("td");
  const wrap = document.createElement("div");
  wrap.className = "cell-actions";
  if (p.supported && !p.installed && p.status !== "installing") {
    const b = document.createElement("button");
    b.className = "btn-connect";
    b.textContent = L("models.install");
    b.addEventListener("click", () => providerAction(p, "install"));
    wrap.appendChild(b);
  }
  if (p.supported && p.installed && p.upgradeAvailable && p.status !== "installing") {
    const b = document.createElement("button");
    b.className = "btn-connect";
    b.textContent = L("models.upgrade");
    b.addEventListener("click", () => providerAction(p, "upgrade"));
    wrap.appendChild(b);
  }
  wrap.append(
    linkBtn(L("models.details"), () => openModelDetail(p.id), "link-btn--muted"),
    linkBtn("×", () => removeProvider(p), "link-btn--danger"));
  wrap.lastChild.title = L("common.delete");
  tdActions.appendChild(wrap);

  tr.append(tdName,
    cell(yesNo(p.installed, p.supported)),
    cell(yesNo(p.canInfer, p.supported)),
    cell(p.currentVersion || "—"),
    cell(p.latestVersion || "—"),
    tdStatus, tdActions);
  return tr;
}

async function providerAction(p, action) {
  const cmd = action === "install" ? p.installCommand : p.upgradeCommand;
  if (!window.confirm(L("models.confirm" + (action === "install" ? "Install" : "Upgrade"), { name: p.name, cmd }))) return;
  try {
    await (action === "install" ? api.installProvider(p.id) : api.upgradeProvider(p.id));
  } catch (e) { /* ignore */ }
  refreshModels();
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
  setModelTab("info");
  loadModelDetail(id);
}

async function loadModelDetail(id) {
  let p;
  try { const res = await api.providerDetail(id); p = res && res.data; } catch (e) { /* ignore */ }
  if (!p || modelDetailId !== id) return;
  mel.title.textContent = p.name;
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
}
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
