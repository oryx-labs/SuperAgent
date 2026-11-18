// SuperAgent UI — Files: each person's folder tree. Folders are theirs alone; a file sits in one of them (or none)
// for each person who can see it. Files come in by dragging, by the select in the preview, or from agents.
// Below them, each project's shared tree: one file can be in several projects; taking it out never deletes it.
// Classic script sharing one global scope with the others; index.html loads them in order.
"use strict";

Object.assign(api, {
  ffList: () => fetch("/api/v1/files/folders").then(kbJson),
  ffCreate: (name, parentId) => kbPost("/api/v1/files/folders", { name, parentId }),
  ffRename: (id, name) => fetch("/api/v1/files/folders/" + encodeURIComponent(id), {
    method: "PUT", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ name }),
  }).then(kbJson),
  ffDelete: id => fetch("/api/v1/files/folders/" + encodeURIComponent(id), { method: "DELETE" }).then(kbJson),
  ffMoveFile: (id, folderId) => fetch("/api/v1/files/" + encodeURIComponent(id) + "/folder", {
    method: "PUT", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ folderId }),
  }).then(kbJson),
  pfList: () => fetch("/api/v1/files/project-folders").then(kbJson),
  pfCreate: (projectId, name, parentId) => kbPost("/api/v1/files/project-folders", { projectId, name, parentId }),
  pfRename: (id, name) => fetch("/api/v1/files/project-folders/" + encodeURIComponent(id), {
    method: "PUT", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ name }),
  }).then(kbJson),
  pfDelete: id => fetch("/api/v1/files/project-folders/" + encodeURIComponent(id), { method: "DELETE" }).then(kbJson),
  pfMount: (fileId, projectId, folderId) => fetch("/api/v1/files/" + encodeURIComponent(fileId) + "/projects", {
    method: "PUT", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ projectId, folderId }),
  }).then(kbJson),
  pfUnmount: (fileId, projectId) => fetch("/api/v1/files/" + encodeURIComponent(fileId) + "/projects/"
    + encodeURIComponent(projectId), { method: "DELETE" }).then(kbJson),
});

const FF_ALL = "*";       // every file
const FF_UNFILED = "";    // files in no folder
const FF_AGENT = "agent:"; // + an agent id: every file that agent made or was given, a folder of its own nobody keeps up
const ffAgentId = id => typeof id === "string" && id.startsWith(FF_AGENT) ? id.slice(FF_AGENT.length) : null;
const FF_PROJECT = "project:"; // + a project id: that project's shared root
const FF_PFOLDER = "pfolder:"; // + a project folder id
const FF_MAX_DEPTH = 3;
const ffEl = {
  tree: document.getElementById("ffTree"),
  add: document.getElementById("ffAdd"),
  move: document.getElementById("ffMove"),
  projects: document.getElementById("ffProjects"),
};
let ffFolders = [];       // [{id, name, parentId, path}], sorted by path
let ffChosen = FF_ALL;
const ffCollapsed = new Set();
let ffProjects = [];       // [{projectId, name, archived, writable, folders, placements: {fileId: folderId|""}}]
let ffProjectFiles = null; // the chosen project's files (members see files there they may not see elsewhere)

async function ffLoad() {
  const [folders, projects] = await Promise.all([
    api.ffList().catch(e => { showToast(e.message, true); return []; }),
    api.pfList().catch(() => []),
  ]);
  ffFolders = folders || [];
  ffProjects = projects || [];
  if (ffChosen !== FF_ALL && ffChosen !== FF_UNFILED && !ffAgentId(ffChosen) && !ffProjectTree(ffChosen)
    && !ffFolders.some(f => f.id === ffChosen)) ffChosen = FF_ALL;
  await ffLoadProjectFiles();
}

/** The project a tree node belongs to (its root or one of its folders), or null for the person's own nodes. */
function ffProjectTree(id) {
  if (typeof id !== "string") return null;
  if (id.startsWith(FF_PROJECT)) return ffProjects.find(t => t.projectId === id.slice(FF_PROJECT.length)) || null;
  if (id.startsWith(FF_PFOLDER)) {
    const fid = id.slice(FF_PFOLDER.length);
    return ffProjects.find(t => t.folders.some(f => f.id === fid)) || null;
  }
  return null;
}

