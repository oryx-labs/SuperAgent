// SuperAgent UI — Projects: a set of agents and the people who use them. Members can use every agent in a project and see
// every task and file made in it; only the owner names it, adds or removes people and archives it.
// Classic script sharing one global scope with the others; index.html loads them in order.
"use strict";

Object.assign(api, {
  listProjects: () => fetch("/api/v1/projects").then(kbJson),
  getProject: id => fetch("/api/v1/projects/" + id).then(kbJson),
  createProject: body => kbPost("/api/v1/projects", body),
  updateProject: (id, body) => fetch("/api/v1/projects/" + id, {
    method: "PUT", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body),
  }).then(kbJson),
  archiveProject: id => kbPost("/api/v1/projects/" + id + "/archive", {}),
  reopenProject: id => kbPost("/api/v1/projects/" + id + "/reopen", {}),
  addProjectMembers: (id, usernames) => kbPost("/api/v1/projects/" + id + "/members", { usernames }),
  removeProjectMember: (id, username) => fetch("/api/v1/projects/" + id + "/members/" + encodeURIComponent(username),
    { method: "DELETE" }).then(kbJson),
  setAgentProject: (agentId, projectId) => fetch("/api/v1/agents/" + agentId + "/project", {
    method: "PUT", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ projectId }),
  }).then(kbJson),
  projectTasks: id => fetch("/api/v1/tasks?page=1&size=100&project=" + encodeURIComponent(id)).then(kbJson),
  projectFiles: id => fetch("/api/v1/files?project=" + encodeURIComponent(id)).then(kbJson),
});

const projPane = document.querySelector('[data-pane="projects"]');
const pjel = {
  listView: document.getElementById("projListView"),
  detailView: document.getElementById("projDetailView"),
  tabs: document.getElementById("projTabs"),
  grid: document.getElementById("projGrid"),
  empty: document.getElementById("projEmpty"),
  newBtn: document.getElementById("projNewBtn"),
  back: document.getElementById("projBack"),
  name: document.getElementById("projName"),
  state: document.getElementById("projState"),
  desc: document.getElementById("projDesc"),
  editBtn: document.getElementById("projEditBtn"),
  archiveBtn: document.getElementById("projArchiveBtn"),
  inviteBtn: document.getElementById("projInviteBtn"),
  addAgentBtn: document.getElementById("projAddAgentBtn"),
  formArchive: document.getElementById("projFormArchive"),
  detailTabs: document.getElementById("projDetailTabs"),
  rows: document.getElementById("projRows"),
  rowsEmpty: document.getElementById("projRowsEmpty"),
  modal: document.getElementById("projModal"),
  form: document.getElementById("projForm"),
  modalTitle: document.getElementById("projModalTitle"),
  formName: document.getElementById("projFormName"),
  formDesc: document.getElementById("projFormDesc"),
  formError: document.getElementById("projFormError"),
  pick: document.getElementById("projPickModal"),
  pickForm: document.getElementById("projPickForm"),
  pickTitle: document.getElementById("projPickTitle"),
  pickHint: document.getElementById("projPickHint"),
  pickSearch: document.getElementById("projPickSearch"),
  pickList: document.getElementById("projPickList"),
  pickError: document.getElementById("projPickError"),
};

/** The user's projects, kept for the agent sidebar and the task filter as well as this page. */
let projectList = [];
let projState = "active";
let projOpen = null;      // the project shown in the detail view
let projTab = "agents";
let projEditing = null;   // null = creating
let projPickItems = [];   // [{ id, label, sub }]
let projPickAction = null;
const projPicked = new Set();

/** Reloads the user's projects; the sidebar groups agents by them. */
async function refreshProjects() {
  try { projectList = (await api.listProjects()) || []; } catch (e) { projectList = []; }
  return projectList;
}

/** The project an agent is in, among the user's projects (null when none). */
function projectOfAgentId(agentId) {
  return projectList.find(p => (p.agentIds || []).includes(agentId)) || null;
}

async function loadProjects() {
  await refreshProjects();
  if (projOpen) {
    const fresh = projectList.find(p => p.id === projOpen.id);
    if (fresh) { openProject(fresh); return; }
    projOpen = null;
  }
  showProjectList();
}

function showProjectList() {
  projOpen = null;
  pjel.detailView.hidden = true;
  pjel.listView.hidden = false;
  pjel.tabs.querySelectorAll(".model-tab").forEach(t => {
    const s = t.dataset.state;
    t.classList.toggle("is-active", s === projState);
    t.querySelector("b").textContent = projectList.filter(p => p.status === s).length || "";
  });
  const shown = projectList.filter(p => p.status === projState);
  pjel.grid.replaceChildren(...shown.map(projectCard));
  pjel.empty.hidden = shown.length > 0;
  pjel.empty.textContent = L(projState === "archived" ? "proj.emptyArchived" : "proj.empty");
}

