// SuperAgent UI — Skills: working standards, personal or company-wide — list, view, edit, images.
// Classic script sharing one global scope with the others; index.html loads them in order.
"use strict";

// ---------- skills ----------
// Working standards; a skill is the user's own or the company's (everyone uses it, only admins change it).
// company skills everyone). Agents get the skills attached to them; their tasks inherit those.
Object.assign(api, {
  listSkills: () => fetch("/api/v1/skills").then(kbJson),
  createSkill: body => kbPost("/api/v1/skills", body),
  updateSkill: (id, body) => fetch("/api/v1/skills/" + id, {
    method: "PUT", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body),
  }).then(kbJson),
  deleteSkill: id => fetch("/api/v1/skills/" + id, { method: "DELETE" }).then(kbJson),
  addSkillImage: (id, name, data) => kbPost("/api/v1/skills/" + id + "/images", { name, data }),
  deleteSkillImage: (id, name) => fetch("/api/v1/skills/" + id + "/images/" + encodeURIComponent(name),
    { method: "DELETE" }).then(kbJson),
});
const skillImageUrl = (id, name) => "/api/v1/skills/" + id + "/images/" + encodeURIComponent(name);

const skPane = document.querySelector('[data-pane="skills"]');
const skel = {
  tabs: document.getElementById("skTabs"),
  rows: document.getElementById("skRows"),
  empty: document.getElementById("skEmpty"),
  hint: document.getElementById("skHint"),
  search: document.getElementById("skSearch"),
  newBtn: document.getElementById("skNewBtn"),
  drawer: document.getElementById("skDrawer"),
  heading: document.getElementById("skHeading"),
  actions: document.getElementById("skActions"),
  edit: document.getElementById("skEditBtn"),
  del: document.getElementById("skDeleteBtn"),
  view: document.getElementById("skView"),
  form: document.getElementById("skForm"),
  name: document.getElementById("sfName"),
  category: document.getElementById("sfCategory"),
  space: document.getElementById("sfSpace"),
  categories: document.getElementById("skCategories"),
  description: document.getElementById("sfDescription"),
  content: document.getElementById("sfContent"),
  enabled: document.getElementById("sfEnabled"),
  imagesField: document.getElementById("sfImagesField"),
  images: document.getElementById("sfImages"),
  imageInput: document.getElementById("sfImageInput"),
  error: document.getElementById("sfError"),
  cancel: document.getElementById("sfCancel"),
};
let skills = [];             // SkillDto[] from the server
let skLayer = "personal";    // the tab: personal | company
let skSelectedId = null;     // the skill open in the drawer (set before setView("skills") to open one)
let skEditing = null;        // null = viewing; "new" or a skill id = editing

const skLayerOf = s => s.space === "personal" ? "personal" : "company";

async function loadSkillList() {
  try { skills = (await api.listSkills()) || []; } catch (e) { skills = []; showToast(e.message, true); }
  return skills;
}

async function loadSkills() {
  await loadSkillList();
  const open = skills.find(s => s.id === skSelectedId);
  if (open) { skLayer = skLayerOf(open); openSkillDrawer(open.id); } else closeSkillDrawer();
  renderSkillTable();
}

function renderSkillTable() {
  const q = skel.search.value.trim().toLowerCase();
  const matches = s => !q || [s.name, s.description, s.category].some(v => (v || "").toLowerCase().includes(q));
  skel.tabs.querySelectorAll(".model-tab").forEach(t => {
    t.classList.toggle("is-active", t.dataset.layer === skLayer);
    t.querySelector("b").textContent = skills.filter(s => skLayerOf(s) === t.dataset.layer && matches(s)).length || "";
  });
  skel.hint.textContent = L("sk.hint." + skLayer);
  // By category, then name.
  const shown = skills.filter(s => skLayerOf(s) === skLayer && matches(s)).sort((a, b) =>
    (a.category || "").localeCompare(b.category || "") || a.name.localeCompare(b.name));
  skel.rows.replaceChildren(...shown.map(skillRow));
  skel.empty.hidden = shown.length > 0;
  skel.empty.textContent = L(q ? "sk.noMatch" : "sk.empty." + skLayer);
}

