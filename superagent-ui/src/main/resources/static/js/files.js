// SuperAgent UI — Files: the work results agents produced (documents, images, videos), kept apart from the
// knowledge base. One preview drawer serves the Files page, the file cards in a chat and an agent's panel.
// Classic script sharing one global scope with the others; index.html loads them in order.
"use strict";

Object.assign(api, {
  listFiles: agent => fetch("/api/v1/files" + (agent ? "?agent=" + encodeURIComponent(agent) : "")).then(kbJson),
  getFile: id => fetch("/api/v1/files/" + encodeURIComponent(id)).then(kbJson),
  renameFile: (id, name) => fetch("/api/v1/files/" + encodeURIComponent(id), {
    method: "PUT", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ name }),
  }).then(kbJson),
  deleteFile: id => fetch("/api/v1/files/" + encodeURIComponent(id), { method: "DELETE" }).then(kbJson),
  fileToKb: (id, folder) => kbPost("/api/v1/files/" + encodeURIComponent(id) + "/knowledge", { folder }),
});
const fileContentUrl = (id, download) => "/api/v1/files/" + encodeURIComponent(id) + "/content"
  + (download ? "?download=true" : "");

const filesPane = document.querySelector('[data-pane="files"]');
const flel = {
  tabs: document.getElementById("flTabs"),
  agent: document.getElementById("flAgent"),
  search: document.getElementById("flSearch"),
  rows: document.getElementById("flRows"),
  empty: document.getElementById("flEmpty"),
  drawer: document.getElementById("flDrawer"),
  title: document.getElementById("flTitle"),
  meta: document.getElementById("flMeta"),
  download: document.getElementById("flDownload"),
  toKb: document.getElementById("flToKb"),
  rename: document.getElementById("flRename"),
  del: document.getElementById("flDelete"),
  preview: document.getElementById("flPreview"),
};
let files = [];
let flKind = "";
let flOpen = null; // the WorkFileDto in the drawer

const FILE_ICON = { document: "📄", image: "🖼", video: "🎬", other: "📎" };
const fileIcon = f => FILE_ICON[f.kind] || FILE_ICON.other;
// Where a file came from, in one short phrase: the agent, plus the task when a task made it.
const fileSource = f => [f.agentName ? (f.agentAvatar ? f.agentAvatar + " " : "") + f.agentName : null,
  f.taskTitle ? L("fl.fromTask", { title: f.taskTitle }) : null].filter(Boolean).join(" · ") || "—";

// ---------- the Files page ----------
async function loadFiles() {
  try { files = (await api.listFiles()) || []; } catch (e) { files = []; showToast(e.message, true); }
  renderFileAgents();
  renderFileTable();
}

function renderFileAgents() {
  const current = flel.agent.value;
  const seen = new Map();
  files.forEach(f => { if (f.agentId && !seen.has(f.agentId)) seen.set(f.agentId, f.agentName || f.agentId); });
  flel.agent.replaceChildren(new Option(L("fl.allAgents"), ""),
    ...[...seen].map(([id, name]) => new Option(name, id)));
  flel.agent.value = seen.has(current) ? current : "";
}

function renderFileTable() {
  const q = flel.search.value.trim().toLowerCase();
  const agent = flel.agent.value;
  const base = files.filter(f => (!agent || f.agentId === agent)
    && (!q || [f.name, f.agentName, f.taskTitle].some(v => (v || "").toLowerCase().includes(q))));
  flel.tabs.querySelectorAll(".model-tab").forEach(t => {
    t.classList.toggle("is-active", t.dataset.kind === flKind);
    t.querySelector("b").textContent = base.filter(f => !t.dataset.kind || f.kind === t.dataset.kind).length || "";
  });
  const shown = base.filter(f => !flKind || f.kind === flKind);
  flel.rows.replaceChildren(...shown.map(fileRow));
  flel.empty.hidden = shown.length > 0;
  flel.empty.textContent = L(files.length ? "fl.noMatch" : "fl.empty");
}

