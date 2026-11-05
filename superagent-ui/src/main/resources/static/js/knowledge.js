// SuperAgent UI — Knowledge base: file tree, preview, folders.
// Classic script sharing one global scope with the others; index.html loads them in order.
"use strict";

// ---------- knowledge ----------
// The signed-in user's private file-system knowledge base. Paths are "/"-separated, "" is the root.
// Declared before setView: the initial view (from the URL hash) may be #knowledge.
// The knowledge bases this user can open, in three layers: their own (personal), their teams' (team:<path>,
// including the teams above theirs, read-only), and the company's (written by admins). Each is its own
// tree; paths are "/"-separated within a space, "" is its root.
// Declared before setView: the initial view (from the URL hash) may be #knowledge.
const kbQ = space => "space=" + encodeURIComponent(space || "personal");
Object.assign(api, {
  kbSpaces: () => fetch("/api/v1/knowledge/spaces").then(kbJson),
  kbList: (space, path) => fetch("/api/v1/knowledge/entries?" + kbQ(space) + "&path=" + encodeURIComponent(path)).then(kbJson),
  kbFolders: space => fetch("/api/v1/knowledge/folders?" + kbQ(space)).then(kbJson),
  kbCreateFolder: (space, path) => kbPost("/api/v1/knowledge/folders?" + kbQ(space), { path }),
  kbRename: (space, path, newName) => kbPost("/api/v1/knowledge/rename?" + kbQ(space), { path, newName }),
  kbMove: (space, path, targetDir) => kbPost("/api/v1/knowledge/move?" + kbQ(space), { path, targetDir }),
  kbDelete: (space, path) => fetch("/api/v1/knowledge/entries?" + kbQ(space) + "&path=" + encodeURIComponent(path),
    { method: "DELETE" }).then(kbJson),
});
// Server errors come back as {code, message}; surface the message.
async function kbJson(r) {
  const body = await r.json().catch(() => null);
  if (!r.ok) throw new Error((body && body.message) || ("HTTP " + r.status));
  return body ? body.data : null;
}
function kbPost(url, body) {
  return fetch(url, { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body) }).then(kbJson);
}
const kbFileUrl = (path, download, space) => "/api/v1/knowledge/file?" + kbQ(space || kbSelected.space)
  + "&path=" + encodeURIComponent(path) + (download ? "&download=true" : "");

const kbPane = document.querySelector('[data-pane="knowledge"]');
const kel = {
  tree: document.getElementById("kbTree"),
  crumbs: document.getElementById("kbCrumbs"),
  view: document.getElementById("kbView"),
  up: document.getElementById("kbUpBtn"),
  newFolder: document.getElementById("kbNewFolderBtn"),
  rename: document.getElementById("kbRenameBtn"),
  move: document.getElementById("kbMoveBtn"),
  del: document.getElementById("kbDeleteBtn"),
  download: document.getElementById("kbDownloadBtn"),
  modal: document.getElementById("kbModal"),
  form: document.getElementById("kbForm"),
  modalTitle: document.getElementById("kbModalTitle"),
  modalMsg: document.getElementById("kbModalMsg"),
  nameField: document.getElementById("kbNameField"),
  nameInput: document.getElementById("kbNameInput"),
  targetField: document.getElementById("kbTargetField"),
  targetSelect: document.getElementById("kbTargetSelect"),
  modalError: document.getElementById("kbModalError"),
  modalSubmit: document.getElementById("kbModalSubmit"),
};
let kbSpaces = [];                    // [{id, kind, team, canWrite}] from /knowledge/spaces
const kbChildren = new Map();         // "space|path" -> entries, filled lazily as folders are opened
const kbExpanded = new Set(["personal|"]); // folders open in the tree ("space|path"; "space|" = the space itself)
let kbSelected = { space: "personal", path: "", dir: true, name: "" };
let kbModalAction = null;

