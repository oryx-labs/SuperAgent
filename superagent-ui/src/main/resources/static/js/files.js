// SuperAgent UI — Files: what agents produced and what people sent them (documents, images, videos, links).
// One preview drawer serves the Files page, the file cards in a chat and an agent's panel.
// Classic script sharing one global scope with the others; index.html loads them in order.
"use strict";

Object.assign(api, {
  listFiles: agent => fetch("/api/v1/files" + (agent ? "?agent=" + encodeURIComponent(agent) : "")).then(kbJson),
  getFile: id => fetch("/api/v1/files/" + encodeURIComponent(id)).then(kbJson),
  renameFile: (id, name) => fetch("/api/v1/files/" + encodeURIComponent(id), {
    method: "PUT", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ name }),
  }).then(kbJson),
  deleteFile: id => fetch("/api/v1/files/" + encodeURIComponent(id), { method: "DELETE" }).then(kbJson),
  setFileTags: (id, tags) => fetch("/api/v1/files/" + encodeURIComponent(id) + "/tags", {
    method: "PUT", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ tags }),
  }).then(kbJson),
});
const fileContentUrl = (id, download) => "/api/v1/files/" + encodeURIComponent(id) + "/content"
  + (download ? "?download=true" : "");

const filesPane = document.querySelector('[data-pane="files"]');
const flel = {
  tabs: document.getElementById("flTabs"),
  agent: document.getElementById("flAgent"),
  project: document.getElementById("flProject"),
  search: document.getElementById("flSearch"),
  rows: document.getElementById("flRows"),
  empty: document.getElementById("flEmpty"),
  drawer: document.getElementById("flDrawer"),
  title: document.getElementById("flTitle"),
  meta: document.getElementById("flMeta"),
  download: document.getElementById("flDownload"),
  rename: document.getElementById("flRename"),
  del: document.getElementById("flDelete"),
  preview: document.getElementById("flPreview"),
  tagBar: document.getElementById("flTagBar"),
  tags: document.getElementById("flTags"),
};
let files = [];
let flKind = "";
const flTags = new Set();       // tags picked in the bar: a file must carry all of them
let flTagsExpanded = false;
const TAG_BAR_LIMIT = 12;       // more than this folds behind "more"
let flOpen = null; // the WorkFileDto in the drawer

const FILE_ICON = { document: "📄", image: "🖼", video: "🎬", link: "🔗", other: "📎" };
const fileIcon = f => FILE_ICON[f.kind] || FILE_ICON.other;
// Where a file came from, in one short phrase: the agent, plus the task when a task made it.
const fileSource = f => [f.agentName ? (f.agentAvatar ? f.agentAvatar + " " : "") + f.agentName : null,
  f.taskTitle ? L("fl.fromTask", { title: f.taskTitle }) : null].filter(Boolean).join(" · ") || "—";

// ---------- the Files page ----------
async function loadFiles() {
  await renderFileProjects();
  // A project shows every file made in it, whoever asked for it; otherwise the files the user can see.
  const project = flel.project.value;
  try { files = (await (project ? api.projectFiles(project) : api.listFiles())) || []; } catch (e) { files = []; showToast(e.message, true); }
  await ffLoad();
  renderFileAgents();
  renderFileTable();
}

