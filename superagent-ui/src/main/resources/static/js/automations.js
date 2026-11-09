// SuperAgent UI — Automations: schedules that start tasks; boots the app.
// Classic script sharing one global scope with the others; index.html loads them in order.
"use strict";

// ---------- automations ----------
Object.assign(api, {
  listAutomations: () => fetch("/api/v1/automations").then(r => r.json()),
  automationDetail: id => fetch("/api/v1/automations/" + id).then(r => r.json()),
  saveAutomation: (id, body) => fetch("/api/v1/automations" + (id ? "/" + id : ""), {
    method: id ? "PUT" : "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body),
  }).then(r => r.json()),
  previewAutomation: body => fetch("/api/v1/automations/preview", {
    method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body),
  }).then(r => r.json()),
  setAutomationEnabled: (id, on) => fetch("/api/v1/automations/" + id + "/enabled?value=" + on, { method: "POST" })
    .then(r => r.json()),
  runAutomation: id => fetch("/api/v1/automations/" + id + "/run", { method: "POST" }).then(r => r.json()),
  regenerateAutomationToken: id => fetch("/api/v1/automations/" + id + "/token", { method: "POST" })
    .then(r => r.json()),
  deleteAutomation: id => fetch("/api/v1/automations/" + id, { method: "DELETE" }).then(r => r.json()),
});

const ael2 = {
  listView: document.getElementById("autoListView"),
  detailView: document.getElementById("autoDetailView"),
  rows: document.getElementById("autoRows"),
  empty: document.getElementById("autoEmpty"),
  type: document.getElementById("autoTypeFilter"),
  search: document.getElementById("autoSearch"),
  owner: document.getElementById("autoOwnerFilter"),
  addBtn: document.getElementById("autoAddBtn"),
  back: document.getElementById("autoBackBtn"),
  dTitle: document.getElementById("autoDetailTitle"),
  dMeta: document.getElementById("autoDetailMeta"),
  dActions: document.getElementById("autoDetailActions"),
  facts: document.getElementById("autoFacts"),
  prompt: document.getElementById("autoPrompt"),
  runRows: document.getElementById("autoRunRows"),
  runsEmpty: document.getElementById("autoRunsEmpty"),
  tokenBox: document.getElementById("autoTokenBox"),
  tokenValue: document.getElementById("autoTokenValue"),
  tokenCurl: document.getElementById("autoTokenCurl"),
  drawer: document.getElementById("autoDrawer"),
  form: document.getElementById("autoForm"),
  formTitle: document.getElementById("autoFormTitle"),
  fPrompt: document.getElementById("afPrompt"),
  fName: document.getElementById("afAutoName"),
  fType: document.getElementById("afType"),
  fRecurring: document.getElementById("afRecurring"),
  fPreset: document.getElementById("afPreset"),
  fWeekday: document.getElementById("afWeekday"),
  fDay: document.getElementById("afDay"),
  fHour: document.getElementById("afHour"),
  fMinute: document.getElementById("afMinute"),
  fCron: document.getElementById("afCron"),
  fRunAt: document.getElementById("afRunAt"),
  fTimezone: document.getElementById("afTimezone"),
  fOverlap: document.getElementById("afOverlap"),
  fPreview: document.getElementById("afPreview"),
  fError: document.getElementById("afAutoError"),
  fSubmit: document.getElementById("afAutoSubmit"),
};
let automations = [];
let autoFilter = "all";
let autoDetailId = null;
let autoEditing = null; // the automation being edited, or null for a new one

const AUTO_TIMEZONES = ["Asia/Shanghai", "Asia/Hong_Kong", "Asia/Singapore", "Asia/Tokyo", "UTC",
  "Europe/London", "Europe/Berlin", "America/New_York", "America/Los_Angeles"];
const pad2 = n => String(n).padStart(2, "0");