async function ffLoadProjectFiles() {
  const tree = ffProjectTree(ffChosen);
  ffProjectFiles = null;
  if (!tree) return;
  try { ffProjectFiles = (await api.projectFiles(tree.projectId)) || []; } catch (e) { ffProjectFiles = []; showToast(e.message, true); }
}

/** What the table lists: the chosen project's files, or the person's own view. */
const ffPool = () => (ffProjectTree(ffChosen) && ffProjectFiles) || files;

function ffPfSubtree(tree, id) {
  const out = new Set([id]);
  tree.folders.forEach(f => { let at = f; while (at) { if (at.id === id) out.add(f.id); at = tree.folders.find(p => p.id === at.parentId); } });
  return out;
}

/** Ids of the files in a project node and everything under it. */
function ffProjectFileIds(id) {
  const tree = ffProjectTree(id);
  if (!tree) return new Set();
  const placed = Object.entries(tree.placements || {});
  if (id.startsWith(FF_PROJECT)) return new Set(placed.map(([fileId]) => fileId));
  const inside = ffPfSubtree(tree, id.slice(FF_PFOLDER.length));
  return new Set(placed.filter(([, at]) => inside.has(at)).map(([fileId]) => fileId));
}

// A folder and everything under it.
function ffSubtree(id) {
  const out = new Set([id]);
  ffFolders.forEach(f => { let at = f; while (at) { if (at.id === id) out.add(f.id); at = ffFolders.find(p => p.id === at.parentId); } });
  return out;
}

function ffInScope(file) {
  if (ffChosen === FF_ALL) return true;
  if (ffProjectTree(ffChosen)) return ffProjectFileIds(ffChosen).has(file.id);
  if (ffAgentId(ffChosen)) return file.agentId === ffAgentId(ffChosen);
  if (ffChosen === FF_UNFILED) return !file.folderId;
  return ffSubtree(ffChosen).has(file.folderId);
}

function ffRenderTree() {
  const count = id => {
    if (id === FF_ALL) return files.length;
    if (ffAgentId(id)) return files.filter(f => f.agentId === ffAgentId(id)).length;
    if (id === FF_UNFILED) return files.filter(f => !f.folderId).length;
    const inside = ffSubtree(id);
    return files.filter(f => inside.has(f.folderId)).length;
  };
  const row = (id, name, depth, folder) => {
    const item = document.createElement("div");
    const kind = id === FF_ALL ? "all" : id === FF_UNFILED ? "unfiled" : ffAgentId(id) ? "agent" : "folder";
    item.className = "ff-node ff-node--" + kind + (ffChosen === id ? " is-chosen" : "") + (depth > 0 ? " is-nested" : "");
    item.style.setProperty("--ff-depth", depth);
    item.setAttribute("role", "treeitem");
    const hasKids = folder && ffFolders.some(f => f.parentId === folder.id);
    const caret = document.createElement("button");
    caret.type = "button";
    caret.className = "ff-node__caret" + (hasKids ? " has-kids" + (ffCollapsed.has(id) ? "" : " is-open") : "");
    caret.setAttribute("aria-label", hasKids ? L(ffCollapsed.has(id) ? "ff.expand" : "ff.collapse") : "");
    caret.tabIndex = hasKids ? 0 : -1;
    caret.addEventListener("click", e => {
      e.stopPropagation();
      if (!hasKids) return;
      if (!ffCollapsed.delete(id)) ffCollapsed.add(id);
      ffRenderTree();
    });
    const label = document.createElement("button");
    label.type = "button";
    label.className = "ff-node__name";
    label.textContent = name;
    label.title = folder ? folder.path : name;
    label.addEventListener("click", () => ffChoose(id));
    const n = document.createElement("span");
    n.className = "ff-node__count";
    n.textContent = count(id) || "";
    const icon = document.createElement("span");
    icon.className = "ff-node__icon";
    icon.setAttribute("aria-hidden", "true");
    if (kind === "agent") icon.textContent = (files.find(f => f.agentId === ffAgentId(id)) || {}).agentAvatar || "🙂";
    item.append(caret, icon, label, n);
    if (folder) item.appendChild(ffActions(folder, depth));
    if (id !== FF_ALL && kind !== "agent") ffMakeDropTarget(item, id); // an agent's folder follows its files, not drops
    return item;
  };
  const nodes = [row(FF_ALL, L("ff.all"), 0), row(FF_UNFILED, L("ff.unfiled"), 0), document.createElement("div")];
  nodes[2].className = "ff-sep";
  if (!ffFolders.length) {
    const hint = document.createElement("p");
    hint.className = "ff-hint";
    hint.textContent = L("ff.noFolders");
    nodes.push(hint);
  }
  const walk = (parentId, depth) => ffFolders.filter(f => (f.parentId || null) === parentId).forEach(f => {
    nodes.push(row(f.id, f.name, depth, f));
    if (!ffCollapsed.has(f.id)) walk(f.id, depth + 1);
  });
  walk(null, 0);
  // One folder per agent, made from the files themselves: everything an agent saved or was handed, kept together.
  const byAgent = new Map();
  files.filter(f => f.agentId).forEach(f => { if (!byAgent.has(f.agentId)) byAgent.set(f.agentId, f.agentName || f.agentId); });
  if (byAgent.size) {
    const head = document.createElement("div");
    head.className = "ff-section";
    head.textContent = L("ff.agents");
    head.title = L("ff.agentsHint");
    nodes.push(head);
    [...byAgent].sort((a, b) => a[1].localeCompare(b[1])).forEach(([id, name]) => nodes.push(row(FF_AGENT + id, name, 0)));
  }
  nodes.push(...ffProjectNodes());
  ffEl.tree.replaceChildren(...nodes);
}

