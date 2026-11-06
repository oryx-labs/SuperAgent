// SuperAgent UI — Create / edit agent dialog: avatar, character, skills.
// Classic script sharing one global scope with the others; index.html loads them in order.
"use strict";

// ---------- wiring ----------
// ---------- agent modal ----------
const ael = {
  modal: document.getElementById("agentModal"),
  form: document.getElementById("agentForm"),
  title: document.getElementById("agentModalTitle"),
  name: document.getElementById("afName"),
  persona: document.getElementById("afPersona"),
  avatars: document.getElementById("afAvatars"),
  character: document.getElementById("afCharacter"),
  characterHint: document.getElementById("afCharacterHint"),
  personaLabel: document.getElementById("afPersonaLabel"),
};
let avatarChoices = [];   // built-in emoji avatars, loaded once from the server
let chosenAvatar = null;

async function renderAvatarPicker(current) {
  if (!avatarChoices.length) {
    try { const res = await api.agentAvatars(); avatarChoices = (res && res.data) || []; } catch (e) { avatarChoices = []; }
  }
  // A new agent starts on a random pick (the user can change it); an edit keeps its avatar.
  chosenAvatar = current || (avatarChoices.length ? avatarChoices[Math.floor(Math.random() * avatarChoices.length)] : null);
  ael.avatars.innerHTML = "";
  avatarChoices.forEach(av => {
    const b = document.createElement("button");
    b.type = "button";
    b.className = "avatar-option" + (av === chosenAvatar ? " is-selected" : "");
    b.textContent = av;
    b.setAttribute("role", "radio");
    b.setAttribute("aria-checked", String(av === chosenAvatar));
    b.addEventListener("click", () => {
      chosenAvatar = av;
      ael.avatars.querySelectorAll(".avatar-option").forEach(x => {
        const on = x.textContent === av;
        x.classList.toggle("is-selected", on);
        x.setAttribute("aria-checked", String(on));
      });
    });
    ael.avatars.appendChild(b);
  });
}

// Characters: built-in "what kind of person" presets from the server, plus a custom one written in the persona box.
let characterChoices = [];
let chosenCharacter = null;   // preset key, or null = custom
const zh = () => window.I18N.getLang() === "zh";
function characterByKey(key) { return characterChoices.find(c => c.key === key) || null; }
function characterName(c) { return zh() ? c.nameZh : c.nameEn; }
async function loadCharacters() {
  if (characterChoices.length) return;
  try { const res = await api.agentCharacters(); characterChoices = (res && res.data) || []; } catch (e) { characterChoices = []; }
}

// Single choice: the presets, then "custom" (value ""). The selected one's summary shows under the select.
async function renderCharacterPicker(current) {
  await loadCharacters();
  ael.character.innerHTML = "";
  const add = (value, label) => {
    const o = document.createElement("option");
    o.value = value;
    o.textContent = label;
    ael.character.appendChild(o);
  };
  characterChoices.forEach(c => add(c.key, c.emoji + " " + characterName(c)));
  add("", "✏️ " + L("agent.characterCustom"));
  selectCharacter(current);
}

// Custom: the persona box IS the character (required). Preset: it's optional extra instructions.
function selectCharacter(key) {
  chosenCharacter = key || null;
  ael.character.value = key || "";
  const preset = characterByKey(chosenCharacter);
  ael.characterHint.textContent = preset ? (zh() ? preset.summaryZh : preset.summaryEn) : L("agent.characterCustomHint");
  const custom = !chosenCharacter;
  ael.personaLabel.textContent = L(custom ? "agent.fPersonaCustom" : "agent.fPersonaExtra");
  ael.persona.placeholder = L(custom ? "agent.fPersonaPh" : "agent.fPersonaExtraPh");
  ael.persona.required = custom;
}

ael.character.addEventListener("change", () => selectCharacter(ael.character.value));

function openAgentModal(agent) {
  if (streaming) return;
  editingAgentId = agent && agent.id ? agent.id : null;
  editingAgentModel = agent && agent.model ? agent.model : null;
  ael.title.textContent = L(editingAgentId ? "agent.modalEdit" : "agent.modalAdd");
  ael.name.value = agent && agent.name ? agent.name : "";
  ael.persona.value = agent && agent.persona ? agent.persona : "";
  ael.modal.hidden = false;
  ael.name.focus();
  renderAvatarPicker(agent && agent.avatar ? agent.avatar : null);
  // A new agent starts on the first preset; an edit keeps its character (or custom).
  renderCharacterPicker(agent ? agent.character || null : "rigorous-engineer");
  renderSkillPicker(agent ? agent.skillIds : []);
}
function closeAgentModal() { ael.modal.hidden = true; }

ael.modal.querySelectorAll("[data-close]").forEach(x => x.addEventListener("click", closeAgentModal));
ael.form.addEventListener("submit", async e => {
  e.preventDefault();
  const name = ael.name.value.trim();
  if (!name) return;
  // No model: new agents chat with the admin-set system default (System Settings).
  const body = { name, persona: ael.persona.value.trim(), model: editingAgentId ? editingAgentModel : null,
    avatar: chosenAvatar, character: chosenCharacter, skillIds: Array.from(chosenSkills) };
  const res = editingAgentId
    ? await api.updateAgent(editingAgentId, body)
    : await api.createAgent(body);
  closeAgentModal();
  await loadAgents();
  if (res && res.data) {
    if (editingAgentId) {
      if (activeAgentId === res.data.id) { activeAgent = res.data; renderAgentPanel(res.data); }
    } else {
      selectAgent(res.data);
    }
  }
});

const agentEditBtn = document.getElementById("agentEditBtn");
if (agentEditBtn) agentEditBtn.addEventListener("click", () => { if (activeAgent) openAgentModal(activeAgent); });

el.newChatBtn.addEventListener("click", () => openAgentModal());

const chatImages = imageAttacher({
  button: document.getElementById("chatAttachBtn"),
  fileInput: document.getElementById("chatImageInput"),
  tray: document.getElementById("chatImageTray"),
  pasteTarget: el.input,
  dropZone: el.composer,
});

el.composer.addEventListener("submit", async e => {
  e.preventDefault();
  if (streaming) { stopGeneration(); return; }
  if (noChatModel) { showToast(L(isAdmin ? "chat.noModelAdmin" : "chat.noModelUser"), true); return; }
  const text = el.input.value.trim();
  if (!text && chatImages.count() === 0) return;
  // Attachments go to Files first (under this agent); the message then carries their ids.
  let fileIds = [];
  if (chatImages.count()) {
    el.sendBtn.disabled = true;
    try { fileIds = await chatImages.upload({ agent: activeAgentId }); }
    catch (err) { showToast(err.message, true); return; } // keep text and files so they can be resent
    finally { el.sendBtn.disabled = noChatModel; }
  }
  const urls = chatImages.previewUrls();
  const names = chatImages.fileNames();
  el.input.value = "";
  autoGrow();
  chatImages.clear();
  sendMessage(text, [], urls, fileIds, names);
});

el.input.addEventListener("input", autoGrow);
