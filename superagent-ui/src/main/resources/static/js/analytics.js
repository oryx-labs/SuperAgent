// SuperAgent UI — Admin analytics: platform-wide figures, tasks per day, usage by model.
// Classic script sharing one global scope with the others; index.html loads them in order.
"use strict";

async function loadAnalytics() {
  const res = await fetch("/api/v1/admin/analytics?days=14").then(r => r.json()).catch(() => null);
  const a = res && res.data;
  if (!a) return;
  const pct = a.successRate == null ? "—" : Math.round(a.successRate * 100) + "%";
  const cards = [
    [a.users, L("an.users"), L("an.usersSub", { n: a.enabledUsers })],
    [a.activeUsers, L("an.active", { days: a.activeDays }), ""],
    [a.tasks, L("an.tasksTotal"), L("an.tasksSub", { done: a.tasksDone, failed: a.tasksFailed })],
    [pct, L("an.success"), ""],
    [a.agents, L("an.agents"), ""],
    [a.automations, L("an.automations"), L("an.enabledSub", { n: a.enabledAutomations })],
    ["$" + Number(a.spend || 0).toFixed(2), L("an.spendTotal"), ""],
  ];
  document.getElementById("anCards").replaceChildren(...cards.map(([value, label, sub]) => {
    const card = document.createElement("div");
    card.className = "stat-card";
    const v = document.createElement("span");
    v.className = "stat-card__value";
    v.textContent = value;
    const l = document.createElement("span");
    l.className = "stat-card__label";
    l.textContent = label;
    card.append(v, l);
    if (sub) {
      const s = document.createElement("span");
      s.className = "stat-card__sub";
      s.textContent = sub;
      card.appendChild(s);
    }
    return card;
  }));

  // Tasks per day: three thin bars per day (created / done / failed), scaled to the busiest day.
  const max = Math.max(1, ...a.daily.map(d => Math.max(d.created, d.done, d.failed)));
  document.getElementById("anChart").replaceChildren(...a.daily.map(d => {
    const col = document.createElement("div");
    col.className = "an-day";
    col.title = d.date + " · " + L("an.created") + " " + d.created + " · " + L("an.done") + " " + d.done
      + " · " + L("an.failed") + " " + d.failed;
    const bars = document.createElement("div");
    bars.className = "an-bars";
    [["is-created", d.created], ["is-done", d.done], ["is-failed", d.failed]].forEach(([cls, n]) => {
      const bar = document.createElement("i");
      bar.className = cls;
      bar.style.height = (n / max * 100) + "%";
      bars.appendChild(bar);
    });
    const label = document.createElement("span");
    label.className = "an-day__label";
    label.textContent = String(d.date).slice(5);
    col.append(bars, label);
    return col;
  }));

  document.getElementById("anModels").replaceChildren(...a.models.map(m => {
    const tr = document.createElement("tr");
    [m.model || L("an.modelDefault"), m.tasks, "$" + Number(m.spend || 0).toFixed(2)].forEach(v => {
      const td = document.createElement("td");
      td.textContent = v;
      tr.appendChild(td);
    });
    return tr;
  }));
}
