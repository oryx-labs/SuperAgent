// SuperAgent UI — Session guard and user management.
// Classic script sharing one global scope with the others; index.html loads them in order.
"use strict";

// ---------- auth (session guard + user management) ----------
Object.assign(api, {
  me: () => fetch("/api/v1/auth/me"),
  logout: () => fetch("/api/v1/auth/logout", { method: "POST" }),
  listUsers: () => fetch("/api/v1/users").then(r => r.json()),
  createUser: body => fetch("/api/v1/users", {
    method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body),
  }).then(r => r.json()),
  updateUser: (id, body) => fetch("/api/v1/users/" + id, {
    method: "PUT", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body),
  }).then(r => r.json()),
  deleteUser: id => fetch("/api/v1/users/" + id, { method: "DELETE" }),
});


let currentUsername = null;
let currentMe = null;

async function initAuth() {
  let res;
  try { res = await api.me(); } catch (e) { window.location.href = "/login"; return false; }
  if (res.status === 401) { window.location.href = "/login"; return false; }
  const me = (await res.json()).data;
  currentUsername = me.username;
  currentMe = me;
  renderAccount();
  // Admin-only sections: hide for non-admins.
  const showAdmin = !!me.admin;
  isAdmin = showAdmin;
  document.getElementById("adminTopNav").hidden = !showAdmin;
  document.getElementById("addConnectorBtn").hidden = !showAdmin; // the list is for all; managing it is not
  return true;
}

// The topbar button (name + company short name) and the card atop the account menu.
function renderAccount() {
  const me = currentMe;
  if (!me) return;
  const shown = me.displayName || me.username || "";
  document.getElementById("currentUser").textContent = shown;
  document.getElementById("userAvatar").textContent = shown.trim().charAt(0).toUpperCase();
  const short = me.companyShortName || "";
  const shortEl = document.getElementById("currentCompanyShort");
  shortEl.textContent = short;
  shortEl.hidden = !short;
  document.getElementById("ucName").textContent = shown;
  document.getElementById("ucUsername").textContent = me.displayName && me.displayName !== me.username ? me.username : "";
  const teams = document.getElementById("ucTeams");
  teams.innerHTML = "";
  (me.teams || []).forEach(t => {
    const span = document.createElement("span");
    span.className = "user-card__team";
    span.textContent = t;
    teams.appendChild(span);
  });
  if (!(me.teams || []).length) teams.innerHTML = '<span class="is-empty">' + L("me.noTeam") + "</span>";
  const company = document.getElementById("ucCompany");
  const full = me.companyName || me.companyShortName;
  company.textContent = full || L("me.noCompany");
  company.classList.toggle("is-empty", !full);
}

document.getElementById("logoutBtn").addEventListener("click", async () => {
  try { await api.logout(); } catch (e) { /* ignore */ }
  window.location.href = "/login";
});

// --- users admin ---
const ROLE_LABEL = { ADMIN: "Admin", USER: "User" };
const uel = {
  rows: document.getElementById("userRows"),
  empty: document.getElementById("usersEmpty"),
  addBtn: document.getElementById("addUserBtn"),
  modal: document.getElementById("userModal"),
  form: document.getElementById("userForm"),
  title: document.getElementById("userModalTitle"),
  username: document.getElementById("ufUsername"),
  displayName: document.getElementById("ufDisplayName"),
  source: document.getElementById("ufSource"),
  role: document.getElementById("ufRole"),
  password: document.getElementById("ufPassword"),
  phone: document.getElementById("ufPhone"),
  externalId: document.getElementById("ufExternalId"),
  enabled: document.getElementById("ufEnabled"),
  submit: document.getElementById("ufSubmit"),
  manager: document.getElementById("ufManager"),
  teams: document.getElementById("ufTeams"),
  teamInput: document.getElementById("ufTeamInput"),
  teamAdd: document.getElementById("ufTeamAdd"),
  teamOptions: document.getElementById("ufTeamOptions"),
  orgTeams: document.getElementById("orgTeams"),
};
let editingUserId = null;
// The organisation, maintained one user at a time: each user's manager and teams (team = "a/b" path).
let allUsers = [];
let org = { members: [], teams: [] };
let teamAdmins = {};         // team path → usernames who manage its skills (and its sub-teams')
let editingTeams = [];
const orgMember = username => org.members.find(m => m.username === username) || { manager: null, teams: [] };

Object.assign(api, {
  orgOverview: () => fetch("/api/v1/admin/org").then(r => r.json()),
  saveUserOrg: (id, body) => fetch("/api/v1/users/" + encodeURIComponent(id) + "/org", {
    method: "PUT", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body),
  }).then(r => r.json()),
  teamAdmins: () => fetch("/api/v1/admin/org/team-admins").then(r => r.json()),
  saveTeamAdmins: (team, usernames) => fetch("/api/v1/admin/org/team-admins", {
    method: "PUT", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ team, usernames }),
  }).then(r => r.json()),
  renameTeam: (from, to) => fetch("/api/v1/admin/org/teams/rename", {
    method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ from, to }),
  }).then(r => r.json()),
});

