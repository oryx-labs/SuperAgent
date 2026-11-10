// SuperAgent UI — Files: what people add themselves. Upload any files (button or drop them on the page) and write
// Markdown in the page; both land in the folder that's open. Markdown and plain-text files can be edited later.
// Classic script sharing one global scope with the others; index.html loads them in order.
"use strict";

Object.assign(api, {
  uploadFileTo: (file, folderId) => {
    const form = new FormData();
    form.append("file", file);
    return fetch("/api/v1/files/uploads" + (folderId ? "?folder=" + encodeURIComponent(folderId) : ""),
      { method: "POST", body: form }).then(kbJson);
  },
  createMarkdown: (name, content, folderId) => kbPost("/api/v1/files/markdown", { name, content, folderId }),
  editFileContent: (id, content) => fetch("/api/v1/files/" + encodeURIComponent(id) + "/content", {
    method: "PUT", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ content }),
  }).then(kbJson),
});

const fwEl = {
  newMd: document.getElementById("flNewMd"),
  upload: document.getElementById("flUpload"),
  input: document.getElementById("flUploadInput"),
  edit: document.getElementById("flEdit"),
  modal: document.getElementById("flEditor"),
  form: document.getElementById("flEditorForm"),
  title: document.getElementById("flEditorTitle"),
  nameField: document.getElementById("flEditorNameField"),
  name: document.getElementById("flEditorName"),
  text: document.getElementById("flEditorText"),
  preview: document.getElementById("flEditorPreview"),
  error: document.getElementById("flEditorError"),
  save: document.getElementById("flEditorSave"),
};
const FW_MAX_BYTES = 50 * 1024 * 1024; // the server's limit per file
let fwEditing = null; // the file being edited; null while writing a new one

/** Where new files go: the folder picked in the tree, if it's a real one. */
const fwFolder = () => (ffChosen !== FF_ALL && ffChosen !== FF_UNFILED && !ffAgentId(ffChosen) ? ffChosen : null);

// ---------- upload ----------

async function fwUpload(list) {
  const picked = [...list];
  if (!picked.length) return;
  const tooBig = picked.filter(f => f.size > FW_MAX_BYTES);
  const send = picked.filter(f => f.size <= FW_MAX_BYTES);
  const failed = tooBig.map(f => L("fw.tooBig", { name: f.name }));
  fwEl.upload.disabled = true;
  let done = 0;
  for (const f of send) {
    fwEl.upload.textContent = L("fw.uploading", { n: done + 1, total: send.length });
    try { await api.uploadFileTo(f, fwFolder()); done++; } catch (e) { failed.push(f.name + "：" + e.message); }
  }
  fwEl.upload.disabled = false;
  fwEl.upload.textContent = L("fl.upload");
  fwEl.input.value = "";
  if (done) await filesChanged();
  if (failed.length) showToast(L("fw.someFailed", { n: failed.length }) + " " + failed.join("；"), true);
  else showToast(L("fw.uploaded", { n: done }));
}

fwEl.upload.addEventListener("click", () => fwEl.input.click());
fwEl.input.addEventListener("change", () => fwUpload(fwEl.input.files));

// Files dropped from the computer anywhere on the page (moving a file into a folder is a drag inside the page,
// carrying no files, and stays the folder tree's).
const fwHasFiles = e => e.dataTransfer && [...e.dataTransfer.types].includes("Files");
filesPane.addEventListener("dragover", e => {
  if (!fwHasFiles(e)) return;
  e.preventDefault();
  filesPane.classList.add("fl-dropping");
});
filesPane.addEventListener("dragleave", e => {
  if (!filesPane.contains(e.relatedTarget)) filesPane.classList.remove("fl-dropping");
});
filesPane.addEventListener("drop", e => {
  filesPane.classList.remove("fl-dropping");
  if (!fwHasFiles(e) || !e.dataTransfer.files.length) return;
  e.preventDefault();
  fwUpload(e.dataTransfer.files);
});

// ---------- writing Markdown ----------

function fwOpenEditor(file, text) {
  fwEditing = file;
  fwEl.title.textContent = L(file ? "fw.editTitle" : "fw.newTitle");
  fwEl.nameField.hidden = !!file; // renaming has its own button
  fwEl.name.required = !file;
  fwEl.name.value = file ? file.name : "";
  fwEl.text.value = text || "";
  fwEl.error.hidden = true;
  fwRenderPreview();
  fwEl.modal.hidden = false;
  (file ? fwEl.text : fwEl.name).focus();
}

function fwRenderPreview() {
  const md = !fwEditing || /\.(md|markdown)$/i.test(fwEditing.name);
  fwEl.preview.hidden = !md;
  if (md) fwEl.preview.replaceChildren(markdownDocument(fwEl.text.value || ""));
}
let fwPreviewTimer = null;
fwEl.text.addEventListener("input", () => { clearTimeout(fwPreviewTimer); fwPreviewTimer = setTimeout(fwRenderPreview, 250); });

const fwClose = () => { fwEl.modal.hidden = true; fwEditing = null; };
fwEl.modal.querySelectorAll("[data-close]").forEach(x => x.addEventListener("click", fwClose));
document.addEventListener("keydown", e => { if (e.key === "Escape" && !fwEl.modal.hidden) fwClose(); });

fwEl.form.addEventListener("submit", async e => {
  e.preventDefault();
  fwEl.save.disabled = true;
  fwEl.error.hidden = true;
  try {
    const saved = fwEditing ? await api.editFileContent(fwEditing.id, fwEl.text.value)
      : await api.createMarkdown(fwEl.name.value.trim(), fwEl.text.value, fwFolder());
    fwClose();
    await filesChanged();
    openFile(saved);
    showToast(L("fw.saved"));
  } catch (err) {
    fwEl.error.textContent = err.message;
    fwEl.error.hidden = false;
  } finally {
    fwEl.save.disabled = false;
  }
});

fwEl.newMd.addEventListener("click", () => fwOpenEditor(null, ""));

/** The preview drawer offers Edit on a Markdown or plain-text file the viewer may change. */
function fwSyncEdit(f) {
  fwEl.edit.hidden = !(f && f.canManage && f.kind !== "link" && /\.(md|markdown|txt)$/i.test(f.name));
}
fwEl.edit.addEventListener("click", async () => {
  const f = flOpen;
  if (!f) return;
  let text;
  try {
    const r = await fetch(fileContentUrl(f.id, false));
    if (!r.ok) throw new Error("HTTP " + r.status);
    text = await r.text();
  } catch (e) { showToast(L("fl.loadFailed", { msg: e.message }), true); return; }
  fwOpenEditor(f, text);
});