async function renderFileProjects() {
  const current = flel.project.value;
  const list = await refreshProjects();
  flel.project.replaceChildren(new Option(L("proj.filterAll"), ""),
    ...list.map(p => new Option(p.name + (p.status === "archived" ? " · " + L("proj.archived") : ""), p.id)));
  flel.project.value = list.some(p => p.id === current) ? current : "";
  flel.project.hidden = !list.length;
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
  ffRenderTree();
  const scoped = files.filter(f => ffInScope(f) && (!agent || f.agentId === agent)
    && (!q || [f.name, f.agentName, f.taskTitle, ...(f.tags || [])].some(v => (v || "").toLowerCase().includes(q))));
  renderTagBar(scoped);
  const base = scoped.filter(f => [...flTags].every(t => (f.tags || []).includes(t)));
  flel.tabs.querySelectorAll(".model-tab").forEach(t => {
    t.classList.toggle("is-active", t.dataset.kind === flKind);
    t.querySelector("b").textContent = base.filter(f => !t.dataset.kind || f.kind === t.dataset.kind).length || "";
  });
  const shown = base.filter(f => !flKind || f.kind === flKind);
  flel.rows.replaceChildren(...shown.map(fileRow));
  flel.empty.hidden = shown.length > 0;
  flel.empty.textContent = L(files.length ? (ffChosen === FF_ALL ? "fl.noMatch" : "ff.empty") : "fl.empty");
}

// The tags on the files in view, most used first, each with its count; picked ones stay visible even at zero.
function renderTagBar(scoped) {
  const counts = new Map();
  scoped.filter(f => !flKind || f.kind === flKind).forEach(f => (f.tags || []).forEach(t => counts.set(t, (counts.get(t) || 0) + 1)));
  flTags.forEach(t => { if (!counts.has(t)) counts.set(t, 0); });
  const all = [...counts].sort((a, b) => (flTags.has(b[0]) - flTags.has(a[0])) || b[1] - a[1] || a[0].localeCompare(b[0]));
  flel.tagBar.hidden = all.length === 0;
  const shown = flTagsExpanded ? all : all.slice(0, Math.max(TAG_BAR_LIMIT, flTags.size));
  const chips = shown.map(([tag, n]) => {
    const b = document.createElement("button");
    b.type = "button";
    b.className = "fl-tag" + (flTags.has(tag) ? " is-active" : "");
    b.setAttribute("aria-pressed", String(flTags.has(tag)));
    b.textContent = tag;
    const c = document.createElement("span");
    c.className = "fl-tag__count";
    c.textContent = n;
    b.appendChild(c);
    b.addEventListener("click", () => { if (!flTags.delete(tag)) flTags.add(tag); renderFileTable(); });
    return b;
  });
  if (all.length > shown.length || flTagsExpanded && all.length > TAG_BAR_LIMIT) {
    chips.push(linkBtn(flTagsExpanded ? L("fl.tagsLess") : L("fl.tagsMore", { n: all.length - shown.length }),
      () => { flTagsExpanded = !flTagsExpanded; renderFileTable(); }, "link-btn--muted"));
  }
  if (flTags.size) {
    chips.push(linkBtn(L("fl.tagsClear"), () => { flTags.clear(); renderFileTable(); }, "link-btn--muted"));
  }
  const label = document.createElement("span");
  label.className = "fl-tagbar__label";
  label.textContent = L("fl.tagsLabel");
  flel.tagBar.replaceChildren(label, ...chips);
}

function tagChips(tags) {
  const box = document.createElement("span");
  box.className = "fl-row__tags";
  (tags || []).forEach(t => {
    const s = document.createElement("span");
    s.className = "fl-chip";
    s.textContent = t;
    box.appendChild(s);
  });
  return box;
}

// A row: the file's kind as a coloured tile, its name with its tags under it, then where it came from.
function fileRow(f) {
  const tr = document.createElement("tr");
  tr.className = "sk-row fl-row";
  tr.tabIndex = 0;
  ffMakeDraggable(tr, f);
  const cell = (text, cls) => {
    const td = document.createElement("td");
    if (cls) td.className = cls;
    td.textContent = text;
    td.title = text;
    return td;
  };
  const name = document.createElement("td");
  const wrap = document.createElement("div");
  wrap.className = "fl-row__name";
  const icon = document.createElement("span");
  icon.className = "fl-tile fl-tile--" + (FILE_ICON[f.kind] ? f.kind : "other");
  icon.textContent = fileIcon(f);
  const text = document.createElement("div");
  text.className = "fl-row__text";
  const label = document.createElement("span");
  label.className = "fl-row__label";
  label.textContent = f.name;
  text.appendChild(label);
  if ((f.tags || []).length) text.appendChild(tagChips(f.tags));
  name.title = f.name;
  wrap.append(icon, text);
  name.appendChild(wrap);
  tr.append(name, cell(fileSource(f), "sk-row__meta"), cell(f.ownerName || f.owner || "—", "sk-row__meta"),
    cell(f.kind === "link" ? "—" : kbFormatSize(f.size), "sk-row__meta fl-num"),
    cell(formatDateTime(f.updatedAt), "sk-row__meta fl-num"));
  tr.addEventListener("click", () => openFile(f));
  tr.addEventListener("keydown", e => { if (e.key === "Enter") openFile(f); });
  return tr;
}

