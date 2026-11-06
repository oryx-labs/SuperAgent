// SuperAgent UI — Attachments (files and images) for the agent and task composers.
// Classic script sharing one global scope with the others; index.html loads them in order.
"use strict";

// ---------- attachments (agent + task composers) ----------
// Any file goes: it's uploaded to Files (under the agent or task it's for) when the message is sent, and the agent
// reads it with its own tools. The SuperAgent inbox still takes images only, inline. Limits mirror the server's.
const IMAGE_TYPES = ["image/png", "image/jpeg", "image/gif", "image/webp"];
const MAX_IMAGES = 5;
const MAX_IMAGE_BYTES = 5 * 1024 * 1024;
const MAX_FILES = 10;
const MAX_FILE_BYTES = 50 * 1024 * 1024;

// Pick (button), paste and drop files into a composer; shows them as removable thumbnails or chips.
// {@code imagesOnly()}: true where only inline images are accepted.
function imageAttacher({ button, fileInput, tray, pasteTarget, dropZone, imagesOnly }) {
  let picked = []; // [{ file, url (images only) }]
  const onlyImages = () => !!(imagesOnly && imagesOnly());

  function render() {
    tray.replaceChildren();
    tray.hidden = picked.length === 0;
    picked.forEach((p, i) => {
      const item = document.createElement("div");
      if (p.url) {
        item.className = "image-tray__item";
        const img = document.createElement("img");
        img.src = p.url;
        img.alt = p.file.name || "";
        item.appendChild(img);
      } else {
        item.className = "file-chip";
        item.title = p.file.name;
        const name = document.createElement("span");
        name.className = "file-chip__name";
        name.textContent = p.file.name;
        const size = document.createElement("span");
        size.className = "file-chip__size";
        size.textContent = formatBytes(p.file.size);
        item.append(document.createTextNode("📄 "), name, size);
      }
      const remove = document.createElement("button");
      remove.type = "button";
      remove.className = "image-tray__remove";
      remove.textContent = "×";
      remove.title = L("img.remove");
      remove.addEventListener("click", () => {
        if (p.url) URL.revokeObjectURL(p.url);
        picked.splice(i, 1);
        render();
      });
      item.appendChild(remove);
      tray.appendChild(item);
    });
  }

  function add(files) {
    for (const file of files) {
      const image = IMAGE_TYPES.includes(file.type);
      if (onlyImages()) {
        if (!image) { showToast(L("img.badType"), true); continue; }
        if (file.size > MAX_IMAGE_BYTES) { showToast(L("img.tooBig"), true); continue; }
        if (picked.length >= MAX_IMAGES) { showToast(L("img.tooMany", { max: MAX_IMAGES }), true); break; }
      } else {
        if (file.size > MAX_FILE_BYTES) { showToast(L("fl.tooBig", { name: file.name }), true); continue; }
        if (picked.length >= MAX_FILES) { showToast(L("fl.tooMany", { max: MAX_FILES }), true); break; }
      }
      picked.push({ file, url: image ? URL.createObjectURL(file) : null });
    }
    render();
  }

  button.addEventListener("click", () => {
    fileInput.accept = onlyImages() ? IMAGE_TYPES.join(",") : "";
    fileInput.click();
  });
  fileInput.addEventListener("change", () => { add(Array.from(fileInput.files)); fileInput.value = ""; });
  pasteTarget.addEventListener("paste", e => {
    const files = Array.from(e.clipboardData ? e.clipboardData.files : []);
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
    previewUrls: () => picked.filter(p => p.url).map(p => p.url),
    fileNames: () => picked.filter(p => !p.url).map(p => p.file.name),
    // Inline images (the SuperAgent inbox).
    payload: () => Promise.all(picked.map(p => readBase64(p.file).then(data => ({ mediaType: p.file.type, data })))),
    // Uploads everything to Files under {agent} or {task}; resolves to the file ids to send with the message.
    upload: target => Promise.all(picked.map(p => uploadFile(p.file, target))),
    clear() { picked = []; render(); },
    setDisabled(on) { button.disabled = on; },
  };
}

async function uploadFile(file, target) {
  const form = new FormData();
  form.append("file", file, file.name);
  const q = target.agent ? "agent=" + encodeURIComponent(target.agent) : "task=" + encodeURIComponent(target.task);
  const res = await fetch("/api/v1/files/uploads?" + q, { method: "POST", body: form });
  const body = await res.json().catch(() => null);
  if (!res.ok || !body || !body.data) throw new Error((body && body.message) || L("fl.uploadFailed", { name: file.name }));
  return body.data.id;
}

// Optimistic chips for files sent with a message (their real cards come once the conversation reloads).
function fileNameChips(names) {
  const box = document.createElement("div");
  box.className = "file-cards";
  names.forEach(n => {
    const chip = document.createElement("span");
    chip.className = "file-card file-card--pending";
    const icon = document.createElement("span");
    icon.className = "file-card__icon";
    icon.textContent = "📄";
    const name = document.createElement("span");
    name.className = "file-card__name";
    name.textContent = n;
    chip.append(icon, name);
    box.appendChild(chip);
  });
  return box;
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
