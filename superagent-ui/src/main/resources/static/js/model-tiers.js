// SuperAgent UI — Model tiers: the Fast / Standard / Strong choice people get when the admin turns tiers on, and the
// admin's view of how models landed in them. Classic script sharing one global scope with the others.
"use strict";

Object.assign(api, {
  mtTiers: () => fetch("/api/v1/model-tiers").then(kbJson),
  mtOverview: () => fetch("/api/v1/admin/model-tiers").then(kbJson),
  mtRefresh: () => kbPost("/api/v1/admin/model-tiers/refresh", {}),
  mtPlace: (modelId, tier) => fetch("/api/v1/admin/model-tiers/place", {
    method: "PUT", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ modelId, tier }),
  }).then(kbJson),
});

const MT_TIERS = ["fast", "standard", "strong"];
const tierLabel = t => MT_TIERS.includes(t) ? L("tier." + t) : (t || "");

const mtEl = {
  enabled: document.getElementById("sfTiersEnabled"),
  defaultTier: document.getElementById("sfDefaultTier"),
  modelFields: document.getElementById("sfModelFields"),
  tierFields: document.getElementById("sfTierFields"),
  panel: document.getElementById("mtPanel"),
  status: document.getElementById("mtStatus"),
  refresh: document.getElementById("mtRefresh"),
  rows: document.getElementById("mtRows"),
  empty: document.getElementById("mtEmpty"),
};

function mtOption(value, label) {
  const o = document.createElement("option");
  o.value = value;
  o.textContent = label;
  return o;
}

/** A select of the tiers that have a model, plus the default; a saved tier stays visible even if it has none now. */
async function mtFillSelect(select, selected) {
  const tiers = await api.mtTiers().catch(() => []);
  select.replaceChildren(mtOption("", L("tier.defaultOption", { tier: tierLabel(systemSettings.defaultTier) })));
  MT_TIERS.filter(t => t === selected || tiers.some(x => x.tier === t)).forEach(t => select.appendChild(mtOption(t, tierLabel(t))));
  select.value = selected || "";
  return tiers;
}

// The composer's picker for an agent. No model at all: same notice as without tiers.
async function renderComposerTiers(agent) {
  const tiers = await mtFillSelect(composerModel, agent.tier);
  if (activeAgent !== agent) return; // switched agents while loading
  setNoChatModel(!tiers.length);
  if (!tiers.length) {
    composerModel.replaceChildren(mtOption("", L("chat.noModel")));
    composerModel.disabled = true;
    return;
  }
  // Hovering shows which model the choice means now.
  const wanted = agent.tier || systemSettings.defaultTier;
  const now = tiers.find(x => x.tier === wanted);
  if (isOwner(agent)) {
    composerModel.title = now ? L("tier.current", { tier: tierLabel(wanted), model: now.model }) : "";
  }
}

// ---------- system settings ----------

function mtFillSettings() {
  mtEl.enabled.checked = !!systemSettings.tiersEnabled;
  mtEl.defaultTier.replaceChildren(...MT_TIERS.map(t => mtOption(t, tierLabel(t))));
  mtEl.defaultTier.value = systemSettings.defaultTier || "standard";
  mtSyncFields();
  if (systemSettings.tiersEnabled) mtLoadOverview();
}

function mtSyncFields() {
  const on = mtEl.enabled.checked;
  mtEl.modelFields.hidden = on;
  mtEl.tierFields.hidden = !on;
  mtEl.panel.hidden = !systemSettings.tiersEnabled || !on; // shown once saved: until then nothing routes by it
}
mtEl.enabled.addEventListener("change", mtSyncFields);

async function mtLoadOverview() {
  try { mtRender(await api.mtOverview()); } catch (e) { showToast(e.message, true); }
}

function mtRender(view) {
  mtEl.status.textContent = view.pricesError ? L("tier.pricesError", { msg: view.pricesError })
    : view.pricesReadAt ? L("tier.pricesRead", { when: formatDateTime(view.pricesReadAt) }) : L("tier.pricesBuiltIn");
  mtEl.status.classList.toggle("mt-none", !!view.pricesError);
  mtEl.empty.hidden = view.models.length > 0;
  const inUse = new Set(view.tiers.map(t => t.model));
  mtEl.rows.replaceChildren(...view.models.map(m => {
    const tr = document.createElement("tr");
    const name = document.createElement("td");
    const id = document.createElement("span");
    id.className = "mt-model";
    id.textContent = m.id;
    name.appendChild(id);
    if (inUse.has(m.id)) {
      const use = document.createElement("span");
      use.className = "mt-use";
      use.textContent = L("tier.inUse");
      name.appendChild(use);
    }
    const tier = document.createElement("td");
    const select = document.createElement("select");
    select.setAttribute("aria-label", L("tier.colTier"));
    select.append(mtOption("", L("tier.auto", { tier: tierLabel(m.autoTier) })), ...MT_TIERS.map(t => mtOption(t, tierLabel(t))));
    select.value = m.manual ? m.tier : "";
    select.addEventListener("change", async () => {
      try { mtRender(await api.mtPlace(m.id, select.value)); } catch (e) { showToast(e.message, true); mtLoadOverview(); }
    });
    tier.appendChild(select);
    const price = document.createElement("td");
    if (m.input == null) { price.className = "mt-none"; price.textContent = L("tier.noPrice"); }
    else price.textContent = "$" + m.input + " / $" + m.output;
    const released = document.createElement("td");
    released.className = "kb-list__meta";
    released.textContent = m.released || "—";
    const source = document.createElement("td");
    source.className = "kb-list__meta";
    source.textContent = m.source || "—";
    tr.append(name, tier, price, released, source);
    return tr;
  }));
}

mtEl.refresh.addEventListener("click", async () => {
  mtEl.refresh.disabled = true;
  try { mtRender(await api.mtRefresh()); } catch (e) { showToast(e.message, true); }
  finally { mtEl.refresh.disabled = false; }
});
