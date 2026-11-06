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
  loadAvatarVersions(); // other people's pictures, for member lists
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
  paintAvatar(document.getElementById("userAvatar"), me.username, shown, me.avatarVersion);
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

// ---- avatars: anyone's picture, or their initial ----
// username → version for everyone with an avatar; loaded once, so lists can show pictures without a request each.
let avatarVersions = {};
Object.assign(api, {
  avatarVersions: () => fetch("/api/v1/avatars").then(r => r.json()),
  setAvatar: data => fetch("/api/v1/account/avatar", {
    method: "PUT", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ data }),
  }).then(r => r.json()),
  removeAvatar: () => fetch("/api/v1/account/avatar", { method: "DELETE" }).then(r => r.json()),
  changePassword: (current, next) => fetch("/api/v1/account/password", {
    method: "PUT", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ current, next }),
  }).then(r => r.json()),
});

async function loadAvatarVersions() {
  try { const res = await api.avatarVersions(); avatarVersions = (res && res.data) || {}; } catch (e) { return; }
  if (typeof activeAgent !== "undefined" && activeAgent) renderMembers(activeAgent); // drawn before they arrived
}

/** Puts {@code username}'s picture into {@code box}, or their initial when they have none. */
function paintAvatar(box, username, label, version) {
  const v = version !== undefined ? version : avatarVersions[username];
  box.replaceChildren();
  box.classList.toggle("has-image", !!v);
  if (v) {
    const img = document.createElement("img");
    img.src = "/api/v1/avatars/" + encodeURIComponent(username) + "?v=" + v;
    img.alt = "";
    box.appendChild(img);
  } else {
    box.textContent = (label || username || "?").trim().charAt(0).toUpperCase();
  }
}

// ---- account settings ----
const acel = {
  modal: document.getElementById("accountModal"),
  avatar: document.getElementById("acAvatar"),
  input: document.getElementById("acAvatarInput"),
  remove: document.getElementById("acAvatarRemove"),
  form: document.getElementById("acPasswordForm"),
  none: document.getElementById("acPasswordNone"),
  fields: document.getElementById("acPasswordFields"),
  current: document.getElementById("acCurrent"),
  next: document.getElementById("acNext"),
  confirm: document.getElementById("acConfirm"),
  error: document.getElementById("acError"),
};

function openAccount() {
  const me = currentMe;
  renderAccountAvatar();
  acel.fields.hidden = !me.canChangePassword;
  acel.none.hidden = me.canChangePassword;
  acel.none.textContent = L(me.username && me.admin && !me.canChangePassword ? "account.passwordConfig" : "account.passwordNone");
  [acel.current, acel.next, acel.confirm].forEach(i => { i.value = ""; });
  acel.error.hidden = true;
  acel.modal.hidden = false;
}

function renderAccountAvatar() {
  const me = currentMe;
  paintAvatar(acel.avatar, me.username, me.displayName || me.username, me.avatarVersion);
  acel.remove.hidden = !me.avatarVersion;
}

function avatarChanged(version) {
  currentMe.avatarVersion = version || null;
  if (version) avatarVersions[currentMe.username] = version; else delete avatarVersions[currentMe.username];
  renderAccount();
  renderAccountAvatar();
  if (activeAgent) renderMembers(activeAgent);
}

/** Center-crops to a square and scales to 256px, so any photo becomes a small avatar. */
function squareAvatar(file) {
  return new Promise((resolve, reject) => {
    const img = new Image();
    const url = URL.createObjectURL(file);
    img.onload = () => {
      const side = Math.min(img.naturalWidth, img.naturalHeight);
      const canvas = document.createElement("canvas");
      canvas.width = canvas.height = 256;
      canvas.getContext("2d").drawImage(img, (img.naturalWidth - side) / 2, (img.naturalHeight - side) / 2,
        side, side, 0, 0, 256, 256);
      URL.revokeObjectURL(url);
      resolve(canvas.toDataURL("image/png").split(",")[1]);
    };
    img.onerror = () => { URL.revokeObjectURL(url); reject(new Error(L("account.badImage"))); };
    img.src = url;
  });
}