function fillOptions(select, entries) {
  select.replaceChildren(...entries.map(([value, label]) => {
    const o = document.createElement("option");
    o.value = value;
    o.textContent = label;
    return o;
  }));
}
fillOptions(ael2.fMinute, Array.from({ length: 12 }, (_, i) => [String(i * 5), pad2(i * 5)]));
fillOptions(ael2.fHour, Array.from({ length: 24 }, (_, i) => [String(i), pad2(i) + ":00"]));
fillOptions(ael2.fDay, Array.from({ length: 28 }, (_, i) => [String(i + 1), String(i + 1)]));
fillOptions(ael2.fWeekday, [1, 2, 3, 4, 5, 6, 0].map(d => [String(d), L("auto.wd" + d)]));
(() => {
  let zones = AUTO_TIMEZONES.slice();
  try {
    const local = Intl.DateTimeFormat().resolvedOptions().timeZone;
    if (local && !zones.includes(local)) zones = [local].concat(zones);
  } catch (e) { /* keep the list */ }
  fillOptions(ael2.fTimezone, zones.map(z => [z, z]));
})();

// --- schedule <-> preset (the form's friendly controls are just a cron builder) ---
function presetToCron() {
  const m = ael2.fMinute.value, h = ael2.fHour.value;
  switch (ael2.fPreset.value) {
    case "hourly": return m + " * * * *";
    case "daily": return m + " " + h + " * * *";
    case "weekdays": return m + " " + h + " * * 1-5";
    case "weekly": return m + " " + h + " * * " + ael2.fWeekday.value;
    case "monthly": return m + " " + h + " " + ael2.fDay.value + " * *";
    default: return ael2.fCron.value.trim();
  }
}

// Recognize the crons the presets produce; anything else is shown as custom.
function cronToPreset(cron) {
  const f = (cron || "").trim().split(/\s+/);
  const num = v => /^\d+$/.test(v);
  const fiveMin = v => num(v) && Number(v) % 5 === 0 && Number(v) < 60;
  if (f.length === 5 && fiveMin(f[0])) {
    const [m, h, dom, mon, dow] = f;
    if (h === "*" && dom === "*" && mon === "*" && dow === "*") return { preset: "hourly", m };
    if (num(h) && mon === "*") {
      if (dom === "*" && dow === "*") return { preset: "daily", m, h };
      if (dom === "*" && dow === "1-5") return { preset: "weekdays", m, h };
      if (dom === "*" && /^[0-6]$/.test(dow)) return { preset: "weekly", m, h, wd: dow };
      if (num(dom) && Number(dom) <= 28 && dow === "*") return { preset: "monthly", m, h, d: dom };
    }
  }
  return { preset: "custom" };
}

function describeSchedule(a) {
  if (a.type === "api") return L("auto.typeApi");
  if (a.type === "once") return L("auto.descOnce", { time: formatDateTime(a.runAt) });
  const p = cronToPreset(a.cron);
  const hm = (p.h != null ? pad2(p.h) : "") + ":" + pad2(p.m);
  switch (p.preset) {
    case "hourly": return L("auto.descHourly", { m: pad2(p.m) });
    case "daily": return L("auto.descDaily", { time: hm });
    case "weekdays": return L("auto.descWeekdays", { time: hm });
    case "weekly": return L("auto.descWeekly", { wd: L("auto.wd" + p.wd), time: hm });
    case "monthly": return L("auto.descMonthly", { d: p.d, time: hm });
    default: return "cron " + a.cron;
  }
}

// --- list ---
function showAutoList() {
  autoDetailId = null;
  ael2.listView.hidden = false;
  ael2.detailView.hidden = true;
}

async function loadAutomations() {
  try { const res = await api.listAutomations(); automations = (res && res.data) || []; } catch (e) { automations = []; }
  renderAutomations();
}

function autoMatches(a, filter) {
  switch (filter) {
    case "active": return a.enabled;
    case "once": case "recurring": case "api": return a.type === filter;
    default: return true;
  }
}

