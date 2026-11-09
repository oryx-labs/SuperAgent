// SuperAgent UI — System settings (admin).
// Classic script sharing one global scope with the others; index.html loads them in order.
"use strict";

// ---------- system settings (admin) ----------
Object.assign(api, {
  getSettings: () => fetch("/api/v1/settings").then(r => r.json()),
  updateSettings: body => fetch("/api/v1/settings", {
    method: "PUT", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body),
  }).then(r => r.json()),
});

const sel = {
  form: document.getElementById("settingsForm"),
  agentModel: document.getElementById("sfAgentModel"),
  companyName: document.getElementById("sfCompanyName"),
  logoPreview: document.getElementById("sfLogoPreview"),
  logoInput: document.getElementById("sfLogoInput"),
  logoReset: document.getElementById("sfLogoReset"),
  companyShortName: document.getElementById("sfCompanyShortName"),
  publicUrl: document.getElementById("sfPublicUrl"),
  complexMaxSubtasks: document.getElementById("sfComplexMaxSubtasks"),
  complexMaxParallel: document.getElementById("sfComplexMaxParallel"),
  missing: document.getElementById("sfAgentModelMissing"),
  filtered: document.getElementById("sfAgentModelFiltered"),
  status: document.getElementById("sfStatus"),
};

async function loadSystemSettings() {
  try { const res = await api.getSettings(); systemSettings = (res && res.data) || {}; } catch (e) { systemSettings = {}; }
}

async function loadSettingsForm() {
  sel.status.textContent = "";
  const [{ groups, listed, failed }] = await Promise.all([loadChatModels(), loadSystemSettings()]);
  const ok = groups.reduce((n, g) => n + g.models.length, 0);
  const current = systemSettings.agentDefaultModel || "";
  sel.companyName.value = systemSettings.companyName || "";
  refreshLogo();
  sel.companyShortName.value = systemSettings.companyShortName || "";
  sel.publicUrl.value = systemSettings.publicUrl || "";
  sel.complexMaxSubtasks.value = systemSettings.complexMaxSubtasks || 10;
  sel.complexMaxParallel.value = systemSettings.complexMaxParallel || 5;

  sel.agentModel.innerHTML = "";
  const def = document.createElement("option");
  def.value = "";
  // Nothing picked = the most expensive model that can run; say so, or that nothing can.
  def.textContent = L(failed || ok ? "settings.modelAuto" : "chat.noModel");
  sel.agentModel.appendChild(def);
  fillModelGroups(sel.agentModel, groups,
    m => m.displayName && m.displayName !== m.id ? m.displayName + " (" + m.id + ")" : m.id);
  // Keep a saved value selectable even if it no longer passes the check.
  if (current && !groups.some(g => g.models.some(m => m.id === current))) {
    fillModelGroups(sel.agentModel, [{ label: L("settings.modelCurrent"), models: [{ id: current }] }], m => m.id);
  }
  sel.agentModel.value = current;
  const none = !failed && !ok;
  sel.agentModel.disabled = none && !current;
  sel.agentModel.classList.toggle("is-unavailable", none && !current);
  sel.missing.hidden = !none;
  sel.filtered.hidden = !ok || ok === listed;
  sel.filtered.textContent = L("settings.modelsFiltered", { ok, total: listed });
  backupModels = (systemSettings.backupModels || []).slice();
  backupGroups = groups;
  renderBackupModels();
}

