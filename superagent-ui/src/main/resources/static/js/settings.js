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
// A multi-select dropdown; the order models are ticked in is the order they're tried.
let backupModels = [];
let backupGroups = [];
const backupSelect = {
  root: document.getElementById("sfBackup"),
  trigger: document.getElementById("sfBackupTrigger"),
  menu: document.getElementById("sfBackupMenu"),
};
function renderBackupModels() {
  backupSelect.menu.replaceChildren();
  const offered = backupGroups.flatMap(g => g.models.map(m => ({ group: g.label, model: m })));
  // A saved backup that no longer passes the check stays listed, so it can be unticked.
  backupModels.filter(id => !offered.some(o => o.model.id === id))
    .forEach(id => offered.push({ group: L("settings.modelCurrent"), model: { id } }));
  if (!offered.length) {
    const p = document.createElement("p");
    p.className = "multi-select__empty";
    p.textContent = L("settings.modelsMissing");
    backupSelect.menu.appendChild(p);
  }
  let lastGroup = null;
  offered.forEach(({ group, model: m }) => {
    if (group !== lastGroup) {
      const h = document.createElement("p");
      h.className = "multi-select__group";
      h.textContent = group;
      backupSelect.menu.appendChild(h);
      lastGroup = group;
    }
    const label = document.createElement("label");
    label.className = "multi-select__option";
    label.setAttribute("role", "option");
    const cb = document.createElement("input");
    cb.type = "checkbox";
    cb.checked = backupModels.includes(m.id);
    label.setAttribute("aria-selected", String(cb.checked));
    cb.addEventListener("change", () => {
      backupModels = backupModels.filter(id => id !== m.id);
      if (cb.checked) backupModels.push(m.id);
      label.setAttribute("aria-selected", String(cb.checked));
      renderBackupTrigger();
    });
    const name = document.createElement("span");
    name.className = "multi-select__name";
    name.textContent = m.displayName && m.displayName !== m.id ? m.displayName + " (" + m.id + ")" : m.id;
    label.append(cb, name);
    backupSelect.menu.appendChild(label);
  });
  renderBackupTrigger();
}
function renderBackupTrigger() {
  backupSelect.trigger.replaceChildren();
  if (!backupModels.length) {
    const ph = document.createElement("span");
    ph.className = "multi-select__placeholder";
    ph.textContent = L("settings.backupAuto");
    backupSelect.trigger.appendChild(ph);
    return;
  }
  backupModels.forEach((id, i) => {
    const chip = document.createElement("span");
    chip.className = "multi-select__chip";
    chip.textContent = (i + 1) + ". " + id;
    backupSelect.trigger.appendChild(chip);
  });
}
function closeBackupMenu() {
  backupSelect.menu.hidden = true;
  backupSelect.trigger.setAttribute("aria-expanded", "false");
}
backupSelect.trigger.addEventListener("click", () => {
  const open = backupSelect.menu.hidden;
  backupSelect.menu.hidden = !open;
  backupSelect.trigger.setAttribute("aria-expanded", String(open));
});
document.addEventListener("click", e => { if (!backupSelect.root.contains(e.target)) closeBackupMenu(); });
backupSelect.root.addEventListener("keydown", e => { if (e.key === "Escape" && !backupSelect.menu.hidden) { e.stopPropagation(); closeBackupMenu(); backupSelect.trigger.focus(); } });

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
    backupModels = (systemSettings.backupModels || []).slice();
    renderBackupModels();
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
