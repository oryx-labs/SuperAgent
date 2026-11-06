// SuperAgent UI — Admin: where Files keeps content (local disk, S3-compatible, …).
// Classic script sharing one global scope with the others; index.html loads them in order.
"use strict";

// ---------- file storage (admin) ----------
// Each installed store (a plugin) says which settings it needs; the form is built from that. Saving checks the
// settings with a real write/read/delete first. Files remember their channel (a store at one location), so moving
// only affects new files; the list under the form shows every channel and how many files are still in it.
Object.assign(api, {
  fsGet: () => fetch("/api/v1/admin/file-storage").then(kbJson),
  fsSave: (type, values) => fetch("/api/v1/admin/file-storage", {
    method: "PUT", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ type, values }),
  }).then(kbJson),
  fsTest: (type, values) => kbPost("/api/v1/admin/file-storage/test", { type, values }),
});

const fsEl = {
  types: document.getElementById("fsTypes"),
  form: document.getElementById("fsForm"),
  fields: document.getElementById("fsFields"),
  none: document.getElementById("fsNone"),
  result: document.getElementById("fsResult"),
  test: document.getElementById("fsTest"),
  save: document.getElementById("fsSave"),
  channels: document.getElementById("fsChannels"),
  channelList: document.getElementById("fsChannelList"),
};
let fsState = null;     // FileStorageDto
let fsChosen = null;    // the type being looked at / set up

async function loadFileStorage() {
  try { fsState = await api.fsGet(); } catch (e) { showToast(e.message, true); return; }
  fsChosen = fsState.activeType;
  renderFileStorage();
}

const fsLabel = p => L("fs.type." + p.type) !== "fs.type." + p.type ? L("fs.type." + p.type) : p.label;

function renderFileStorage() {
  fsEl.types.replaceChildren(...fsState.providers.map(p => {
    const card = document.createElement("button");
    card.type = "button";
    card.className = "fs-type" + (p.type === fsChosen ? " is-chosen" : "");
    card.setAttribute("role", "radio");
    card.setAttribute("aria-checked", String(p.type === fsChosen));
    const name = document.createElement("b");
    name.textContent = fsLabel(p);
    const note = document.createElement("span");
    note.textContent = L(p.type === fsState.activeType ? "fs.inUse" : "fs.notInUse");
    if (p.type === fsState.activeType) note.className = "fs-type__badge";
    card.append(name, note);
    card.addEventListener("click", () => { fsChosen = p.type; fsEl.result.hidden = true; renderFileStorage(); });
    return card;
  }));
  const p = fsState.providers.find(x => x.type === fsChosen);
  fsEl.fields.replaceChildren(...p.fields.map(fsField));
  fsEl.none.hidden = p.fields.length > 0;
  fsEl.test.hidden = p.fields.length === 0;
  fsEl.save.disabled = p.type === fsState.activeType && p.fields.length === 0;
  renderFsChannels();
}

function renderFsChannels() {
  // The local disk alone isn't worth a list: there's nothing to tell apart until a second channel exists.
  fsEl.channels.hidden = fsState.channels.length < 2;
  fsEl.channelList.replaceChildren(...fsState.channels.map(c => {
    const li = document.createElement("li");
    li.className = "fs-channel" + (c.active ? " is-active" : "");
    const main = document.createElement("div");
    main.className = "fs-channel__main";
    const name = document.createElement("b");
    const provider = fsState.providers.find(p => p.type === c.type);
    name.textContent = provider ? fsLabel(provider) : c.type;
    const where = document.createElement("span");
    where.className = "fs-channel__where";
    where.textContent = Object.values(c.location).join(" · ") || L("fs.thisServer");
    main.append(name, where);
    const side = document.createElement("div");
    side.className = "fs-channel__side";
    const count = document.createElement("span");
    count.textContent = L("fs.fileCount", { n: c.files });
    side.appendChild(count);
    if (c.active) {
      const badge = document.createElement("span");
      badge.className = "fs-type__badge";
      badge.textContent = L("fs.inUse");
      side.appendChild(badge);
    }
    li.append(main, side);
    return li;
  }));
}

function fsField(f) {
  const label = document.createElement("label");
  label.className = "field";
  const caption = document.createElement("span");
  caption.textContent = (L("fs.field." + f.key) !== "fs.field." + f.key ? L("fs.field." + f.key) : f.label)
    + (f.required ? "" : " " + L("fs.optional"));
  const input = document.createElement("input");
  input.name = f.key;
  input.autocomplete = "off";
  if (f.secret) {
    input.type = "password";
    input.autocomplete = "new-password";
    input.placeholder = f.set ? L("fs.secretKept") : "";
  } else {
    input.type = "text";
    input.value = f.value || "";
    input.placeholder = f.placeholder || "";
  }
  label.append(caption, input);
  return label;
}

function fsValues() {
  const values = {};
  fsEl.fields.querySelectorAll("input").forEach(i => { values[i.name] = i.value.trim(); });
  return values;
}

function fsShow(ok, message) {
  fsEl.result.hidden = false;
  fsEl.result.className = "fs-result " + (ok ? "is-ok" : "is-error");
  fsEl.result.textContent = message;
}

fsEl.test.addEventListener("click", async () => {
  fsEl.test.disabled = true;
  try { await api.fsTest(fsChosen, fsValues()); fsShow(true, L("fs.testOk")); }
  catch (e) { fsShow(false, e.message); }
  finally { fsEl.test.disabled = false; }
});

fsEl.form.addEventListener("submit", async e => {
  e.preventDefault();
  fsEl.save.disabled = true;
  try {
    fsState = await api.fsSave(fsChosen, fsValues());
    renderFileStorage();
    fsShow(true, L("fs.saved"));
  } catch (err) { fsShow(false, err.message); fsEl.save.disabled = false; }
});
