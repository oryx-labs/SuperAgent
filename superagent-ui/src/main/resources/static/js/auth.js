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

async function initAuth() {
  let res;
  try { res = await api.me(); } catch (e) { window.location.href = "/login"; return false; }
  if (res.status === 401) { window.location.href = "/login"; return false; }
  const me = (await res.json()).data;
  currentUsername = me.username;
  const shown = me.displayName || me.username || "";
  document.getElementById("currentUser").textContent = shown;
  document.getElementById("userAvatar").textContent = shown.trim().charAt(0).toUpperCase();
  // Admin-only sections: hide for non-admins.
  const showAdmin = !!me.admin;
  isAdmin = showAdmin;
  document.getElementById("adminTopNav").hidden = !showAdmin;
  document.getElementById("addConnectorBtn").hidden = !showAdmin; // the list is for all; managing it is not
  return true;
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
};
let editingUserId = null;

async function loadUsers() {
  let list = [];
  try { const res = await api.listUsers(); list = (res && res.data) || []; } catch (e) { /* ignore */ }
  uel.rows.innerHTML = "";
  uel.empty.hidden = list.length > 0;
  list.forEach(u => uel.rows.appendChild(userRow(u)));
}

function userRow(u) {
  const tr = document.createElement("tr");

  const tdUser = document.createElement("td");
  tdUser.className = "c-name";
  tdUser.textContent = u.username;
  if (u.displayName) { const d = document.createElement("small"); d.textContent = u.displayName; tdUser.appendChild(d); }

  const tdRoles = document.createElement("td");
  tdRoles.textContent = (u.roles || []).map(r => ROLE_LABEL[r] || r).join(", ");

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

  tr.append(tdUser, tdRoles, tdSource, tdStatus, tdActions);
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
  try {
    if (editingUserId) await api.updateUser(editingUserId, body);
    else await api.createUser(body);
    closeUserModal();
    await loadUsers();
  } catch (err) { /* ignore */ } finally { uel.submit.disabled = false; }
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