// Backup models: where conversations go, in order, when the default model can't run.
let backupModels = [];
let backupGroups = [];
function renderBackupModels() {
  const list = document.getElementById("sfBackupList");
  list.replaceChildren(...backupModels.map((id, i) => {
    const row = document.createElement("div");
    row.className = "backup-models__row";
    const n = document.createElement("span");
    n.className = "backup-models__n";
    n.textContent = i + 1;
    const name = document.createElement("span");
    name.className = "backup-models__id";
    name.textContent = id;
    const up = document.createElement("button");
    up.type = "button";
    up.textContent = "↑";
    up.title = L("settings.backupUp");
    up.disabled = i === 0;
    up.addEventListener("click", () => { [backupModels[i - 1], backupModels[i]] = [backupModels[i], backupModels[i - 1]]; renderBackupModels(); });
    const del = document.createElement("button");
    del.type = "button";
    del.textContent = "✕";
    del.title = L("settings.backupRemove");
    del.addEventListener("click", () => { backupModels.splice(i, 1); renderBackupModels(); });
    row.append(n, name, up, del);
    return row;
  }));
  const add = document.getElementById("sfBackupAdd");
  add.replaceChildren();
  const first = document.createElement("option");
  first.value = "";
  first.textContent = L(backupModels.length ? "settings.backupAddMore" : "settings.backupAuto");
  add.appendChild(first);
  const taken = new Set([...backupModels, sel.agentModel.value]);
  fillModelGroups(add, backupGroups.map(g => Object.assign({}, g, { models: g.models.filter(m => !taken.has(m.id)) }))
    .filter(g => g.models.length), m => m.displayName && m.displayName !== m.id ? m.displayName + " (" + m.id + ")" : m.id);
  add.value = "";
}
document.getElementById("sfBackupAdd").addEventListener("change", e => {
  if (e.target.value) { backupModels.push(e.target.value); renderBackupModels(); }
});
sel.agentModel.addEventListener("change", () => {
  backupModels = backupModels.filter(m => m !== sel.agentModel.value);
  renderBackupModels();
});

sel.form.addEventListener("submit", async e => {
  e.preventDefault();
  sel.status.textContent = "";
  try {
    const res = await api.updateSettings({
      agentDefaultModel: sel.agentModel.value || null,
      companyName: sel.companyName.value.trim() || null,
      companyShortName: sel.companyShortName.value.trim() || null,
      publicUrl: sel.publicUrl.value.trim(), // "" clears it
      backupModels,
      complexMaxSubtasks: Number(sel.complexMaxSubtasks.value) || null,
      complexMaxParallel: Number(sel.complexMaxParallel.value) || null,
    });
    if (!res || !res.data) throw new Error((res && res.message) || "no data");
    systemSettings = res.data;
    sel.status.textContent = L("settings.saved");
      if (currentMe) {
      currentMe.companyName = systemSettings.companyName;
      currentMe.companyShortName = systemSettings.companyShortName;
      renderAccount();
    }
    if (activeAgent) renderAgentPanel(activeAgent);
  } catch (err) {
    sel.status.textContent = L("settings.saveFailed") + " " + err.message;
  }
});

// ---------- the company logo ----------
// Applied the moment it's chosen (no Save needed); the sign-in page and the top left both show it.
Object.assign(api, {
  hasLogo: () => fetch("/api/v1/settings/logo").then(kbJson),
  setLogo: data => fetch("/api/v1/settings/logo", {
    method: "PUT", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ data }),
  }).then(kbJson),
  resetLogo: () => fetch("/api/v1/settings/logo", { method: "DELETE" }).then(kbJson),
});

async function refreshLogo() {
  const bust = "/api/v1/branding/logo?t=" + Date.now();
  sel.logoPreview.src = bust;
  document.getElementById("brandLogo").src = bust;
  try { sel.logoReset.hidden = !(await api.hasLogo()); } catch (e) { sel.logoReset.hidden = true; }
}

sel.logoInput.addEventListener("change", async () => {
  const file = sel.logoInput.files && sel.logoInput.files[0];
  sel.logoInput.value = "";
  if (!file) return;
  if (file.size > 1024 * 1024) { showToast(L("settings.logoTooBig"), true); return; }
  try {
    await api.setLogo(await readBase64(file));
    await refreshLogo();
    showToast(L("settings.logoSaved"), false);
  } catch (e) { showToast(e.message, true); }
});
sel.logoReset.addEventListener("click", async () => {
  try { await api.resetLogo(); await refreshLogo(); showToast(L("settings.logoResetDone"), false); }
  catch (e) { showToast(e.message, true); }
});