function fileRow(f) {
  const tr = document.createElement("tr");
  tr.className = "sk-row";
  tr.tabIndex = 0;
  const cell = (text, cls) => {
    const td = document.createElement("td");
    if (cls) td.className = cls;
    td.textContent = text;
    td.title = text;
    return td;
  };
  const name = document.createElement("td");
  name.className = "sk-row__name fl-row__name";
  const icon = document.createElement("span");
  icon.className = "fl-icon";
  icon.textContent = fileIcon(f);
  const label = document.createElement("span");
  label.textContent = f.name;
  name.title = f.name;
  name.append(icon, label);
  tr.append(name, cell(fileSource(f), "sk-row__meta"), cell(f.ownerName || f.owner || "—", "sk-row__meta"),
    cell(kbFormatSize(f.size), "sk-row__meta"), cell(formatDateTime(f.updatedAt), "sk-row__meta"));
  tr.addEventListener("click", () => openFile(f));
  tr.addEventListener("keydown", e => { if (e.key === "Enter") openFile(f); });
  return tr;
}

flel.search.addEventListener("input", renderFileTable);
flel.agent.addEventListener("change", renderFileTable);
flel.tabs.addEventListener("click", e => {
  const t = e.target.closest(".model-tab");
  if (t) { flKind = t.dataset.kind; renderFileTable(); }
});

// ---------- the preview drawer ----------
/** Opens a file by its DTO, or by id (a chat card made while streaming only knows the id). */
async function openFile(fileOrId) {
  let f = fileOrId;
  if (typeof fileOrId === "string") {
    try { f = await api.getFile(fileOrId); } catch (e) { showToast(L("fl.gone"), true); return; }
  }
  flOpen = f;
  flel.title.textContent = fileIcon(f) + " " + f.name;
  flel.meta.textContent = [fileSource(f), L("fl.askedBy", { name: f.ownerName || f.owner }),
    kbFormatSize(f.size), formatDateTime(f.updatedAt)].join(" · ");
  flel.download.href = fileContentUrl(f.id, true);
  flel.rename.hidden = flel.del.hidden = !f.canManage;
  flel.drawer.hidden = false;
  renderFilePreview(f);
}

async function renderFilePreview(f) {
  const box = flel.preview;
  box.replaceChildren();
  const src = fileContentUrl(f.id, false);
  const ext = (f.name.split(".").pop() || "").toLowerCase();
  if (f.kind === "image") {
    const img = document.createElement("img");
    img.className = "fl-media";
    img.src = src;
    img.alt = f.name;
    box.appendChild(img);
  } else if (f.kind === "video") {
    const video = document.createElement("video");
    video.className = "fl-media";
    video.controls = true;
    video.preload = "metadata";
    video.src = src;
    box.appendChild(video);
  } else if (["md", "markdown", "txt", "csv", "json", "html", "htm"].includes(ext)) {
    let text;
    try {
      const r = await fetch(src);
      if (!r.ok) throw new Error("HTTP " + r.status);
      text = await r.text();
    } catch (e) { box.textContent = L("fl.loadFailed", { msg: e.message }); return; }
    if (flOpen !== f) return; // another file was opened meanwhile
    if (ext === "md" || ext === "markdown") {
      const md = document.createElement("div");
      md.className = "markdown fl-markdown";
      md.innerHTML = renderMarkdown(text);
      box.appendChild(md);
    } else {
      const pre = document.createElement("pre");
      pre.className = "fl-text";
      pre.textContent = text;
      box.appendChild(pre);
    }
  } else {
    // PDFs and the rest: the browser (or the user's own app) does it better than an embed here.
    const p = document.createElement("p");
    p.className = "kb-empty";
    p.textContent = L(ext === "pdf" ? "fl.pdfHint" : "fl.noPreview");
    box.appendChild(p);
    if (ext === "pdf") {
      const open = document.createElement("a");
      open.className = "btn btn--sm fl-open-tab";
      open.href = src;
      open.target = "_blank";
      open.rel = "noopener";
      open.textContent = L("fl.openTab");
      box.appendChild(open);
    }
  }
}

function closeFile() { flel.drawer.hidden = true; flOpen = null; }
flel.drawer.querySelectorAll("[data-close]").forEach(x => x.addEventListener("click", closeFile));
document.addEventListener("keydown", e => {
  if (e.key === "Escape" && !flel.drawer.hidden && document.getElementById("kbModal").hidden) closeFile();
});

flel.rename.addEventListener("click", () => {
  const f = flOpen;
  if (!f) return;
  kbOpenModal({
    title: L("fl.renameTitle"), submit: L("common.save"), name: f.name,
    action: async () => {
      const updated = await api.renameFile(f.id, kel.nameInput.value.trim());
      await filesChanged();
      openFile(updated);
    },
  });
});

