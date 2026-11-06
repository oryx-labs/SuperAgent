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
  companyShortName: document.getElementById("sfCompanyShortName"),
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
  sel.companyShortName.value = systemSettings.companyShortName || "";

  sel.agentModel.innerHTML = "";
  const def = document.createElement("option");
  def.value = "";
  // Never offer a default that can't run: say there's nothing, or ask for a pick.
  def.textContent = L(failed || cliDefaultWorks(groups) ? "settings.modelCliDefault"
    : ok ? "settings.modelPick" : "chat.noModel");
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
}

sel.form.addEventListener("submit", async e => {
  e.preventDefault();
  sel.status.textContent = "";
  try {
    const res = await api.updateSettings({
      agentDefaultModel: sel.agentModel.value || null,
      companyName: sel.companyName.value.trim() || null,
      companyShortName: sel.companyShortName.value.trim() || null,
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