const kbKey = (space, path) => space + "|" + path;
const kbParent = path => path.includes("/") ? path.slice(0, path.lastIndexOf("/")) : "";
const kbBaseName = path => path.slice(path.lastIndexOf("/") + 1);
const kbCurrentFolder = () => kbSelected.dir ? kbSelected.path : kbParent(kbSelected.path);
const kbSpaceOf = id => kbSpaces.find(s => s.id === id);
const kbCanWrite = () => { const s = kbSpaceOf(kbSelected.space); return !!(s && s.canWrite); };
const kbSpaceLabel = s => s.kind === "personal" ? L("kb.mine") : s.kind === "company" ? L("kb.companyBase")
  : s.team.slice(s.team.lastIndexOf("/") + 1);
const KB_IMAGE = /\.(png|jpe?g|gif|webp|svg)$/i;
const KB_MARKDOWN = /\.(md|markdown)$/i;
const KB_TEXT = /\.(txt|log|csv|json|ya?ml|xml|html)$/i;
const kbIcon = e => e.dir ? "📁" : KB_IMAGE.test(e.name) ? "🖼️" : KB_MARKDOWN.test(e.name) ? "📝" : "📄";

async function kbLoad(space, path) {
  const entries = await api.kbList(space, path);
  kbChildren.set(kbKey(space, path), entries || []);
  return kbChildren.get(kbKey(space, path));
}

async function loadKnowledge() {
  try { kbSpaces = (await api.kbSpaces()) || []; } catch (e) { kbSpaces = [{ id: "personal", kind: "personal", canWrite: true }]; }
  if (!kbSpaceOf(kbSelected.space)) kbSelected = { space: "personal", path: "", dir: true, name: "" };
  kbExpanded.add(kbKey(kbSelected.space, ""));
  kbChildren.clear();
  // Re-read every open folder of a space that still exists; ones gone since just close.
  for (const key of Array.from(kbExpanded)) {
    const [space, path] = [key.slice(0, key.indexOf("|")), key.slice(key.indexOf("|") + 1)];
    if (!kbSpaceOf(space)) { kbExpanded.delete(key); continue; }
    try { await kbLoad(space, path); } catch (e) { kbExpanded.delete(key); }
  }
  renderKbTree();
  await kbSelect(kbSelected.space, kbSelected.path, kbSelected.dir, true);
}

// Three groups — Personal, Team, Company — each space a collapsible header with its own folder tree.
function renderKbTree() {
  kel.tree.innerHTML = "";
  const groups = [["personal", L("kb.groupPersonal")], ["team", L("kb.groupTeam")], ["company", L("kb.groupCompany")]];
  groups.forEach(([kind, title]) => {
    const spaces = kbSpaces.filter(s => s.kind === kind);
    if (!spaces.length) return;
    const head = document.createElement("div");
    head.className = "kb-group";
    head.textContent = title;
    kel.tree.appendChild(head);
    spaces.forEach(space => renderKbSpace(space));
  });
}

function renderKbSpace(space) {
  const rootKey = kbKey(space.id, "");
  const open = kbExpanded.has(rootKey);
  const row = document.createElement("div");
  const selected = kbSelected.space === space.id && !kbSelected.path;
  row.className = "kb-node kb-space" + (selected ? " is-selected" : "");
  // A team space is indented by its depth, so the hierarchy reads at a glance.
  const depth = space.kind === "team" ? space.team.split("/").length - 1 : 0;
  row.style.paddingLeft = (6 + depth * 14) + "px";
  row.title = space.kind === "team" ? space.team : "";
  const twisty = document.createElement("span");
  twisty.className = "kb-node__twisty";
  twisty.textContent = open ? "▾" : "▸";
  const name = document.createElement("span");
  name.className = "kb-node__name";
  name.textContent = (space.kind === "personal" ? "👤 " : space.kind === "team" ? "👥 " : "🏢 ") + kbSpaceLabel(space);
  row.append(twisty, name);
  if (!space.canWrite) {
    const ro = document.createElement("span");
    ro.className = "kb-readonly";
    ro.textContent = L("kb.readOnly");
    row.appendChild(ro);
  }
  twisty.addEventListener("click", ev => { ev.stopPropagation(); kbToggle(space.id, ""); });
  row.addEventListener("click", () => kbSelect(space.id, "", true));
  kel.tree.appendChild(row);
  if (!open) return;
  const walk = (folder, level) => {
    (kbChildren.get(kbKey(space.id, folder)) || []).forEach(e => {
      const node = document.createElement("div");
      node.className = "kb-node" + (kbSelected.space === space.id && e.path === kbSelected.path ? " is-selected" : "");
      node.style.paddingLeft = (6 + (depth + level) * 14) + "px";
      node.setAttribute("role", "treeitem");
      const isOpen = e.dir && kbExpanded.has(kbKey(space.id, e.path));
      if (e.dir) node.setAttribute("aria-expanded", String(isOpen));
      const tw = document.createElement("span");
      tw.className = "kb-node__twisty";
      tw.textContent = e.dir ? (isOpen ? "▾" : "▸") : "";
      const label = document.createElement("span");
      label.className = "kb-node__name";
      label.textContent = kbIcon(e) + " " + e.name;
      node.append(tw, label);
      tw.addEventListener("click", ev => { ev.stopPropagation(); if (e.dir) kbToggle(space.id, e.path); });
      node.addEventListener("click", () => kbSelect(space.id, e.path, e.dir));
      kel.tree.appendChild(node);
      if (isOpen) walk(e.path, level + 1);
    });
  };
  walk("", 1);
  if (!(kbChildren.get(rootKey) || []).length) {
    const empty = document.createElement("p");
    empty.className = "kb-space__empty";
    empty.style.paddingLeft = (26 + depth * 14) + "px";
    empty.textContent = L("kb.spaceEmpty");
    kel.tree.appendChild(empty);
  }
}