async function loadUsers() {
  const [usersRes, orgRes, adminsRes] = await Promise.all([api.listUsers().catch(() => null),
    api.orgOverview().catch(() => null), api.teamAdmins().catch(() => null)]);
  allUsers = (usersRes && usersRes.data) || [];
  org = (orgRes && orgRes.data) || { members: [], teams: [] };
  teamAdmins = (adminsRes && adminsRes.data) || {};
  uel.rows.innerHTML = "";
  uel.empty.hidden = allUsers.length > 0;
  allUsers.forEach(u => uel.rows.appendChild(userRow(u)));
  renderOrgTeams();
}

// Every team, indented by depth; a team (with everything under it) can be renamed — its knowledge base moves along —
// and given admins, who manage its skills and those of the teams under it.
function renderOrgTeams() {
  uel.orgTeams.replaceChildren(...org.teams.map(team => {
    const li = document.createElement("li");
    const depth = team.split("/").length - 1;
    li.style.paddingLeft = (10 + depth * 18) + "px";
    const name = document.createElement("span");
    name.className = "org-teams__name";
    name.textContent = team.slice(team.lastIndexOf("/") + 1);
    const count = document.createElement("span");
    count.className = "org-teams__count";
    count.textContent = L("org.memberCount", { n: org.members.filter(m => m.teams.includes(team)).length });
    const admins = teamAdmins[team] || [];
    const adminsEl = document.createElement("span");
    adminsEl.className = "org-teams__admins";
    adminsEl.textContent = admins.length ? L("org.admins", { users: admins.join(", ") }) : "";
    li.append(name, count, adminsEl, linkBtn(L("org.setAdmins"), async () => {
      const input = window.prompt(L("org.adminsPrompt", { team }), admins.join(", "));
      if (input === null) return;
      const usernames = input.split(/[,，\s]+/).map(x => x.trim()).filter(Boolean);
      const res = await api.saveTeamAdmins(team, usernames).catch(() => null);
      if (!res || res.code !== "ok") { showToast((res && res.message) || L("org.adminsFailed"), true); return; }
      await loadUsers();
    }, "link-btn--muted"), linkBtn(L("org.rename"), async () => {
      const to = window.prompt(L("org.renamePrompt", { team }), team);
      if (!to || to.trim() === team) return;
      const res = await api.renameTeam(team, to.trim()).catch(() => null);
      if (!res || res.code !== "ok") { showToast((res && res.message) || L("org.renameFailed"), true); return; }
      await loadUsers();
    }, "link-btn--muted"));
    return li;
  }));
  if (!org.teams.length) {
    const li = document.createElement("li");
    li.className = "org-teams__empty";
    li.textContent = L("org.noTeams");
    uel.orgTeams.appendChild(li);
  }
}

function renderTeamChips() {
  uel.teams.replaceChildren(...editingTeams.map(team => {
    const chip = document.createElement("span");
    chip.className = "team-chip";
    chip.textContent = team;
    const x = document.createElement("button");
    x.type = "button";
    x.textContent = "×";
    x.title = L("common.delete");
    x.addEventListener("click", () => { editingTeams = editingTeams.filter(t => t !== team); renderTeamChips(); });
    chip.appendChild(x);
    return chip;
  }));
}

function addTeamFromInput() {
  const team = uel.teamInput.value.split("/").map(s => s.trim()).filter(Boolean).join("/");
  if (team && !editingTeams.includes(team)) editingTeams.push(team);
  uel.teamInput.value = "";
  renderTeamChips();
}
uel.teamAdd.addEventListener("click", addTeamFromInput);
uel.teamInput.addEventListener("keydown", e => { if (e.key === "Enter") { e.preventDefault(); addTeamFromInput(); } });

function userRow(u) {
  const tr = document.createElement("tr");

  const tdUser = document.createElement("td");
  tdUser.className = "c-name";
  tdUser.textContent = u.username;
  if (u.displayName) { const d = document.createElement("small"); d.textContent = u.displayName; tdUser.appendChild(d); }

  const tdRoles = document.createElement("td");
  tdRoles.textContent = (u.roles || []).map(r => ROLE_LABEL[r] || r).join(", ");

  const member = orgMember(u.username);
  const tdManager = document.createElement("td");
  const boss = member.manager && allUsers.find(x => x.username === member.manager);
  tdManager.textContent = member.manager ? (boss && boss.displayName ? boss.displayName : member.manager) : "—";
  const tdTeams = document.createElement("td");
  if (member.teams.length) {
    member.teams.forEach(t => { const c = document.createElement("span"); c.className = "team-chip team-chip--static"; c.textContent = t; tdTeams.appendChild(c); });
  } else {
    tdTeams.textContent = "—";
  }

  const tdSource = document.createElement("td");
  const pill = document.createElement("span");
  pill.className = "pill pill--method";
  pill.textContent = L("src." + u.source);
  tdSource.appendChild(pill);

  const tdStatus = document.createElement("td");
  const st = document.createElement("span");
  st.className = "status" + (u.enabled ? " is-connected" : "");
  const dot = document.createElement("span"); dot.className = "status__dot";
  st.append(dot, document.createTextNode(u.enabled ? L("users.enabled") : L("users.disabled")));
  tdStatus.appendChild(st);

  const tdActions = document.createElement("td");
  const cell = document.createElement("div");
  cell.className = "cell-actions";
  cell.append(
    linkBtn(u.enabled ? L("users.disable") : L("users.enable"), () => toggleUser(u), "link-btn--muted"),
    linkBtn("✎", () => openUserModal(u), "link-btn--muted"),
    linkBtn("×", () => deleteUser(u), "link-btn--danger"));
  cell.children[1].title = L("common.edit"); cell.children[2].title = L("common.delete");
  tdActions.appendChild(cell);

  tr.append(tdUser, tdRoles, tdManager, tdTeams, tdSource, tdStatus, tdActions);
  return tr;
}