function projectCard(p) {
  const card = document.createElement("button");
  card.type = "button";
  card.className = "proj-card" + (p.status === "archived" ? " is-archived" : "");
  const title = document.createElement("div");
  title.className = "proj-card__title";
  title.textContent = p.name;
  const desc = document.createElement("div");
  desc.className = "proj-card__desc";
  desc.textContent = p.description || "";
  const meta = document.createElement("div");
  meta.className = "proj-card__meta";
  meta.textContent = projectCounts(p);
  const owner = document.createElement("div");
  owner.className = "proj-card__owner";
  owner.textContent = p.mine ? L("proj.youOwn") : L("proj.ownedBy", { name: p.ownerName });
  card.append(title, desc, meta, owner);
  if (p.mine) {
    const edit = document.createElement("span");
    edit.className = "proj-card__edit";
    edit.setAttribute("role", "button");
    edit.tabIndex = 0;
    edit.textContent = L("common.edit");
    const go = e => { e.stopPropagation(); openProjectModal(p); };
    edit.addEventListener("click", go);
    edit.addEventListener("keydown", e => { if (e.key === "Enter") go(e); });
    card.appendChild(edit);
  }
  card.addEventListener("click", () => openProject(p));
  return card;
}

const projectCounts = p => L("proj.counts", {
  agents: (p.agentIds || []).length, tasks: p.taskCount, files: p.fileCount, people: (p.members || []).length,
});

pjel.tabs.addEventListener("click", e => {
  const t = e.target.closest(".model-tab");
  if (t) { projState = t.dataset.state; showProjectList(); }
});

// ---------- one project ----------

function openProject(p) {
  projOpen = p;
  pjel.listView.hidden = true;
  pjel.detailView.hidden = false;
  pjel.name.textContent = p.name;
  const archived = p.status === "archived";
  pjel.state.textContent = L(archived ? "proj.archived" : "proj.active");
  pjel.state.classList.toggle("is-archived", archived);
  pjel.desc.textContent = p.description || "";
  pjel.desc.hidden = !p.description;
  pjel.editBtn.hidden = !p.mine;
  pjel.inviteBtn.hidden = !p.mine || archived;
  pjel.addAgentBtn.hidden = archived; // members put their own agents in too
  pjel.archiveBtn.hidden = !p.mine || !archived; // archiving is in the edit dialog; reopening is right here
  const counts = { agents: (p.agentIds || []).length, tasks: p.taskCount, files: p.fileCount, members: (p.members || []).length };
  pjel.detailTabs.querySelectorAll(".model-tab").forEach(t => {
    t.classList.toggle("is-active", t.dataset.tab === projTab);
    t.querySelector("b").textContent = counts[t.dataset.tab] || "";
  });
  renderProjectTab();
}

pjel.back.addEventListener("click", showProjectList);
pjel.detailTabs.addEventListener("click", e => {
  const t = e.target.closest(".model-tab");
  if (!t || !projOpen) return;
  projTab = t.dataset.tab;
  pjel.detailTabs.querySelectorAll(".model-tab").forEach(x => x.classList.toggle("is-active", x === t));
  renderProjectTab();
});

async function renderProjectTab() {
  const p = projOpen;
  pjel.rows.replaceChildren();
  pjel.rowsEmpty.hidden = true;
  let rows = [];
  if (projTab === "agents") {
    const all = await api.listAgents().then(r => (r && r.data) || []).catch(() => []);
    rows = all.filter(a => (p.agentIds || []).includes(a.id)).map(projectAgentRow);
    pjel.rowsEmpty.textContent = L("proj.noAgents");
  } else if (projTab === "tasks") {
    const page = await api.projectTasks(p.id).catch(e => { showToast(e.message, true); return null; });
    rows = ((page && page.items) || []).map(projectTaskRow);
    pjel.rowsEmpty.textContent = L("proj.noTasks");
  } else if (projTab === "files") {
    const files = await api.projectFiles(p.id).catch(e => { showToast(e.message, true); return []; });
    rows = (files || []).map(projectFileRow);
    pjel.rowsEmpty.textContent = L("proj.noFiles");
  } else {
    rows = (p.members || []).map(projectMemberRow);
  }
  if (projOpen !== p) return; // switched away while loading
  pjel.rows.replaceChildren(...rows);
  pjel.rowsEmpty.hidden = rows.length > 0;
}

