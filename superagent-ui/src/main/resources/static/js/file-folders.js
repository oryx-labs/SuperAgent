// SuperAgent UI — Files: each person's folder tree. Folders are theirs alone; a file sits in one of them (or none)
// for each person who can see it. Files come in by dragging, by the select in the preview, or from agents.
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
});

const FF_ALL = "*";       // every file
const FF_UNFILED = "";    // files in no folder
const FF_MAX_DEPTH = 3;
const ffEl = {
  tree: document.getElementById("ffTree"),
  add: document.getElementById("ffAdd"),
  move: document.getElementById("ffMove"),
};
let ffFolders = [];       // [{id, name, parentId, path}], sorted by path
let ffChosen = FF_ALL;
const ffCollapsed = new Set();

async function ffLoad() {
  try { ffFolders = (await api.ffList()) || []; } catch (e) { ffFolders = []; showToast(e.message, true); }
  if (ffChosen !== FF_ALL && ffChosen !== FF_UNFILED && !ffFolders.some(f => f.id === ffChosen)) ffChosen = FF_ALL;
}

// A folder and everything under it.
function ffSubtree(id) {
  const out = new Set([id]);
  ffFolders.forEach(f => { let at = f; while (at) { if (at.id === id) out.add(f.id); at = ffFolders.find(p => p.id === at.parentId); } });
  return out;
}

function ffInScope(file) {
  if (ffChosen === FF_ALL) return true;
  if (ffChosen === FF_UNFILED) return !file.folderId;
  return ffSubtree(ffChosen).has(file.folderId);
}

function ffRenderTree() {
  const count = id => {
    if (id === FF_ALL) return files.length;
    if (id === FF_UNFILED) return files.filter(f => !f.folderId).length;
    const inside = ffSubtree(id);
    return files.filter(f => inside.has(f.folderId)).length;
  };
  const row = (id, name, depth, folder) => {
    const item = document.createElement("div");
    item.className = "ff-node" + (ffChosen === id ? " is-chosen" : "");
    item.style.setProperty("--ff-depth", depth);
    item.setAttribute("role", "treeitem");
    const hasKids = folder && ffFolders.some(f => f.parentId === folder.id);
    const caret = document.createElement("button");
    caret.type = "button";
    caret.className = "ff-node__caret";
    caret.textContent = hasKids ? (ffCollapsed.has(id) ? "▸" : "▾") : "";
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
    label.addEventListener("click", () => { ffChosen = id; renderFileTable(); });
    const n = document.createElement("span");
    n.className = "ff-node__count";
    n.textContent = count(id) || "";
    item.append(caret, label, n);
    if (folder) item.appendChild(ffActions(folder, depth));
    if (id !== FF_ALL) ffMakeDropTarget(item, id);
    return item;
  };
  const nodes = [row(FF_ALL, L("ff.all"), 0), row(FF_UNFILED, L("ff.unfiled"), 0)];
  const walk = (parentId, depth) => ffFolders.filter(f => (f.parentId || null) === parentId).forEach(f => {
    nodes.push(row(f.id, f.name, depth, f));
    if (!ffCollapsed.has(f.id)) walk(f.id, depth + 1);
  });
  walk(null, 0);
  ffEl.tree.replaceChildren(...nodes);
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
  if (!ffFolders.length) await ffLoad();
  if (flOpen !== file) return;
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