async function toggleUser(u) {
  try { await api.updateUser(u.id, { enabled: !u.enabled }); } catch (e) { /* ignore */ }
  await loadUsers();
}
async function deleteUser(u) {
  if (!window.confirm(L("users.deleteConfirm", { name: u.username }))) return;
  try { await api.deleteUser(u.id); } catch (e) { /* ignore */ }
  await loadUsers();
}

function openUserModal(u) {
  editingUserId = u ? u.id : null;
  uel.title.textContent = u ? L("users.modalEdit") : L("users.modalAdd");
  uel.username.value = u ? u.username : "";
  uel.displayName.value = u && u.displayName ? u.displayName : "";
  uel.source.value = u ? u.source : "local";
  uel.role.value = u && (u.roles || []).includes("ADMIN") ? "ADMIN" : "USER";
  uel.password.value = "";
  uel.phone.value = u && u.phone ? u.phone : "";
  uel.externalId.value = u && u.externalId ? u.externalId : "";
  uel.enabled.checked = u ? u.enabled : true;
  // Manager: anyone but the user themselves; teams: free paths, suggested from the teams that exist.
  const member = u ? orgMember(u.username) : { manager: null, teams: [] };
  uel.manager.replaceChildren();
  const none = document.createElement("option");
  none.value = "";
  none.textContent = L("users.noManager");
  uel.manager.appendChild(none);
  allUsers.filter(x => !u || x.username !== u.username).forEach(x => {
    const o = document.createElement("option");
    o.value = x.username;
    o.textContent = x.displayName ? x.displayName + " (" + x.username + ")" : x.username;
    uel.manager.appendChild(o);
  });
  uel.manager.value = member.manager || "";
  editingTeams = member.teams.slice();
  renderTeamChips();
  uel.teamInput.value = "";
  uel.teamOptions.replaceChildren(...org.teams.map(t => { const o = document.createElement("option"); o.value = t; return o; }));
  uel.modal.hidden = false;
  uel.username.focus();
}
function closeUserModal() { uel.modal.hidden = true; }
uel.addBtn.addEventListener("click", () => openUserModal(null));
uel.modal.querySelectorAll("[data-close]").forEach(x => x.addEventListener("click", closeUserModal));
uel.form.addEventListener("submit", async e => {
  e.preventDefault();
  const body = {
    username: uel.username.value.trim(),
    displayName: uel.displayName.value.trim(),
    source: uel.source.value,
    roles: [uel.role.value],
    password: uel.password.value,
    phone: uel.phone.value.trim() || null,
    externalId: uel.externalId.value.trim() || null,
    enabled: uel.enabled.checked,
  };
  if (!body.username) return;
  uel.submit.disabled = true;
  if (uel.teamInput.value.trim()) addTeamFromInput(); // a typed-but-not-added team still counts
  try {
    const saved = editingUserId ? await api.updateUser(editingUserId, body) : await api.createUser(body);
    const id = editingUserId || (saved && saved.data && saved.data.id);
    if (!id) { showToast((saved && saved.message) || L("users.saveFailed"), true); return; }
    const orgRes = await api.saveUserOrg(id, { manager: uel.manager.value || null, teams: editingTeams });
    if (!orgRes || orgRes.code !== "ok") { showToast((orgRes && orgRes.message) || L("users.saveFailed"), true); return; }
    closeUserModal();
    await loadUsers();
  } catch (err) { showToast(L("users.saveFailed"), true); } finally { uel.submit.disabled = false; }
});

// language switcher
const langSwitch = document.getElementById("langSwitch");
if (langSwitch) {
  const cur = window.I18N.getLang();
  langSwitch.querySelectorAll("[data-lang]").forEach(b => {
    b.classList.toggle("is-active", b.dataset.lang === cur);
    b.addEventListener("click", () => window.I18N.switchTo(b.dataset.lang));
  });
}

// Guard first, then load the app only when authenticated.