async function kbToggle(space, path) {
  const key = kbKey(space, path);
  if (kbExpanded.has(key)) {
    kbExpanded.delete(key);
  } else {
    kbExpanded.add(key);
    if (!kbChildren.has(key)) {
      try { await kbLoad(space, path); } catch (e) { kbExpanded.delete(key); showToast(e.message, true); }
    }
  }
  renderKbTree();
}

// Selecting a folder opens it in the tree and lists it; selecting a file previews it.
async function kbSelect(space, path, dir, quiet) {
  kbSelected = { space, path, dir, name: kbBaseName(path) };
  if (dir) {
    // Open every ancestor too (and the space itself), so the selection is visible in the tree.
    let p = path;
    while (p) { kbExpanded.add(kbKey(space, p)); p = kbParent(p); }
    kbExpanded.add(kbKey(space, ""));
    for (const key of Array.from(kbExpanded)) {
      if (!key.startsWith(space + "|") || kbChildren.has(key)) continue;
      try { await kbLoad(space, key.slice(space.length + 1)); } catch (e) { kbExpanded.delete(key); }
    }
  }
  renderKbTree();
  renderKbBar();
  try {
    const key = kbKey(space, path);
    if (dir) renderKbFolder(kbChildren.has(key) ? kbChildren.get(key) : await kbLoad(space, path));
    else await renderKbFile(path);
  } catch (e) {
    if (!quiet) showToast(e.message, true);
    if (path) { kbSelect(space, "", true, true); return; } // gone: fall back to the space's root
    kel.view.innerHTML = "";
  }
}

function renderKbBar() {
  kel.crumbs.innerHTML = "";
  const parts = kbSelected.path ? kbSelected.path.split("/") : [];
  const crumb = (label, path, isDir, current) => {
    const b = document.createElement("button");
    b.type = "button";
    b.textContent = label;
    if (current) b.className = "is-current";
    b.addEventListener("click", () => kbSelect(kbSelected.space, path, isDir));
    kel.crumbs.appendChild(b);
  };
  const space = kbSpaceOf(kbSelected.space);
  crumb(space ? (space.kind === "team" ? space.team : kbSpaceLabel(space)) : L("kb.root"), "", true, !parts.length);
  parts.forEach((part, i) => {
    const sep = document.createElement("span");
    sep.className = "kb-crumbs__sep";
    sep.textContent = "/";
    kel.crumbs.appendChild(sep);
    const last = i === parts.length - 1;
    crumb(part, parts.slice(0, i + 1).join("/"), last ? kbSelected.dir : true, last);
  });
  const atRoot = !kbSelected.path;
  const writable = kbCanWrite();
  kel.up.disabled = atRoot;
  [kel.rename, kel.move, kel.del].forEach(b => { b.disabled = atRoot || !writable; });
  // A read-only space (a parent team's, or the company's for non-admins) can't get new folders either.
  kel.newFolder.disabled = !writable;
  kel.newFolder.title = writable ? "" : L("kb.readOnlyHint");
  kel.download.hidden = kbSelected.dir;
  if (!kbSelected.dir) kel.download.href = kbFileUrl(kbSelected.path, true);
}

