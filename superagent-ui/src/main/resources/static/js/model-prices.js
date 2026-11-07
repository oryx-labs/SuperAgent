// SuperAgent UI — Model prices (admin, Models → Prices): what each model that can run costs. Tasks are
// matched to the cheapest model able to do them, and token usage priced, by these. The admin can set a model's price
// by hand (a relay bills its own) and keep a model out of tasks. Classic script sharing one global scope.
"use strict";

Object.assign(api, {
  mpOverview: () => fetch("/api/v1/admin/model-prices").then(kbJson),
  mpRefresh: () => kbPost("/api/v1/admin/model-prices/refresh", {}),
  mpSetPrice: (model, price) => fetch("/api/v1/admin/model-prices/price?model=" + encodeURIComponent(model), {
    method: "PUT", headers: { "Content-Type": "application/json" }, body: JSON.stringify(price),
  }).then(kbJson),
  mpSetForTasks: (model, forTasks) => fetch("/api/v1/admin/model-prices/tasks?model=" + encodeURIComponent(model), {
    method: "PUT", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ forTasks }),
  }).then(kbJson),
});

const mtEl = {
  panel: document.getElementById("mtPanel"),
  status: document.getElementById("mtStatus"),
  refresh: document.getElementById("mtRefresh"),
  rows: document.getElementById("mtRows"),
  empty: document.getElementById("mtEmpty"),
};
let mpEditing = null; // the model whose price row is open for editing

async function mpLoad() {
  try { mpRender(await api.mpOverview()); } catch (e) { showToast(e.message, true); }
}

const mpPrice = v => v == null ? "—" : "$" + v;

function mpRender(view) {
  mtEl.status.textContent = view.pricesError ? L("mp.pricesError", { msg: view.pricesError })
    : view.pricesReadAt ? L("mp.pricesRead", { when: formatDateTime(view.pricesReadAt) }) : L("mp.pricesBuiltIn");
  mtEl.status.classList.toggle("mt-none", !!view.pricesError);
  mtEl.empty.hidden = view.models.length > 0;
  const def = systemSettings.agentDefaultModel; // loaded with the app (settings.js)
  mtEl.rows.replaceChildren(...view.models.map(m => mpEditing === m.id ? mpEditRow(m) : mpRow(m, def)));
}

function mpRow(m, def) {
  const tr = document.createElement("tr");
  const name = document.createElement("td");
  const id = document.createElement("span");
  id.className = "mt-model";
  id.textContent = m.id;
  name.appendChild(id);
  if (m.id === def) {
    const tag = document.createElement("span");
    tag.className = "mt-use";
    tag.textContent = L("mp.default");
    name.appendChild(tag);
  }
  const cells = [m.input, m.output, m.cacheRead, m.cacheWrite].map(v => {
    const td = document.createElement("td");
    td.className = "num" + (m.input == null ? " mt-none" : "");
    td.textContent = m.input == null && v === m.input ? L("mp.noPrice") : mpPrice(v);
    return td;
  });
  const source = document.createElement("td");
  source.className = "kb-list__meta";
  source.textContent = m.manual ? L("mp.manual") : (m.source || "—") + (m.released ? " · " + m.released : "");
  const tasks = document.createElement("td");
  const toggle = document.createElement("input");
  toggle.type = "checkbox";
  toggle.checked = toggle.defaultChecked = m.forTasks;
  toggle.setAttribute("aria-label", L("mp.colTasks"));
  toggle.addEventListener("change", async () => {
    try { mpRender(await api.mpSetForTasks(m.id, toggle.checked)); } catch (e) { showToast(e.message, true); mpLoad(); }
  });
  tasks.appendChild(toggle);
  const act = document.createElement("td");
  const edit = document.createElement("button");
  edit.type = "button";
  edit.className = "link-btn";
  edit.textContent = L("mp.edit");
  edit.addEventListener("click", () => { mpEditing = m.id; mpLoad(); });
  act.appendChild(edit);
  tr.append(name, ...cells, source, tasks, act);
  return tr;
}

/** The row as inputs: input and output are needed, cache prices optional (else worked out from input). */
function mpEditRow(m) {
  const tr = document.createElement("tr");
  tr.className = "mp-editing";
  const name = document.createElement("td");
  name.textContent = m.id;
  const inputs = [m.input, m.output, m.cacheRead, m.cacheWrite].map(v => {
    const td = document.createElement("td");
    const i = document.createElement("input");
    i.type = "number";
    i.min = "0";
    i.step = "any";
    i.className = "mp-price-input";
    i.value = v == null ? "" : v;
    td.appendChild(i);
    return td;
  });
  const hint = document.createElement("td");
  hint.colSpan = 2;
  hint.className = "kb-list__meta";
  hint.textContent = L("mp.editHint");
  const act = document.createElement("td");
  const val = n => { const x = inputs[n].querySelector("input").value; return x === "" ? null : Number(x); };
  const save = document.createElement("button");
  save.type = "button";
  save.className = "btn btn--sm btn--primary";
  save.textContent = L("common.save");
  save.addEventListener("click", async () => {
    if (val(0) == null || val(1) == null) { showToast(L("mp.needBoth"), true); return; }
    try { mpEditing = null; mpRender(await api.mpSetPrice(m.id, { input: val(0), output: val(1), cacheRead: val(2), cacheWrite: val(3) })); }
    catch (e) { showToast(e.message, true); mpLoad(); }
  });
  const reset = document.createElement("button");
  reset.type = "button";
  reset.className = "link-btn";
  reset.textContent = L(m.manual ? "mp.reset" : "common.cancel");
  reset.addEventListener("click", async () => {
    mpEditing = null;
    if (!m.manual) { mpLoad(); return; }
    try { mpRender(await api.mpSetPrice(m.id, {})); } catch (e) { showToast(e.message, true); mpLoad(); }
  });
  act.append(save, reset);
  tr.append(name, ...inputs, hint, act);
  return tr;
}

mtEl.refresh.addEventListener("click", async () => {
  mtEl.refresh.disabled = true;
  try { mpRender(await api.mpRefresh()); } catch (e) { showToast(e.message, true); }
  finally { mtEl.refresh.disabled = false; }
});