/** A table row: a main cell (title + small line), a meta cell, and actions. */
function projRow(title, sub, meta, actions, onOpen) {
  const tr = document.createElement("tr");
  const main = document.createElement("td");
  const t = document.createElement("div");
  t.className = "proj-row__title";
  t.append(...(Array.isArray(title) ? title : [title]));
  main.appendChild(t);
  if (sub) {
    const s = document.createElement("div");
    s.className = "proj-row__sub";
    s.textContent = sub;
    main.appendChild(s);
  }
  const m = document.createElement("td");
  m.className = "proj-row__meta";
  m.append(...(Array.isArray(meta) ? meta : [meta || ""]));
  const act = document.createElement("td");
  act.className = "ctable__actions-col proj-row__actions";
  (actions || []).forEach(([label, fn, cls]) => {
    const b = document.createElement("button");
    b.type = "button";
    b.className = "link-btn" + (cls ? " " + cls : "");
    b.textContent = label;
    b.addEventListener("click", e => { e.stopPropagation(); fn(); });
    act.appendChild(b);
  });
  tr.append(main, m, act);
  if (onOpen) {
    tr.classList.add("is-link");
    tr.addEventListener("click", onOpen);
  }
  return tr;
}

function projectAgentRow(a) {
  const mineAgent = isOwner(a);
  const actions = [[L("proj.openChat"), () => openAgentFromProject(a)]];
  if (mineAgent) actions.push([L("proj.removeAgent"), () => moveAgent(a.id, null), "link-btn--muted"]);
  return projRow([(a.avatar || "🙂") + " " + a.name], a.persona ? a.persona.split("\n")[0] : "",
    mineAgent ? L("proj.yourAgent") : L("proj.ownedBy", { name: agentOwnerName(a) }), actions,
    () => openAgentFromProject(a));
}

const agentOwnerName = a => ((a.members || []).find(m => m.username === a.owner) || {}).displayName || personName(a.owner);

async function openAgentFromProject(a) {
  setView("chat");
  await loadAgents();
  const fresh = agents.find(x => x.id === a.id);
  if (fresh) selectAgent(fresh);
}

function projectTaskRow(t) {
  return projRow(t.title, [t.agentName, personName(t.createdBy)].filter(Boolean).join(" · "),
    [statusBadge(t.status)], [], () => { setView("tasks"); openTask(t.id); });
}

function projectFileRow(f) {
  return projRow(fileIcon(f) + " " + f.name, [f.agentName, f.ownerName].filter(Boolean).join(" · "),
    formatDateTime(f.updatedAt), [], () => openFile(f));
}

function projectMemberRow(m) {
  const actions = projOpen.mine && !m.owner && projOpen.status !== "archived"
    ? [[L("proj.removeMember"), () => removeProjectMember(m.username), "link-btn--danger"]] : [];
  return projRow(m.displayName, "", m.owner ? L("proj.ownerTag") : "", actions); // m.username is the user id
}

async function moveAgent(agentId, projectId) {
  try {
    await api.setAgentProject(agentId, projectId);
  } catch (e) { showToast(e.message, true); return; }
  await reloadOpenProject();
  loadAgents();
}

async function removeProjectMember(username) {
  try { await api.removeProjectMember(projOpen.id, username); } catch (e) { showToast(e.message, true); return; }
  await reloadOpenProject();
}

async function reloadOpenProject() {
  await refreshProjects();
  const fresh = projectList.find(p => p.id === projOpen.id);
  if (fresh) openProject(fresh); else showProjectList();
}

async function setProjectArchived(p, archiving) {
  if (archiving && !window.confirm(L("proj.archiveConfirm", { name: p.name }))) return false;
  try {
    await (archiving ? api.archiveProject(p.id) : api.reopenProject(p.id));
  } catch (e) { showToast(e.message, true); return false; }
  await refreshProjects();
  const fresh = projectList.find(x => x.id === p.id);
  if (projOpen && projOpen.id === p.id && fresh) openProject(fresh); else showProjectList();
  loadAgents();
  return true;
}
pjel.archiveBtn.addEventListener("click", () => { if (projOpen) setProjectArchived(projOpen, false); });
pjel.inviteBtn.addEventListener("click", () => { if (projOpen) pickMembersForProject(); });
pjel.addAgentBtn.addEventListener("click", () => { if (projOpen) pickAgentsForProject(); });
pjel.formArchive.addEventListener("click", async () => {
  const p = projEditing;
  if (!p) return;
  const archiving = p.status !== "archived";
  if (await setProjectArchived(p, archiving)) pjel.modal.hidden = true;
});

// ---------- create / edit ----------