document.getElementById("accountBtn").addEventListener("click", () => {
  document.getElementById("userMenu").classList.remove("is-open");
  document.activeElement.blur(); // the menu stays open while it holds focus
  openAccount();
});
acel.modal.querySelectorAll("[data-close]").forEach(x => x.addEventListener("click", () => { acel.modal.hidden = true; }));
acel.input.addEventListener("change", async () => {
  const file = acel.input.files && acel.input.files[0];
  acel.input.value = "";
  if (!file) return;
  try {
    const res = await api.setAvatar(await squareAvatar(file));
    if (!res || res.data == null) throw new Error((res && res.message) || L("account.avatarFailed"));
    avatarChanged(res.data);
    showToast(L("account.avatarSaved"), false);
  } catch (e) { showToast(e.message, true); }
});
acel.remove.addEventListener("click", async () => {
  const res = await api.removeAvatar().catch(() => null);
  if (!res || res.code !== "ok") { showToast((res && res.message) || L("account.avatarFailed"), true); return; }
  avatarChanged(null);
});
acel.form.addEventListener("submit", async e => {
  e.preventDefault();
  const fail = msg => { acel.error.textContent = msg; acel.error.hidden = false; };
  acel.error.hidden = true;
  if (acel.next.value.length < 8) return fail(L("account.tooShort"));
  if (acel.next.value !== acel.confirm.value) return fail(L("account.mismatch"));
  const res = await api.changePassword(acel.current.value, acel.next.value).catch(() => null);
  if (!res || res.code !== "ok") return fail((res && res.message) || L("account.passwordFailed"));
  [acel.current, acel.next, acel.confirm].forEach(i => { i.value = ""; });
  showToast(L("account.passwordSaved"), false);
});

document.getElementById("logoutBtn").addEventListener("click", async () => {
  try { await api.logout(); } catch (e) { /* ignore */ }
  window.location.href = "/login";
});

// --- users admin ---
// Two tabs: everyone who can sign in, and the organisation (read-only — it's kept by an external directory and
// recorded on the users). Adding someone is just a username + password, or a pre-registered phone number.
const ROLE_LABEL = { ADMIN: "users.roleAdmin", USER: "users.roleUser" };
const uel = {
  rows: document.getElementById("userRows"),
  empty: document.getElementById("usersEmpty"),
  search: document.getElementById("userSearch"),
  tabs: document.getElementById("usersTabs"),
  addBtn: document.getElementById("addUserBtn"),
  modal: document.getElementById("userModal"),
  form: document.getElementById("userForm"),
  title: document.getElementById("userModalTitle"),
  kind: document.getElementById("ufKind"),
  username: document.getElementById("ufUsername"),
  displayName: document.getElementById("ufDisplayName"),
  displayLabel: document.getElementById("ufDisplayLabel"),
  phone: document.getElementById("ufPhone"),
  password: document.getElementById("ufPassword"),
  passwordLabel: document.getElementById("ufPasswordLabel"),
  role: document.getElementById("ufRole"),
  hint: document.getElementById("ufHint"),
  submit: document.getElementById("ufSubmit"),
  orgTree: document.getElementById("orgTree"),
  orgTitle: document.getElementById("orgTeamTitle"),
  orgMembers: document.getElementById("orgMembers"),
  orgEmpty: document.getElementById("orgEmpty"),
};
let allUsers = [];
let org = { members: [], teams: [] };
let usersTab = "users";
let orgTeam = null;          // the team picked in the organisation tab
let editingUser = null;      // null = adding
let userKind = "local";      // what's being added: local (username + password) or phone

Object.assign(api, {
  orgOverview: () => fetch("/api/v1/admin/org").then(r => r.json()),
});

async function loadUsers() {
  const [usersRes, orgRes] = await Promise.all([api.listUsers().catch(() => null), api.orgOverview().catch(() => null)]);
  allUsers = (usersRes && usersRes.data) || [];
  org = (orgRes && orgRes.data) || { members: [], teams: [] };
  renderUsers();
  renderOrg();
}

function showUsersTab(tab) {
  usersTab = tab;
  uel.tabs.querySelectorAll(".model-tab").forEach(b => b.classList.toggle("is-active", b.dataset.tab === tab));
  document.querySelectorAll("[data-users-tab]").forEach(p => { p.hidden = p.dataset.usersTab !== tab; });
  uel.addBtn.hidden = tab !== "users";
}
uel.tabs.querySelectorAll(".model-tab").forEach(b => b.addEventListener("click", () => showUsersTab(b.dataset.tab)));

const userLabel = u => u.displayName || u.username;