async function ffChoose(id) {
  ffChosen = id;
  await ffLoadProjectFiles();
  renderFileTable();
}

// ---------- projects' shared trees ----------

function ffProjectNodes() {
  if (!ffProjects.length) return [];
  const head = document.createElement("div");
  head.className = "ff-section";
  head.textContent = L("ff.projects");
  head.title = L("ff.projectsHint");
  const nodes = [head];
  ffProjects.forEach(tree => {
    const rootId = FF_PROJECT + tree.projectId;
    nodes.push(ffProjectNode(tree, rootId, tree.name + (tree.archived ? " · " + L("proj.archived") : ""), 0, null));
    if (ffCollapsed.has(rootId)) return;
    const walk = (parentId, depth) => tree.folders.filter(f => (f.parentId || null) === parentId).forEach(f => {
      nodes.push(ffProjectNode(tree, FF_PFOLDER + f.id, f.name, depth, f));
      if (!ffCollapsed.has(FF_PFOLDER + f.id)) walk(f.id, depth + 1);
    });
    walk(null, 1);
  });
  // While looking at a project, a place to drag its files out of it (they stay everywhere else).
  const chosen = ffProjectTree(ffChosen);
  if (chosen && chosen.writable) {
    const out = document.createElement("div");
    out.className = "ff-node ff-node--out";
    out.textContent = L("ff.dropOut", { name: chosen.name });
    const isFile = e => e.dataTransfer.types.includes("application/x-superagent-file");
    out.addEventListener("dragover", e => { if (isFile(e)) { e.preventDefault(); out.classList.add("is-drop"); } });
    out.addEventListener("dragleave", () => out.classList.remove("is-drop"));
    out.addEventListener("drop", e => {
      if (!isFile(e)) return;
      e.preventDefault();
      out.classList.remove("is-drop");
      ffUnmount(e.dataTransfer.getData("application/x-superagent-file"), chosen);
    });
    nodes.push(out);
  }
  return nodes;
}