function openProjectModal(p) {
  projEditing = p || null;
  pjel.modalTitle.textContent = L(p ? "proj.editTitle" : "proj.newTitle");
  pjel.formName.value = p ? p.name : "";
  pjel.formDesc.value = p ? (p.description || "") : "";
  pjel.formError.hidden = true;
  pjel.formArchive.hidden = !p || !p.mine;
  pjel.formArchive.textContent = L(p && p.status === "archived" ? "proj.reopen" : "proj.archiveProject");
  pjel.modal.hidden = false;
  pjel.formName.focus();
}
pjel.newBtn.addEventListener("click", () => openProjectModal(null));
pjel.editBtn.addEventListener("click", () => openProjectModal(projOpen));
pjel.modal.querySelectorAll("[data-close]").forEach(x => x.addEventListener("click", () => { pjel.modal.hidden = true; }));
pjel.form.addEventListener("submit", async e => {
  e.preventDefault();
  const body = { name: pjel.formName.value.trim(), description: pjel.formDesc.value.trim() };
  try {
    const saved = projEditing ? await api.updateProject(projEditing.id, body) : await api.createProject(body);
    pjel.modal.hidden = true;
    await refreshProjects();
    projState = "active";
    openProject(projectList.find(p => p.id === saved.id) || saved);
  } catch (err) {
    pjel.formError.textContent = err.message;
    pjel.formError.hidden = false;
  }
});

// ---------- picking agents or people to add ----------

async function pickAgentsForProject() {
  const all = await api.listAgents().then(r => (r && r.data) || []).catch(() => []);
  // The user's own agents that aren't in a project yet; the SuperAgent is never in one.
  const free = all.filter(a => a.kind !== "super" && isOwner(a) && !projectOfAgentId(a.id));
  openProjectPick(L("proj.addAgentTitle", { name: projOpen.name }), L("proj.addAgentHint"),
    free.map(a => ({ id: a.id, label: (a.avatar || "🙂") + " " + a.name, sub: "" })), L("proj.noAgentsToAdd"),
    async ids => { for (const id of ids) await api.setAgentProject(id, projOpen.id); loadAgents(); });
}

async function pickMembersForProject() {
  let people = [];
  try { people = (await api.directory()) || []; } catch (e) { showToast(e.message, true); return; }
  const already = new Set((projOpen.members || []).map(m => m.username));
  openProjectPick(L("proj.addMemberTitle", { name: projOpen.name }), L("proj.addMemberHint"),
    people.filter(p => !already.has(p.id)).map(p => ({ id: p.id, label: p.displayName, sub: p.username })), // members carry user ids
    L("co.noOneElse"), ids => api.addProjectMembers(projOpen.id, ids));
}

function openProjectPick(title, hint, items, emptyText, action) {
  projPickItems = items;
  projPickAction = action;
  projPicked.clear();
  pjel.pickTitle.textContent = title;
  pjel.pickHint.textContent = hint;
  pjel.pickSearch.value = "";
  pjel.pickError.hidden = true;
  pjel.pickList.dataset.empty = emptyText;
  renderProjectPick();
  pjel.pick.hidden = false;
  pjel.pickSearch.focus();
}

function renderProjectPick() {
  const q = pjel.pickSearch.value.trim().toLowerCase();
  const shown = projPickItems.filter(i => !q || i.label.toLowerCase().includes(q) || (i.sub || "").toLowerCase().includes(q));
  pjel.pickList.replaceChildren();
  if (!shown.length) {
    const p = document.createElement("p");
    p.className = "kb-empty";
    p.textContent = projPickItems.length ? L("sk.noMatch") : pjel.pickList.dataset.empty;
    pjel.pickList.appendChild(p);
    return;
  }
  shown.forEach(i => {
    const label = document.createElement("label");
    const cb = document.createElement("input");
    cb.type = "checkbox";
    cb.checked = projPicked.has(i.id);
    cb.addEventListener("change", () => { if (cb.checked) projPicked.add(i.id); else projPicked.delete(i.id); });
    const sub = document.createElement("span");
    sub.className = "invite-user";
    sub.textContent = i.sub || "";
    label.append(cb, document.createTextNode(i.label), sub);
    pjel.pickList.appendChild(label);
  });
}
pjel.pickSearch.addEventListener("input", renderProjectPick);
pjel.pick.querySelectorAll("[data-close]").forEach(x => x.addEventListener("click", () => { pjel.pick.hidden = true; }));
pjel.pickForm.addEventListener("submit", async e => {
  e.preventDefault();
  if (!projPicked.size || !projPickAction) { pjel.pick.hidden = true; return; }
  try {
    await projPickAction([...projPicked]);
    pjel.pick.hidden = true;
    await reloadOpenProject();
  } catch (err) {
    pjel.pickError.textContent = err.message;
    pjel.pickError.hidden = false;
  }
});