function renderUsers() {
  const q = uel.search.value.trim().toLowerCase();
  const shown = allUsers.filter(u => !q || [u.username, u.displayName, u.phone].some(v => (v || "").toLowerCase().includes(q)));
  uel.rows.replaceChildren(...shown.map(userRow));
  uel.empty.hidden = shown.length > 0;
  uel.empty.textContent = L(allUsers.length ? "users.noMatch" : "users.empty");
}
uel.search.addEventListener("input", renderUsers);

function userRow(u) {
  const tr = document.createElement("tr");
  const builtIn = u.source === "CONFIG";

  const tdUser = document.createElement("td");
  tdUser.className = "c-name";
  tdUser.textContent = userLabel(u);
  const sub = u.source === "PHONE" ? u.phone : u.displayName ? u.username : null;
  if (sub) { const d = document.createElement("small"); d.textContent = sub; tdUser.appendChild(d); }

  const tdLogin = document.createElement("td");
  tdLogin.className = "kb-list__meta";
  tdLogin.textContent = L(builtIn ? "users.loginConfig" : u.source === "PHONE" ? "users.loginPhone" : "users.loginLocal");

  const tdRole = document.createElement("td");
  tdRole.textContent = L((u.roles || []).includes("ADMIN") ? ROLE_LABEL.ADMIN : ROLE_LABEL.USER);

  const tdStatus = document.createElement("td");
  const st = document.createElement("span");
  const pending = u.source === "PHONE"; // phone sign-in isn't open yet: registered, can't log in
  st.className = "status" + (u.enabled && !pending ? " is-connected" : "");
  const dot = document.createElement("span"); dot.className = "status__dot";
  st.append(dot, document.createTextNode(L(!u.enabled ? "users.disabled" : pending ? "users.preRegistered" : "users.enabled")));
  tdStatus.appendChild(st);

  const tdActions = document.createElement("td");
  const cell = document.createElement("div");
  cell.className = "cell-actions";
  if (!builtIn) {
    cell.append(
      linkBtn(L("common.edit"), () => openUserModal(u), "link-btn--muted"),
      linkBtn(u.enabled ? L("users.disable") : L("users.enable"), () => toggleUser(u), "link-btn--muted"),
      linkBtn(L("common.delete"), () => deleteUser(u), "link-btn--danger"));
  }
  tdActions.appendChild(cell);

  tr.append(tdUser, tdLogin, tdRole, tdStatus, tdActions);
  return tr;
}

async function toggleUser(u) {
  try { await api.updateUser(u.id, { enabled: !u.enabled }); } catch (e) { /* ignore */ }
  await loadUsers();
}
async function deleteUser(u) {
  if (!window.confirm(L("users.deleteConfirm", { name: userLabel(u) }))) return;
  try { await api.deleteUser(u.id); } catch (e) { /* ignore */ }
  await loadUsers();
}

// ---- organisation (read-only) ----

function renderOrg() {
  const empty = !org.teams.length && !org.members.some(m => m.manager);
  uel.orgEmpty.hidden = !empty;
  uel.orgTree.hidden = empty;
  uel.orgTree.parentElement.querySelector(".org-members").hidden = empty;
  if (empty) return;
  if (orgTeam && !org.teams.includes(orgTeam)) orgTeam = null;
  if (!orgTeam) orgTeam = org.teams[0] || "";
  const item = (label, team, depth, count) => {
    const b = document.createElement("button");
    b.type = "button";
    b.className = "org-tree__item" + (team === orgTeam ? " is-selected" : "");
    b.style.paddingLeft = (10 + depth * 16) + "px";
    const name = document.createElement("span");
    name.textContent = label;
    const n = document.createElement("span");
    n.className = "org-tree__count";
    n.textContent = count;
    b.append(name, n);
    b.addEventListener("click", () => { orgTeam = team; renderOrg(); });
    return b;
  };
  const members = team => org.members.filter(m => m.teams.some(t => t === team || t.startsWith(team + "/")));
  const rows = org.teams.map(t => item(t.slice(t.lastIndexOf("/") + 1), t, t.split("/").length - 1, members(t).length));
  const loose = org.members.filter(m => !m.teams.length);
  if (loose.length) rows.push(item(L("org.noTeam"), "", 0, loose.length));
  uel.orgTree.replaceChildren(...rows);

  const shown = orgTeam ? members(orgTeam) : loose;
  uel.orgTitle.textContent = orgTeam || L("org.noTeam");
  uel.orgMembers.replaceChildren(...shown.map(m => {
    const u = allUsers.find(x => x.username === m.username) || { username: m.username };
    const boss = m.manager && allUsers.find(x => x.username === m.manager);
    const li = document.createElement("li");
    const initial = document.createElement("span");
    initial.className = "member-initial";
    paintAvatar(initial, m.username, userLabel(u));
    const name = document.createElement("span");
    name.className = "org-members__name";
    name.textContent = userLabel(u);
    const meta = document.createElement("span");
    meta.className = "org-members__meta";
    const sub = m.teams.filter(t => t !== orgTeam && orgTeam && t.startsWith(orgTeam + "/"));
    meta.textContent = [m.manager ? L("org.reportsTo", { name: boss ? userLabel(boss) : m.manager }) : null,
      sub.length ? sub.join("、") : null].filter(Boolean).join(" · ");
    li.append(initial, name, meta);
    return li;
  }));
}