function ffProjectNode(tree, id, name, depth, folder) {
  const item = document.createElement("div");
  item.className = "ff-node " + (folder ? "ff-node--folder" : "ff-node--project") + (ffChosen === id ? " is-chosen" : "")
    + (depth > 0 ? " is-nested" : "") + (tree.archived ? " is-archived" : "");
  item.style.setProperty("--ff-depth", depth);
  item.setAttribute("role", "treeitem");
  const hasKids = folder ? tree.folders.some(f => f.parentId === folder.id) : tree.folders.length > 0;
  const caret = document.createElement("button");
  caret.type = "button";
  caret.className = "ff-node__caret" + (hasKids ? " has-kids" + (ffCollapsed.has(id) ? "" : " is-open") : "");
  caret.tabIndex = hasKids ? 0 : -1;
  caret.addEventListener("click", e => {
    e.stopPropagation();
    if (!hasKids) return;
    if (!ffCollapsed.delete(id)) ffCollapsed.add(id);
    ffRenderTree();
  });
  const icon = document.createElement("span");
  icon.className = "ff-node__icon";
  icon.setAttribute("aria-hidden", "true");
  const label = document.createElement("button");
  label.type = "button";
  label.className = "ff-node__name";
  label.textContent = name;
  label.title = folder ? tree.name + "/" + folder.path : L("ff.projectsHint");
  label.addEventListener("click", () => ffChoose(id));
  const n = document.createElement("span");
  n.className = "ff-node__count";
  n.textContent = ffProjectFileIds(id).size || "";
  item.append(caret, icon, label, n);
  if (tree.writable) {
    const box = document.createElement("span");
    box.className = "ff-node__actions";
    const act = (text, title, handler) => {
      const b = document.createElement("button");
      b.type = "button";
      b.textContent = text;
      b.title = title;
      b.setAttribute("aria-label", title + " " + name);
      b.addEventListener("click", e => { e.stopPropagation(); handler(); });
      box.appendChild(b);
    };
    if (depth < FF_MAX_DEPTH) {
      act("+", L(folder ? "ff.newSub" : "ff.new"), () => ffAsk(L(folder ? "ff.newSub" : "ff.new"), "",
        nm => api.pfCreate(tree.projectId, nm, folder ? folder.id : null)));
    }
    if (folder) {
      act("✎", L("ff.rename"), () => ffAsk(L("ff.rename"), folder.name, nm => api.pfRename(folder.id, nm)));
      act("×", L("common.delete"), () => kbOpenModal({
        title: L("ff.deleteTitle"), submit: L("common.delete"), danger: true,
        message: L("ff.deleteConfirm", { name: folder.name }),
        action: async () => { await api.pfDelete(folder.id); await loadFiles(); },
      }));
    }
    item.appendChild(box);
    const isFile = e => e.dataTransfer.types.includes("application/x-superagent-file");
    item.addEventListener("dragover", e => { if (isFile(e)) { e.preventDefault(); item.classList.add("is-drop"); } });
    item.addEventListener("dragleave", () => item.classList.remove("is-drop"));
    item.addEventListener("drop", e => {
      if (!isFile(e)) return;
      e.preventDefault();
      item.classList.remove("is-drop");
      ffMount(e.dataTransfer.getData("application/x-superagent-file"), tree, folder ? folder.id : "");
    });
  }
  return item;
}

async function ffMount(fileId, tree, folderId) {
  try { await api.pfMount(fileId, tree.projectId, folderId); } catch (e) { showToast(e.message, true); return; }
  showToast(L("ff.mounted", { name: tree.name }));
  await ffAfterProjectChange();
}

async function ffUnmount(fileId, tree) {
  try { await api.pfUnmount(fileId, tree.projectId); } catch (e) { showToast(e.message, true); return; }
  showToast(L("ff.unmounted", { name: tree.name }));
  await ffAfterProjectChange();
}

async function ffAfterProjectChange() {
  try { ffProjects = (await api.pfList()) || []; } catch (e) { /* keep what we had */ }
  await ffLoadProjectFiles();
  if (!filesPane.hidden) renderFileTable();
  if (flOpen) ffFillProjects(flOpen);
}

/** In the preview: the projects the file is in (× takes it out), and adding it to another. */
function ffFillProjects(file) {
  const box = ffEl.projects;
  if (!box) return;
  const inside = ffProjects.filter(t => t.placements && Object.prototype.hasOwnProperty.call(t.placements, file.id));
  const addable = ffProjects.filter(t => t.writable && !inside.includes(t));
  box.replaceChildren();
  box.hidden = !inside.length && !addable.length;
  if (box.hidden) return;
  const label = document.createElement("span");
  label.className = "ff-projects__label";
  label.textContent = L("ff.inProjects");
  box.appendChild(label);
  inside.forEach(t => {
    const chip = document.createElement("span");
    chip.className = "ff-projects__chip";
    chip.textContent = t.name;
    if (t.writable) {
      const x = document.createElement("button");
      x.type = "button";
      x.textContent = "×";
      x.title = L("ff.removeFromProject", { name: t.name });
      x.setAttribute("aria-label", x.title);
      x.addEventListener("click", () => ffUnmount(file.id, t));
      chip.appendChild(x);
    }
    box.appendChild(chip);
  });
  if (addable.length) {
    const sel = document.createElement("select");
    sel.append(new Option(L("ff.addToProject"), ""), ...addable.map(t => new Option(t.name, t.projectId)));
    sel.addEventListener("change", () => {
      const t = addable.find(x => x.projectId === sel.value);
      if (t) ffMount(file.id, t, "");
    });
    box.appendChild(sel);
  }
}

