// SuperAgent UI — Image attachments for the agent and task composers.
// Classic script sharing one global scope with the others; index.html loads them in order.
"use strict";

// ---------- image attachments (agent + task composers) ----------
// Mirrors the server's limits (ImageAttachmentStore) so a bad pick fails here, not after upload.
const IMAGE_TYPES = ["image/png", "image/jpeg", "image/gif", "image/webp"];
const MAX_IMAGES = 5;
const MAX_IMAGE_BYTES = 5 * 1024 * 1024;

// Pick (button), paste and drop images into a composer; shows them as removable thumbnails.
function imageAttacher({ button, fileInput, tray, pasteTarget, dropZone }) {
  let picked = []; // [{ file, url }]

  function render() {
    tray.replaceChildren();
    tray.hidden = picked.length === 0;
    picked.forEach((p, i) => {
      const item = document.createElement("div");
      item.className = "image-tray__item";
      const img = document.createElement("img");
      img.src = p.url;
      img.alt = p.file.name || "";
      const remove = document.createElement("button");
      remove.type = "button";
      remove.className = "image-tray__remove";
      remove.textContent = "×";
      remove.title = L("img.remove");
      remove.addEventListener("click", () => {
        URL.revokeObjectURL(p.url);
        picked.splice(i, 1);
        render();
      });
      item.append(img, remove);
      tray.appendChild(item);
    });
  }

  function add(files) {
    for (const file of files) {
      if (!IMAGE_TYPES.includes(file.type)) { showToast(L("img.badType"), true); continue; }
      if (file.size > MAX_IMAGE_BYTES) { showToast(L("img.tooBig"), true); continue; }
      if (picked.length >= MAX_IMAGES) { showToast(L("img.tooMany", { max: MAX_IMAGES }), true); break; }
      picked.push({ file, url: URL.createObjectURL(file) });
    }
    render();
  }

  button.addEventListener("click", () => fileInput.click());
  fileInput.addEventListener("change", () => { add(Array.from(fileInput.files)); fileInput.value = ""; });
  pasteTarget.addEventListener("paste", e => {
    const files = Array.from(e.clipboardData ? e.clipboardData.files : []).filter(f => f.type.startsWith("image/"));
    if (files.length) { e.preventDefault(); add(files); }
  });
  dropZone.addEventListener("dragover", e => {
    if (!button.disabled) { e.preventDefault(); dropZone.classList.add("is-dragover"); }
  });
  dropZone.addEventListener("dragleave", () => dropZone.classList.remove("is-dragover"));
  dropZone.addEventListener("drop", e => {
    dropZone.classList.remove("is-dragover");
    if (button.disabled || !e.dataTransfer || !e.dataTransfer.files.length) return;
    e.preventDefault();
    add(Array.from(e.dataTransfer.files));
  });

  return {
    count: () => picked.length,
    // Object URLs for the optimistic bubble; they stay valid until the page unloads.
    previewUrls: () => picked.map(p => p.url),
    payload: () => Promise.all(picked.map(p => readBase64(p.file).then(data => ({ mediaType: p.file.type, data })))),
    clear() { picked = []; render(); },
    setDisabled(on) { button.disabled = on; },
  };
}

function readBase64(file) {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(String(reader.result).split(",")[1] || "");
    reader.onerror = () => reject(reader.error);
    reader.readAsDataURL(file);
  });
}

// Thumbnails of a message's images; each opens full size in a new tab.
function imageStrip(urls) {
  const strip = document.createElement("div");
  strip.className = "msg-images";
  urls.forEach(url => {
    const a = document.createElement("a");
    a.href = url;
    a.target = "_blank";
    a.rel = "noopener";
    const img = document.createElement("img");
    img.src = url;
    img.alt = "";
    img.loading = "lazy";
    a.appendChild(img);
    strip.appendChild(a);
  });
  return strip;
}