function renderKbFolder(entries) {
  kel.view.innerHTML = "";
  if (!entries.length) {
    kel.view.innerHTML = '<p class="kb-empty">' + L("kb.folderEmpty") + "</p>";
    return;
  }
  const table = document.createElement("table");
  table.className = "kb-list";
  table.innerHTML = "<thead><tr><th>" + L("kb.name") + "</th><th>" + L("kb.size") + "</th><th>"
    + L("kb.modified") + "</th></tr></thead>";
  const tbody = document.createElement("tbody");
  entries.forEach(e => {
    const tr = document.createElement("tr");
    tr.className = "kb-row";
    const name = document.createElement("td");
    name.textContent = kbIcon(e) + " " + e.name;
    const size = document.createElement("td");
    size.className = "kb-list__meta";
    size.textContent = e.dir ? "—" : kbFormatSize(e.size);
    const when = document.createElement("td");
    when.className = "kb-list__meta";
    when.textContent = formatDateTime(e.modifiedAt);
    tr.append(name, size, when);
    tr.addEventListener("click", () => kbSelect(e.path, e.dir));
    tbody.appendChild(tr);
  });
  table.appendChild(tbody);
  kel.view.appendChild(table);
}

async function renderKbFile(path) {
  const entry = (kbChildren.get(kbKey(kbSelected.space, kbParent(path))) || []).find(e => e.path === path);
  kel.view.innerHTML = "";
  const meta = document.createElement("p");
  meta.className = "kb-preview__meta";
  meta.textContent = entry ? kbFormatSize(entry.size) + " · " + formatDateTime(entry.modifiedAt) : "";
  kel.view.appendChild(meta);
  const name = kbBaseName(path);
  if (KB_IMAGE.test(name)) {
    const img = document.createElement("img");
    img.className = "kb-preview__img";
    img.alt = name;
    img.src = kbFileUrl(path);
    kel.view.appendChild(img);
    return;
  }
  if (!KB_MARKDOWN.test(name) && !KB_TEXT.test(name)) {
    const p = document.createElement("p");
    p.className = "kb-empty";
    p.textContent = L("kb.noPreview");
    kel.view.appendChild(p);
    return;
  }
  const res = await fetch(kbFileUrl(path));
  if (!res.ok) throw new Error(((await res.json().catch(() => null)) || {}).message || "HTTP " + res.status);
  const text = await res.text();
  if (kbSelected.path !== path) return; // selection moved on while loading
  if (KB_MARKDOWN.test(name)) {
    const div = document.createElement("div");
    div.className = "markdown";
    div.innerHTML = renderMarkdown(text);
    // Relative image links in a note resolve against the note's own folder in the knowledge base.
    div.querySelectorAll("img").forEach(img => {
      const src = img.getAttribute("src") || "";
      if (!src || /^([a-z]+:|\/|#)/i.test(src)) return;
      const parts = kbParent(path) ? kbParent(path).split("/") : [];
      src.split("/").forEach(seg => { if (seg === "..") parts.pop(); else if (seg && seg !== ".") parts.push(seg); });
      img.src = kbFileUrl(parts.join("/"));
    });
    kel.view.appendChild(div);
  } else {
    const pre = document.createElement("pre");
    pre.className = "kb-preview__text";
    pre.textContent = text;
    kel.view.appendChild(pre);
  }
}

function kbFormatSize(n) {
  if (n < 1024) return n + " B";
  if (n < 1024 * 1024) return (n / 1024).toFixed(1) + " KB";
  return (n / 1024 / 1024).toFixed(1) + " MB";
}

// One small dialog for new folder / rename / move / delete; action() does the request.
function kbOpenModal({ title, submit, danger, message, name, nameLabel, targets, action }) {
  kbModalAction = action;
  kel.modalTitle.textContent = title;
  kel.modalSubmit.textContent = submit;
  kel.modalSubmit.classList.toggle("btn--danger", !!danger);
  kel.modalMsg.hidden = !message;
  kel.modalMsg.textContent = message || "";
  kel.nameField.hidden = name === undefined;
  kel.nameField.querySelector("span").textContent = nameLabel || L("kb.name");
  kel.nameInput.value = name || "";
  kel.targetField.hidden = !targets;
  kel.targetSelect.innerHTML = "";
  (targets || []).forEach(t => {
    const o = document.createElement("option");
    o.value = t;
    o.textContent = t ? "/ " + t.split("/").join(" / ") : L("kb.root");
    kel.targetSelect.appendChild(o);
  });
  kel.modalError.hidden = true;
  kel.modal.hidden = false;
  if (name !== undefined) { kel.nameInput.focus(); kel.nameInput.select(); }
}
const kbCloseModal = () => { kel.modal.hidden = true; kbModalAction = null; };
kel.modal.querySelectorAll("[data-close]").forEach(x => x.addEventListener("click", kbCloseModal));
kel.form.addEventListener("submit", async e => {
  e.preventDefault();
  if (!kbModalAction) return;
  kel.modalSubmit.disabled = true;
  try {
    await kbModalAction();
    kbCloseModal();
  } catch (err) {
    kel.modalError.textContent = err.message;
    kel.modalError.hidden = false;
  } finally {
    kel.modalSubmit.disabled = false;
  }
});

kel.newFolder.addEventListener("click", () => {
  const { space } = kbSelected;
  const parent = kbCurrentFolder();
  kbOpenModal({
    title: parent ? L("kb.newFolderIn", { folder: parent }) : L("kb.newFolderTitle"),
    submit: L("kb.create"), name: "",
    action: async () => {
      const name = kel.nameInput.value.trim();
      if (!name) throw new Error(L("kb.nameRequired"));
      const created = await api.kbCreateFolder(space, parent ? parent + "/" + name : name);
      kbExpanded.add(kbKey(space, parent));
      kbSelected = { space, path: created.path, dir: true, name: created.name };
      await loadKnowledge();
    },
  });
});
kel.rename.addEventListener("click", () => {
  const { space, path, dir, name } = kbSelected;
  if (!path) return;
  kbOpenModal({
    title: L("kb.renameTitle"), submit: L("kb.rename"), name,
    action: async () => {
      const updated = await api.kbRename(space, path, kel.nameInput.value.trim());
      if (dir) kbRemapExpanded(space, path, updated.path);
      kbSelected = { space, path: updated.path, dir, name: updated.name };
      await loadKnowledge();
    },
  });
});
kel.move.addEventListener("click", async () => {
  const { space, path, dir, name } = kbSelected;
  if (!path) return;
  let folders = [];
  try { folders = await api.kbFolders(space); } catch (e) { showToast(e.message, true); return; }
  // A folder can't go into itself, and moving to where it already is is a no-op.
  const targets = folders.filter(f => f !== kbParent(path) && (!dir || (f !== path && !f.startsWith(path + "/"))));
  if (!targets.length) { showToast(L("kb.noTargets"), true); return; }
  kbOpenModal({
    title: L("kb.moveTitle", { name }), submit: L("kb.move"), targets,
    action: async () => {
      const target = kel.targetSelect.value;
      const moved = await api.kbMove(space, path, target);
      if (dir) kbRemapExpanded(space, path, moved.path);
      kbExpanded.add(kbKey(space, target));
      kbSelected = { space, path: moved.path, dir, name: moved.name };
      await loadKnowledge();
    },
  });
});
kel.del.addEventListener("click", () => {
  const { space, path, dir, name } = kbSelected;
  if (!path) return;
  kbOpenModal({
    title: L("kb.deleteTitle"), submit: L("kb.delete"), danger: true,
    message: L(dir ? "kb.deleteFolderConfirm" : "kb.deleteFileConfirm", { name }),
    action: async () => {
      await api.kbDelete(space, path);
      kbRemapExpanded(space, path, null);
      kbSelected = { space, path: kbParent(path), dir: true, name: kbBaseName(kbParent(path)) };
      await loadKnowledge();
    },
  });
});
kel.up.addEventListener("click", () => {
  if (kbSelected.path) kbSelect(kbSelected.space, kbParent(kbSelected.path), true);
});

// Task artifact → the signed-in user's knowledge base: pick a folder, optionally a new subfolder in it.
async function saveArtifactToKb(taskId, name) {
  let folders = [];
  try { folders = await api.kbFolders("personal"); } catch (e) { showToast(e.message, true); return; }
  kbOpenModal({
    title: L("tasks.saveToKbTitle", { name }), submit: L("tasks.saveToKbSubmit"),
    targets: folders, name: "", nameLabel: L("tasks.kbNewSubfolder"),
    action: async () => {
      const sub = kel.nameInput.value.trim();
      const folder = [kel.targetSelect.value, sub].filter(Boolean).join("/");
      const saved = await kbPost("/api/v1/tasks/" + encodeURIComponent(taskId) + "/artifacts/"
        + encodeURIComponent(name) + "/knowledge", { folder });
      kbSelected = { space: "personal", path: saved.path, dir: false, name: saved.name };
      kbExpanded.add(kbKey("personal", kbParent(saved.path)));
      showToast(L("tasks.savedToKb", { path: saved.path }), false);
    },
  });
}

// Keep open folders open across a rename/move (or drop them on delete).
function kbRemapExpanded(space, from, to) {
  const prefix = kbKey(space, from);
  Array.from(kbExpanded).forEach(key => {
    if (key === prefix || key.startsWith(prefix + "/")) {
      kbExpanded.delete(key);
      if (to !== null) kbExpanded.add(kbKey(space, to) + key.slice(prefix.length));
    }
  });
}

// Declared before setView: setView can run during load (OAuth return) and calls stopModelPolling.
let modelPollTimer = null;
let modelDetailId = null;

// View state lives in the URL hash (#tasks, #connectors, …) so a refresh or
// back/forward restores the same view. Admin-only views fall back to the default
// for non-admins. See initialView().
const KNOWN_VIEWS = new Set(["todo", "chat", "tasks", "inbox", "knowledge", "skills", "automation", "connectors", "models",
  "users", "settings", "analytics"]);
// The "Workspace" dropdown's pages, and the admin pages (shown with the admin sidebar).
const WORKSPACE_VIEWS = new Set(["inbox", "knowledge", "skills", "connectors"]);
const ADMIN_VIEWS = new Set(["settings", "analytics", "models", "users"]);
const DEFAULT_VIEW = "todo"; // the to-do list is home: the first thing to look at each day
let isAdmin = false;

function initialView() {
  const view = (window.location.hash || "").replace(/^#/, "");
  if (!KNOWN_VIEWS.has(view)) return DEFAULT_VIEW;
  if (ADMIN_VIEWS.has(view) && !isAdmin) return DEFAULT_VIEW;
  return view;
}

function setView(view) {
  if (window.location.hash !== "#" + view) {
    history.replaceState(history.state, document.title, window.location.pathname + window.location.search + "#" + view);
  }
  // Scheduled tasks are a tab of Tasks, so "Tasks" stays lit for both.
  const navView = view === "automation" ? "tasks" : view;
  navItems.forEach(a => a.classList.toggle("is-active", a.dataset.view === navView));
  // Top menu: the dropdown and "Admin" light up for any of their pages; the dropdown also says which one.
  const inWorkspace = WORKSPACE_VIEWS.has(view);
  const inAdmin = ADMIN_VIEWS.has(view);
  document.getElementById("workspaceMenu").classList.toggle("is-active", inWorkspace);
  const wsItem = inWorkspace ? navItems.find(a => a.dataset.view === view) : null;
  document.getElementById("workspaceCurrent").textContent = wsItem ? " · " + wsItem.textContent.trim() : "";
  document.getElementById("adminTopNav").classList.toggle("is-active", inAdmin);
  document.getElementById("adminSide").hidden = !inAdmin;
  document.querySelector(".layout").classList.toggle("layout--admin", inAdmin);
  closeWorkspaceMenu();
  const isChat = view === "chat";
  const isConnectors = view === "connectors";
  const isTasks = view === "tasks";
  const isUsers = view === "users";
  const isModels = view === "models";
  const isSettings = view === "settings";
  const isKnowledge = view === "knowledge";
  const isAutomation = view === "automation";
  const isSkills = view === "skills";
  const isInbox = view === "inbox";
  const isAnalytics = view === "analytics";
  const isTodo = view === "todo";
  todoPane.hidden = !isTodo;
  chatPanes.forEach(p => { p.hidden = !isChat; });
  analyticsPane.hidden = !isAnalytics;
  automationPane.hidden = !isAutomation;
  connectorsPane.hidden = !isConnectors;
  tasksPane.hidden = !isTasks;
  usersPane.hidden = !isUsers;
  modelsPane.hidden = !isModels;
  settingsPane.hidden = !isSettings;
  kbPane.hidden = !isKnowledge;
  skPane.hidden = !isSkills;
  if (!isSkills && !skel.drawer.hidden) closeSkillDrawer();
  ibPane.hidden = !isInbox;
  placeholderPane.hidden = isChat || isConnectors || isTasks || isUsers || isModels || isSettings || isKnowledge
    || isAutomation || isSkills || isInbox || isAnalytics || isTodo;
  if (!placeholderPane.hidden) placeholderText.textContent = L(placeholderLabels[view] || "placeholder.default");
  if (isConnectors) loadConnectors();
  if (isUsers) loadUsers();
  if (isSettings) loadSettingsForm();
  if (isKnowledge) loadKnowledge();
  if (isSkills) loadSkills();
  if (isInbox) loadInbox();
  if (isAnalytics) loadAnalytics();
  if (isTodo) loadTodos();
  if (isAutomation) { showAutoList(); loadAutomations(); }
  if (isTasks) { showTaskList(); loadTasks(); } else { stopTaskPolling(); }
  if (isModels) { showModelList(); startModelPolling(); } else { stopModelPolling(); }
}

navItems.forEach(a => a.addEventListener("click", e => {
  e.preventDefault();
  setView(a.dataset.view);
  closeUserMenu();
}));

// The account menu opens on hover; a click (touch screens) toggles it, and choosing an item or
// clicking elsewhere closes it. Blurring the focused item matters: :focus-within would keep it open.
const userMenu = document.getElementById("userMenu");
const userMenuBtn = document.getElementById("userMenuBtn");
function closeUserMenu() {
  userMenu.classList.remove("is-open");
  userMenuBtn.setAttribute("aria-expanded", "false");
  if (userMenu.contains(document.activeElement)) document.activeElement.blur();
}
userMenuBtn.addEventListener("click", e => {
  e.stopPropagation();
  const open = !userMenu.classList.contains("is-open");
  userMenu.classList.toggle("is-open", open);
  userMenuBtn.setAttribute("aria-expanded", String(open));
});
document.addEventListener("click", e => {
  if (!userMenu.contains(e.target)) closeUserMenu();
  if (!workspaceMenu.contains(e.target)) closeWorkspaceMenu();
});

// The "Workspace" dropdown in the top menu: same behaviour as the account menu (hover, or click on touch).
const workspaceMenu = document.getElementById("workspaceMenu");
const workspaceBtn = document.getElementById("workspaceBtn");
function closeWorkspaceMenu() {
  workspaceMenu.classList.remove("is-open");
  workspaceBtn.setAttribute("aria-expanded", "false");
  if (workspaceMenu.contains(document.activeElement)) document.activeElement.blur();
}
workspaceBtn.addEventListener("click", e => {
  e.stopPropagation();
  const open = !workspaceMenu.classList.contains("is-open");
  workspaceMenu.classList.toggle("is-open", open);
  workspaceBtn.setAttribute("aria-expanded", String(open));
});

// "Admin" opens the first admin page; the admin sidebar takes it from there.
document.getElementById("adminTopNav").addEventListener("click", e => { e.preventDefault(); setView("settings"); });

// The logo goes home: the Agent page with the user's SuperAgent open.
document.getElementById("brandHome").addEventListener("click", e => {
  e.preventDefault();
  setView("chat");
  const home = agents.find(a => a.kind === "super") || agents[0];
  if (home && home.id !== activeAgentId) selectAgent(home);
});
document.addEventListener("keydown", e => { if (e.key === "Escape") { closeUserMenu(); closeWorkspaceMenu(); } });

// Back/forward (and manual hash edits) re-drive the view from the URL.
window.addEventListener("hashchange", () => setView(initialView()));