function skillRow(s) {
  const tr = document.createElement("tr");
  tr.className = "sk-row" + (s.enabled ? "" : " is-off");
  tr.tabIndex = 0;
  const cell = (text, cls) => {
    const td = document.createElement("td");
    if (cls) td.className = cls;
    td.textContent = text;
    td.title = text;
    return td;
  };
  const name = cell(s.name, "sk-row__name");
  const owner = s.createdBy || "—";
  const state = document.createElement("td");
  state.className = "sk-row__state";
  const badge = document.createElement("span");
  badge.className = "status-badge " + (s.enabled ? "is-done" : "is-stopped");
  badge.textContent = L(s.enabled ? "sk.enabled" : "sk.disabled");
  state.appendChild(badge);
  if (!s.canWrite) {
    const ro = document.createElement("span");
    ro.className = "kb-readonly";
    ro.textContent = L("sk.readOnly");
    state.appendChild(ro);
  }
  tr.append(name, cell(s.category || "—", "sk-row__meta"), cell(s.description || "", "sk-row__desc"),
    cell(owner, "sk-row__meta"), cell(formatDateTime(s.updatedAt), "sk-row__meta"), state);
  tr.addEventListener("click", () => openSkillDrawer(s.id));
  tr.addEventListener("keydown", e => { if (e.key === "Enter") openSkillDrawer(s.id); });
  return tr;
}

skel.search.addEventListener("input", renderSkillTable);
skel.tabs.addEventListener("click", e => {
  const t = e.target.closest(".model-tab");
  if (t) { skLayer = t.dataset.layer; renderSkillTable(); }
});

function skLayerLabel(space) {
  return L(space === "personal" ? "sk.layerPersonal" : "sk.layerCompany");
}

// Markdown references images as images/<name>; point those at the skill's image endpoint.
function renderSkillMarkdown(skill) {
  const div = document.createElement("div");
  div.className = "markdown";
  div.innerHTML = renderMarkdown(skill.content || "");
  div.querySelectorAll("img").forEach(img => {
    const m = /^(?:\.\/)?images\/(.+)$/.exec(img.getAttribute("src") || "");
    if (m) img.src = skillImageUrl(skill.id, decodeURIComponent(m[1]));
  });
  return div;
}

function openSkillDrawer(id) {
  skSelectedId = id;
  skEditing = null;
  skel.drawer.hidden = false;
  renderSkill();
}

function closeSkillDrawer() {
  skel.drawer.hidden = true;
  skSelectedId = null;
  skEditing = null;
}
skel.drawer.querySelectorAll("[data-close]").forEach(x => x.addEventListener("click", closeSkillDrawer));
document.addEventListener("keydown", e => {
  if (e.key === "Escape" && !skel.drawer.hidden && document.getElementById("kbModal").hidden) closeSkillDrawer();
});

function renderSkill() {
  const skill = skills.find(s => s.id === skSelectedId);
  skel.form.hidden = !skEditing;
  skel.view.hidden = !!skEditing;
  skel.actions.hidden = !!skEditing || !skill || !skill.canWrite;
  if (skEditing) return;
  skel.view.innerHTML = "";
  if (!skill) { closeSkillDrawer(); return; }
  skel.heading.textContent = skill.name;
  const meta = document.createElement("p");
  meta.className = "sk-meta";
  meta.textContent = [skLayerLabel(skill.space), skill.category || L("sk.uncategorized"),
    skill.canWrite ? null : L("sk.readOnly"), skill.enabled ? null : L("sk.disabled"),
    L("sk.updatedBy", { user: skill.updatedBy || skill.createdBy || "—", time: formatDateTime(skill.updatedAt) })]
    .filter(Boolean).join(" · ");
  skel.view.appendChild(meta);
  if (skill.description) {
    const d = document.createElement("p");
    d.className = "sk-desc";
    d.textContent = L("sk.whenToUse") + skill.description;
    skel.view.appendChild(d);
  }
  skel.view.appendChild(renderSkillMarkdown(skill));
}

function openSkillForm(skill) {
  skEditing = skill ? skill.id : "new";
  if (!skill) skSelectedId = null;
  skel.drawer.hidden = false;
  skel.heading.textContent = skill ? L("sk.editTitle", { name: skill.name }) : L("sk.newTitle");
  skel.name.value = skill ? skill.name : "";
  skel.category.value = skill ? skill.category || "" : "";
  skel.description.value = skill ? skill.description || "" : "";
  skel.content.value = skill ? skill.content || "" : "";
  skel.enabled.checked = skill ? skill.enabled : true;
  skel.error.hidden = true;
  skel.categories.innerHTML = "";
  Array.from(new Set(skills.map(s => s.category).filter(Boolean))).forEach(c => {
    const o = document.createElement("option");
    o.value = c;
    skel.categories.appendChild(o);
  });
  skel.imagesField.hidden = !skill; // images attach to a saved skill
  if (skill) renderSkillImages(skill);
  renderSkillSpaces(skill ? skill.space : "personal", skill ? null : skLayer);
  renderSkill();
  skel.name.focus();
}