function renderAutomations() {
  const mine = ael2.owner.value === "mine";
  const q = ael2.search.value.trim().toLowerCase();
  const scoped = automations.filter(a => !mine || a.owner === currentUserId);
  ael2.type.value = autoFilter;
  // API triggers aren't offered yet; the choice shows only for ones that already exist.
  document.getElementById("autoTypeApi").hidden = !scoped.some(a => a.type === "api");
  const shown = scoped.filter(a => autoMatches(a, autoFilter) && (!q || a.name.toLowerCase().includes(q)));
  ael2.rows.replaceChildren(...shown.map(autoRow));
  ael2.empty.hidden = shown.length > 0;
}

const canEditAuto = a => isAdmin || a.owner === currentUserId;

function autoRow(a) {
  const tr = document.createElement("tr");

  const tdName = document.createElement("td");
  tdName.className = "c-name";
  tdName.appendChild(linkBtn(a.name, () => openAutomation(a.id)));

  const tdSched = document.createElement("td");
  tdSched.className = "auto-cell";
  tdSched.textContent = describeSchedule(a);
  if (a.type === "recurring") {
    const c = document.createElement("small");
    c.className = "auto-cron";
    c.textContent = a.cron + " · " + a.timezone;
    tdSched.appendChild(c);
  }

  const tdModel = document.createElement("td");
  tdModel.textContent = autoModelLabel(a);
  const tdSource = document.createElement("td");
  tdSource.textContent = L(a.source === "chat" ? "auto.sourceChat" : "auto.sourceManual");

  const tdLast = document.createElement("td");
  if (a.lastRunAt) {
    tdLast.appendChild(document.createTextNode(formatDateTime(a.lastRunAt) + " "));
    if (a.lastStatus) tdLast.appendChild(taskStatusBadge(a.lastStatus));
  } else {
    tdLast.textContent = "—";
  }
  const tdNext = document.createElement("td");
  tdNext.textContent = a.nextRunAt ? formatDateTime(a.nextRunAt) : "—";
  const tdOwner = document.createElement("td");
  tdOwner.textContent = personName(a.owner) || "—";

  const tdOn = document.createElement("td");
  tdOn.appendChild(enabledSwitch(a));

  const tdActions = document.createElement("td");
  tdActions.className = "ctable__actions";
  if (canEditAuto(a)) {
    tdActions.append(
      linkBtn(L("auto.runNow"), () => runAutomationNow(a)),
      linkBtn(L("common.edit"), () => openAutoForm(a)),
      linkBtn(L("common.delete"), () => deleteAutomation(a), "link-btn--danger"));
  }
  tr.append(tdName, tdSched, tdModel, tdSource, tdLast, tdNext, tdOwner, tdOn, tdActions);
  return tr;
}

function taskStatusBadge(status) {
  const b = document.createElement("span");
  b.className = "status-badge is-" + status;
  b.textContent = status === "skipped" ? L("auto.skipped")
    : status === "deleted" ? L("auto.taskDeleted") : (TASK_STATUS_LABEL[status] || status);
  return b;
}

function enabledSwitch(a) {
  const label = document.createElement("label");
  label.className = "auto-switch";
  label.title = L(a.enabled ? "auto.pause" : "auto.resume");
  const input = document.createElement("input");
  input.type = "checkbox";
  input.checked = a.enabled;
  input.disabled = !canEditAuto(a);
  input.addEventListener("change", async () => {
    const res = await api.setAutomationEnabled(a.id, input.checked).catch(() => null);
    if (!res || !res.data) {
      input.checked = !input.checked;
      showToast((res && res.message) || L("auto.actionFailed"), true);
      return;
    }
    await refreshAutomationViews();
  });
  label.append(input, document.createElement("span"));
  return label;
}

async function refreshAutomationViews() {
  if (autoDetailId) await openAutomation(autoDetailId);
  await loadAutomations();
}

async function runAutomationNow(a) {
  const res = await api.runAutomation(a.id).catch(() => null);
  const run = res && res.data;
  if (!run) { showToast((res && res.message) || L("auto.actionFailed"), true); return; }
  showToast(run.taskId ? L("auto.started") : L("auto.notStarted", { why: run.skippedReason || "" }), !run.taskId);
  await refreshAutomationViews();
}