flel.search.addEventListener("input", renderFileTable);
flel.agent.addEventListener("change", renderFileTable);
flel.project.addEventListener("change", loadFiles);
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
  const link = f.kind === "link";
  flel.meta.textContent = [fileSource(f), L(link ? "fl.sharedBy" : "fl.askedBy", { name: f.ownerName || personName(f.owner) }),
    link ? null : kbFormatSize(f.size), formatDateTime(f.updatedAt)].filter(Boolean).join(" · ");
  flel.download.href = fileContentUrl(f.id, true);
  flel.download.hidden = link; // a link has no content of its own
  flel.rename.hidden = flel.del.hidden = !f.canManage;
  fwSyncEdit(f);
  renderDrawerTags(f);
  ffFillMove(f);
  flel.drawer.hidden = false;
  renderFilePreview(f);
}

/**
 * A markdown file read as a document, not a chat reply: line breaks in the source don't break lines, front matter
 * shows as a code block instead of a stray rule and heading, wide tables scroll on their own, code is coloured
 * (only when the block names a language: guessing colours prose) and links leave the app in a new tab.
 */
function markdownDocument(text) {
  const doc = document.createElement("div");
  doc.className = "markdown fl-markdown";
  const source = text.replace(/^\uFEFF?---\r?\n([\s\S]*?)\r?\n---[ \t]*(\r?\n|$)/, "```yaml\n$1\n```\n\n");
  doc.innerHTML = window.DOMPurify && window.marked
    ? window.DOMPurify.sanitize(window.marked.parse(source, { gfm: true, breaks: false })) : renderMarkdown(text);
  doc.querySelectorAll("table").forEach(table => {
    const scroll = document.createElement("div");
    scroll.className = "fl-md-table";
    table.replaceWith(scroll);
    scroll.appendChild(table);
  });
  doc.querySelectorAll("a[href]").forEach(a => { a.target = "_blank"; a.rel = "noopener noreferrer"; });
  if (window.hljs) {
    doc.querySelectorAll('pre code[class*="language-"]').forEach(code => {
      const lang = code.className.match(/language-([\w+#.-]+)/);
      if (lang && window.hljs.getLanguage(lang[1])) window.hljs.highlightElement(code);
    });
  }
  return doc;
}

async function renderFilePreview(f) {
  const box = flel.preview;
  box.replaceChildren();
  const src = fileContentUrl(f.id, false);
  const ext = (f.name.split(".").pop() || "").toLowerCase();
  // Documents read better wider than the drawer's usual width.
  flel.drawer.querySelector(".drawer__panel").classList.toggle("fl-panel-doc", ["md", "markdown", "pdf"].includes(ext));
  if (f.kind === "link") {
    // The address and a way to open it; nothing is fetched here (many links need the viewer's own login).
    const url = document.createElement("p");
    url.className = "fl-link";
    url.textContent = f.url;
    const open = document.createElement("a");
    open.className = "btn btn--sm fl-open-tab";
    open.href = /^https?:\/\//i.test(f.url || "") ? f.url : "#";
    open.target = "_blank";
    open.rel = "noopener noreferrer";
    open.textContent = L("fl.openLink");
    box.append(url, open);
  } else if (f.kind === "image") {
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
  } else if (ext === "pdf") {
    // The browser's own PDF viewer, inside the drawer: pages, zoom and search come with it.
    const frame = document.createElement("iframe");
    frame.className = "fl-pdf";
    frame.src = src;
    frame.title = f.name;
    const open = document.createElement("a");
    open.className = "btn btn--sm fl-open-tab";
    open.href = src;
    open.target = "_blank";
    open.rel = "noopener";
    open.textContent = L("fl.openTab");
    box.append(frame, open);
  } else if (["md", "markdown", "txt", "csv", "json", "html", "htm"].includes(ext)) {
    let text;
    try {
      const r = await fetch(src);
      if (!r.ok) throw new Error("HTTP " + r.status);
      text = await r.text();
    } catch (e) { box.textContent = L("fl.loadFailed", { msg: e.message }); return; }
    if (flOpen !== f) return; // another file was opened meanwhile
    if (ext === "md" || ext === "markdown") {
      box.appendChild(markdownDocument(text));
    } else {
      const pre = document.createElement("pre");
      pre.className = "fl-text";
      pre.textContent = text;
      box.appendChild(pre);
    }
  } else {
    // The rest (slides, spreadsheets, archives…): the user's own app does it, after downloading.
    const p = document.createElement("p");
    p.className = "kb-empty";
    p.textContent = L("fl.noPreview");
    box.appendChild(p);
  }
}

// Tags in the drawer: those who may manage the file can remove one (×) or add one; a person's edit sticks.
function renderDrawerTags(f) {
  const box = flel.tags;
  box.replaceChildren();
  (f.tags || []).forEach(t => {
    const chip = document.createElement("span");
    chip.className = "fl-chip";
    chip.textContent = t;
    if (f.canManage) {
      const x = document.createElement("button");
      x.type = "button";
      x.className = "fl-chip__x";
      x.textContent = "×";
      x.title = L("fl.tagRemove");
      x.addEventListener("click", () => saveFileTags(f, (f.tags || []).filter(o => o !== t)));
      chip.appendChild(x);
    }
    box.appendChild(chip);
  });
  if (f.canManage && (f.tags || []).length < 5) {
    const input = document.createElement("input");
    input.type = "text";
    input.className = "fl-tag-input";
    input.maxLength = 20;
    input.placeholder = L("fl.tagAdd");
    input.addEventListener("keydown", e => {
      if (e.key === "Enter" && input.value.trim()) {
        e.preventDefault();
        saveFileTags(f, [...(f.tags || []), input.value.trim()]);
      }
    });
    box.appendChild(input);
  } else if (!(f.tags || []).length) {
    const none = document.createElement("span");
    none.className = "fl-meta";
    none.textContent = L(f.tagState ? "fl.tagsNone" : "fl.tagsPending");
    box.appendChild(none);
  }
}

async function saveFileTags(f, tags) {
  let updated;
  try { updated = await api.setFileTags(f.id, tags); } catch (e) { showToast(e.message, true); return; }
  if (flOpen && flOpen.id === f.id) { flOpen = updated; renderDrawerTags(updated); }
  files = files.map(x => x.id === updated.id ? Object.assign({}, x, { tags: updated.tags, tagState: updated.tagState }) : x);
  if (!filesPane.hidden) renderFileTable();
  const focus = flel.tags.querySelector(".fl-tag-input");
  if (focus) focus.focus();
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
    title: L("fl.deleteTitle"), submit: L("common.delete"), danger: true,
    message: L("fl.deleteConfirm", { name: f.name }),
    action: async () => { await api.deleteFile(f.id); closeFile(); await filesChanged(); },
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
  // A link someone sent is already in their message's text; it's kept in Files, but needs no card there.
  list.filter(f => !(f.kind === "link" && f.origin === "upload")).forEach(f => (f.messageIds || []).forEach(mid => {
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
