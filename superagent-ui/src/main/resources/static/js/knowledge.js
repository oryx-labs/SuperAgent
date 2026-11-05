// SuperAgent UI — Knowledge base: file tree, preview, folders.
// Classic script sharing one global scope with the others; index.html loads them in order.
"use strict";

// ---------- knowledge ----------
// The signed-in user's private file-system knowledge base. Paths are "/"-separated, "" is the root.
// Declared before setView: the initial view (from the URL hash) may be #knowledge.
Object.assign(api, {
  kbList: path => fetch("/api/v1/knowledge/entries?path=" + encodeURIComponent(path)).then(kbJson),
  kbFolders: () => fetch("/api/v1/knowledge/folders").then(kbJson),
  kbCreateFolder: path => kbPost("/api/v1/knowledge/folders", { path }),
  kbRename: (path, newName) => kbPost("/api/v1/knowledge/rename", { path, newName }),
  kbMove: (path, targetDir) => kbPost("/api/v1/knowledge/move", { path, targetDir }),
  kbDelete: path => fetch("/api/v1/knowledge/entries?path=" + encodeURIComponent(path), { method: "DELETE" }).then(kbJson),
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
const kbFileUrl = (path, download) =>
  "/api/v1/knowledge/file?path=" + encodeURIComponent(path) + (download ? "&download=true" : "");

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
const kbChildren = new Map();      // folder path -> entries, filled lazily as folders are opened
const kbExpanded = new Set([""]);  // folders open in the tree
let kbSelected = { path: "", dir: true, name: "" };
let kbModalAction = null;

const kbParent = path => path.includes("/") ? path.slice(0, path.lastIndexOf("/")) : "";
const kbBaseName = path => path.slice(path.lastIndexOf("/") + 1);
const kbCurrentFolder = () => kbSelected.dir ? kbSelected.path : kbParent(kbSelected.path);
const KB_IMAGE = /\.(png|jpe?g|gif|webp|svg)$/i;
const KB_MARKDOWN = /\.(md|markdown)$/i;
const KB_TEXT = /\.(txt|log|csv|json|ya?ml|xml|html)$/i;
const kbIcon = e => e.dir ? "📁" : KB_IMAGE.test(e.name) ? "🖼️" : KB_MARKDOWN.test(e.name) ? "📝" : "📄";

async function kbLoad(path) {
  const entries = await api.kbList(path);
  kbChildren.set(path, entries || []);
  return kbChildren.get(path);
}

async function loadKnowledge() {
  kbChildren.clear();
  // Re-read every open folder; ones deleted since (e.g. on disk) just close.
  for (const path of Array.from(kbExpanded)) {
    try { await kbLoad(path); } catch (e) { kbExpanded.delete(path); }
  }
  if (!kbExpanded.has("")) { kbExpanded.add(""); await kbLoad(""); }
  renderKbTree();
  await kbSelect(kbSelected.path, kbSelected.dir, true);
}

function renderKbTree() {
  kel.tree.innerHTML = "";
  const walk = (folder, depth) => {
    (kbChildren.get(folder) || []).forEach(e => {
      const row = document.createElement("div");
      row.className = "kb-node" + (e.path === kbSelected.path ? " is-selected" : "");
      row.style.paddingLeft = (6 + depth * 14) + "px";
      row.setAttribute("role", "treeitem");
      const open = e.dir && kbExpanded.has(e.path);
      if (e.dir) row.setAttribute("aria-expanded", String(open));
      const twisty = document.createElement("span");
      twisty.className = "kb-node__twisty";
      twisty.textContent = e.dir ? (open ? "▾" : "▸") : "";
      const name = document.createElement("span");
      name.className = "kb-node__name";
      name.textContent = kbIcon(e) + " " + e.name;
      row.append(twisty, name);
      twisty.addEventListener("click", ev => { ev.stopPropagation(); if (e.dir) kbToggle(e.path); });
      row.addEventListener("click", () => kbSelect(e.path, e.dir));
      kel.tree.appendChild(row);
      if (open) walk(e.path, depth + 1);
    });
  };
  walk("", 0);
  if (!(kbChildren.get("") || []).length) {
    kel.tree.innerHTML = '<p class="kb-empty kb-empty--tree">' + L("kb.treeEmpty") + "</p>";
  }
}

async function kbToggle(path) {
  if (kbExpanded.has(path)) {
    kbExpanded.delete(path);
  } else {
    kbExpanded.add(path);
    if (!kbChildren.has(path)) {
      try { await kbLoad(path); } catch (e) { kbExpanded.delete(path); showToast(e.message, true); }
    }
  }
  renderKbTree();
}

// Selecting a folder opens it in the tree and lists it; selecting a file previews it.
async function kbSelect(path, dir, quiet) {
  kbSelected = { path, dir, name: kbBaseName(path) };
  if (dir) {
    // Open every ancestor too, so the selection is visible in the tree.
    let p = path;
    while (p) { kbExpanded.add(p); p = kbParent(p); }
    for (const f of Array.from(kbExpanded)) {
      if (!kbChildren.has(f)) { try { await kbLoad(f); } catch (e) { kbExpanded.delete(f); } }
    }
  }
  renderKbTree();
  renderKbBar();
  try {
    if (dir) renderKbFolder(kbChildren.has(path) ? kbChildren.get(path) : await kbLoad(path));
    else await renderKbFile(path);
  } catch (e) {
    if (!quiet) showToast(e.message, true);
    if (path) { kbSelect("", true, true); return; } // gone: fall back to the root
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
    b.addEventListener("click", () => kbSelect(path, isDir));
    kel.crumbs.appendChild(b);
  };
  crumb(L("kb.root"), "", true, !parts.length);
  parts.forEach((part, i) => {
    const sep = document.createElement("span");
    sep.className = "kb-crumbs__sep";
    sep.textContent = "/";
    kel.crumbs.appendChild(sep);
    const last = i === parts.length - 1;
    crumb(part, parts.slice(0, i + 1).join("/"), last ? kbSelected.dir : true, last);
  });
  const atRoot = !kbSelected.path;
  kel.up.disabled = atRoot;
  [kel.rename, kel.move, kel.del].forEach(b => { b.disabled = atRoot; });
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
  const entry = (kbChildren.get(kbParent(path)) || []).find(e => e.path === path);
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
  const parent = kbCurrentFolder();
  kbOpenModal({
    title: parent ? L("kb.newFolderIn", { folder: parent }) : L("kb.newFolderTitle"),
    submit: L("kb.create"), name: "",
    action: async () => {
      const name = kel.nameInput.value.trim();
      if (!name) throw new Error(L("kb.nameRequired"));
      const created = await api.kbCreateFolder(parent ? parent + "/" + name : name);
      kbExpanded.add(parent);
      kbSelected = { path: created.path, dir: true, name: created.name };
      await loadKnowledge();
    },
  });
});
kel.rename.addEventListener("click", () => {
  const { path, dir, name } = kbSelected;
  if (!path) return;
  kbOpenModal({
    title: L("kb.renameTitle"), submit: L("kb.rename"), name,
    action: async () => {
      const updated = await api.kbRename(path, kel.nameInput.value.trim());
      if (dir) kbRemapExpanded(path, updated.path);
      kbSelected = { path: updated.path, dir, name: updated.name };
      await loadKnowledge();
    },
  });
});
kel.move.addEventListener("click", async () => {
  const { path, dir, name } = kbSelected;
  if (!path) return;
  let folders = [];
  try { folders = await api.kbFolders(); } catch (e) { showToast(e.message, true); return; }
  // A folder can't go into itself, and moving to where it already is is a no-op.
  const targets = folders.filter(f => f !== kbParent(path) && (!dir || (f !== path && !f.startsWith(path + "/"))));
  if (!targets.length) { showToast(L("kb.noTargets"), true); return; }
  kbOpenModal({
    title: L("kb.moveTitle", { name }), submit: L("kb.move"), targets,
    action: async () => {
      const target = kel.targetSelect.value;
      const moved = await api.kbMove(path, target);
      if (dir) kbRemapExpanded(path, moved.path);
      kbExpanded.add(target);
      kbSelected = { path: moved.path, dir, name: moved.name };
      await loadKnowledge();
    },
  });
});
kel.del.addEventListener("click", () => {
  const { path, dir, name } = kbSelected;
  if (!path) return;
  kbOpenModal({
    title: L("kb.deleteTitle"), submit: L("kb.delete"), danger: true,
    message: L(dir ? "kb.deleteFolderConfirm" : "kb.deleteFileConfirm", { name }),
    action: async () => {
      await api.kbDelete(path);
      kbRemapExpanded(path, null);
      kbSelected = { path: kbParent(path), dir: true, name: kbBaseName(kbParent(path)) };
      await loadKnowledge();
    },
  });
});
kel.up.addEventListener("click", () => {
  if (kbSelected.path) kbSelect(kbParent(kbSelected.path), true);
});

// Task artifact → the signed-in user's knowledge base: pick a folder, optionally a new subfolder in it.
async function saveArtifactToKb(taskId, name) {
  let folders = [];
  try { folders = await api.kbFolders(); } catch (e) { showToast(e.message, true); return; }
  kbOpenModal({
    title: L("tasks.saveToKbTitle", { name }), submit: L("tasks.saveToKbSubmit"),
    targets: folders, name: "", nameLabel: L("tasks.kbNewSubfolder"),
    action: async () => {
      const sub = kel.nameInput.value.trim();
      const folder = [kel.targetSelect.value, sub].filter(Boolean).join("/");
      const saved = await kbPost("/api/v1/tasks/" + encodeURIComponent(taskId) + "/artifacts/"
        + encodeURIComponent(name) + "/knowledge", { folder });
      kbSelected = { path: saved.path, dir: false, name: saved.name };
      kbExpanded.add(kbParent(saved.path));
      showToast(L("tasks.savedToKb", { path: saved.path }), false);
    },
  });
}

// Keep open folders open across a rename/move (or drop them on delete).
function kbRemapExpanded(from, to) {
  Array.from(kbExpanded).forEach(p => {
    if (p === from || p.startsWith(from + "/")) {
      kbExpanded.delete(p);
      if (to !== null) kbExpanded.add(to + p.slice(from.length));
    }
  });
}

// Declared before setView: setView can run during load (OAuth return) and calls stopModelPolling.
let modelPollTimer = null;
let modelDetailId = null;

// View state lives in the URL hash (#tasks, #connectors, …) so a refresh or
// back/forward restores the same view. Admin-only views fall back to the default
// for non-admins. See initialView().
const KNOWN_VIEWS = new Set(["chat", "tasks", "inbox", "knowledge", "skills", "automation", "connectors", "models", "users", "settings"]);
const ADMIN_VIEWS = new Set(["models", "users", "settings"]);
const DEFAULT_VIEW = "chat";
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
  navItems.forEach(a => a.classList.toggle("is-active", a.dataset.view === view));
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
  chatPanes.forEach(p => { p.hidden = !isChat; });
  automationPane.hidden = !isAutomation;
  connectorsPane.hidden = !isConnectors;
  tasksPane.hidden = !isTasks;
  usersPane.hidden = !isUsers;
  modelsPane.hidden = !isModels;
  settingsPane.hidden = !isSettings;
  kbPane.hidden = !isKnowledge;
  skPane.hidden = !isSkills;
  ibPane.hidden = !isInbox;
  placeholderPane.hidden = isChat || isConnectors || isTasks || isUsers || isModels || isSettings || isKnowledge
    || isAutomation || isSkills || isInbox;
  if (!placeholderPane.hidden) placeholderText.textContent = L(placeholderLabels[view] || "placeholder.default");
  if (isConnectors) loadConnectors();
  if (isUsers) loadUsers();
  if (isSettings) loadSettingsForm();
  if (isKnowledge) loadKnowledge();
  if (isSkills) loadSkills();
  if (isInbox) loadInbox();
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
document.addEventListener("click", e => { if (!userMenu.contains(e.target)) closeUserMenu(); });

// The logo goes home: the Agent page with the user's SuperAgent open.
document.getElementById("brandHome").addEventListener("click", e => {
  e.preventDefault();
  setView("chat");
  const home = agents.find(a => a.kind === "super") || agents[0];
  if (home && home.id !== activeAgentId) selectAgent(home);
});
document.addEventListener("keydown", e => { if (e.key === "Escape") closeUserMenu(); });

// Back/forward (and manual hash edits) re-drive the view from the URL.
window.addEventListener("hashchange", () => setView(initialView()));