// ---- add / edit ----

function setUserKind(kind) {
  userKind = kind;
  uel.kind.querySelectorAll(".seg__btn").forEach(b => b.classList.toggle("is-active", b.dataset.kind === kind));
  uel.form.querySelectorAll("[data-for]").forEach(f => { f.hidden = f.dataset.for !== kind; });
  uel.displayLabel.textContent = L(kind === "phone" ? "users.fName" : "users.fDisplay");
  uel.displayName.placeholder = kind === "phone" ? "" : L("common.optional");
  uel.hint.textContent = kind === "phone" ? L("users.phoneHint") : "";
  uel.hint.hidden = kind !== "phone";
}
uel.kind.querySelectorAll(".seg__btn").forEach(b => b.addEventListener("click", () => { if (!editingUser) setUserKind(b.dataset.kind); }));

function openUserModal(u) {
  editingUser = u;
  uel.title.textContent = L(u ? "users.modalEdit" : "users.modalAdd");
  uel.kind.hidden = !!u; // how someone signs in is chosen when they're added
  setUserKind(u && u.source === "PHONE" ? "phone" : "local");
  uel.username.value = u ? u.username : "";
  uel.username.disabled = !!u;
  uel.displayName.value = u && u.displayName ? u.displayName : "";
  uel.phone.value = u && u.phone ? u.phone : "";
  uel.phone.disabled = !!u;
  uel.password.value = "";
  uel.passwordLabel.textContent = L(u ? "users.fNewPassword" : "users.fPassword");
  uel.password.placeholder = u ? L("users.fNewPasswordPh") : "";
  uel.role.value = u && (u.roles || []).includes("ADMIN") ? "ADMIN" : "USER";
  uel.modal.hidden = false;
  (u ? uel.displayName : userKind === "phone" ? uel.displayName : uel.username).focus();
}
function closeUserModal() { uel.modal.hidden = true; }
uel.addBtn.addEventListener("click", () => openUserModal(null));
uel.modal.querySelectorAll("[data-close]").forEach(x => x.addEventListener("click", closeUserModal));

uel.form.addEventListener("submit", async e => {
  e.preventDefault();
  let body;
  if (editingUser) {
    body = { displayName: uel.displayName.value.trim(), roles: [uel.role.value] };
    if (uel.password.value) body.password = uel.password.value;
  } else if (userKind === "phone") {
    const phone = uel.phone.value.replace(/[\s-]/g, "");
    const name = uel.displayName.value.trim();
    if (!/^\+?\d{6,15}$/.test(phone)) { showToast(L("users.phoneInvalid"), true); return; }
    if (!name) { showToast(L("users.nameRequired"), true); return; }
    body = { username: phone, displayName: name, phone, source: "phone", roles: [uel.role.value] };
  } else {
    const username = uel.username.value.trim();
    if (!username) { showToast(L("users.usernameRequired"), true); return; }
    if (!uel.password.value) { showToast(L("users.passwordRequired"), true); return; }
    body = { username, displayName: uel.displayName.value.trim(), password: uel.password.value, source: "local", roles: [uel.role.value] };
  }
  uel.submit.disabled = true;
  try {
    const res = editingUser ? await api.updateUser(editingUser.id, body) : await api.createUser(body);
    if (!res || !res.data) { showToast((res && res.message) || L("users.saveFailed"), true); return; }
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
