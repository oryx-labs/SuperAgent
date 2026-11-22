// SuperAgent UI — "Confirm for me": the user's rules (global on the to-do page, per project on the project page)
// and the admin's read-only audit list. Classic script sharing one global scope; loaded after todo.js.
"use strict";

Object.assign(api, {
  proxyRule: projectId => fetch("/api/v1/proxy-confirm/rule" + (projectId ? "?projectId=" + encodeURIComponent(projectId) : "")).then(kbJson),
  proxyRuleSave: (projectId, body) => fetch("/api/v1/proxy-confirm/rule" + (projectId ? "?projectId=" + encodeURIComponent(projectId) : ""), {
    method: "PUT", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body),
  }).then(kbJson),
  proxyRuleInherit: projectId => fetch("/api/v1/proxy-confirm/rule?projectId=" + encodeURIComponent(projectId), { method: "DELETE" }).then(kbJson),
  proxyRevoke: id => kbPost("/api/v1/proxy-confirm/records/" + id + "/revoke", {}),
  proxyAudit: q => fetch("/api/v1/admin/proxy-confirm?" + new URLSearchParams(q)).then(kbJson),
});

/** Fills {@code box} with the rule for {@code projectId} (empty: the user's own global rule). */
async function renderProxyConfirmPanel(box, projectId) {
  box.replaceChildren();
  let rule;
  try { rule = await api.proxyRule(projectId); } catch (e) { box.textContent = e.message; return; }
  const save = async () => {
    // The switch carries the ticks as shown (tier 1 starts ticked); the ticks are kept while it is off.
    const types = [...box.querySelectorAll("input[data-type]:checked")].map(i => i.dataset.type);
    try {
      await api.proxyRuleSave(projectId, { enabled: master.checked, types });
      await renderProxyConfirmPanel(box, projectId);
    } catch (e) { showToast(e.message, true); }
  };
  const master = document.createElement("input");
  master.type = "checkbox";
  master.checked = rule.enabled;
  master.addEventListener("change", save);
  const head = document.createElement("label");
  head.className = "field field--inline";
  const title = document.createElement("span");
  title.textContent = L(projectId ? "pc.projectOn" : "pc.on");
  head.append(master, title);
  box.append(head);
  if (projectId) {
    const note = document.createElement("p");
    note.className = "settings-form__hint";
    note.textContent = rule.inherited ? L("pc.inherited") : L("pc.own");
    box.append(note);
    if (!rule.inherited) {
      const back = document.createElement("button");
      back.type = "button";
      back.className = "link-btn";
      back.textContent = L("pc.inherit");
      back.addEventListener("click", async () => {
        try { await api.proxyRuleInherit(projectId); await renderProxyConfirmPanel(box, projectId); } catch (e) { showToast(e.message, true); }
      });
      box.append(back);
    }
  }
  [1, 2, 3].forEach(tier => {
    const group = document.createElement("div");
    group.className = "proxy-tier proxy-tier--" + tier;
    const h = document.createElement("h4");
    h.textContent = L("pc.tier" + tier);
    group.append(h);
    rule.types.filter(t => t.tier === tier).forEach(t => {
      const row = document.createElement("label");
      row.className = "field field--inline";
      const cb = document.createElement("input");
      cb.type = "checkbox";
      cb.dataset.type = t.type;
      cb.checked = t.enabled;
      cb.disabled = tier === 3;
      cb.addEventListener("change", save);
      const text = document.createElement("span");
      text.textContent = L("pc.type." + t.type) + (tier === 2 ? " · " + L(t.revocable ? "pc.revocable" : "pc.irrevocable") : "");
      row.append(cb, text);
      group.append(row);
    });
    if (tier === 3) {
      const why = document.createElement("p");
      why.className = "settings-form__hint";
      why.textContent = L("pc.tier3Why");
      group.append(why);
    }
    box.append(group);
  });
}

/** The admin's read-only list of everything SuperAgent confirmed in the company. */
async function renderProxyAudit(box) {
  box.replaceChildren();
  const user = document.createElement("input");
  user.type = "text";
  user.placeholder = L("pc.auditUser");
  const type = document.createElement("select");
  type.append(new Option(L("pc.auditAllTypes"), ""));
  ["TASK_PARAMS", "CODING_SPEC", "WRITING_OUTLINE", "MERGE_LOCAL", "CONTINUATION", "SUBTASK_SPLIT"].forEach(k => type.append(new Option(L("pc.type." + k), k)));
  const from = document.createElement("input");
  from.type = "date";
  const to = document.createElement("input");
  to.type = "date";
  const go = document.createElement("button");
  go.type = "button";
  go.className = "link-btn";
  go.textContent = L("pc.auditSearch");
  const table = document.createElement("table");
  table.className = "ctable";
  const load = async () => {
    const q = {};
    if (user.value.trim()) q.user = user.value.trim();
    if (type.value) q.type = type.value;
    if (from.value) q.from = new Date(from.value + "T00:00:00").toISOString();
    if (to.value) q.to = new Date(new Date(to.value + "T00:00:00").getTime() + 86400000).toISOString(); // the day itself is included
    let rows = [];
    try { rows = await api.proxyAudit(q); } catch (e) { showToast(e.message, true); }
    table.replaceChildren(...rows.map(r => {
      const tr = document.createElement("tr");
      [r.owner, L("pc.type." + r.type) + " (T" + r.tier + ")", r.objectType + " " + r.objectId, r.reason,
        L(r.status === "revoked" ? "pc.revoked" : "pc.confirmed"), new Date(r.at).toLocaleString()].forEach(v => {
        const td = document.createElement("td");
        td.textContent = v;
        tr.append(td);
      });
      return tr;
    }));
  };
  go.addEventListener("click", load);
  box.append(user, type, from, to, go, table);
  await load();
}

(function bootProxyConfirm() {
  const audit = document.getElementById("proxyAuditPanel");
  if (audit) audit.addEventListener("toggle", () => { if (audit.open) renderProxyAudit(document.getElementById("proxyAuditBody")); });
})();