async function deleteAutomation(a) {
  if (!window.confirm(L("auto.deleteConfirm", { name: a.name }))) return;
  const res = await api.deleteAutomation(a.id).catch(() => null);
  if (!res || res.code !== "ok") { showToast((res && res.message) || L("auto.actionFailed"), true); return; }
  showAutoList();
  await loadAutomations();
}

ael2.type.addEventListener("change", () => { autoFilter = ael2.type.value; renderAutomations(); });
ael2.search.addEventListener("input", renderAutomations);
ael2.owner.addEventListener("change", renderAutomations);
ael2.back.addEventListener("click", () => { showAutoList(); loadAutomations(); });

// --- detail ---
async function openAutomation(id, freshToken) {
  const res = await api.automationDetail(id).catch(() => null);
  const d = res && res.data;
  if (!d) { showAutoList(); return; }
  autoDetailId = id;
  const a = d.automation;
  ael2.listView.hidden = true;
  ael2.detailView.hidden = false;
  ael2.dTitle.textContent = a.name;
  ael2.dMeta.textContent = [L(a.source === "chat" ? "auto.sourceChat" : "auto.sourceManual"), personName(a.owner),
    L(a.enabled ? "auto.enabled" : "auto.paused")].filter(Boolean).join(" · ");

  ael2.dActions.replaceChildren();
  ael2.dActions.appendChild(enabledSwitch(a));
  if (canEditAuto(a)) {
    const run = document.createElement("button");
    run.type = "button";
    run.className = "btn";
    run.textContent = L("auto.runNow");
    run.addEventListener("click", () => runAutomationNow(a));
    const edit = document.createElement("button");
    edit.type = "button";
    edit.className = "btn";
    edit.textContent = L("common.edit");
    edit.addEventListener("click", () => openAutoForm(a));
    ael2.dActions.append(run, edit);
    if (a.type === "api") {
      const regen = document.createElement("button");
      regen.type = "button";
      regen.className = "btn";
      regen.textContent = L("auto.regenToken");
      regen.addEventListener("click", async () => {
        if (!window.confirm(L("auto.regenConfirm"))) return;
        const r = await api.regenerateAutomationToken(a.id).catch(() => null);
        if (!r || !r.data) { showToast((r && r.message) || L("auto.actionFailed"), true); return; }
        openAutomation(a.id, r.data.apiToken);
      });
      ael2.dActions.appendChild(regen);
    }
  }

  // A token is only ever shown right after it is minted.
  ael2.tokenBox.hidden = !freshToken;
  if (freshToken) {
    ael2.tokenValue.textContent = freshToken;
    ael2.tokenCurl.textContent = "curl -X POST " + window.location.origin + a.triggerPath + " \\\n"
      + "  -H 'X-Automation-Token: " + freshToken + "' \\\n"
      + "  -H 'Content-Type: application/json' \\\n"
      + "  -d '{\"input\": \"" + L("auto.curlInput") + "\"}'";
  }

  const facts = [
    [L("auto.type"), L(a.type === "recurring" ? "auto.typeRecurringLong" : a.type === "once" ? "auto.typeOnceLong" : "auto.typeApi")],
    [L("auto.colSchedule"), describeSchedule(a) + (a.type === "recurring" ? "  (" + a.cron + ")" : "")],
    [L("auto.timezone"), a.timezone],
    [L("auto.colModel"), autoModelLabel(a)],
    [L("auto.overlap"), L(a.overlap === "parallel" ? "auto.overlapParallel" : "auto.overlapSkip")],
    [L("auto.upcoming"), d.upcoming.length ? d.upcoming.map(formatDateTime).join("  ·  ") : "—"],
  ];
  if (a.type === "api") facts.push([L("auto.endpoint"), "POST " + a.triggerPath]);
  ael2.facts.replaceChildren(...facts.flatMap(([k, v]) => {
    const dt = document.createElement("dt");
    dt.textContent = k;
    const dd = document.createElement("dd");
    dd.textContent = v;
    return [dt, dd];
  }));
  ael2.prompt.textContent = a.prompt;

  ael2.runRows.replaceChildren(...d.runs.map(r => {
    const tr = document.createElement("tr");
    const tdAt = document.createElement("td");
    tdAt.textContent = formatDateTime(r.firedAt);
    const tdTrig = document.createElement("td");
    tdTrig.textContent = L("auto.trigger." + r.trigger);
    const tdTask = document.createElement("td");
    if (r.taskId) {
      tdTask.appendChild(linkBtn(L("auto.openTask"), async () => { setView("tasks"); await openTask(r.taskId); }));
    } else {
      tdTask.textContent = r.skippedReason || "—";
    }
    const tdStatus = document.createElement("td");
    tdStatus.appendChild(taskStatusBadge(r.status));
    tr.append(tdAt, tdTrig, tdTask, tdStatus);
    return tr;
  }));
  ael2.runsEmpty.hidden = d.runs.length > 0;
}