function ffActions(folder, depth) {
  const box = document.createElement("span");
  box.className = "ff-node__actions";
  const act = (text, title, handler) => {
    const b = document.createElement("button");
    b.type = "button";
    b.textContent = text;
    b.title = title;
    b.setAttribute("aria-label", title + " " + folder.name);
    b.addEventListener("click", e => { e.stopPropagation(); handler(); });
    box.appendChild(b);
  };
  if (depth + 1 < FF_MAX_DEPTH) act("+", L("ff.newSub"), () => ffAsk(L("ff.newSub"), "", name => api.ffCreate(name, folder.id)));
  act("✎", L("ff.rename"), () => ffAsk(L("ff.rename"), folder.name, name => api.ffRename(folder.id, name)));
  act("×", L("common.delete"), () => kbOpenModal({
    title: L("ff.deleteTitle"), submit: L("common.delete"), danger: true,
    message: L("ff.deleteConfirm", { name: folder.name }),
    action: async () => { await api.ffDelete(folder.id); await loadFiles(); },
  }));
  return box;
}

function ffAsk(title, name, save) {
  kbOpenModal({
    title, submit: L("common.save"), name, nameLabel: L("ff.name"),
    action: async () => { await save(kel.nameInput.value.trim()); await loadFiles(); },
  });
}
ffEl.add.addEventListener("click", () => ffAsk(L("ff.new"), "", name => api.ffCreate(name, null)));

// ---------- moving a file ----------

async function ffMove(fileId, folderId) {
  try {
    const updated = await api.ffMoveFile(fileId, folderId);
    files = files.map(f => f.id === updated.id ? updated : f);
    if (flOpen && flOpen.id === updated.id) flOpen = updated;
    renderFileTable();
    return updated;
  } catch (e) { showToast(e.message, true); return null; }
}

function ffMakeDraggable(row, file) {
  row.draggable = true;
  row.addEventListener("dragstart", e => {
    e.dataTransfer.setData("application/x-superagent-file", file.id);
    e.dataTransfer.effectAllowed = "move";
  });
}

function ffMakeDropTarget(el, folderId) {
  const isFile = e => e.dataTransfer.types.includes("application/x-superagent-file");
  el.addEventListener("dragover", e => { if (isFile(e)) { e.preventDefault(); el.classList.add("is-drop"); } });
  el.addEventListener("dragleave", () => el.classList.remove("is-drop"));
  el.addEventListener("drop", e => {
    if (!isFile(e)) return;
    e.preventDefault();
    el.classList.remove("is-drop");
    ffMove(e.dataTransfer.getData("application/x-superagent-file"), folderId);
  });
}

// The select in the preview; the drawer also opens from a chat, where the tree may never have loaded.
async function ffFillMove(file) {
  if (!ffFolders.length && !ffProjects.length) await ffLoad();
  if (flOpen !== file) return;
  ffFillProjects(file);
  ffEl.move.replaceChildren(new Option(L("ff.unfiled"), ""), ...ffFolders.map(f => new Option(f.path, f.id)));
  ffEl.move.value = file.folderId || "";
  ffEl.move.closest(".ff-move").hidden = !ffFolders.length;
}
ffEl.move.addEventListener("change", async () => {
  if (!flOpen) return;
  const fileId = flOpen.id;
  const saved = await api.ffMoveFile(fileId, ffEl.move.value).catch(e => { showToast(e.message, true); return null; });
  if (!saved) { ffEl.move.value = flOpen.folderId || ""; return; }
  files = files.map(f => f.id === fileId ? saved : f);
  flOpen = saved;
  if (!filesPane.hidden) renderFileTable();
});