flel.del.addEventListener("click", () => {
  const f = flOpen;
  if (!f) return;
  kbOpenModal({
    title: L("fl.deleteTitle"), submit: L("kb.delete"), danger: true,
    message: L("fl.deleteConfirm", { name: f.name }),
    action: async () => { await api.deleteFile(f.id); closeFile(); await filesChanged(); },
  });
});

// A person decides a result is worth keeping as knowledge: pick a folder in their own knowledge base.
flel.toKb.addEventListener("click", async () => {
  const f = flOpen;
  if (!f) return;
  let folders = [];
  try { folders = await api.kbFolders("personal"); } catch (e) { showToast(e.message, true); return; }
  kbOpenModal({
    title: L("fl.toKbTitle", { name: f.name }), submit: L("tasks.saveToKbSubmit"),
    targets: folders, name: "", nameLabel: L("tasks.kbNewSubfolder"),
    action: async () => {
      const folder = [kel.targetSelect.value, kel.nameInput.value.trim()].filter(Boolean).join("/");
      const saved = await api.fileToKb(f.id, folder);
      showToast(L("tasks.savedToKb", { path: saved.path }), false);
    },
  });
});

/** After a rename or delete: refresh wherever files are on screen. */
async function filesChanged() {
  if (!filesPane.hidden) await loadFiles();
  if (activeAgent && activeAgent.kind !== "super" && !document.querySelector('[data-pane="chat"]').hidden) {
    el.messages.querySelectorAll(".file-card").forEach(c => c.remove());
    attachFileCards(activeAgent);
    renderAgentWork(activeAgent);
  }
}

// ---------- file cards (chat replies, agent panel) ----------
/** A compact, clickable card for one file; {@code f} may be a full DTO or just {id, name}. */
function fileCard(f) {
  const card = document.createElement("button");
  card.type = "button";
  card.className = "file-card";
  card.dataset.fid = f.id;
  const icon = document.createElement("span");
  icon.className = "file-card__icon";
  icon.textContent = f.kind ? fileIcon(f) : "📄";
  const name = document.createElement("span");
  name.className = "file-card__name";
  name.textContent = f.name; // textContent: a produced file name never becomes markup
  card.title = L("fl.openFile");
  card.append(icon, name);
  card.addEventListener("click", () => openFile(f.kind ? f : f.id));
  return card;
}

/** Puts each file's card under the replies that produced it (a revised file shows under both). */
async function attachFileCards(agent) {
  if (!agent || agent.kind === "super") return;
  let list = [];
  try { list = (await api.listFiles(agent.id)) || []; } catch (e) { return; }
  if (activeAgent !== agent) return;
  list.forEach(f => (f.messageIds || []).forEach(mid => {
    const msg = el.messages.querySelector('.msg[data-mid="' + CSS.escape(mid) + '"]');
    if (!msg || msg.querySelector('[data-fid="' + CSS.escape(f.id) + '"]')) return;
    // A picture someone sent shows as a thumbnail; everything else as a card.
    fileCardsBox(msg).appendChild(f.origin === "upload" && f.kind === "image" ? fileThumb(f) : fileCard(f));
  }));
}

function fileThumb(f) {
  const b = document.createElement("button");
  b.type = "button";
  b.className = "file-thumb";
  b.dataset.fid = f.id;
  b.title = f.name;
  const img = document.createElement("img");
  img.src = "/api/v1/files/" + encodeURIComponent(f.id) + "/content";
  img.alt = f.name;
  img.loading = "lazy";
  b.appendChild(img);
  b.addEventListener("click", () => openFile(f));
  return b;
}

/** Under a reply: inside its bubble. Under a user message: above the bubble, which may be empty and hidden. */
function fileCardsBox(msg) {
  let box = msg.querySelector(".file-cards");
  if (!box) {
    box = document.createElement("div");
    box.className = "file-cards";
    const bubble = msg.querySelector(".bubble");
    if (msg.classList.contains("msg--user")) msg.insertBefore(box, bubble);
    else (bubble || msg).appendChild(box);
  }
  return box;
}

/** While a reply streams: the file tool's result line names the file, so its card shows at once. */
function parseSavedFile(payload) {
  if (!payload) return null;
  const m = /已保存文件「([^」]+)」（id: ([0-9a-fA-F-]{36})）/.exec(payload);
  return m ? { id: m[2], name: m[1] } : null;
}