// --- form (drawer) ---
// No model to pick: each run's task gets one chosen for the job.
const autoModelLabel = () => L("auto.modelAuto");

function syncAutoForm() {
  const type = ael2.fType.value;
  const preset = ael2.fPreset.value;
  ael2.fRecurring.hidden = type !== "recurring";
  document.getElementById("afRunAtField").hidden = type !== "once";
  document.getElementById("afTimezoneField").hidden = type === "api";
  document.getElementById("afWeekdayField").hidden = preset !== "weekly";
  document.getElementById("afDayField").hidden = preset !== "monthly";
  document.getElementById("afHourField").hidden = preset === "hourly" || preset === "custom";
  document.getElementById("afMinuteField").hidden = preset === "custom";
  document.getElementById("afCronField").hidden = preset !== "custom";
  schedulePreview();
}

function autoFormBody() {
  const type = ael2.fType.value;
  return {
    name: ael2.fName.value.trim(),
    prompt: ael2.fPrompt.value,
    type,
    cron: type === "recurring" ? presetToCron() : null,
    runAt: type === "once" ? ael2.fRunAt.value || null : null,
    timezone: ael2.fTimezone.value,
    overlap: ael2.fOverlap.value,
  };
}

// Server-side preview: the same validation and time-zone math the scheduler uses.
let previewTimer = null;
function schedulePreview() {
  clearTimeout(previewTimer);
  previewTimer = setTimeout(async () => {
    const body = autoFormBody();
    if (body.type === "api") {
      ael2.fPreview.className = "field-hint auto-preview";
      ael2.fPreview.textContent = L("auto.apiHint");
      return;
    }
    if ((body.type === "once" && !body.runAt) || (body.type === "recurring" && !body.cron)) {
      ael2.fPreview.textContent = "";
      return;
    }
    const res = await api.previewAutomation(body).catch(() => null);
    const ok = res && Array.isArray(res.data);
    ael2.fPreview.className = "field-hint auto-preview" + (ok ? "" : " is-error");
    ael2.fPreview.textContent = ok
      ? (res.data.length ? L("auto.nextRuns", { times: res.data.map(formatDateTime).join("  ·  ") }) : "")
      : ((res && res.message) || L("auto.actionFailed"));
  }, 250);
}

function toLocalInput(iso, timezone) {
  if (!iso) return "";
  try {
    // en-CA formats as YYYY-MM-DD, HH:MM — close to what datetime-local wants.
    const parts = new Intl.DateTimeFormat("en-CA", {
      timeZone: timezone, year: "numeric", month: "2-digit", day: "2-digit",
      hour: "2-digit", minute: "2-digit", hourCycle: "h23",
    }).formatToParts(new Date(iso)).reduce((o, p) => (o[p.type] = p.value, o), {});
    return parts.year + "-" + parts.month + "-" + parts.day + "T" + parts.hour + ":" + parts.minute;
  } catch (e) { return ""; }
}