// The layers the user may put a skill in: their own, or the company's (admins). A new skill starts in
// the open tab's layer when the user may write there.
function renderSkillSpaces(current, preferLayer) {
  skel.space.innerHTML = "";
  const add = (id, label) => {
    const o = document.createElement("option");
    o.value = id;
    o.textContent = label;
    skel.space.appendChild(o);
  };
  add("personal", L("sk.spacePersonal"));
  if (isAdmin || current === "company") add("company", L("sk.spaceCompany"));
  skel.space.value = !current || current === "personal" ? (preferLayer === "company" && isAdmin ? "company" : "personal")
    : current;
}

function renderSkillImages(skill) {
  skel.images.innerHTML = "";
  (skill.images || []).forEach(name => {
    const box = document.createElement("div");
    box.className = "sk-image";
    const img = document.createElement("img");
    img.src = skillImageUrl(skill.id, name);
    img.alt = name;
    const row = document.createElement("div");
    row.className = "sk-image__row";
    const label = document.createElement("span");
    label.textContent = name;
    label.title = name;
    const actions = document.createElement("span");
    const insert = document.createElement("button");
    insert.type = "button";
    insert.className = "link-btn";
    insert.textContent = L("sk.insert");
    insert.addEventListener("click", () => insertAtCursor(skel.content, "![" + name + "](images/" + name + ")"));
    const remove = document.createElement("button");
    remove.type = "button";
    remove.className = "link-btn link-btn--danger";
    remove.textContent = "×";
    remove.title = L("common.delete");
    remove.addEventListener("click", async () => {
      try { replaceSkill(await api.deleteSkillImage(skill.id, name)); renderSkillImages(skills.find(s => s.id === skill.id)); }
      catch (e) { showToast(e.message, true); }
    });
    actions.append(insert, remove);
    row.append(label, actions);
    box.append(img, row);
    skel.images.appendChild(box);
  });
}

function insertAtCursor(textarea, text) {
  const start = textarea.selectionStart ?? textarea.value.length;
  textarea.value = textarea.value.slice(0, start) + text + textarea.value.slice(textarea.selectionEnd ?? start);
  textarea.focus();
  textarea.selectionStart = textarea.selectionEnd = start + text.length;
}

function replaceSkill(updated) {
  skills = skills.map(s => s.id === updated.id ? updated : s);
}

skel.imageInput.addEventListener("change", async () => {
  const file = skel.imageInput.files && skel.imageInput.files[0];
  skel.imageInput.value = "";
  if (!file || !skEditing || skEditing === "new") return;
  try {
    const data = await new Promise((resolve, reject) => {
      const reader = new FileReader();
      reader.onload = () => resolve(String(reader.result).replace(/^data:[^,]*,/, ""));
      reader.onerror = () => reject(reader.error);
      reader.readAsDataURL(file);
    });
    const updated = await api.addSkillImage(skEditing, file.name, data);
    replaceSkill(updated);
    renderSkillImages(updated);
  } catch (e) { showToast(e.message, true); }
});

skel.newBtn.addEventListener("click", () => openSkillForm(null));
skel.edit.addEventListener("click", () => {
  const skill = skills.find(s => s.id === skSelectedId);
  if (skill) openSkillForm(skill);
});
skel.cancel.addEventListener("click", () => {
  if (skEditing === "new") closeSkillDrawer(); else { skEditing = null; renderSkill(); }
});
skel.form.addEventListener("submit", async e => {
  e.preventDefault();
  const body = {
    name: skel.name.value.trim(), category: skel.category.value.trim(),
    description: skel.description.value.trim(), content: skel.content.value, enabled: skel.enabled.checked,
    space: skel.space.value || "personal",
  };
  skel.error.hidden = true;
  try {
    const saved = skEditing === "new" ? await api.createSkill(body) : await api.updateSkill(skEditing, body);
    skSelectedId = saved.id;
    await loadSkills();
  } catch (err) {
    skel.error.textContent = err.message;
    skel.error.hidden = false;
  }
});
skel.del.addEventListener("click", () => {
  const skill = skills.find(s => s.id === skSelectedId);
  if (!skill) return;
  kbOpenModal({
    title: L("sk.deleteTitle"), submit: L("common.delete"), danger: true,
    message: L("sk.deleteConfirm", { name: skill.name }),
    action: async () => { await api.deleteSkill(skill.id); closeSkillDrawer(); await loadSkills(); },
  });
});