async function openAutoForm(a) {
  autoEditing = a || null;
  ael2.formTitle.textContent = L(a ? "auto.formEdit" : "auto.formNew");
  ael2.fError.hidden = true;
  ael2.fPrompt.value = a ? a.prompt : "";
  ael2.fName.value = a ? a.name : "";
  document.getElementById("afTypeApi").hidden = !(a && a.type === "api");
  ael2.fType.value = a ? a.type : "recurring";
  ael2.fOverlap.value = a ? a.overlap : "skip";
  if (a && a.timezone) {
    if (![...ael2.fTimezone.options].some(o => o.value === a.timezone)) {
      const o = document.createElement("option");
      o.value = o.textContent = a.timezone;
      ael2.fTimezone.appendChild(o);
    }
    ael2.fTimezone.value = a.timezone;
  } else {
    ael2.fTimezone.value = ael2.fTimezone.options.length && [...ael2.fTimezone.options].some(o => o.value === "Asia/Shanghai")
      ? "Asia/Shanghai" : ael2.fTimezone.options[0].value;
  }
  const p = a && a.type === "recurring" ? cronToPreset(a.cron) : { preset: "daily", m: "0", h: "9" };
  ael2.fPreset.value = p.preset;
  ael2.fMinute.value = String(Number(p.m || 0));
  ael2.fHour.value = String(Number(p.h || 9));
  ael2.fWeekday.value = p.wd || "1";
  ael2.fDay.value = p.d || "1";
  ael2.fCron.value = a && a.type === "recurring" ? a.cron : "";
  ael2.fRunAt.value = a && a.type === "once" ? toLocalInput(a.runAt, a.timezone) : "";
  ael2.drawer.hidden = false;
  syncAutoForm();
  ael2.fPrompt.focus();
}

function closeAutoForm() { ael2.drawer.hidden = true; clearTimeout(previewTimer); }

ael2.addBtn.addEventListener("click", () => openAutoForm(null));
ael2.drawer.querySelectorAll("[data-close]").forEach(x => x.addEventListener("click", closeAutoForm));
[ael2.fType, ael2.fPreset].forEach(x => x.addEventListener("change", syncAutoForm));
[ael2.fWeekday, ael2.fDay, ael2.fHour, ael2.fMinute, ael2.fTimezone, ael2.fRunAt]
  .forEach(x => x.addEventListener("change", schedulePreview));
ael2.fCron.addEventListener("input", schedulePreview);

ael2.form.addEventListener("submit", async e => {
  e.preventDefault();
  const body = autoFormBody();
  if (!body.prompt.trim()) {
    ael2.fError.textContent = L("auto.promptRequired");
    ael2.fError.hidden = false;
    return;
  }
  // Untouched one-off time on edit: leave it out so the server keeps it (and doesn't re-check "in the past").
  if (autoEditing && body.type === "once" && autoEditing.type === "once"
      && body.runAt === toLocalInput(autoEditing.runAt, autoEditing.timezone)
      && body.timezone === autoEditing.timezone) {
    body.runAt = null;
  }
  ael2.fSubmit.disabled = true;
  const res = await api.saveAutomation(autoEditing && autoEditing.id, body).catch(() => null);
  ael2.fSubmit.disabled = false;
  if (!res || !res.data) {
    ael2.fError.textContent = (res && res.message) || L("auto.actionFailed");
    ael2.fError.hidden = false;
    return;
  }
  closeAutoForm();
  await openAutomation(res.data.id, res.data.apiToken);
  loadAutomations();
});

// Guard first, then load the app only when authenticated. The OAuth-return handler
// above may already have forced #connectors; otherwise restore the view from the hash
// (now that admin visibility is known) so a refresh stays on the same page.
initAuth().then(async ok => {
  if (!ok) return;
  await loadSystemSettings();  // before agents, so the agent panel shows the effective model
  loadAgents(true);
  setView(initialView());
  if (todoPane.hidden) api.listNotices().then(list => { notices = list || []; updateTodoBadge(); }).catch(() => {});
});

// Tasks | Scheduled tasks: two tabs of one page, each its own view (and URL hash).