// Agent form: a multi-select dropdown of skills (enabled ones; a disabled one already attached stays listed).
let chosenSkills = new Set();
const skillSelect = {
  root: document.getElementById("afSkills"),
  trigger: document.getElementById("afSkillsTrigger"),
  menu: document.getElementById("afSkillsMenu"),
};

async function renderSkillPicker(current) {
  await loadSkillList();
  chosenSkills = new Set(current || []);
  skillSelect.menu.innerHTML = "";
  const offered = skills.filter(s => s.enabled || chosenSkills.has(s.id));
  if (!offered.length) {
    skillSelect.menu.innerHTML = '<p class="multi-select__empty">' + L("sk.noneYet") + "</p>";
  }
  offered.forEach(s => {
    const label = document.createElement("label");
    label.className = "multi-select__option";
    label.setAttribute("role", "option");
    const cb = document.createElement("input");
    cb.type = "checkbox";
    cb.checked = chosenSkills.has(s.id);
    cb.addEventListener("change", () => {
      if (cb.checked) chosenSkills.add(s.id); else chosenSkills.delete(s.id);
      label.setAttribute("aria-selected", String(cb.checked));
      renderSkillTrigger();
    });
    label.setAttribute("aria-selected", String(cb.checked));
    const text = document.createElement("span");
    text.className = "multi-select__text";
    const name = document.createElement("span");
    name.className = "multi-select__name";
    name.textContent = s.name + (s.enabled ? "" : " (" + L("sk.disabled") + ")");
    const desc = document.createElement("span");
    desc.className = "multi-select__desc";
    desc.textContent = [skLayerLabel(s.space), s.description].filter(Boolean).join(" · ");
    text.append(name, desc);
    label.append(cb, text);
    skillSelect.menu.appendChild(label);
  });
  closeSkillMenu();
  renderSkillTrigger();
}

// The closed dropdown shows the chosen skills as chips, or a placeholder.
function renderSkillTrigger() {
  skillSelect.trigger.innerHTML = "";
  const chosen = skills.filter(s => chosenSkills.has(s.id));
  if (!chosen.length) {
    const ph = document.createElement("span");
    ph.className = "multi-select__placeholder";
    ph.textContent = L("sk.pickPh");
    skillSelect.trigger.appendChild(ph);
    return;
  }
  chosen.forEach(s => {
    const chip = document.createElement("span");
    chip.className = "multi-select__chip";
    chip.textContent = s.name;
    skillSelect.trigger.appendChild(chip);
  });
}

function closeSkillMenu() {
  skillSelect.menu.hidden = true;
  skillSelect.trigger.setAttribute("aria-expanded", "false");
}
skillSelect.trigger.addEventListener("click", () => {
  const open = skillSelect.menu.hidden;
  skillSelect.menu.hidden = !open;
  skillSelect.trigger.setAttribute("aria-expanded", String(open));
});
document.addEventListener("click", e => { if (!skillSelect.root.contains(e.target)) closeSkillMenu(); });
skillSelect.root.addEventListener("keydown", e => { if (e.key === "Escape" && !skillSelect.menu.hidden) { e.stopPropagation(); closeSkillMenu(); skillSelect.trigger.focus(); } });

// Agent side panel: its skills as chips that open the skill.
async function renderAgentSkills(agent) {
  const box = document.getElementById("agentInfoSkills");
  if (!box) return;
  const ids = agent.skillIds || [];
  if (ids.length && !ids.every(id => skills.some(s => s.id === id))) await loadSkillList();
  if (activeAgent !== agent) return;
  box.innerHTML = "";
  const attached = ids.map(id => skills.find(s => s.id === id)).filter(Boolean);
  if (!attached.length) {
    box.innerHTML = '<span class="skill-chips__empty">' + L("sk.agentNone") + "</span>";
    return;
  }
  attached.forEach(s => {
    const chip = document.createElement("button");
    chip.type = "button";
    chip.className = "skill-chip";
    chip.textContent = s.name + (s.enabled ? "" : " (" + L("sk.disabled") + ")");
    chip.title = s.description || "";
    chip.addEventListener("click", () => { skSelectedId = s.id; setView("skills"); });
    box.appendChild(chip);
  });
}
