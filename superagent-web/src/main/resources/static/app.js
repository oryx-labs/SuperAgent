// SuperAgent chat UI — REST + SSE backend, with stop and inline edit.
(function () {
  "use strict";

  const L = (k, v) => window.I18N.t(k, v);

  const api = {
    listSessions: () => fetch("/api/v1/sessions").then(r => r.json()),
    createSession: () =>
      fetch("/api/v1/sessions", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ agentType: "claude" }),
      }).then(r => r.json()),
    getSession: id => fetch("/api/v1/sessions/" + id).then(r => r.json()),
    stop: id => fetch("/api/v1/sessions/" + id + "/stop", { method: "POST" }),
    rename: (id, title) =>
      fetch("/api/v1/sessions/" + id + "/title", {
        method: "PUT",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ title }),
      }).then(r => { if (!r.ok) throw new Error("rename failed"); return r.json(); }),
    remove: id => fetch("/api/v1/sessions/" + id, { method: "DELETE" }),
    editMessage: (sid, mid, content) =>
      fetch("/api/v1/sessions/" + sid + "/messages/" + mid, {
        method: "PUT",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ content }),
      }).then(r => { if (!r.ok) throw new Error("edit failed"); return r.json(); }),
    listAgents: () => fetch("/api/v1/agents").then(r => r.json()),
    agentHistory: id => fetch("/api/v1/agents/" + id + "/history").then(r => r.json()),
    superState: () => fetch("/api/v1/superagent").then(r => r.json()),
    superSubmit: body => fetch("/api/v1/superagent/messages", {
      method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body),
    }).then(r => r.json()),
    superWork: (status, memberId, limit) => fetch("/api/v1/superagent/work?status=" + encodeURIComponent(status)
      + (memberId ? "&memberId=" + encodeURIComponent(memberId) : "") + "&limit=" + limit).then(r => r.json()),
    superWorkEvents: taskId => fetch("/api/v1/superagent/work/" + encodeURIComponent(taskId) + "/events")
      .then(r => r.json()),
    superApprove: planId => fetch("/api/v1/superagent/plans/" + encodeURIComponent(planId) + "/approve",
      { method: "POST" }).then(r => r.json()),
    createAgent: body =>
      fetch("/api/v1/agents", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(body),
      }).then(r => r.json()),
    agentAvatars: () => fetch("/api/v1/agents/avatars").then(r => r.json()),
    agentCharacters: () => fetch("/api/v1/agents/characters").then(r => r.json()),
    updateAgent: (id, body) =>
      fetch("/api/v1/agents/" + id, {
        method: "PUT",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(body),
      }).then(r => { if (!r.ok) throw new Error("update failed"); return r.json(); }),
    deleteAgent: id => fetch("/api/v1/agents/" + id, { method: "DELETE" }),
  };

  const el = {
    threadList: document.getElementById("threadList"),
    messages: document.getElementById("messages"),
    emptyState: document.getElementById("emptyState"),
    composer: document.getElementById("composer"),
    input: document.getElementById("composerInput"),
    newChatBtn: document.getElementById("newChatBtn"),
    sendBtn: document.getElementById("sendBtn"),
  };

  let agents = [];
  let activeAgentId = null;
  let activeAgent = null;
  let editingAgentId = null;
  let editingAgentModel = null;  // legacy per-agent override; the form no longer edits it, so carry it through
  let systemSettings = {};       // SystemSettingsDto from /settings
  let activeSessionId = null;
  let streaming = false;

  // ---------- helpers ----------
  function clearEmptyState() { if (el.emptyState) el.emptyState.hidden = true; }
  function scrollToBottom() { el.messages.scrollTop = el.messages.scrollHeight; }

  if (window.marked && window.marked.setOptions) window.marked.setOptions({ gfm: true, breaks: true });

  // Render assistant/agent markdown to sanitized HTML. Raw HTML in the source is
  // passed through by marked, so DOMPurify is the XSS boundary (no script/on*/javascript:).
  function renderMarkdown(content) {
    const raw = content == null ? "" : String(content);
    if (!window.marked || !window.DOMPurify) {
      const d = document.createElement("div");
      d.textContent = raw;
      return d.innerHTML;
    }
    return window.DOMPurify.sanitize(window.marked.parse(raw));
  }

  const isUserRole = role => role === "user";

  // Fill a bubble with a message body, keeping the raw markdown in dataset.md so
  // inline edit can recover the source. User text stays plain; agent text is markdown.
  function setBubbleContent(bubble, role, content) {
    bubble.dataset.md = content == null ? "" : String(content);
    if (isUserRole(role)) bubble.textContent = content;
    else bubble.innerHTML = renderMarkdown(content);
  }

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

  // ---------- stored messages (with inline edit) ----------
  function renderStoredMessage(role, content, id, images, sessionId) {
    clearEmptyState();
    const msg = document.createElement("div");
    msg.className = "msg " + (role === "user" ? "msg--user" : role === "system" ? "msg--system" : "msg--agent");
    if (id) msg.dataset.mid = id;
    if (sessionId) msg.dataset.sid = sessionId;
    if (role === "system") { // SuperAgent inbox notes: task reports, plan approvals
      const note = document.createElement("div");
      note.className = "msg__note";
      note.textContent = content;
      msg.appendChild(note);
      el.messages.appendChild(msg);
      return msg;
    }
    if (images && images.length) msg.appendChild(imageStrip(images));
    const bubble = document.createElement("div");
    bubble.className = "bubble";
    setBubbleContent(bubble, role, content);
    bubble.hidden = !content && !!(images && images.length); // image-only message: no empty bubble
    msg.appendChild(bubble);
    if (id) {
      const edit = document.createElement("button");
      edit.className = "msg__edit";
      edit.title = L("common.edit");
      edit.textContent = "✎";
      edit.addEventListener("click", () => beginEdit(msg, id));
      msg.appendChild(edit);
    }
    el.messages.appendChild(msg);
    return msg;
  }

  function beginEdit(msgEl, mid) {
    if (streaming) return;
    const role = msgEl.classList.contains("msg--user") ? "user" : "agent";
    const bubble = msgEl.querySelector(".bubble");
    const original = bubble.dataset.md != null ? bubble.dataset.md : bubble.textContent;
    const ta = document.createElement("textarea");
    ta.className = "edit-area";
    ta.value = original;
    const row = document.createElement("div");
    row.className = "edit-actions";
    const save = document.createElement("button");
    save.className = "edit-save";
    save.textContent = L("common.save");
    const cancel = document.createElement("button");
    cancel.className = "edit-cancel";
    cancel.textContent = L("common.cancel");
    row.append(save, cancel);
    bubble.replaceChildren(ta, row);
    ta.focus();
    ta.style.height = Math.min(ta.scrollHeight, 220) + "px";

    cancel.addEventListener("click", () => setBubbleContent(bubble, role, original));
    save.addEventListener("click", async () => {
      const val = ta.value;
      save.disabled = true;
      try {
        await api.editMessage(msgEl.dataset.sid || activeSessionId, mid, val);
        setBubbleContent(bubble, role, val);
      } catch (e) {
        setBubbleContent(bubble, role, original);
      }
    });
  }

  // ---------- streaming bubbles ----------
  function addUserBubble(text, imageUrls) {
    clearEmptyState();
    const msg = document.createElement("div");
    msg.className = "msg msg--user";
    if (imageUrls && imageUrls.length) msg.appendChild(imageStrip(imageUrls));
    const bubble = document.createElement("div");
    bubble.className = "bubble";
    bubble.textContent = text;
    bubble.hidden = !text;
    msg.appendChild(bubble);
    el.messages.appendChild(msg);
    scrollToBottom();
  }

  function addAssistantBubble() {
    clearEmptyState();
    const msg = document.createElement("div");
    msg.className = "msg msg--agent";
    const bubble = document.createElement("div");
    bubble.className = "bubble";
    const thinking = document.createElement("details");
    thinking.className = "thinking";
    thinking.hidden = true;
    const summary = document.createElement("summary");
    summary.textContent = L("chat.thinking");
    const thinkingBody = document.createElement("div");
    thinkingBody.className = "thinking__body";
    thinking.append(summary, thinkingBody);
    const tools = document.createElement("div");
    tools.className = "tools";
    tools.hidden = true;
    const text = document.createElement("div");
    text.className = "text is-streaming";
    bubble.append(thinking, tools, text);
    msg.appendChild(bubble);
    el.messages.appendChild(msg);
    scrollToBottom();
    return { thinking, thinkingBody, tools, text };
  }

  // ---------- agents (sidebar) ----------
  // Phase 1 is 1 agent : 1 session; each agent carries its owning sessionId, which
  // is what we actually open to chat. The agent is the named role; the session is plumbing.
  async function loadAgents(autoOpen) {
    const res = await api.listAgents();
    agents = res.data || [];
    renderThreads(agents);
    if (autoOpen && !activeAgentId) {
      const first = agents.find(a => a.sessionId);
      if (first) selectAgent(first);
    }
  }

  function renderThreads(list) {
    el.threadList.innerHTML = "";
    list.forEach(a => {
      if (a.kind === "super") { el.threadList.append(superThread(a), threadSeparator()); return; }
      const li = document.createElement("li");
      li.className = "thread" + (a.id === activeAgentId ? " is-active" : "");
      li.dataset.id = a.id;

      const avatar = document.createElement("span");
      avatar.className = "agent-avatar";
      avatar.setAttribute("aria-hidden", "true");
      avatar.textContent = a.avatar || "🙂";

      const name = document.createElement("span");
      name.className = "thread__name";
      name.textContent = a.name || "";

      if (a.managedBy) {
        const tag = document.createElement("span");
        tag.className = "thread__tag";
        tag.textContent = L("super.teamTag");
        tag.title = L("super.teamHint");
        name.appendChild(tag);
      }

      const time = document.createElement("span");
      time.className = "thread__time";
      time.textContent = relativeTime(a.updatedAt);

      const actions = document.createElement("span");
      actions.className = "thread__actions";
      const rename = document.createElement("button");
      rename.className = "thread__act";
      rename.title = L("common.edit");
      rename.textContent = "✎";
      rename.addEventListener("click", e => { e.stopPropagation(); openAgentModal(a); });
      const del = document.createElement("button");
      del.className = "thread__act";
      del.title = L("common.delete");
      del.textContent = "×";
      del.addEventListener("click", e => { e.stopPropagation(); deleteThread(li, a); });
      actions.append(rename, del);

      li.append(avatar, name, time, actions);
      li.addEventListener("click", () => selectAgent(a));
      el.threadList.appendChild(li);
    });
  }

  // The SuperAgent isn't one more row: it leads the list as an orb with its role under the name.
  function superThread(a) {
    const li = document.createElement("li");
    li.className = "thread thread--super" + (a.id === activeAgentId ? " is-active" : "");
    li.dataset.id = a.id;
    const orb = document.createElement("span");
    orb.className = "super-orb";
    orb.setAttribute("aria-hidden", "true");
    const body = document.createElement("span");
    body.className = "super-body";
    const name = document.createElement("span");
    name.className = "thread__name super-name";
    name.textContent = a.name || "SuperAgent";
    const role = document.createElement("span");
    role.className = "super-role";
    role.textContent = L("super.role");
    body.append(name, role);
    li.append(orb, body);
    li.addEventListener("click", () => selectAgent(a));
    return li;
  }

  function threadSeparator() {
    const li = document.createElement("li");
    li.className = "thread-sep";
    li.setAttribute("aria-hidden", "true");
    return li;
  }

  function beginRename(li, agent, nameEl) {
    if (streaming) return;
    const original = nameEl.textContent;
    const input = document.createElement("input");
    input.className = "thread__rename-input";
    input.value = original;
    nameEl.replaceWith(input);
    input.focus();
    input.select();

    let done = false;
    const commit = async save => {
      if (done) return;
      done = true;
      const span = document.createElement("span");
      span.className = "thread__name";
      const value = input.value.trim();
      if (save && value && value !== original) {
        try {
          await api.updateAgent(agent.id, {
            name: value, avatar: agent.avatar, character: agent.character, persona: agent.persona, model: agent.model,
          });
          agent.name = value;
          span.textContent = value;
        } catch (e) { span.textContent = original; }
      } else {
        span.textContent = original;
      }
      input.replaceWith(span);
    };
    input.addEventListener("keydown", e => {
      if (e.key === "Enter") { e.preventDefault(); commit(true); }
      else if (e.key === "Escape") { commit(false); }
    });
    input.addEventListener("blur", () => commit(true));
    input.addEventListener("click", e => e.stopPropagation());
  }

  async function deleteThread(li, agent) {
    if (streaming) return;
    if (!window.confirm(L("agent.deleteConfirm", { name: agent.name || "" }))) return;
    try { await api.deleteAgent(agent.id); } catch (e) { /* ignore */ }
    li.remove();
    agents = agents.filter(a => a.id !== agent.id);
    if (activeAgentId === agent.id) {
      activeAgentId = null;
      activeSessionId = null;
      el.messages.innerHTML = '<div class="empty-state"><p>' + L("agent.deletedState") + '</p></div>';
    }
  }

  function markActiveThread() {
    el.threadList.querySelectorAll(".thread").forEach(t =>
      t.classList.toggle("is-active", t.dataset.id === activeAgentId));
  }

  async function selectAgent(agent) {
    if (streaming) return;
    activeAgentId = agent.id;
    activeAgent = agent;
    renderAgentPanel(agent);
    markActiveThread();
    setSuperMode(agent.kind === "super");
    if (!agent.sessionId) {
      activeSessionId = null;
      el.messages.innerHTML = '<div class="empty-state"><p>' + L("agent.emptyChat") + '</p></div>';
      return;
    }
    await selectSession(agent.sessionId);
  }

  function renderAgentPanel(agent) {
    const panel = document.getElementById("agentInfoPanel");
    if (!panel) return;
    if (!agent) { panel.hidden = true; return; }
    panel.hidden = false;
    document.getElementById("agentInfoName").textContent = agent.name || "Agent";
    document.getElementById("agentInfoAvatar").textContent = agent.avatar || "🙂";
    const ch = characterByKey(agent.character);
    document.getElementById("agentInfoCharacter").textContent = agent.kind === "super" ? L("super.role")
      : ch ? ch.emoji + " " + characterName(ch) : L("agent.characterCustom");
    const editBtn = document.getElementById("agentEditBtn");
    if (editBtn) editBtn.hidden = agent.kind === "super";
    panel.classList.toggle("agent-info--super", agent.kind === "super");
    document.getElementById("agentInfoDetails").hidden = agent.kind === "super";
    document.getElementById("superIntro").hidden = agent.kind !== "super";
    const persona = document.getElementById("agentInfoPersona");
    persona.textContent = agent.persona || "";
    persona.hidden = !agent.persona;
    if (!characterChoices.length) loadCharacters().then(() => { if (activeAgent === agent && agent.character) renderAgentPanel(agent); });
    const model = agent.model || systemSettings.agentDefaultModel || "default";
    document.getElementById("agentInfoModel").textContent = model;
    renderComposerModel(agent);
    renderAgentSkills(agent);
  }

  // Composer model picker: the agent's model override, limited to Claude models the background probe saw answer.
  const composerModel = document.getElementById("statusHint");

  async function renderComposerModel(agent) {
    const { groups } = await loadChatModels();
    if (activeAgent !== agent) return; // switched agents while loading
    composerModel.innerHTML = "";
    const def = document.createElement("option");
    def.value = "";
    def.textContent = L("chat.modelDefault", { model: systemSettings.agentDefaultModel || L("settings.modelCliDefault") });
    composerModel.appendChild(def);
    fillModelGroups(composerModel, groups, m => m.id);
    // Show the truth when the saved model has since failed its probe, rather than silently displaying another.
    if (agent.model && !groups.some(g => g.models.some(m => m.id === agent.model))) {
      fillModelGroups(composerModel, [{ label: L("settings.modelCurrent"), models: [{ id: agent.model }] }], m => m.id);
    }
    composerModel.value = agent.model || "";
  }

  // Chat models across every runnable CLI (the backend runs a turn under the CLI that owns the chosen
  // model), keeping only those the background probe saw answer: being listed isn't enough.
  const CLI_LABELS = { claude: "Claude Code", codex: "Codex" };
  async function loadChatModels() {
    let options = [];
    try { const res = await api.taskAgents(); options = (res && res.data) || []; } catch (e) { /* keep empty */ }
    let listed = 0;
    const groups = options.map(o => {
      const models = o.models || [];
      listed += models.length;
      return { label: CLI_LABELS[o.key] || o.name || o.key, models: models.filter(m => m.inferStatus === "ok") };
    }).filter(g => g.models.length);
    return { groups, listed };
  }

  function fillModelGroups(select, groups, label) {
    groups.forEach(gr => {
      const g = document.createElement("optgroup");
      g.label = gr.label;
      gr.models.forEach(m => {
        const o = document.createElement("option");
        o.value = m.id;
        o.textContent = label(m);
        g.appendChild(o);
      });
      select.appendChild(g);
    });
  }

  composerModel.addEventListener("change", async () => {
    const agent = activeAgent;
    if (!agent) return;
    try {
      const res = await api.updateAgent(agent.id, {
        name: agent.name, avatar: agent.avatar, character: agent.character, persona: agent.persona,
        model: composerModel.value || null,
      });
      activeAgent = res.data;
      renderAgentPanel(res.data);
    } catch (e) {
      showToast(L("chat.modelSaveFailed"), true);
      composerModel.value = agent.model || "";
    }
  });

  async function selectSession(id) {
    if (superMode) { activeSessionId = id; superFingerprint = null; await refreshSuper(); return; }
    activeSessionId = id;
    el.messages.innerHTML = "";
    let messages;
    if (activeAgent) {
      const res = await api.agentHistory(activeAgent.id);
      const segments = (res && res.data) || [];
      // The next turn continues the newest session.
      if (segments.length) activeSessionId = segments[segments.length - 1].sessionId;
      messages = flattenHistory(segments);
    } else {
      const res = await api.getSession(id);
      messages = (res.data && res.data.messages) || [];
    }
    if (!messages.some(m => !m.divider)) {
      el.messages.innerHTML = '<div class="empty-state"><p>' + L("chat.startState") + '</p></div>';
    }
    messages.forEach(renderHistoryItem);
    scrollToBottom();
  }

  // An agent has many sessions over time (a model change starts a new one, which picks up from the
  // agent's memory rather than the old conversation). Show them as one thread, marking each new start.
  function flattenHistory(segments) {
    const out = [];
    segments.forEach((seg, i) => {
      if (!seg.messages.length) return;
      if (out.length) out.push({ divider: true, at: seg.startedAt });
      seg.messages.forEach(m => out.push(Object.assign({ sessionId: seg.sessionId }, m)));
    });
    return out;
  }

  function renderHistoryItem(m) {
    if (m.divider) {
      const d = document.createElement("div");
      d.className = "session-divider";
      d.textContent = L("chat.newSession", { time: formatDateTime(m.at) });
      el.messages.appendChild(d);
      return null;
    }
    return renderStoredMessage(m.role, m.content, m.role === "system" ? null : m.id, m.images, m.sessionId);
  }

  // ---------- send / stream / stop ----------
  async function sendMessage(text, images, imageUrls) {
    if (!activeSessionId) { openAgentModal(); return; }
    addUserBubble(text, imageUrls);
    const handles = addAssistantBubble();
    setStreaming(true);
    try {
      const resp = await fetch("/api/v1/sessions/" + activeSessionId + "/messages", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ content: text, images: images || [] }),
      });
      if (!resp.ok) {
        const err = await resp.json().catch(() => null);
        throw new Error((err && err.message) || "HTTP " + resp.status);
      }
      await consumeSse(resp, handles);
    } catch (e) {
      handles.text.classList.remove("is-streaming");
      handles.text.textContent = L("chat.connFailed", { msg: e.message });
    } finally {
      handles.text.classList.remove("is-streaming");
      setStreaming(false);
      await selectSession(activeSessionId); // refresh with ids so messages become editable
      loadAgents();
    }
  }

  async function stopGeneration() {
    if (!activeSessionId) return;
    try { await api.stop(activeSessionId); } catch (e) { /* ignore */ }
  }

  async function consumeSse(resp, handles) {
    const reader = resp.body.getReader();
    const decoder = new TextDecoder();
    let buffer = "";
    while (true) {
      const { value, done } = await reader.read();
      if (done) break;
      buffer += decoder.decode(value, { stream: true });
      let idx;
      while ((idx = buffer.indexOf("\n\n")) >= 0) {
        handleFrame(buffer.slice(0, idx), handles);
        buffer = buffer.slice(idx + 2);
      }
    }
  }

  // Streamed text arrives incrementally; accumulate the full source in dataset.md
  // and re-render the whole bubble each tick so markdown stays well-formed (and we
  // avoid per-token flicker from re-parsing partial tokens into throwaway nodes).
  function renderStreamText(textEl, fullRaw) {
    textEl.dataset.md = fullRaw;
    textEl.innerHTML = renderMarkdown(fullRaw);
  }

  function handleFrame(frame, handles) {
    const dataLine = frame.split("\n").find(l => l.startsWith("data:"));
    if (!dataLine) return;
    let ev;
    try { ev = JSON.parse(dataLine.slice(5).trim()); } catch (e) { return; }
    switch (ev.type) {
      case "thinking":
        handles.thinking.hidden = false;
        handles.thinking.open = true;
        handles.thinkingBody.textContent += ev.payload;
        break;
      case "text":
        renderStreamText(handles.text, (handles.text.dataset.md || "") + (ev.payload || ""));
        break;
      case "tool_use": {
        handles.tools.hidden = false;
        const chip = document.createElement("span");
        chip.className = "tool-chip";
        chip.textContent = "⚙ " + ev.payload;
        handles.tools.appendChild(chip);
        break;
      }
      case "tool_result": {
        const created = parseCreatedTask(ev.payload);
        if (created) appendTaskLink(handles, created);
        break;
      }
      case "result":
        if (ev.payload) renderStreamText(handles.text, ev.payload);
        break;
      case "stopped": {
        const chip = document.createElement("span");
        chip.className = "stopped-chip";
        chip.textContent = L("chat.stopped");
        handles.text.after(chip);
        break;
      }
      case "error":
        handles.text.classList.add("is-error");
        handles.text.textContent = L("chat.errored", { msg: ev.payload || L("chat.errGeneric") });
        break;
      case "done":
        handles.thinking.open = false;
        break;
      default:
        break;
    }
    scrollToBottom();
  }

  // Only create_task results carry this prefix, so other tool results stay out of the bubble.
  function parseCreatedTask(payload) {
    if (!payload) return null;
    const m = /已创建任务\s+([0-9a-fA-F-]{36})（([^）]+)）/.exec(payload);
    return m ? { id: m[1], title: m[2] } : null;
  }

  // Inline, clickable task card appended to the assistant bubble — jumps straight to
  // the task detail view; no polling, shown the moment creation succeeds.
  function appendTaskLink(handles, created) {
    const card = document.createElement("button");
    card.type = "button";
    card.className = "task-link";
    card.title = L("chat.openTask");
    const icon = document.createElement("span");
    icon.className = "task-link__icon";
    icon.textContent = "📋";
    const title = document.createElement("span");
    title.className = "task-link__title";
    title.textContent = created.title || created.id; // textContent → no XSS from task title
    card.append(icon, title);
    card.addEventListener("click", () => { setView("tasks"); openTask(created.id); });
    handles.text.parentNode.appendChild(card);
  }

  function setStreaming(on) {
    streaming = on;
    el.input.disabled = on;
    chatImages.setDisabled(on);
    el.sendBtn.classList.toggle("is-stop", on);
    el.sendBtn.textContent = on ? "■" : "↑";
    el.sendBtn.title = on ? L("msg.stop") : L("msg.send");
  }

  function relativeTime(iso) {
    const diff = Date.now() - new Date(iso).getTime();
    const m = Math.floor(diff / 60000);
    if (m < 1) return "now";
    if (m < 60) return m + "m";
    const h = Math.floor(m / 60);
    if (h < 24) return h + "h";
    return Math.floor(h / 24) + "d";
  }

  function autoGrow() {
    el.input.style.height = "auto";
    el.input.style.height = Math.min(el.input.scrollHeight, 180) + "px";
  }

  // ---------- wiring ----------
  // ---------- agent modal ----------
  const ael = {
    modal: document.getElementById("agentModal"),
    form: document.getElementById("agentForm"),
    title: document.getElementById("agentModalTitle"),
    name: document.getElementById("afName"),
    persona: document.getElementById("afPersona"),
    avatars: document.getElementById("afAvatars"),
    characters: document.getElementById("afCharacters"),
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

  async function renderCharacterPicker(current) {
    await loadCharacters();
    chosenCharacter = current;
    ael.characters.innerHTML = "";
    const card = (key, emoji, name, summary) => {
      const b = document.createElement("button");
      b.type = "button";
      b.className = "character-option";
      b.dataset.key = key || "";
      b.setAttribute("role", "radio");
      const head = document.createElement("span");
      head.className = "character-option__name";
      head.textContent = emoji + " " + name;
      const sub = document.createElement("span");
      sub.className = "character-option__summary";
      sub.textContent = summary;
      b.append(head, sub);
      b.addEventListener("click", () => selectCharacter(key));
      ael.characters.appendChild(b);
    };
    characterChoices.forEach(c => card(c.key, c.emoji, characterName(c), zh() ? c.summaryZh : c.summaryEn));
    card(null, "✏️", L("agent.characterCustom"), L("agent.characterCustomHint"));
    selectCharacter(current);
  }

  // Custom: the persona box IS the character (required). Preset: it's optional extra instructions.
  function selectCharacter(key) {
    chosenCharacter = key;
    ael.characters.querySelectorAll(".character-option").forEach(x => {
      const on = x.dataset.key === (key || "");
      x.classList.toggle("is-selected", on);
      x.setAttribute("aria-checked", String(on));
    });
    const custom = !key;
    ael.personaLabel.textContent = L(custom ? "agent.fPersonaCustom" : "agent.fPersonaExtra");
    ael.persona.placeholder = L(custom ? "agent.fPersonaPh" : "agent.fPersonaExtraPh");
    ael.persona.required = custom;
  }

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
    const text = el.input.value.trim();
    const hasImages = chatImages.count() > 0;
    if (!text && !hasImages) return;
    let images;
    try { images = await chatImages.payload(); } catch (err) { showToast(L("img.readFailed"), true); return; }
    const urls = chatImages.previewUrls();
    if (superMode) { submitToSuper(text, images); return; }
    el.input.value = "";
    autoGrow();
    chatImages.clear();
    sendMessage(text, images, urls);
  });

  el.input.addEventListener("input", autoGrow);


  // ---------- SuperAgent inbox mode ----------
  // The SuperAgent doesn't answer live: messages are dropped into its inbox and worked through in
  // rounds once input goes quiet. The view polls its state and conversation while it is open.
  let superMode = false;
  let superTimer = null;
  let superFingerprint = null;
  const composerPlaceholder = el.input.getAttribute("placeholder");

  function setSuperMode(on) {
    superMode = on;
    clearInterval(superTimer);
    // Only while the conversation is actually on screen (tab visible, chat view shown).
    superTimer = on ? setInterval(() => {
      if (!document.hidden && el.messages.offsetParent !== null) refreshSuper();
    }, 3000) : null;
    el.input.setAttribute("placeholder", on ? L("super.placeholder") : composerPlaceholder);
    el.composer.classList.toggle("composer--inbox", on);
    document.getElementById("superWorkPanel").hidden = !on;
    if (on) loadSuperRecent();
    el.messages.classList.toggle("messages--super", on);
  }

  async function submitToSuper(text, images) {
    el.sendBtn.disabled = true;
    const res = await api.superSubmit({ content: text, images }).catch(() => null);
    el.sendBtn.disabled = false;
    if (!res || !res.data) { showToast((res && res.message) || L("super.sendFailed"), true); return; }
    el.input.value = ""; // only now, so a failed send keeps the text
    autoGrow();
    chatImages.clear();
    superFingerprint = null;
    await refreshSuper();
  }

  async function refreshSuper() {
    if (!superMode || !activeSessionId) return;
    const agentId = activeAgentId;
    const [stateRes, historyRes] = await Promise.all([
      api.superState().catch(() => null), api.agentHistory(agentId).catch(() => null)]);
    if (!superMode || activeAgentId !== agentId) return; // switched away meanwhile
    const state = stateRes && stateRes.data;
    const items = flattenHistory((historyRes && historyRes.data) || []);
    const messages = items.filter(m => !m.divider);
    if (!state) return;
    if (state.sessionId) activeSessionId = state.sessionId;
    // Re-render only on change, so polling doesn't reset scroll or flicker.
    const last = messages[messages.length - 1];
    const fp = [messages.length, last && last.id, state.processedUntil, state.running, state.roundCutoff,
      state.plan && state.plan.id, state.plan && state.plan.status].join("|");
    if (fp === superFingerprint) return;
    superFingerprint = fp;
    loadSuperRecent(); // a finished task always lands a report in the inbox, which changes the fingerprint
    const nearBottom = el.messages.scrollHeight - el.messages.scrollTop - el.messages.clientHeight < 80;
    el.messages.innerHTML = "";
    if (!messages.length) {
      el.messages.innerHTML = '<div class="empty-state"><p>' + escapeHtml(L("super.welcome")) + "</p></div>";
    }
    const processed = state.processedUntil ? new Date(state.processedUntil) : null;
    const cutoff = state.roundCutoff ? new Date(state.roundCutoff) : null;
    // The plan sits in the conversation where it was made: right under the reply of the round that
    // proposed it. That reply is written when the round ends, so until then the card waits at the end.
    const planAt = state.plan && state.plan.proposedAt ? new Date(state.plan.proposedAt) : null;
    let planShown = !state.plan;
    items.forEach(m => {
      const msgEl = renderHistoryItem(m);
      if (!planShown && planAt && !m.divider && m.role === "assistant" && new Date(m.createdAt) >= planAt) {
        el.messages.appendChild(planCard(state.plan));
        planShown = true;
      }
      if (m.divider || m.role !== "user") return;
      const at = new Date(m.createdAt);
      if (processed && at <= processed) return; // handled in an earlier round
      const chip = document.createElement("span");
      const thinking = state.running && cutoff && at <= cutoff;
      chip.className = "msg__status" + (thinking ? " is-thinking" : "");
      chip.textContent = L(thinking ? "super.thinking" : "super.received");
      msgEl.appendChild(chip);
    });
    if (!planShown) el.messages.appendChild(planCard(state.plan));
    if (state.running) {
      const working = document.createElement("div");
      working.className = "super-working";
      working.textContent = L("super.working");
      el.messages.appendChild(working);
    }
    if (nearBottom || !last || last.role !== "assistant") scrollToBottom();
  }

  function planCard(plan) {
    const card = document.createElement("div");
    card.className = "plan-card" + (plan.status === "approved" ? " is-approved" : "");
    const head = document.createElement("div");
    head.className = "plan-card__head";
    const title = document.createElement("span");
    title.className = "plan-card__title";
    title.textContent = L("super.planTitle") + " #" + plan.id;
    const badge = document.createElement("span");
    badge.className = "status-badge " + (plan.status === "approved" ? "is-done" : "is-needs_confirmation");
    badge.textContent = L(plan.status === "approved" ? "super.approved" : "super.awaiting");
    head.append(title, badge);
    const summary = document.createElement("p");
    summary.className = "plan-card__summary";
    summary.textContent = plan.summary;
    card.append(head, summary);

    const section = (label, items) => {
      if (!items.length) return;
      const h = document.createElement("div");
      h.className = "plan-card__label";
      h.textContent = label;
      const ul = document.createElement("ul");
      ul.className = "plan-card__list";
      items.forEach(node => { const li = document.createElement("li"); li.append(...node); ul.appendChild(li); });
      card.append(h, ul);
    };
    const strong = t => { const b = document.createElement("b"); b.textContent = t; return b; };
    const muted = t => { const m = document.createElement("span"); m.className = "plan-card__muted"; m.textContent = t; return m; };
    section(L("super.planMembers"), (plan.members || []).map(m => [
      strong(m.name), muted(" · " + L(m.agentId ? "super.planReuse" : "super.planNew")),
      document.createTextNode(m.specialty ? " — " + m.specialty : "")]));
    section(L("super.planTasks"), (plan.tasks || []).map(t => [
      strong(t.title), muted(t.assignee ? " → " + t.assignee : ""),
      document.createTextNode(t.brief ? " — " + t.brief : "")]));

    if (plan.status === "proposed") {
      const foot = document.createElement("div");
      foot.className = "plan-card__foot";
      const hint = muted(L("super.approveHint"));
      const btn = document.createElement("button");
      btn.type = "button";
      btn.className = "btn btn--primary";
      btn.textContent = L("super.approve");
      btn.addEventListener("click", async () => {
        btn.disabled = true;
        const res = await api.superApprove(plan.id).catch(() => null);
        if (!res || !res.data) {
          btn.disabled = false;
          showToast((res && res.message) || L("super.approveFailed"), true);
          return;
        }
        superFingerprint = null;
        refreshSuper();
      });
      foot.append(hint, btn);
      card.appendChild(foot);
    }
    return card;
  }

  function escapeHtml(s) {
    return String(s).replace(/[&<>"']/g, c => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
  }

  // ---------- SuperAgent work history ----------
  // What the SuperAgent actually got done: every task it assigned, who ran it, and how it ended.
  const wel = {
    recent: document.getElementById("superWorkList"),
    recentEmpty: document.getElementById("superWorkEmpty"),
    drawer: document.getElementById("superWorkDrawer"),
    list: document.getElementById("workList"),
    empty: document.getElementById("workEmpty"),
    count: document.getElementById("workCount"),
    status: document.getElementById("workStatusFilter"),
    member: document.getElementById("workMemberFilter"),
  };

  async function loadSuperRecent() {
    const res = await api.superWork("finished", null, 10).catch(() => null);
    const items = (res && res.data) || [];
    wel.recent.replaceChildren(...items.map(recentRow));
    wel.recentEmpty.hidden = items.length > 0;
  }

  // Side-panel row: a status dot, the title, then who ran it and how long ago (exact time on hover).
  function recentRow(w) {
    const li = document.createElement("li");
    const btn = document.createElement("button");
    btn.type = "button";
    btn.className = "sw-row";
    btn.title = (TASK_STATUS_LABEL[w.status] || L("status." + w.status)) + " · " + formatDateTime(w.finishedAt);
    const dot = document.createElement("span");
    dot.className = "sw-dot is-" + w.status;
    const body = document.createElement("span");
    body.className = "sw-body";
    const title = document.createElement("span");
    title.className = "sw-title";
    title.textContent = w.title;
    const meta = document.createElement("span");
    meta.className = "sw-meta";
    const who = document.createElement("span");
    who.className = "sw-who";
    who.textContent = (w.memberAvatar ? w.memberAvatar + " " : "") + (w.memberName || "—");
    const when = document.createElement("span");
    when.className = "sw-when";
    when.textContent = w.finishedAt ? relativeTime(w.finishedAt) : "";
    meta.append(who, when);
    body.append(title, meta);
    btn.append(dot, body);
    btn.addEventListener("click", () => openTaskFromWork(w.taskId));
    li.appendChild(btn);
    return li;
  }

  function workBadge(status) {
    const b = document.createElement("span");
    b.className = "status-badge is-" + status;
    b.textContent = TASK_STATUS_LABEL[status] || L("status." + status);
    return b;
  }

  // One work item: status, title, who ran it and when; the drawer also shows the outcome summary.
  function workItem(w, withSummary, onClick) {
    const btn = document.createElement("button");
    btn.type = "button";
    btn.className = "work-item";
    const top = document.createElement("div");
    top.className = "work-item__top";
    const title = document.createElement("span");
    title.className = "work-item__title";
    title.textContent = w.title;
    title.title = w.title;
    top.append(workBadge(w.status), title);
    const meta = document.createElement("div");
    meta.className = "work-item__meta";
    const who = document.createElement("span");
    who.textContent = (w.memberAvatar ? w.memberAvatar + " " : "") + (w.memberName || "—");
    const when = document.createElement("span");
    when.textContent = w.finishedAt ? L("work.finished", { time: formatDateTime(w.finishedAt) })
      : L("work.assigned", { time: formatDateTime(w.assignedAt) });
    meta.append(who, when);
    btn.append(top, meta);
    if (withSummary && w.summary) {
      const sum = document.createElement("div");
      sum.className = "work-item__summary";
      sum.textContent = w.summary;
      btn.appendChild(sum);
    }
    btn.addEventListener("click", onClick);
    return btn;
  }

  async function openTaskFromWork(taskId) {
    wel.drawer.hidden = true;
    setView("tasks");
    await openTask(taskId);
  }

  function openWorkDrawer() {
    // Members to filter by: the user's agents other than the SuperAgent (its hires and the user's own alike).
    const current = wel.member.value;
    wel.member.replaceChildren();
    const all = document.createElement("option");
    all.value = "";
    all.textContent = L("work.fMembers");
    wel.member.appendChild(all);
    agents.filter(a => a.kind !== "super").forEach(a => {
      const o = document.createElement("option");
      o.value = a.id;
      o.textContent = (a.avatar ? a.avatar + " " : "") + a.name;
      wel.member.appendChild(o);
    });
    wel.member.value = [...wel.member.options].some(o => o.value === current) ? current : "";
    wel.drawer.hidden = false;
    loadWorkList();
  }

  async function loadWorkList() {
    const res = await api.superWork(wel.status.value, wel.member.value, 500).catch(() => null);
    const items = (res && res.data) || [];
    wel.count.textContent = L("work.count", { n: items.length });
    wel.list.replaceChildren(...items.map(workRow));
    wel.empty.hidden = items.length > 0;
  }

  // A row expands in place to the item's timeline, with a way into the task itself.
  function workRow(w) {
    const li = document.createElement("li");
    li.className = "work-row";
    const detail = document.createElement("div");
    detail.className = "work-row__detail";
    detail.hidden = true;
    li.append(workItem(w, true, async () => {
      if (!detail.hidden) { detail.hidden = true; return; }
      detail.hidden = false;
      detail.replaceChildren();
      const res = await api.superWorkEvents(w.taskId).catch(() => null);
      const ul = document.createElement("ul");
      ul.className = "work-events";
      ((res && res.data) || []).forEach(e => {
        const item = document.createElement("li");
        const t = document.createElement("time");
        t.textContent = formatDateTime(e.at);
        const k = document.createElement("b");
        k.textContent = L("work.ev." + e.kind);
        item.append(t, k, document.createTextNode(e.detail || ""));
        ul.appendChild(item);
      });
      const open = document.createElement("button");
      open.type = "button";
      open.className = "btn";
      open.textContent = L("work.openTask");
      open.addEventListener("click", () => openTaskFromWork(w.taskId));
      detail.append(ul);
      if (!w.planId) {
        const note = document.createElement("p");
        note.className = "super-work__empty";
        note.textContent = L("work.backfilled");
        detail.appendChild(note);
      }
      detail.appendChild(open);
    }), detail);
    return li;
  }

  document.getElementById("superWorkAllBtn").addEventListener("click", openWorkDrawer);
  wel.drawer.querySelectorAll("[data-close]").forEach(x => x.addEventListener("click", () => { wel.drawer.hidden = true; }));
  [wel.status, wel.member].forEach(x => x.addEventListener("change", loadWorkList));

  // ---------- views (sidebar routing) ----------
  const navItems = Array.from(document.querySelectorAll(".nav__item[data-view]"));
  const chatPanes = Array.from(document.querySelectorAll('[data-pane="chat"]'));
  const connectorsPane = document.querySelector('[data-pane="connectors"]');
  const tasksPane = document.querySelector('[data-pane="tasks"]');
  const usersPane = document.querySelector('[data-pane="users"]');
  const modelsPane = document.querySelector('[data-pane="models"]');
  const settingsPane = document.querySelector('[data-pane="settings"]');
  const placeholderPane = document.querySelector('[data-pane="placeholder"]');
  const automationPane = document.querySelector('[data-pane="automation"]');
  const placeholderText = document.getElementById("placeholderText");
  const placeholderLabels = {
    automation: "placeholder.automation",
  };

  // ---------- inbox ----------
  // Messages the user's own Feishu bot receives for them, the to-dos extracted from them, and the bot settings.
  Object.assign(api, {
    ibSource: () => fetch("/api/v1/inbox/source").then(kbJson),
    ibSave: body => fetch("/api/v1/inbox/source", {
      method: "PUT", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body),
    }).then(kbJson),
    ibBindCode: () => kbPost("/api/v1/inbox/source/bind-code", {}),
    ibEvents: () => fetch("/api/v1/inbox/events?limit=200").then(kbJson),
    ibTodos: () => fetch("/api/v1/inbox/todos").then(kbJson),
    ibTodoStatus: (id, status) => fetch("/api/v1/inbox/todos/" + id, {
      method: "PUT", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ status }),
    }).then(kbJson),
    ibTodoTask: id => kbPost("/api/v1/inbox/todos/" + id + "/task", {}),
  });

  const ibPane = document.querySelector('[data-pane="inbox"]');
  const ibel = {
    status: document.getElementById("ibStatus"),
    tabs: document.getElementById("ibTabs"),
    todos: document.getElementById("ibTodos"),
    todosEmpty: document.getElementById("ibTodosEmpty"),
    showClosed: document.getElementById("ibShowClosed"),
    events: document.getElementById("ibEvents"),
    eventsEmpty: document.getElementById("ibEventsEmpty"),
    form: document.getElementById("ibForm"),
    appId: document.getElementById("ibAppId"),
    appSecret: document.getElementById("ibAppSecret"),
    secretHint: document.getElementById("ibSecretHint"),
    bind: document.getElementById("ibBind"),
    vip: document.getElementById("ibVip"),
    keywords: document.getElementById("ibKeywords"),
    digest: document.getElementById("ibDigest"),
    timezone: document.getElementById("ibTimezone"),
    enabled: document.getElementById("ibEnabled"),
    error: document.getElementById("ibError"),
  };
  let ibTab = "todos";
  let ibTodoList = [];

  function showIbTab(tab) {
    ibTab = tab;
    ibel.tabs.querySelectorAll(".model-tab").forEach(b => b.classList.toggle("is-active", b.dataset.tab === tab));
    ibPane.querySelectorAll("[data-ib]").forEach(p => { p.hidden = p.dataset.ib !== tab; });
    if (tab === "todos") loadIbTodos();
    if (tab === "events") loadIbEvents();
    if (tab === "settings") loadIbSource();
  }
  ibel.tabs.querySelectorAll(".model-tab").forEach(b => b.addEventListener("click", () => showIbTab(b.dataset.tab)));

  async function loadInbox() {
    loadIbSource(true);
    showIbTab(ibTab);
  }

  function renderIbStatus(src) {
    const configured = src && src.configured;
    ibel.status.hidden = !configured;
    if (!configured) return;
    const state = !src.enabled ? "off" : src.status || "connecting";
    ibel.status.className = "status-badge is-ib-" + state;
    ibel.status.textContent = L("ib.status." + state);
    ibel.status.title = src.lastError || "";
  }

  async function loadIbSource(statusOnly) {
    let src;
    try { src = await api.ibSource(); } catch (e) { showToast(e.message, true); return; }
    renderIbStatus(src);
    if (statusOnly) return;
    ibel.appId.value = src.appId || "";
    ibel.appSecret.value = "";
    ibel.appSecret.required = !src.hasSecret;
    ibel.appSecret.placeholder = src.hasSecret ? "••••••••" : "";
    ibel.secretHint.hidden = !src.hasSecret;
    ibel.vip.value = src.vipNames || "";
    ibel.keywords.value = src.urgentKeywords || "";
    ibel.digest.value = src.digestTime || "09:00";
    ibel.timezone.value = src.timezone || "Asia/Shanghai";
    ibel.enabled.checked = src.configured ? src.enabled : true;
    ibel.error.hidden = !src.lastError;
    ibel.error.textContent = src.lastError || "";
    renderIbBind(src);
  }

  function renderIbBind(src) {
    ibel.bind.hidden = !src.configured;
    ibel.bind.innerHTML = "";
    if (!src.configured) return;
    const p = document.createElement("div");
    if (src.bound) {
      p.textContent = L("ib.bound");
    } else if (!src.bindCode) {
      p.textContent = L("ib.codeBurned");
    } else {
      p.append(document.createTextNode(L("ib.bindStep")));
      const code = document.createElement("code");
      code.textContent = L("ib.bindWord") + " " + (src.bindCode || "");
      p.append(code);
    }
    const again = document.createElement("button");
    again.type = "button";
    again.className = "link-btn";
    again.textContent = L(src.bound ? "ib.rebind" : "ib.newCode");
    again.addEventListener("click", async () => {
      try { renderIbBind(await api.ibBindCode()); } catch (e) { showToast(e.message, true); }
    });
    p.append(document.createTextNode(" "), again);
    ibel.bind.appendChild(p);
  }

  ibel.form.addEventListener("submit", async e => {
    e.preventDefault();
    ibel.error.hidden = true;
    try {
      const src = await api.ibSave({
        appId: ibel.appId.value.trim(), appSecret: ibel.appSecret.value, enabled: ibel.enabled.checked,
        vipNames: ibel.vip.value, urgentKeywords: ibel.keywords.value,
        digestTime: ibel.digest.value, timezone: ibel.timezone.value.trim(),
      });
      showToast(L("ib.saved"), false);
      renderIbStatus(src);
      renderIbBind(src);
      ibel.appSecret.value = "";
      ibel.secretHint.hidden = false;
      setTimeout(() => { if (!ibPane.hidden && ibTab === "settings") loadIbSource(); }, 4000); // connection status settles
    } catch (err) {
      ibel.error.textContent = err.message;
      ibel.error.hidden = false;
    }
  });

  async function loadIbTodos() {
    try { ibTodoList = (await api.ibTodos()) || []; } catch (e) { ibTodoList = []; showToast(e.message, true); }
    renderIbTodos();
  }
  ibel.showClosed.addEventListener("change", renderIbTodos);

  function renderIbTodos() {
    const closed = t => t.status === "done" || t.status === "dismissed";
    const shown = ibTodoList.filter(t => ibel.showClosed.checked || !closed(t));
    ibel.todos.innerHTML = "";
    ibel.todosEmpty.hidden = shown.length > 0;
    shown.forEach(t => {
      const tr = document.createElement("tr");
      tr.className = "ib-todo" + (closed(t) ? " is-closed" : "");
      const main = document.createElement("td");
      const title = document.createElement("div");
      title.className = "ib-todo__title";
      title.textContent = t.title;
      const meta = document.createElement("div");
      meta.className = "ib-todo__meta";
      meta.textContent = [t.requester && L("ib.from", { who: t.requester }), t.due && L("ib.due", { due: t.due }),
        t.source, formatDateTime(t.createdAt), t.status !== "open" && L("ib.todo." + t.status)].filter(Boolean).join(" · ");
      main.append(title, meta);
      if (t.detail) {
        const d = document.createElement("div");
        d.className = "ib-todo__detail";
        d.textContent = t.detail;
        main.appendChild(d);
      }
      const actions = document.createElement("td");
      actions.className = "ib-actions";
      const btn = (label, fn, danger) => {
        const b = document.createElement("button");
        b.type = "button";
        b.className = "link-btn" + (danger ? " link-btn--muted" : "");
        b.textContent = label;
        b.addEventListener("click", async () => {
          b.disabled = true;
          try { await fn(); await loadIbTodos(); } catch (e) { showToast(e.message, true); b.disabled = false; }
        });
        actions.appendChild(b);
      };
      if (t.status === "tasked" && t.taskId) {
        btn(L("ib.openTask"), async () => { setView("tasks"); openTask(t.taskId); });
      } else if (t.status === "open") {
        btn(L("ib.toTask"), async () => { const r = await api.ibTodoTask(t.id); showToast(L("ib.tasked"), false); return r; });
        btn(L("ib.done"), () => api.ibTodoStatus(t.id, "done"));
        btn(L("ib.dismiss"), () => api.ibTodoStatus(t.id, "dismissed"), true);
      } else {
        btn(L("ib.reopen"), () => api.ibTodoStatus(t.id, "open"));
      }
      tr.append(main, actions);
      ibel.todos.appendChild(tr);
    });
  }

  async function loadIbEvents() {
    let list = [];
    try { list = (await api.ibEvents()) || []; } catch (e) { showToast(e.message, true); }
    ibel.events.innerHTML = "";
    ibel.eventsEmpty.hidden = list.length > 0;
    list.forEach(ev => {
      const tr = document.createElement("tr");
      const prio = document.createElement("td");
      const badge = document.createElement("span");
      badge.className = "ib-prio ib-prio--" + ev.priority;
      badge.textContent = ev.priority === "IGNORE" ? L("ib.ignored") : ev.priority;
      prio.appendChild(badge);
      if (!ev.processed) {
        const pend = document.createElement("span");
        pend.className = "ib-reason";
        pend.textContent = L("ib.pending");
        prio.appendChild(pend);
      }
      const from = document.createElement("td");
      from.textContent = (ev.senderName || "—") + " · " + (ev.chatName || "—");
      const text = document.createElement("td");
      text.className = "ib-text";
      text.textContent = ev.text || "";
      if (ev.triageReason) {
        const r = document.createElement("span");
        r.className = "ib-reason";
        r.textContent = ev.triageReason;
        text.appendChild(r);
      }
      const when = document.createElement("td");
      when.className = "kb-list__meta";
      when.textContent = formatDateTime(ev.receivedAt);
      tr.append(prio, from, text, when);
      ibel.events.appendChild(tr);
    });
  }

  // ---------- skills ----------
  // Shared working standards. Agents get the skills attached to them; their tasks inherit those.
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
    list: document.getElementById("skList"),
    search: document.getElementById("skSearch"),
    newBtn: document.getElementById("skNewBtn"),
    heading: document.getElementById("skHeading"),
    actions: document.getElementById("skActions"),
    edit: document.getElementById("skEditBtn"),
    del: document.getElementById("skDeleteBtn"),
    view: document.getElementById("skView"),
    form: document.getElementById("skForm"),
    name: document.getElementById("sfName"),
    category: document.getElementById("sfCategory"),
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
  let skSelectedId = null;
  let skEditing = null;        // null = viewing; "new" or a skill id = editing

  async function loadSkillList() {
    try { skills = (await api.listSkills()) || []; } catch (e) { skills = []; showToast(e.message, true); }
    return skills;
  }

  async function loadSkills() {
    await loadSkillList();
    if (skSelectedId && !skills.some(s => s.id === skSelectedId)) skSelectedId = null;
    if (!skSelectedId && skills.length) skSelectedId = skills[0].id;
    skEditing = null;
    renderSkillList();
    renderSkill();
  }

  function renderSkillList() {
    const q = skel.search.value.trim().toLowerCase();
    const shown = skills.filter(s => !q || [s.name, s.description, s.category].some(v => (v || "").toLowerCase().includes(q)));
    skel.list.innerHTML = "";
    if (!shown.length) {
      skel.list.innerHTML = '<p class="kb-empty">' + L(skills.length ? "sk.noMatch" : "sk.empty") + "</p>";
      return;
    }
    let group = undefined;
    shown.forEach(s => {
      const cat = s.category || L("sk.uncategorized");
      if (cat !== group) {
        group = cat;
        const h = document.createElement("div");
        h.className = "sk-group";
        h.textContent = cat;
        skel.list.appendChild(h);
      }
      const b = document.createElement("button");
      b.type = "button";
      b.className = "sk-item" + (s.id === skSelectedId ? " is-selected" : "") + (s.enabled ? "" : " is-off");
      const name = document.createElement("span");
      name.className = "sk-item__name";
      name.textContent = s.name;
      const desc = document.createElement("span");
      desc.className = "sk-item__desc";
      desc.textContent = s.description || "";
      b.append(name, desc);
      b.addEventListener("click", () => { skSelectedId = s.id; skEditing = null; renderSkillList(); renderSkill(); });
      skel.list.appendChild(b);
    });
  }
  skel.search.addEventListener("input", renderSkillList);

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

  function renderSkill() {
    const skill = skills.find(s => s.id === skSelectedId);
    skel.form.hidden = !skEditing;
    skel.view.hidden = !!skEditing;
    skel.actions.hidden = !!skEditing || !skill;
    if (skEditing) return;
    skel.view.innerHTML = "";
    if (!skill) {
      skel.heading.textContent = L("sk.title");
      skel.view.innerHTML = '<p class="kb-empty">' + L("sk.pick") + "</p>";
      return;
    }
    skel.heading.textContent = skill.name;
    const meta = document.createElement("p");
    meta.className = "sk-meta";
    meta.textContent = [skill.category || L("sk.uncategorized"), skill.enabled ? null : L("sk.disabled"),
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
    renderSkill();
    skel.name.focus();
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
      remove.title = L("kb.delete");
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
  skel.cancel.addEventListener("click", () => { skEditing = null; renderSkill(); });
  skel.form.addEventListener("submit", async e => {
    e.preventDefault();
    const body = {
      name: skel.name.value.trim(), category: skel.category.value.trim(),
      description: skel.description.value.trim(), content: skel.content.value, enabled: skel.enabled.checked,
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
      title: L("sk.deleteTitle"), submit: L("kb.delete"), danger: true,
      message: L("sk.deleteConfirm", { name: skill.name }),
      action: async () => { await api.deleteSkill(skill.id); skSelectedId = null; await loadSkills(); },
    });
  });

  // Agent form: pick skills (all enabled ones; a disabled one already attached stays visible).
  let chosenSkills = new Set();
  async function renderSkillPicker(current) {
    await loadSkillList();
    chosenSkills = new Set(current || []);
    const box = document.getElementById("afSkills");
    box.innerHTML = "";
    const offered = skills.filter(s => s.enabled || chosenSkills.has(s.id));
    if (!offered.length) {
      box.innerHTML = '<span class="skill-chips__empty">' + L("sk.noneYet") + "</span>";
      return;
    }
    offered.forEach(s => {
      const label = document.createElement("label");
      label.className = chosenSkills.has(s.id) ? "is-on" : "";
      label.title = s.description || "";
      const cb = document.createElement("input");
      cb.type = "checkbox";
      cb.checked = chosenSkills.has(s.id);
      cb.addEventListener("change", () => {
        if (cb.checked) chosenSkills.add(s.id); else chosenSkills.delete(s.id);
        label.className = cb.checked ? "is-on" : "";
      });
      label.append(cb, document.createTextNode(s.name));
      box.appendChild(label);
    });
  }

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

  // ---------- knowledge ----------
  // The signed-in user's private file-system knowledge base. Paths are "/"-separated, "" is the root.
  // Declared before setView: the initial view (from the URL hash) may be #knowledge.
  Object.assign(api, {
    kbList: path => fetch("/api/v1/knowledge/entries?path=" + encodeURIComponent(path)).then(kbJson),
    kbFolders: () => fetch("/api/v1/knowledge/folders").then(kbJson),
    kbCreateFolder: path => kbPost("/api/v1/knowledge/folders", { path }),
    kbRename: (path, newName) => kbPost("/api/v1/knowledge/rename", { path, newName }),
    kbMove: (path, targetDir) => kbPost("/api/v1/knowledge/move", { path, targetDir }),
    kbDelete: path => fetch("/api/v1/knowledge/entries?path=" + encodeURIComponent(path), { method: "DELETE" }).then(kbJson),
  });
  // Server errors come back as {code, message}; surface the message.
  async function kbJson(r) {
    const body = await r.json().catch(() => null);
    if (!r.ok) throw new Error((body && body.message) || ("HTTP " + r.status));
    return body ? body.data : null;
  }
  function kbPost(url, body) {
    return fetch(url, { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body) }).then(kbJson);
  }
  const kbFileUrl = (path, download) =>
    "/api/v1/knowledge/file?path=" + encodeURIComponent(path) + (download ? "&download=true" : "");

  const kbPane = document.querySelector('[data-pane="knowledge"]');
  const kel = {
    tree: document.getElementById("kbTree"),
    crumbs: document.getElementById("kbCrumbs"),
    view: document.getElementById("kbView"),
    up: document.getElementById("kbUpBtn"),
    newFolder: document.getElementById("kbNewFolderBtn"),
    rename: document.getElementById("kbRenameBtn"),
    move: document.getElementById("kbMoveBtn"),
    del: document.getElementById("kbDeleteBtn"),
    download: document.getElementById("kbDownloadBtn"),
    modal: document.getElementById("kbModal"),
    form: document.getElementById("kbForm"),
    modalTitle: document.getElementById("kbModalTitle"),
    modalMsg: document.getElementById("kbModalMsg"),
    nameField: document.getElementById("kbNameField"),
    nameInput: document.getElementById("kbNameInput"),
    targetField: document.getElementById("kbTargetField"),
    targetSelect: document.getElementById("kbTargetSelect"),
    modalError: document.getElementById("kbModalError"),
    modalSubmit: document.getElementById("kbModalSubmit"),
  };
  const kbChildren = new Map();      // folder path -> entries, filled lazily as folders are opened
  const kbExpanded = new Set([""]);  // folders open in the tree
  let kbSelected = { path: "", dir: true, name: "" };
  let kbModalAction = null;

  const kbParent = path => path.includes("/") ? path.slice(0, path.lastIndexOf("/")) : "";
  const kbBaseName = path => path.slice(path.lastIndexOf("/") + 1);
  const kbCurrentFolder = () => kbSelected.dir ? kbSelected.path : kbParent(kbSelected.path);
  const KB_IMAGE = /\.(png|jpe?g|gif|webp|svg)$/i;
  const KB_MARKDOWN = /\.(md|markdown)$/i;
  const KB_TEXT = /\.(txt|log|csv|json|ya?ml|xml|html)$/i;
  const kbIcon = e => e.dir ? "📁" : KB_IMAGE.test(e.name) ? "🖼️" : KB_MARKDOWN.test(e.name) ? "📝" : "📄";

  async function kbLoad(path) {
    const entries = await api.kbList(path);
    kbChildren.set(path, entries || []);
    return kbChildren.get(path);
  }

  async function loadKnowledge() {
    kbChildren.clear();
    // Re-read every open folder; ones deleted since (e.g. on disk) just close.
    for (const path of Array.from(kbExpanded)) {
      try { await kbLoad(path); } catch (e) { kbExpanded.delete(path); }
    }
    if (!kbExpanded.has("")) { kbExpanded.add(""); await kbLoad(""); }
    renderKbTree();
    await kbSelect(kbSelected.path, kbSelected.dir, true);
  }

  function renderKbTree() {
    kel.tree.innerHTML = "";
    const walk = (folder, depth) => {
      (kbChildren.get(folder) || []).forEach(e => {
        const row = document.createElement("div");
        row.className = "kb-node" + (e.path === kbSelected.path ? " is-selected" : "");
        row.style.paddingLeft = (6 + depth * 14) + "px";
        row.setAttribute("role", "treeitem");
        const open = e.dir && kbExpanded.has(e.path);
        if (e.dir) row.setAttribute("aria-expanded", String(open));
        const twisty = document.createElement("span");
        twisty.className = "kb-node__twisty";
        twisty.textContent = e.dir ? (open ? "▾" : "▸") : "";
        const name = document.createElement("span");
        name.className = "kb-node__name";
        name.textContent = kbIcon(e) + " " + e.name;
        row.append(twisty, name);
        twisty.addEventListener("click", ev => { ev.stopPropagation(); if (e.dir) kbToggle(e.path); });
        row.addEventListener("click", () => kbSelect(e.path, e.dir));
        kel.tree.appendChild(row);
        if (open) walk(e.path, depth + 1);
      });
    };
    walk("", 0);
    if (!(kbChildren.get("") || []).length) {
      kel.tree.innerHTML = '<p class="kb-empty kb-empty--tree">' + L("kb.treeEmpty") + "</p>";
    }
  }

  async function kbToggle(path) {
    if (kbExpanded.has(path)) {
      kbExpanded.delete(path);
    } else {
      kbExpanded.add(path);
      if (!kbChildren.has(path)) {
        try { await kbLoad(path); } catch (e) { kbExpanded.delete(path); showToast(e.message, true); }
      }
    }
    renderKbTree();
  }

  // Selecting a folder opens it in the tree and lists it; selecting a file previews it.
  async function kbSelect(path, dir, quiet) {
    kbSelected = { path, dir, name: kbBaseName(path) };
    if (dir) {
      // Open every ancestor too, so the selection is visible in the tree.
      let p = path;
      while (p) { kbExpanded.add(p); p = kbParent(p); }
      for (const f of Array.from(kbExpanded)) {
        if (!kbChildren.has(f)) { try { await kbLoad(f); } catch (e) { kbExpanded.delete(f); } }
      }
    }
    renderKbTree();
    renderKbBar();
    try {
      if (dir) renderKbFolder(kbChildren.has(path) ? kbChildren.get(path) : await kbLoad(path));
      else await renderKbFile(path);
    } catch (e) {
      if (!quiet) showToast(e.message, true);
      if (path) { kbSelect("", true, true); return; } // gone: fall back to the root
      kel.view.innerHTML = "";
    }
  }

  function renderKbBar() {
    kel.crumbs.innerHTML = "";
    const parts = kbSelected.path ? kbSelected.path.split("/") : [];
    const crumb = (label, path, isDir, current) => {
      const b = document.createElement("button");
      b.type = "button";
      b.textContent = label;
      if (current) b.className = "is-current";
      b.addEventListener("click", () => kbSelect(path, isDir));
      kel.crumbs.appendChild(b);
    };
    crumb(L("kb.root"), "", true, !parts.length);
    parts.forEach((part, i) => {
      const sep = document.createElement("span");
      sep.className = "kb-crumbs__sep";
      sep.textContent = "/";
      kel.crumbs.appendChild(sep);
      const last = i === parts.length - 1;
      crumb(part, parts.slice(0, i + 1).join("/"), last ? kbSelected.dir : true, last);
    });
    const atRoot = !kbSelected.path;
    kel.up.disabled = atRoot;
    [kel.rename, kel.move, kel.del].forEach(b => { b.disabled = atRoot; });
    kel.download.hidden = kbSelected.dir;
    if (!kbSelected.dir) kel.download.href = kbFileUrl(kbSelected.path, true);
  }

  function renderKbFolder(entries) {
    kel.view.innerHTML = "";
    if (!entries.length) {
      kel.view.innerHTML = '<p class="kb-empty">' + L("kb.folderEmpty") + "</p>";
      return;
    }
    const table = document.createElement("table");
    table.className = "kb-list";
    table.innerHTML = "<thead><tr><th>" + L("kb.name") + "</th><th>" + L("kb.size") + "</th><th>"
      + L("kb.modified") + "</th></tr></thead>";
    const tbody = document.createElement("tbody");
    entries.forEach(e => {
      const tr = document.createElement("tr");
      tr.className = "kb-row";
      const name = document.createElement("td");
      name.textContent = kbIcon(e) + " " + e.name;
      const size = document.createElement("td");
      size.className = "kb-list__meta";
      size.textContent = e.dir ? "—" : kbFormatSize(e.size);
      const when = document.createElement("td");
      when.className = "kb-list__meta";
      when.textContent = formatDateTime(e.modifiedAt);
      tr.append(name, size, when);
      tr.addEventListener("click", () => kbSelect(e.path, e.dir));
      tbody.appendChild(tr);
    });
    table.appendChild(tbody);
    kel.view.appendChild(table);
  }

  async function renderKbFile(path) {
    const entry = (kbChildren.get(kbParent(path)) || []).find(e => e.path === path);
    kel.view.innerHTML = "";
    const meta = document.createElement("p");
    meta.className = "kb-preview__meta";
    meta.textContent = entry ? kbFormatSize(entry.size) + " · " + formatDateTime(entry.modifiedAt) : "";
    kel.view.appendChild(meta);
    const name = kbBaseName(path);
    if (KB_IMAGE.test(name)) {
      const img = document.createElement("img");
      img.className = "kb-preview__img";
      img.alt = name;
      img.src = kbFileUrl(path);
      kel.view.appendChild(img);
      return;
    }
    if (!KB_MARKDOWN.test(name) && !KB_TEXT.test(name)) {
      const p = document.createElement("p");
      p.className = "kb-empty";
      p.textContent = L("kb.noPreview");
      kel.view.appendChild(p);
      return;
    }
    const res = await fetch(kbFileUrl(path));
    if (!res.ok) throw new Error(((await res.json().catch(() => null)) || {}).message || "HTTP " + res.status);
    const text = await res.text();
    if (kbSelected.path !== path) return; // selection moved on while loading
    if (KB_MARKDOWN.test(name)) {
      const div = document.createElement("div");
      div.className = "markdown";
      div.innerHTML = renderMarkdown(text);
      // Relative image links in a note resolve against the note's own folder in the knowledge base.
      div.querySelectorAll("img").forEach(img => {
        const src = img.getAttribute("src") || "";
        if (!src || /^([a-z]+:|\/|#)/i.test(src)) return;
        const parts = kbParent(path) ? kbParent(path).split("/") : [];
        src.split("/").forEach(seg => { if (seg === "..") parts.pop(); else if (seg && seg !== ".") parts.push(seg); });
        img.src = kbFileUrl(parts.join("/"));
      });
      kel.view.appendChild(div);
    } else {
      const pre = document.createElement("pre");
      pre.className = "kb-preview__text";
      pre.textContent = text;
      kel.view.appendChild(pre);
    }
  }

  function kbFormatSize(n) {
    if (n < 1024) return n + " B";
    if (n < 1024 * 1024) return (n / 1024).toFixed(1) + " KB";
    return (n / 1024 / 1024).toFixed(1) + " MB";
  }

  // One small dialog for new folder / rename / move / delete; action() does the request.
  function kbOpenModal({ title, submit, danger, message, name, nameLabel, targets, action }) {
    kbModalAction = action;
    kel.modalTitle.textContent = title;
    kel.modalSubmit.textContent = submit;
    kel.modalSubmit.classList.toggle("btn--danger", !!danger);
    kel.modalMsg.hidden = !message;
    kel.modalMsg.textContent = message || "";
    kel.nameField.hidden = name === undefined;
    kel.nameField.querySelector("span").textContent = nameLabel || L("kb.name");
    kel.nameInput.value = name || "";
    kel.targetField.hidden = !targets;
    kel.targetSelect.innerHTML = "";
    (targets || []).forEach(t => {
      const o = document.createElement("option");
      o.value = t;
      o.textContent = t ? "/ " + t.split("/").join(" / ") : L("kb.root");
      kel.targetSelect.appendChild(o);
    });
    kel.modalError.hidden = true;
    kel.modal.hidden = false;
    if (name !== undefined) { kel.nameInput.focus(); kel.nameInput.select(); }
  }
  const kbCloseModal = () => { kel.modal.hidden = true; kbModalAction = null; };
  kel.modal.querySelectorAll("[data-close]").forEach(x => x.addEventListener("click", kbCloseModal));
  kel.form.addEventListener("submit", async e => {
    e.preventDefault();
    if (!kbModalAction) return;
    kel.modalSubmit.disabled = true;
    try {
      await kbModalAction();
      kbCloseModal();
    } catch (err) {
      kel.modalError.textContent = err.message;
      kel.modalError.hidden = false;
    } finally {
      kel.modalSubmit.disabled = false;
    }
  });

  kel.newFolder.addEventListener("click", () => {
    const parent = kbCurrentFolder();
    kbOpenModal({
      title: parent ? L("kb.newFolderIn", { folder: parent }) : L("kb.newFolderTitle"),
      submit: L("kb.create"), name: "",
      action: async () => {
        const name = kel.nameInput.value.trim();
        if (!name) throw new Error(L("kb.nameRequired"));
        const created = await api.kbCreateFolder(parent ? parent + "/" + name : name);
        kbExpanded.add(parent);
        kbSelected = { path: created.path, dir: true, name: created.name };
        await loadKnowledge();
      },
    });
  });
  kel.rename.addEventListener("click", () => {
    const { path, dir, name } = kbSelected;
    if (!path) return;
    kbOpenModal({
      title: L("kb.renameTitle"), submit: L("kb.rename"), name,
      action: async () => {
        const updated = await api.kbRename(path, kel.nameInput.value.trim());
        if (dir) kbRemapExpanded(path, updated.path);
        kbSelected = { path: updated.path, dir, name: updated.name };
        await loadKnowledge();
      },
    });
  });
  kel.move.addEventListener("click", async () => {
    const { path, dir, name } = kbSelected;
    if (!path) return;
    let folders = [];
    try { folders = await api.kbFolders(); } catch (e) { showToast(e.message, true); return; }
    // A folder can't go into itself, and moving to where it already is is a no-op.
    const targets = folders.filter(f => f !== kbParent(path) && (!dir || (f !== path && !f.startsWith(path + "/"))));
    if (!targets.length) { showToast(L("kb.noTargets"), true); return; }
    kbOpenModal({
      title: L("kb.moveTitle", { name }), submit: L("kb.move"), targets,
      action: async () => {
        const target = kel.targetSelect.value;
        const moved = await api.kbMove(path, target);
        if (dir) kbRemapExpanded(path, moved.path);
        kbExpanded.add(target);
        kbSelected = { path: moved.path, dir, name: moved.name };
        await loadKnowledge();
      },
    });
  });
  kel.del.addEventListener("click", () => {
    const { path, dir, name } = kbSelected;
    if (!path) return;
    kbOpenModal({
      title: L("kb.deleteTitle"), submit: L("kb.delete"), danger: true,
      message: L(dir ? "kb.deleteFolderConfirm" : "kb.deleteFileConfirm", { name }),
      action: async () => {
        await api.kbDelete(path);
        kbRemapExpanded(path, null);
        kbSelected = { path: kbParent(path), dir: true, name: kbBaseName(kbParent(path)) };
        await loadKnowledge();
      },
    });
  });
  kel.up.addEventListener("click", () => {
    if (kbSelected.path) kbSelect(kbParent(kbSelected.path), true);
  });

  // Task artifact → the signed-in user's knowledge base: pick a folder, optionally a new subfolder in it.
  async function saveArtifactToKb(taskId, name) {
    let folders = [];
    try { folders = await api.kbFolders(); } catch (e) { showToast(e.message, true); return; }
    kbOpenModal({
      title: L("tasks.saveToKbTitle", { name }), submit: L("tasks.saveToKbSubmit"),
      targets: folders, name: "", nameLabel: L("tasks.kbNewSubfolder"),
      action: async () => {
        const sub = kel.nameInput.value.trim();
        const folder = [kel.targetSelect.value, sub].filter(Boolean).join("/");
        const saved = await kbPost("/api/v1/tasks/" + encodeURIComponent(taskId) + "/artifacts/"
          + encodeURIComponent(name) + "/knowledge", { folder });
        kbSelected = { path: saved.path, dir: false, name: saved.name };
        kbExpanded.add(kbParent(saved.path));
        showToast(L("tasks.savedToKb", { path: saved.path }), false);
      },
    });
  }

  // Keep open folders open across a rename/move (or drop them on delete).
  function kbRemapExpanded(from, to) {
    Array.from(kbExpanded).forEach(p => {
      if (p === from || p.startsWith(from + "/")) {
        kbExpanded.delete(p);
        if (to !== null) kbExpanded.add(to + p.slice(from.length));
      }
    });
  }

  // Declared before setView: setView can run during load (OAuth return) and calls stopModelPolling.
  let modelPollTimer = null;
  let modelDetailId = null;

  // View state lives in the URL hash (#tasks, #connectors, …) so a refresh or
  // back/forward restores the same view. Admin-only views fall back to the default
  // for non-admins. See initialView().
  const KNOWN_VIEWS = new Set(["chat", "tasks", "inbox", "knowledge", "skills", "automation", "connectors", "models", "users", "settings"]);
  const ADMIN_VIEWS = new Set(["models", "users", "settings"]);
  const DEFAULT_VIEW = "chat";
  let isAdmin = false;

  function initialView() {
    const view = (window.location.hash || "").replace(/^#/, "");
    if (!KNOWN_VIEWS.has(view)) return DEFAULT_VIEW;
    if (ADMIN_VIEWS.has(view) && !isAdmin) return DEFAULT_VIEW;
    return view;
  }

  function setView(view) {
    if (window.location.hash !== "#" + view) {
      history.replaceState(history.state, document.title, window.location.pathname + window.location.search + "#" + view);
    }
    navItems.forEach(a => a.classList.toggle("is-active", a.dataset.view === view));
    const isChat = view === "chat";
    const isConnectors = view === "connectors";
    const isTasks = view === "tasks";
    const isUsers = view === "users";
    const isModels = view === "models";
    const isSettings = view === "settings";
    const isKnowledge = view === "knowledge";
    const isAutomation = view === "automation";
    const isSkills = view === "skills";
    const isInbox = view === "inbox";
    chatPanes.forEach(p => { p.hidden = !isChat; });
    automationPane.hidden = !isAutomation;
    connectorsPane.hidden = !isConnectors;
    tasksPane.hidden = !isTasks;
    usersPane.hidden = !isUsers;
    modelsPane.hidden = !isModels;
    settingsPane.hidden = !isSettings;
    kbPane.hidden = !isKnowledge;
    skPane.hidden = !isSkills;
    ibPane.hidden = !isInbox;
    placeholderPane.hidden = isChat || isConnectors || isTasks || isUsers || isModels || isSettings || isKnowledge
      || isAutomation || isSkills || isInbox;
    if (!placeholderPane.hidden) placeholderText.textContent = L(placeholderLabels[view] || "placeholder.default");
    if (isConnectors) loadConnectors();
    if (isUsers) loadUsers();
    if (isSettings) loadSettingsForm();
    if (isKnowledge) loadKnowledge();
    if (isSkills) loadSkills();
    if (isInbox) loadInbox();
    if (isAutomation) { showAutoList(); loadAutomations(); }
    if (isTasks) { showTaskList(); loadTasks(); } else { stopTaskPolling(); }
    if (isModels) { showModelList(); startModelPolling(); } else { stopModelPolling(); }
  }

  navItems.forEach(a => a.addEventListener("click", e => {
    e.preventDefault();
    setView(a.dataset.view);
    closeUserMenu();
  }));

  // The account menu opens on hover; a click (touch screens) toggles it, and choosing an item or
  // clicking elsewhere closes it. Blurring the focused item matters: :focus-within would keep it open.
  const userMenu = document.getElementById("userMenu");
  const userMenuBtn = document.getElementById("userMenuBtn");
  function closeUserMenu() {
    userMenu.classList.remove("is-open");
    userMenuBtn.setAttribute("aria-expanded", "false");
    if (userMenu.contains(document.activeElement)) document.activeElement.blur();
  }
  userMenuBtn.addEventListener("click", e => {
    e.stopPropagation();
    const open = !userMenu.classList.contains("is-open");
    userMenu.classList.toggle("is-open", open);
    userMenuBtn.setAttribute("aria-expanded", String(open));
  });
  document.addEventListener("click", e => { if (!userMenu.contains(e.target)) closeUserMenu(); });

  // The logo goes home: the Agent page with the user's SuperAgent open.
  document.getElementById("brandHome").addEventListener("click", e => {
    e.preventDefault();
    setView("chat");
    const home = agents.find(a => a.kind === "super") || agents[0];
    if (home && home.id !== activeAgentId) selectAgent(home);
  });
  document.addEventListener("keydown", e => { if (e.key === "Escape") closeUserMenu(); });

  // Back/forward (and manual hash edits) re-drive the view from the URL.
  window.addEventListener("hashchange", () => setView(initialView()));

  // ---------- connectors ----------
  Object.assign(api, {
    listConnectors: () => fetch("/api/v1/connectors").then(r => r.json()),
    createConnector: body => fetch("/api/v1/connectors", {
      method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body),
    }).then(r => r.json()),
    updateConnector: (id, body) => fetch("/api/v1/connectors/" + id, {
      method: "PUT", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body),
    }).then(r => r.json()),
    deleteConnector: id => fetch("/api/v1/connectors/" + id, { method: "DELETE" }),
    connectConnector: id => fetch("/api/v1/connectors/" + id + "/connect", { method: "POST" }),
    disconnectConnector: id => fetch("/api/v1/connectors/" + id + "/disconnect", { method: "POST" }),
  });

  const TYPE_LABEL = { required: L("conn.typeRequired"), optional: L("conn.typeOptional") };
  const METHOD_LABEL = { system_oauth: L("conn.methodSystem"), personal_token: L("conn.methodToken") };
  const STATUS_LABEL = { connected: L("conn.connected"), not_connected: L("conn.notConnected") };

  const cel = {
    rows: document.getElementById("connectorRows"),
    empty: document.getElementById("connectorsEmpty"),
    modal: document.getElementById("connectorModal"),
    form: document.getElementById("connectorForm"),
    title: document.getElementById("connectorModalTitle"),
    name: document.getElementById("cfName"),
    endpoint: document.getElementById("cfEndpoint"),
    description: document.getElementById("cfDescription"),
    transport: document.getElementById("cfTransport"),
    type: document.getElementById("cfType"),
    authMethod: document.getElementById("cfAuthMethod"),
    submit: document.getElementById("cfSubmit"),
  };
  let editingId = null;

  async function loadConnectors() {
    let list = [];
    try { const res = await api.listConnectors(); list = (res && res.data) || []; } catch (e) { /* ignore */ }
    cel.rows.innerHTML = "";
    cel.empty.hidden = list.length > 0;
    list.forEach(c => cel.rows.appendChild(connectorRow(c)));
  }

  function connectorRow(c) {
    const tr = document.createElement("tr");
    tr.dataset.id = c.id;

    const name = document.createElement("td");
    name.className = "c-name";
    name.appendChild(document.createTextNode(c.name));
    if (c.description) { const d = document.createElement("small"); d.textContent = c.description; name.appendChild(d); }
    if (c.endpoint) {
      const wrap = document.createElement("small");
      const code = document.createElement("code");
      code.textContent = c.endpoint;
      wrap.appendChild(code);
      name.appendChild(wrap);
    }

    const type = document.createElement("td");
    type.textContent = TYPE_LABEL[c.type] || c.type;

    const status = document.createElement("td");
    const st = document.createElement("span");
    st.className = "status" + (c.status === "connected" ? " is-connected" : "");
    const dot = document.createElement("span");
    dot.className = "status__dot";
    st.append(dot, document.createTextNode(STATUS_LABEL[c.status] || c.status));
    status.appendChild(st);
    if (c.status === "connected" && c.account) {
      const acct = document.createElement("span");
      acct.className = "status__account";
      acct.textContent = "@" + c.account;
      status.appendChild(acct);
    }

    const method = document.createElement("td");
    const pill = document.createElement("span");
    pill.className = "pill pill--method";
    pill.textContent = METHOD_LABEL[c.authMethod] || c.authMethod;
    method.appendChild(pill);

    const actions = document.createElement("td");
    const cell = document.createElement("div");
    cell.className = "cell-actions";
    actions.appendChild(cell);
    tr.append(name, type, status, method, actions);
    // Connections are shared by the whole platform, so only admins (dis)connect, edit or delete.
    if (!isAdmin) return tr;
    const reconnect = c.oauthProvider
      ? () => oauthConnect(c)
      : () => connectorAction(api.connectConnector, c.id);
    if (c.status === "connected") {
      cell.append(
        linkBtn(L("conn.reconnect"), reconnect),
        linkBtn(L("conn.disconnect"), () => connectorAction(api.disconnectConnector, c.id), "link-btn--muted"));
    } else {
      const connect = document.createElement("button");
      connect.className = "btn-connect";
      connect.textContent = L("conn.connect");
      connect.addEventListener("click", reconnect);
      cell.append(connect);
    }
    const edit = linkBtn("✎", () => openConnectorModal(c), "link-btn--muted");
    edit.title = L("common.edit");
    const del = linkBtn("×", () => deleteConnector(c), "link-btn--danger");
    del.title = L("common.delete");
    cell.append(edit, del);
    return tr;
  }

  function linkBtn(text, onClick, extra) {
    const b = document.createElement("button");
    b.className = "link-btn" + (extra ? " " + extra : "");
    b.textContent = text;
    b.addEventListener("click", onClick);
    return b;
  }

  async function connectorAction(fn, id) {
    try { await fn(id); } catch (e) { /* ignore */ }
    await loadConnectors();
  }

  async function deleteConnector(c) {
    if (!window.confirm(L("conn.deleteConfirm", { name: c.name }))) return;
    try { await api.deleteConnector(c.id); } catch (e) { /* ignore */ }
    await loadConnectors();
  }

  function openConnectorModal(c) {
    editingId = c ? c.id : null;
    cel.title.textContent = c ? L("conn.modalEdit") : L("conn.modalAdd");
    cel.name.value = c ? c.name : "";
    cel.endpoint.value = c && c.endpoint ? c.endpoint : "";
    cel.description.value = c && c.description ? c.description : "";
    cel.transport.value = c ? c.transport : "stdio";
    cel.type.value = c ? c.type : "optional";
    cel.authMethod.value = c ? c.authMethod : "system_oauth";
    cel.modal.hidden = false;
    cel.name.focus();
  }

  function closeConnectorModal() { cel.modal.hidden = true; }

  cel.modal.querySelectorAll("[data-close]").forEach(e => e.addEventListener("click", closeConnectorModal));
  document.addEventListener("keydown", e => {
    if (e.key === "Escape" && !cel.modal.hidden) closeConnectorModal();
  });

  cel.form.addEventListener("submit", async e => {
    e.preventDefault();
    const body = {
      name: cel.name.value.trim(),
      endpoint: cel.endpoint.value.trim(),
      description: cel.description.value.trim(),
      transport: cel.transport.value,
      type: cel.type.value,
      authMethod: cel.authMethod.value,
    };
    if (!body.name) return;
    cel.submit.disabled = true;
    try {
      if (editingId) await api.updateConnector(editingId, body);
      else await api.createConnector(body);
      closeConnectorModal();
      await loadConnectors();
    } catch (err) { /* ignore */ }
    finally { cel.submit.disabled = false; }
  });

  document.getElementById("addConnectorBtn").addEventListener("click", () => openConnectorModal(null));

  // Full-page navigation to the provider's authorize endpoint; the server 302s to GitHub.
  function oauthConnect(c) {
    if (!c.oauthConfigured) {
      showToast(L("conn.oauthNotConfigured"), true);
      return;
    }
    window.location.href = "/api/v1/oauth/" + c.oauthProvider + "/authorize?connectorId=" + encodeURIComponent(c.id);
  }

  // ---------- toast + OAuth return ----------
  function showToast(message, isError) {
    const t = document.createElement("div");
    t.className = "toast" + (isError ? " toast--error" : "");
    t.textContent = message;
    document.body.appendChild(t);
    requestAnimationFrame(() => t.classList.add("is-show"));
    setTimeout(() => { t.classList.remove("is-show"); setTimeout(() => t.remove(), 300); }, 4000);
  }

  const OAUTH_ERRORS = {
    github_not_configured: "oauth.err.github_not_configured",
    invalid_state: "oauth.err.invalid_state",
    exchange_failed: "oauth.err.exchange_failed",
    missing_code: "oauth.err.missing_code",
    access_denied: "oauth.err.access_denied",
    no_oauth_provider: "oauth.err.no_oauth_provider",
    unknown_connector: "oauth.err.unknown_connector",
  };

  (function handleOAuthReturn() {
    const params = new URLSearchParams(window.location.search);
    if (!params.has("connected") && !params.has("connect_error")) return;
    setView("connectors");
    if (params.has("connected")) {
      showToast(L("conn.oauthConnected"), false);
    } else {
      const code = params.get("connect_error");
      showToast(OAUTH_ERRORS[code] ? L(OAUTH_ERRORS[code]) : L("oauth.err.generic", { code }), true);
    }
    history.replaceState({}, document.title, window.location.pathname + window.location.hash);
  })();

  // ---------- tasks ----------
  Object.assign(api, {
    listTasks: page => fetch("/api/v1/tasks?page=" + page + "&size=" + TASK_PAGE_SIZE).then(r => r.json()),
    taskDetail: id => fetch("/api/v1/tasks/" + id).then(r => r.json()),
    taskAgents: () => fetch("/api/v1/tasks/agents").then(r => r.json()),
    taskStatus: id => fetch("/api/v1/tasks/" + id + "/status").then(r => r.json()),
    taskEvents: (id, offset) => fetch("/api/v1/tasks/" + id + "/events?offset=" + offset).then(r => r.json()),
    taskArtifacts: id => fetch("/api/v1/tasks/" + id + "/artifacts").then(r => r.json()),
    taskMessage: (id, content, images) => fetch("/api/v1/tasks/" + id + "/messages", {
      method: "POST", headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ content, images: images || [] }),
    }),
    stopTask: id => fetch("/api/v1/tasks/" + id + "/stop", { method: "POST" }),
    deleteTask: id => fetch("/api/v1/tasks/" + id, { method: "DELETE" }),
  });

  const TASK_STATUS_LABEL = {
    pending: L("status.pending"), running: L("status.running"), needs_confirmation: L("status.needs_confirmation"),
    done: L("status.done"), error: L("status.error"), stopped: L("status.stopped"),
  };
  const TASK_SOURCE_LABEL = {
    web_chat: L("tasks.srcWebChat"), task_form: L("tasks.srcTaskForm"), automation: L("tasks.srcAutomation"),
  };

  const tel = {
    listView: document.getElementById("taskListView"),
    detailView: document.getElementById("taskDetailView"),
    rows: document.getElementById("taskRows"),
    empty: document.getElementById("tasksEmpty"),
    pager: document.getElementById("taskPager"),
    pagerInfo: document.getElementById("taskPagerInfo"),
    pagerPrev: document.getElementById("taskPagerPrev"),
    pagerNext: document.getElementById("taskPagerNext"),
    back: document.getElementById("taskBackBtn"),
    dTitle: document.getElementById("taskDetailTitle"),
    dMeta: document.getElementById("taskDetailMeta"),
    dStatus: document.getElementById("taskDetailStatus"),
    stopBtn: document.getElementById("taskStopBtn"),
    deleteBtn: document.getElementById("taskDeleteBtn"),
    stream: document.getElementById("taskStream"),
    promptBody: document.getElementById("taskPromptBody"),
    tabs: document.getElementById("taskTabs"),
    runPane: document.getElementById("taskRunPane"),
    sessionInfo: document.getElementById("taskSessionInfo"),
    artifacts: document.getElementById("taskArtifacts"),
    artifactsEmpty: document.getElementById("taskArtifactsEmpty"),
    progress: document.querySelector("#taskDetailView .progress"),
    progressBar: document.getElementById("taskProgressBar"),
    progressPct: document.getElementById("taskProgressPct"),
    facts: document.getElementById("taskFacts"),
    phasesPanel: document.getElementById("taskPhasesPanel"),
    phases: document.getElementById("taskPhases"),
    steerForm: document.getElementById("taskSteerForm"),
    steerInput: document.getElementById("taskSteerInput"),
    steerBtn: document.getElementById("taskSteerBtn"),
  };

  const task = { id: null, renderedEvents: 0, mergeEl: null, mergeType: null, pollTimer: null };

  function isActiveStatus(s) { return s === "running" || s === "pending"; }

  function showTaskList() {
    stopTaskPolling();
    task.id = null;
    tel.detailView.hidden = true;
    tel.listView.hidden = false;
  }

  const TASK_PAGE_SIZE = 20;
  let taskPage = 1;
  let agentOptions = [];  // [{key, name, models:[ProviderModelDto]}] from /tasks/agents

  // Tasks are only ever created by agents and automations, so the list just pages through them.
  function renderTaskPager(page) {
    const pages = page ? Math.max(1, Math.ceil(page.total / page.size)) : 1;
    tel.pager.hidden = !page || pages <= 1;
    if (!page) return;
    tel.pagerInfo.textContent = L("tasks.pageInfo", { total: page.total, page: page.page, pages });
    tel.pagerPrev.disabled = page.page <= 1;
    tel.pagerNext.disabled = page.page >= pages;
  }

  async function loadTasks() {
    if (!agentOptions.length) await loadAgentOptions();
    let list = [];
    let page = null;
    try { const res = await api.listTasks(taskPage); page = res && res.data; } catch (e) { /* ignore */ }
    list = (page && page.items) || [];
    if (page) taskPage = page.page; // the server clamps a page past the end (e.g. after deletes)
    renderTaskPager(page);
    tel.rows.innerHTML = "";
    tel.empty.hidden = list.length > 0;
    list.forEach(t => tel.rows.appendChild(taskRow(t)));
  }

  function taskRow(t) {
    const tr = document.createElement("tr");
    tr.addEventListener("click", () => openTask(t.id));

    const tdTask = document.createElement("td");
    const title = document.createElement("div");
    title.className = "t-title";
    title.textContent = t.title;
    tdTask.appendChild(title);
    const metaBits = [t.repo, t.branch, t.flow].filter(Boolean);
    if (metaBits.length) {
      const sub = document.createElement("small");
      sub.className = "t-sub";
      const code = document.createElement("code");
      code.textContent = metaBits.join(" · ");
      sub.appendChild(code);
      tdTask.appendChild(sub);
    }
    // Short session id under the title: equal ids across rows mean the tasks shared (reused) a session.
    const sess = document.createElement("small");
    sess.className = "t-session";
    sess.textContent = t.sessionId ? "session " + t.sessionId.slice(0, 8) : L("tasks.sessionNone");
    if (t.sessionId) sess.title = t.sessionId;
    tdTask.appendChild(sess);

    const tdAgent = document.createElement("td");
    tdAgent.className = "t-agent";
    const agentName = document.createElement("div");
    agentName.textContent = agentLabel(t.agentType);
    const modelName = document.createElement("small");
    modelName.textContent = t.model || L("tasks.modelDefault");
    tdAgent.append(agentName, modelName);

    const tdSource = document.createElement("td");
    tdSource.textContent = TASK_SOURCE_LABEL[t.source] || t.source;

    const tdCreated = document.createElement("td");
    tdCreated.className = "t-created";
    tdCreated.textContent = formatDateTime(t.createdAt);
    if (t.createdBy) { const by = document.createElement("small"); by.textContent = t.createdBy; tdCreated.appendChild(by); }

    const tdSpend = document.createElement("td");
    tdSpend.className = "t-spend";
    tdSpend.textContent = "$" + Number(t.spend || 0).toFixed(2);

    const tdStatus = document.createElement("td");
    const cell = document.createElement("div");
    cell.className = "t-status-cell";
    const top = document.createElement("div");
    top.className = "t-status-top";
    top.appendChild(statusBadge(t.status));
    const elapsed = document.createElement("span");
    elapsed.className = "t-elapsed";
    elapsed.textContent = formatElapsed(t);
    top.appendChild(elapsed);
    const bar = document.createElement("div");
    bar.className = "progress" + (t.status === "error" ? " is-error" : isActiveStatus(t.status) ? " is-running" : "");
    const fill = document.createElement("div");
    fill.className = "progress__bar";
    fill.style.width = (t.progress || 0) + "%";
    bar.appendChild(fill);
    cell.append(top, bar);
    tdStatus.appendChild(cell);

    const tdActions = document.createElement("td");
    const del = document.createElement("button");
    del.className = "link-btn link-btn--danger";
    del.textContent = "×";
    del.title = L("common.delete");
    del.addEventListener("click", e => { e.stopPropagation(); deleteTask(t); });
    const wrap = document.createElement("div");
    wrap.className = "t-row-actions";
    wrap.appendChild(del);
    tdActions.appendChild(wrap);

    tr.append(tdTask, tdAgent, tdSource, tdCreated, tdSpend, tdStatus, tdActions);
    return tr;
  }

  function statusBadge(status) {
    const b = document.createElement("span");
    b.className = "status-badge is-" + status;
    b.textContent = TASK_STATUS_LABEL[status] || status;
    return b;
  }

  async function deleteTask(t) {
    if (!window.confirm(L("tasks.deleteConfirm", { name: t.title }))) return;
    try { await api.deleteTask(t.id); } catch (e) { /* ignore */ }
    if (task.id === t.id) showTaskList();
    await loadTasks();
  }

  async function openTask(id) {
    task.id = id;
    task.renderedEvents = 0;
    task.mergeEl = null;
    task.mergeType = null;
    tel.listView.hidden = true;
    tel.detailView.hidden = false;
    tel.stream.innerHTML = "";
    let res;
    try { res = await api.taskDetail(id); } catch (e) { showTaskList(); return; }
    const d = res && res.data;
    if (!d) { showTaskList(); return; }
    renderDetail(d);
  }

  function renderDetail(d) {
    const t = d.task;
    tel.dTitle.textContent = t.title;
    const meta = [TASK_SOURCE_LABEL[t.source] || t.source, t.repo, t.branch, t.flow].filter(Boolean).join(" · ");
    tel.dMeta.textContent = meta;

    // Interleave agent events with the user's steering messages by time.
    const timeline = [];
    (d.events || []).forEach(e => timeline.push({ kind: e.type, payload: e.payload, at: e.createdAt }));
    (d.messages || []).forEach(m => {
      if (m.role === "user") timeline.push({ kind: "cmd", payload: m.content, images: m.images, at: m.createdAt });
    });
    timeline.sort((a, b) => new Date(a.at || 0) - new Date(b.at || 0));
    tel.stream.innerHTML = "";
    task.mergeEl = null;
    task.mergeType = null;
    timeline.forEach(appendStreamItem);
    task.renderedEvents = (d.events || []).length;
    if (timeline.length === 0) {
      const empty = document.createElement("p");
      empty.className = "task-stream__empty";
      empty.textContent = L("tasks.streamWaiting");
      tel.stream.appendChild(empty);
    }

    updateStatusUi(t);
    renderFacts(t);
    renderSessionInfo(t);
    renderPhases(d.phases);
    task.hasPhases = !!(d.phases && d.phases.length);
    if (tel.promptBody) tel.promptBody.innerHTML = renderMarkdown(d.brief || t.title || "");
    setTaskTab("run");
    loadArtifacts(t.id);
    if (isActiveStatus(t.status)) startTaskPolling(); else stopTaskPolling();
  }

  // Detail tabs: Prompt / Conversation (run log + coding phases, the default) / Session.
  function setTaskTab(tab) {
    tel.tabs.querySelectorAll(".model-tab").forEach(b => b.classList.toggle("is-active", b.dataset.tab === tab));
    tel.detailView.querySelectorAll("[data-pane-tab]").forEach(p => { p.hidden = p.dataset.paneTab !== tab; });
    if (tab === "run") scrollRunToEnd();
  }
  tel.tabs.addEventListener("click", e => {
    const b = e.target.closest(".model-tab");
    if (b) setTaskTab(b.dataset.tab);
  });

  // After a full render the pane's height is only final once layout runs (markdown, phases),
  // so scroll on the next frame as well as now.
  function scrollRunToEnd() {
    scrollStream();
    requestAnimationFrame(scrollStream);
  }

  async function loadArtifacts(id) {
    let list = [];
    try { const res = await api.taskArtifacts(id); list = (res && res.data) || []; } catch (e) { /* ignore */ }
    tel.artifacts.innerHTML = "";
    tel.artifactsEmpty.hidden = list.length > 0;
    list.forEach(a => {
      const li = document.createElement("li");
      const link = document.createElement("a");
      link.href = "/api/v1/tasks/" + id + "/artifacts/" + encodeURIComponent(a.name);
      link.setAttribute("download", a.name);
      const name = document.createElement("span");
      name.textContent = a.name;
      const size = document.createElement("span");
      size.className = "artifact-size";
      size.textContent = formatBytes(a.size);
      link.append(name, size);
      const save = document.createElement("button");
      save.type = "button";
      save.className = "link-btn artifact-kb";
      save.textContent = L("tasks.saveToKb");
      save.addEventListener("click", () => saveArtifactToKb(id, a.name));
      li.append(link, save);
      tel.artifacts.appendChild(li);
    });
  }

  function formatBytes(n) {
    if (n < 1024) return n + " B";
    if (n < 1024 * 1024) return (n / 1024).toFixed(1) + " KB";
    return (n / 1024 / 1024).toFixed(1) + " MB";
  }

  function formatDateTime(iso) {
    if (!iso) return "—";
    const d = new Date(iso);
    if (isNaN(d.getTime())) return "—";
    const p = n => String(n).padStart(2, "0");
    return d.getFullYear() + "/" + p(d.getMonth() + 1) + "/" + p(d.getDate())
      + " " + p(d.getHours()) + ":" + p(d.getMinutes()) + ":" + p(d.getSeconds());
  }

  function formatElapsed(task) {
    if (!task || !task.startedAt) return "—";
    const start = new Date(task.startedAt).getTime();
    if (isNaN(start)) return "—";
    const end = task.finishedAt ? new Date(task.finishedAt).getTime() : Date.now();
    let s = Math.max(0, Math.floor((end - start) / 1000));
    const h = Math.floor(s / 3600); s -= h * 3600;
    const m = Math.floor(s / 60); s -= m * 60;
    if (h) return h + "h " + m + "m " + s + "s";
    if (m) return m + "m " + s + "s";
    return s + "s";
  }

  // text/result are agent natural-language output → render markdown. Everything
  // else (thinking/tool/cmd/error) is technical text and stays plain.
  const MD_KINDS = { text: 1, result: 1 };

  // Fill an event body. Markdown kinds keep the full source in dataset.md and
  // re-render sanitized HTML each tick (like renderStreamText) to avoid flicker;
  // other kinds stay plain text.
  function setEvBody(bodyEl, kind, fullRaw) {
    if (MD_KINDS[kind]) {
      bodyEl.dataset.md = fullRaw;
      bodyEl.innerHTML = renderMarkdown(fullRaw);
    } else {
      bodyEl.textContent = fullRaw;
    }
  }

  function appendStreamItem(item) {
    const type = item.kind;
    if (type === "system") return; // bootstrap noise
    const mergeable = type === "text" || type === "thinking";
    if (mergeable && task.mergeType === type && task.mergeEl) {
      const bodyEl = task.mergeEl.querySelector(".ev__body");
      const prev = MD_KINDS[type] ? (bodyEl.dataset.md || "") : bodyEl.textContent;
      setEvBody(bodyEl, type, prev + (item.payload || ""));
      scrollStream();
      return;
    }
    const placeholder = tel.stream.querySelector(".task-stream__empty");
    if (placeholder) placeholder.remove();

    const ev = document.createElement("div");
    const label = EV_LABEL[type];
    const kind = type === "thinking" ? "think" : (type === "tool_use" || type === "tool_result") ? "tool" : type;
    ev.className = "ev ev--" + kind;
    ev.dataset.type = type === "thinking" ? "think" : (type === "tool_use" || type === "tool_result") ? "tool"
      : type === "result" ? "result" : type; // filter group
    if (label) {
      const lab = document.createElement("div");
      lab.className = "ev__label";
      lab.textContent = label;
      ev.appendChild(lab);
    }
    if (item.images && item.images.length) ev.appendChild(imageStrip(item.images));
    const body = document.createElement("div");
    body.className = "ev__body";
    setEvBody(body, type, item.payload || "");
    body.hidden = type === "cmd" && !item.payload && !!(item.images && item.images.length);
    ev.appendChild(body);
    tel.stream.appendChild(ev);

    if (mergeable) { task.mergeEl = ev; task.mergeType = type; }
    else { task.mergeEl = null; task.mergeType = null; }
    scrollStream();
  }

  const EV_LABEL = {
    thinking: L("ev.think"), tool_use: L("ev.tool"), tool_result: L("ev.toolResult"),
    result: L("ev.result"), error: L("ev.error"), cmd: "",
  };

  // The run pane is the scrolling container (the stream itself never overflows).
  function scrollStream() { tel.runPane.scrollTop = tel.runPane.scrollHeight; }

  function updateStatusUi(t) {
    tel.dStatus.className = "status-badge is-" + t.status;
    tel.dStatus.textContent = TASK_STATUS_LABEL[t.status] || t.status;
    tel.progress.className = "progress" + (t.status === "error" ? " is-error" : isActiveStatus(t.status) ? " is-running" : "");
    tel.progressBar.style.width = (t.progress || 0) + "%";
    tel.progressPct.textContent = (t.progress || 0) + "%";
    const busy = isActiveStatus(t.status);
    tel.steerInput.disabled = busy;
    tel.steerBtn.disabled = busy;
    taskImages.setDisabled(busy);
    // PENDING with a session = a steering message queued until that session is free (another task's
    // agent is running in it); say so instead of "running".
    const waiting = t.status === "pending" && !!t.sessionId;
    tel.steerInput.placeholder = waiting ? L("tasks.steerWaiting") : busy ? L("tasks.steerBusy") : L("tasks.steer");
    tel.stopBtn.disabled = !busy;
  }

  function renderFacts(t) {
    const facts = [
      [L("tasks.agent"), agentLabel(t.agentType)],
      [L("tasks.model"), t.model || L("tasks.modelDefault")],
      // No effort setting exists yet: tasks run at the CLI's default.
      [L("tasks.effort"), L("tasks.effortDefault"), L("tasks.effortHint")],
      [L("tasks.session"), t.sessionId ? t.sessionId.slice(0, 8) : "—", t.sessionId || ""],
      [L("tasks.created"), formatDateTime(t.createdAt)],
      [L("tasks.by"), t.createdBy || "—"],
      [L("tasks.spend"), "$" + Number(t.spend || 0).toFixed(2)],
      [L("tasks.elapsed"), formatElapsed(t)],
    ];
    if (t.error) facts.push([L("tasks.error"), t.error]);
    fillFacts(tel.facts, facts);
  }

  function renderSessionInfo(t) {
    const state = t.sessionState ? L("tasks.sessionState." + t.sessionState) : "—";
    fillFacts(tel.sessionInfo, [
      [L("tasks.sessionId"), t.sessionId || L("tasks.sessionNone")],
      [L("tasks.sessionState"), state],
      [L("tasks.workspace"), t.workspaceDir || "—"],
      [L("tasks.agentType"), t.agentType ? agentLabel(t.agentType) + " (" + t.agentType + ")" : "—"],
      [L("tasks.model"), t.model || L("tasks.modelDefault")],
      [L("tasks.effort"), L("tasks.effortDefault"), L("tasks.effortHint")],
    ]);
  }

  /** Renders [label, value, title?] rows into a <dl>. */
  function fillFacts(dl, rows) {
    dl.innerHTML = "";
    rows.forEach(([k, v, title]) => {
      const dt = document.createElement("dt"); dt.textContent = k;
      const dd = document.createElement("dd"); dd.textContent = v;
      if (title) dd.title = title;
      dl.append(dt, dd);
    });
  }

  // The coding flow's three steps: a status chip per step, plus the PRD markdown
  // and the acceptance evidence inline. Hidden entirely for non-coding tasks.
  function renderPhases(phases) {
    if (!tel.phasesPanel || !tel.phases) return;
    if (!phases || !phases.length) { tel.phasesPanel.hidden = true; tel.phases.innerHTML = ""; return; }
    tel.phasesPanel.hidden = false;
    tel.phases.innerHTML = "";
    phases.forEach(p => {
      const li = document.createElement("li");
      li.className = "phase phase--" + p.status;
      const head = document.createElement("div");
      head.className = "phase__head";
      const name = document.createElement("span");
      name.className = "phase__name";
      name.textContent = p.name;
      const chip = document.createElement("span");
      chip.className = "phase__status";
      chip.textContent = L("tasks.phaseStatus." + p.status) || p.status;
      head.append(name, chip);
      li.appendChild(head);
      if (p.content) {
        const body = document.createElement("div");
        body.className = "phase__doc";
        body.innerHTML = renderMarkdown(p.content);
        li.appendChild(body);
      }
      if (p.evidence) {
        const ev = document.createElement("div");
        ev.className = "phase__doc";
        ev.innerHTML = renderMarkdown(p.evidence);
        li.appendChild(ev);
      }
      tel.phases.appendChild(li);
    });
  }

  function startTaskPolling() {
    stopTaskPolling();
    task.pollTimer = setInterval(pollTask, 1500);
  }
  function stopTaskPolling() {
    if (task.pollTimer) { clearInterval(task.pollTimer); task.pollTimer = null; }
  }

  async function pollTask() {
    const id = task.id;
    if (!id) { stopTaskPolling(); return; }
    try {
      const [evRes, stRes] = await Promise.all([api.taskEvents(id, task.renderedEvents), api.taskStatus(id)]);
      const newEvents = (evRes && evRes.data) || [];
      newEvents.forEach(e => appendStreamItem({ kind: e.type, payload: e.payload, at: e.createdAt }));
      task.renderedEvents += newEvents.length;
      const t = stRes && stRes.data;
      if (!t) return;
      updateStatusUi(t);
      renderFacts(t);
      renderSessionInfo(t);
      if (task.hasPhases) {
        try { const dRes = await api.taskDetail(id); if (dRes && dRes.data) renderPhases(dRes.data.phases); } catch (e) { /* ignore */ }
      }
      if (!isActiveStatus(t.status)) { stopTaskPolling(); loadArtifacts(id); }
    } catch (e) { /* ignore transient poll errors */ }
  }

  // steering: always resumes this task's own session (see TaskService.sendMessage)
  const taskImages = imageAttacher({
    button: document.getElementById("taskAttachBtn"),
    fileInput: document.getElementById("taskImageInput"),
    tray: document.getElementById("taskImageTray"),
    pasteTarget: tel.steerInput,
    dropZone: tel.steerForm,
  });

  tel.steerForm.addEventListener("submit", async e => {
    e.preventDefault();
    const text = tel.steerInput.value.trim();
    if ((!text && taskImages.count() === 0) || !task.id) return;
    let res;
    try {
      res = await api.taskMessage(task.id, text, await taskImages.payload());
    } catch (err) { res = null; }
    if (!res || !res.ok) {
      showToast(L("tasks.steerFailed"), true); // keep the text and images so they can be resent
      return;
    }
    const urls = taskImages.previewUrls();
    tel.steerInput.value = "";
    autoGrowSteer();
    taskImages.clear();
    setTaskTab("run");
    appendStreamItem({ kind: "cmd", payload: text, images: urls });
    startTaskPolling();
  });
  tel.steerInput.addEventListener("keydown", e => {
    if (e.key === "Enter" && !e.shiftKey) { e.preventDefault(); tel.steerForm.requestSubmit(); }
  });
  tel.steerInput.addEventListener("input", autoGrowSteer);
  function autoGrowSteer() {
    tel.steerInput.style.height = "auto";
    tel.steerInput.style.height = Math.min(tel.steerInput.scrollHeight, 160) + "px";
  }

  tel.back.addEventListener("click", () => { showTaskList(); loadTasks(); });
  tel.pagerPrev.addEventListener("click", () => { taskPage = Math.max(1, taskPage - 1); loadTasks(); });
  tel.pagerNext.addEventListener("click", () => { taskPage += 1; loadTasks(); });
  tel.stopBtn.addEventListener("click", async () => { if (task.id) { try { await api.stopTask(task.id); } catch (e) { /* ignore */ } } });
  tel.deleteBtn.addEventListener("click", async () => {
    if (!task.id || !window.confirm(L("tasks.deleteConfirm2"))) return;
    const id = task.id;
    try { await api.deleteTask(id); } catch (e) { /* ignore */ }
    showTaskList();
    await loadTasks();
  });

  async function loadAgentOptions() {
    try { const res = await api.taskAgents(); agentOptions = (res && res.data) || []; } catch (e) { agentOptions = []; }
    return agentOptions;
  }

  function agentLabel(key) {
    const opt = agentOptions.find(a => a.key === key);
    return opt ? opt.name : (key || "—");
  }



  // ---------- auth (session guard + user management) ----------
  Object.assign(api, {
    me: () => fetch("/api/v1/auth/me"),
    logout: () => fetch("/api/v1/auth/logout", { method: "POST" }),
    listUsers: () => fetch("/api/v1/users").then(r => r.json()),
    createUser: body => fetch("/api/v1/users", {
      method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body),
    }).then(r => r.json()),
    updateUser: (id, body) => fetch("/api/v1/users/" + id, {
      method: "PUT", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body),
    }).then(r => r.json()),
    deleteUser: id => fetch("/api/v1/users/" + id, { method: "DELETE" }),
  });

  const adminNav = document.getElementById("adminNav");
  const adminSep = document.getElementById("adminSep");

  let currentUsername = null;

  async function initAuth() {
    let res;
    try { res = await api.me(); } catch (e) { window.location.href = "/login"; return false; }
    if (res.status === 401) { window.location.href = "/login"; return false; }
    const me = (await res.json()).data;
    currentUsername = me.username;
    const shown = me.displayName || me.username || "";
    document.getElementById("currentUser").textContent = shown;
    document.getElementById("userAvatar").textContent = shown.trim().charAt(0).toUpperCase();
    // Admin-only sections: hide for non-admins.
    const showAdmin = !!me.admin;
    isAdmin = showAdmin;
    adminNav.hidden = !showAdmin;
    adminSep.hidden = !showAdmin;
    document.getElementById("addConnectorBtn").hidden = !showAdmin; // the list is for all; managing it is not
    return true;
  }

  document.getElementById("logoutBtn").addEventListener("click", async () => {
    try { await api.logout(); } catch (e) { /* ignore */ }
    window.location.href = "/login";
  });

  // --- users admin ---
  const ROLE_LABEL = { ADMIN: "Admin", USER: "User" };
  const uel = {
    rows: document.getElementById("userRows"),
    empty: document.getElementById("usersEmpty"),
    addBtn: document.getElementById("addUserBtn"),
    modal: document.getElementById("userModal"),
    form: document.getElementById("userForm"),
    title: document.getElementById("userModalTitle"),
    username: document.getElementById("ufUsername"),
    displayName: document.getElementById("ufDisplayName"),
    source: document.getElementById("ufSource"),
    role: document.getElementById("ufRole"),
    password: document.getElementById("ufPassword"),
    phone: document.getElementById("ufPhone"),
    externalId: document.getElementById("ufExternalId"),
    enabled: document.getElementById("ufEnabled"),
    submit: document.getElementById("ufSubmit"),
  };
  let editingUserId = null;

  async function loadUsers() {
    let list = [];
    try { const res = await api.listUsers(); list = (res && res.data) || []; } catch (e) { /* ignore */ }
    uel.rows.innerHTML = "";
    uel.empty.hidden = list.length > 0;
    list.forEach(u => uel.rows.appendChild(userRow(u)));
  }

  function userRow(u) {
    const tr = document.createElement("tr");

    const tdUser = document.createElement("td");
    tdUser.className = "c-name";
    tdUser.textContent = u.username;
    if (u.displayName) { const d = document.createElement("small"); d.textContent = u.displayName; tdUser.appendChild(d); }

    const tdRoles = document.createElement("td");
    tdRoles.textContent = (u.roles || []).map(r => ROLE_LABEL[r] || r).join(", ");

    const tdSource = document.createElement("td");
    const pill = document.createElement("span");
    pill.className = "pill pill--method";
    pill.textContent = L("src." + u.source);
    tdSource.appendChild(pill);

    const tdStatus = document.createElement("td");
    const st = document.createElement("span");
    st.className = "status" + (u.enabled ? " is-connected" : "");
    const dot = document.createElement("span"); dot.className = "status__dot";
    st.append(dot, document.createTextNode(u.enabled ? L("users.enabled") : L("users.disabled")));
    tdStatus.appendChild(st);

    const tdActions = document.createElement("td");
    const cell = document.createElement("div");
    cell.className = "cell-actions";
    cell.append(
      linkBtn(u.enabled ? L("users.disable") : L("users.enable"), () => toggleUser(u), "link-btn--muted"),
      linkBtn("✎", () => openUserModal(u), "link-btn--muted"),
      linkBtn("×", () => deleteUser(u), "link-btn--danger"));
    cell.children[1].title = L("common.edit"); cell.children[2].title = L("common.delete");
    tdActions.appendChild(cell);

    tr.append(tdUser, tdRoles, tdSource, tdStatus, tdActions);
    return tr;
  }

  async function toggleUser(u) {
    try { await api.updateUser(u.id, { enabled: !u.enabled }); } catch (e) { /* ignore */ }
    await loadUsers();
  }
  async function deleteUser(u) {
    if (!window.confirm(L("users.deleteConfirm", { name: u.username }))) return;
    try { await api.deleteUser(u.id); } catch (e) { /* ignore */ }
    await loadUsers();
  }

  function openUserModal(u) {
    editingUserId = u ? u.id : null;
    uel.title.textContent = u ? L("users.modalEdit") : L("users.modalAdd");
    uel.username.value = u ? u.username : "";
    uel.displayName.value = u && u.displayName ? u.displayName : "";
    uel.source.value = u ? u.source : "local";
    uel.role.value = u && (u.roles || []).includes("ADMIN") ? "ADMIN" : "USER";
    uel.password.value = "";
    uel.phone.value = u && u.phone ? u.phone : "";
    uel.externalId.value = u && u.externalId ? u.externalId : "";
    uel.enabled.checked = u ? u.enabled : true;
    uel.modal.hidden = false;
    uel.username.focus();
  }
  function closeUserModal() { uel.modal.hidden = true; }
  uel.addBtn.addEventListener("click", () => openUserModal(null));
  uel.modal.querySelectorAll("[data-close]").forEach(x => x.addEventListener("click", closeUserModal));
  uel.form.addEventListener("submit", async e => {
    e.preventDefault();
    const body = {
      username: uel.username.value.trim(),
      displayName: uel.displayName.value.trim(),
      source: uel.source.value,
      roles: [uel.role.value],
      password: uel.password.value,
      phone: uel.phone.value.trim() || null,
      externalId: uel.externalId.value.trim() || null,
      enabled: uel.enabled.checked,
    };
    if (!body.username) return;
    uel.submit.disabled = true;
    try {
      if (editingUserId) await api.updateUser(editingUserId, body);
      else await api.createUser(body);
      closeUserModal();
      await loadUsers();
    } catch (err) { /* ignore */ } finally { uel.submit.disabled = false; }
  });

  // language switcher
  const langSwitch = document.getElementById("langSwitch");
  if (langSwitch) {
    const cur = window.I18N.getLang();
    langSwitch.querySelectorAll("[data-lang]").forEach(b => {
      b.classList.toggle("is-active", b.dataset.lang === cur);
      b.addEventListener("click", () => window.I18N.switchTo(b.dataset.lang));
    });
  }

  // Guard first, then load the app only when authenticated.
  // ---------- models (vendor CLIs) ----------
  Object.assign(api, {
    listProviders: () => fetch("/api/v1/providers").then(r => r.json()),
    providerCatalog: () => fetch("/api/v1/providers/catalog").then(r => r.json()),
    addProvider: key => fetch("/api/v1/providers", {
      method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ key }),
    }).then(r => r.json()),
    providerDetail: id => fetch("/api/v1/providers/" + id).then(r => r.json()),
    removeProvider: id => fetch("/api/v1/providers/" + id, { method: "DELETE" }),
    installProvider: id => fetch("/api/v1/providers/" + id + "/install", { method: "POST" }).then(r => r.json()),
    upgradeProvider: id => fetch("/api/v1/providers/" + id + "/upgrade", { method: "POST" }).then(r => r.json()),
    refreshProviderModels: id => fetch("/api/v1/providers/" + id + "/models/refresh", { method: "POST" }).then(r => r.json()),
  });

  const mel = {
    listView: document.getElementById("modelListView"),
    detailView: document.getElementById("modelDetailView"),
    rows: document.getElementById("modelRows"),
    empty: document.getElementById("modelsEmpty"),
    catalog: document.getElementById("modelCatalog"),
    addBtn: document.getElementById("modelAddBtn"),
    back: document.getElementById("modelBackBtn"),
    title: document.getElementById("modelDetailTitle"),
    meta: document.getElementById("modelDetailMeta"),
    status: document.getElementById("modelDetailStatus"),
    tabs: document.getElementById("modelTabs"),
    info: document.getElementById("modelInfo"),
    models: document.getElementById("modelModels"),
    modelsPanel: document.getElementById("modelModelsPanel"),
    modelsMeta: document.getElementById("modelModelsMeta"),
    modelsError: document.getElementById("modelModelsError"),
    modelsEmpty: document.getElementById("modelModelsEmpty"),
    refreshBtn: document.getElementById("modelRefreshBtn"),
  };

  function startModelPolling() {
    stopModelPolling();
    refreshModels();
    modelPollTimer = setInterval(refreshModels, 3000);
  }
  function stopModelPolling() {
    if (modelPollTimer) { clearInterval(modelPollTimer); modelPollTimer = null; }
  }

  function refreshModels() {
    if (modelDetailId) loadModelDetail(modelDetailId); else loadModels();
  }

  function showModelList() {
    modelDetailId = null;
    mel.detailView.hidden = true;
    mel.listView.hidden = false;
    loadCatalog();
  }

  async function loadCatalog() {
    let list = [];
    try { const res = await api.providerCatalog(); list = (res && res.data) || []; } catch (e) { /* ignore */ }
    mel.catalog.innerHTML = "";
    list.forEach(c => {
      const opt = document.createElement("option");
      opt.value = c.key;
      opt.textContent = c.name + (c.supported ? "" : " · " + L("models.displayOnly")) + (c.added ? " ✓" : "");
      opt.disabled = c.added;
      mel.catalog.appendChild(opt);
    });
    const firstFree = list.find(c => !c.added);
    if (firstFree) mel.catalog.value = firstFree.key;
    mel.addBtn.disabled = !firstFree;
  }

  async function loadModels() {
    let list = [];
    try { const res = await api.listProviders(); list = (res && res.data) || []; } catch (e) { /* ignore */ }
    mel.rows.innerHTML = "";
    mel.empty.hidden = list.length > 0;
    list.forEach(p => mel.rows.appendChild(modelRow(p)));
  }

  function yesNo(v, supported) {
    if (!supported) return "—";
    return v ? "✓" : "✗";
  }

  function modelStatusBadge(p) {
    const b = document.createElement("span");
    b.className = "status-badge is-model-" + p.status;
    b.textContent = L("models.status." + p.status);
    return b;
  }

  function modelRow(p) {
    const tr = document.createElement("tr");

    const tdName = document.createElement("td");
    tdName.className = "c-name";
    tdName.appendChild(document.createTextNode(p.name));
    const sub = document.createElement("small");
    const code = document.createElement("code");
    code.textContent = p.cli || "—";
    sub.appendChild(code);
    if (!p.supported) sub.appendChild(document.createTextNode("  · " + L("models.displayOnly")));
    tdName.appendChild(sub);

    const cell = text => { const td = document.createElement("td"); td.textContent = text; return td; };

    const tdStatus = document.createElement("td");
    tdStatus.appendChild(modelStatusBadge(p));
    if (p.message) {
      const m = document.createElement("small");
      m.className = "model-error";
      m.textContent = p.message;
      tdStatus.appendChild(m);
    }

    const tdActions = document.createElement("td");
    const wrap = document.createElement("div");
    wrap.className = "cell-actions";
    if (p.supported && !p.installed && p.status !== "installing") {
      const b = document.createElement("button");
      b.className = "btn-connect";
      b.textContent = L("models.install");
      b.addEventListener("click", () => providerAction(p, "install"));
      wrap.appendChild(b);
    }
    if (p.supported && p.installed && p.upgradeAvailable && p.status !== "installing") {
      const b = document.createElement("button");
      b.className = "btn-connect";
      b.textContent = L("models.upgrade");
      b.addEventListener("click", () => providerAction(p, "upgrade"));
      wrap.appendChild(b);
    }
    wrap.append(
      linkBtn(L("models.details"), () => openModelDetail(p.id), "link-btn--muted"),
      linkBtn("×", () => removeProvider(p), "link-btn--danger"));
    wrap.lastChild.title = L("common.delete");
    tdActions.appendChild(wrap);

    tr.append(tdName,
      cell(yesNo(p.installed, p.supported)),
      cell(yesNo(p.canInfer, p.supported)),
      cell(p.currentVersion || "—"),
      cell(p.latestVersion || "—"),
      tdStatus, tdActions);
    return tr;
  }

  async function providerAction(p, action) {
    const cmd = action === "install" ? p.installCommand : p.upgradeCommand;
    if (!window.confirm(L("models.confirm" + (action === "install" ? "Install" : "Upgrade"), { name: p.name, cmd }))) return;
    try {
      await (action === "install" ? api.installProvider(p.id) : api.upgradeProvider(p.id));
    } catch (e) { /* ignore */ }
    refreshModels();
  }

  async function removeProvider(p) {
    if (!window.confirm(L("models.removeConfirm", { name: p.name }))) return;
    try { await api.removeProvider(p.id); } catch (e) { /* ignore */ }
    loadCatalog();
    loadModels();
  }

  mel.addBtn.addEventListener("click", async () => {
    const key = mel.catalog.value;
    if (!key) return;
    mel.addBtn.disabled = true;
    try { await api.addProvider(key); } catch (e) { /* ignore */ }
    await loadCatalog();
    await loadModels();
  });

  function openModelDetail(id) {
    modelDetailId = id;
    mel.listView.hidden = true;
    mel.detailView.hidden = false;
    setModelTab("info");
    loadModelDetail(id);
  }

  async function loadModelDetail(id) {
    let p;
    try { const res = await api.providerDetail(id); p = res && res.data; } catch (e) { /* ignore */ }
    if (!p || modelDetailId !== id) return;
    mel.title.textContent = p.name;
    mel.meta.textContent = p.cli || "";
    mel.status.className = "status-badge is-model-" + p.status;
    mel.status.textContent = L("models.status." + p.status);

    const facts = [
      [L("models.colName"), p.cli || "—"],
      [L("models.colInstalled"), yesNo(p.installed, p.supported)],
      [L("models.colInfer"), yesNo(p.canInfer, p.supported)],
      [L("models.colVersion"), p.currentVersion || "—"],
      [L("models.colLatest"), p.latestVersion || "—"],
      [L("models.installCmd"), p.installCommand || "—"],
      [L("models.upgradeCmd"), p.upgradeCommand || "—"],
      [L("models.lastChecked"), formatDateTime(p.lastCheckedAt)],
    ];
    if (p.message) facts.push([L("models.lastError"), p.message]);
    mel.info.innerHTML = "";
    facts.forEach(([k, v]) => {
      const dt = document.createElement("dt"); dt.textContent = k;
      const dd = document.createElement("dd"); dd.textContent = v;
      mel.info.append(dt, dd);
    });

    mel.models.innerHTML = "";
    const models = p.models || [];
    const inferRank = m => ({ ok: 0, error: 2 }[m.inferStatus] ?? 1);
    models.slice().sort((a, b) => inferRank(a) - inferRank(b))
      .forEach(m => mel.models.appendChild(modelItem(m)));
    mel.modelsEmpty.hidden = models.length > 0;
    mel.modelsEmpty.textContent = p.supported ? L("models.noModels") : L("models.modelsComingSoon");
    mel.refreshBtn.hidden = !p.supported;
    const inferOk = models.filter(m => m.inferStatus === "ok").length;
    const inferError = models.filter(m => m.inferStatus === "error").length;
    mel.modelsMeta.textContent = p.supported
      ? L("models.modelsMeta", {
          count: models.length,
          source: p.modelsSource || "—",
          time: formatDateTime(p.modelsCheckedAt),
        }) + (inferOk || inferError ? L("models.inferSummary", { ok: inferOk, error: inferError }) : "")
      : "";
    mel.modelsError.hidden = !p.modelsError;
    mel.modelsError.textContent = p.modelsError ? L("models.modelsError", { msg: p.modelsError }) : "";
  }

  // One table row per model: id (+ display name, non-native tag), effort levels, default level, context.
  function modelItem(m) {
    const tr = document.createElement("tr");

    const tdModel = document.createElement("td");
    tdModel.className = "c-name";
    const id = document.createElement("code");
    id.className = "model-id";
    id.textContent = m.id;
    tdModel.appendChild(id);
    if (!m.nativeModel) {
      const tag = document.createElement("span");
      tag.className = "model-tag model-tag--warn";
      tag.textContent = L("models.nonNative");
      tag.title = L("models.nonNativeHint");
      tdModel.appendChild(tag);
    }
    if (m.displayName && m.displayName !== m.id) {
      const name = document.createElement("small");
      name.textContent = m.displayName;
      tdModel.appendChild(name);
    }

    const tdEfforts = document.createElement("td");
    if (m.efforts && m.efforts.length) {
      const chips = document.createElement("div");
      chips.className = "model-chips";
      m.efforts.forEach(e => {
        const chip = document.createElement("span");
        chip.className = "model-tag" + (e === m.defaultEffort ? " model-tag--default" : "");
        chip.textContent = e;
        chips.appendChild(chip);
      });
      tdEfforts.appendChild(chips);
      if (m.effortSource === "cli") {
        const note = document.createElement("small");
        note.className = "model-note";
        note.textContent = L("models.effortCli");
        tdEfforts.appendChild(note);
      }
    } else {
      tdEfforts.className = "model-note";
      tdEfforts.textContent = L("models.effortUnknown");
    }

    const tdDefault = document.createElement("td");
    tdDefault.textContent = m.defaultEffort || "—";
    const tdCtx = document.createElement("td");
    tdCtx.className = "t-spend";
    tdCtx.textContent = m.contextWindow ? formatContext(m.contextWindow) : "—";

    tr.append(tdModel, tdEfforts, tdDefault, tdCtx, inferenceCell(m));
    return tr;
  }

  // Background probe result: healthy / failing (with the reason) / not checked yet.
  function inferenceCell(m) {
    const td = document.createElement("td");
    const state = m.inferStatus === "ok" ? "ok" : m.inferStatus === "error" ? "error" : "unchecked";
    const badge = document.createElement("span");
    badge.className = "status-badge is-infer-" + state;
    badge.textContent = L({ ok: "models.inferOk", error: "models.inferError", unchecked: "models.inferUnchecked" }[state]);
    if (m.inferError) {
      // The reason is long raw API text — keep it out of the row, show it on hover.
      const wrap = document.createElement("span");
      wrap.className = "infer-tip";
      const err = document.createElement("span");
      err.className = "infer-tip__body";
      err.textContent = m.inferError;
      wrap.append(badge, err);
      td.appendChild(wrap);
    } else {
      td.appendChild(badge);
    }
    if (m.inferCheckedAt) {
      const when = document.createElement("small");
      when.className = "model-note";
      when.textContent = L("models.inferCheckedAt", { time: formatDateTime(m.inferCheckedAt) });
      td.appendChild(when);
    }
    return td;
  }


  function formatContext(n) {
    return n >= 1000000 ? (n / 1000000).toFixed(n % 1000000 ? 1 : 0) + "M" : Math.round(n / 1000) + "K";
  }

  function setModelTab(tab) {
    mel.tabs.querySelectorAll(".model-tab").forEach(b => b.classList.toggle("is-active", b.dataset.tab === tab));
    mel.info.hidden = tab !== "info";
    mel.modelsPanel.hidden = tab !== "models";
  }
  mel.tabs.addEventListener("click", e => {
    const b = e.target.closest(".model-tab");
    if (b) setModelTab(b.dataset.tab);
  });
  mel.refreshBtn.addEventListener("click", async () => {
    const id = modelDetailId;
    if (!id) return;
    mel.refreshBtn.disabled = true;
    mel.refreshBtn.textContent = L("models.refreshing");
    try { await api.refreshProviderModels(id); } catch (e) { /* surfaced via modelsError */ }
    mel.refreshBtn.disabled = false;
    mel.refreshBtn.textContent = L("models.refresh");
    loadModelDetail(id);
  });
  mel.back.addEventListener("click", () => { showModelList(); loadModels(); });

  // ---------- system settings (admin) ----------
  Object.assign(api, {
    getSettings: () => fetch("/api/v1/settings").then(r => r.json()),
    updateSettings: body => fetch("/api/v1/settings", {
      method: "PUT", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body),
    }).then(r => r.json()),
  });

  const sel = {
    form: document.getElementById("settingsForm"),
    agentModel: document.getElementById("sfAgentModel"),
    missing: document.getElementById("sfAgentModelMissing"),
    filtered: document.getElementById("sfAgentModelFiltered"),
    status: document.getElementById("sfStatus"),
  };

  async function loadSystemSettings() {
    try { const res = await api.getSettings(); systemSettings = (res && res.data) || {}; } catch (e) { systemSettings = {}; }
  }

  async function loadSettingsForm() {
    sel.status.textContent = "";
    const [{ groups, listed }] = await Promise.all([loadChatModels(), loadSystemSettings()]);
    const ok = groups.reduce((n, g) => n + g.models.length, 0);
    const current = systemSettings.agentDefaultModel || "";

    sel.agentModel.innerHTML = "";
    const def = document.createElement("option");
    def.value = "";
    def.textContent = L("settings.modelCliDefault");
    sel.agentModel.appendChild(def);
    fillModelGroups(sel.agentModel, groups,
      m => m.displayName && m.displayName !== m.id ? m.displayName + " (" + m.id + ")" : m.id);
    // Keep a saved value selectable even if it no longer passes the check.
    if (current && !groups.some(g => g.models.some(m => m.id === current))) {
      fillModelGroups(sel.agentModel, [{ label: L("settings.modelCurrent"), models: [{ id: current }] }], m => m.id);
    }
    sel.agentModel.value = current;
    sel.missing.hidden = ok > 0;
    sel.filtered.hidden = !ok || ok === listed;
    sel.filtered.textContent = L("settings.modelsFiltered", { ok, total: listed });
  }

  sel.form.addEventListener("submit", async e => {
    e.preventDefault();
    sel.status.textContent = "";
    try {
      const res = await api.updateSettings({ agentDefaultModel: sel.agentModel.value || null });
      if (!res || !res.data) throw new Error((res && res.message) || "no data");
      systemSettings = res.data;
      sel.status.textContent = L("settings.saved");
      if (activeAgent) renderAgentPanel(activeAgent);
    } catch (err) {
      sel.status.textContent = L("settings.saveFailed") + " " + err.message;
    }
  });

  // ---------- automations ----------
  Object.assign(api, {
    listAutomations: () => fetch("/api/v1/automations").then(r => r.json()),
    automationDetail: id => fetch("/api/v1/automations/" + id).then(r => r.json()),
    saveAutomation: (id, body) => fetch("/api/v1/automations" + (id ? "/" + id : ""), {
      method: id ? "PUT" : "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body),
    }).then(r => r.json()),
    previewAutomation: body => fetch("/api/v1/automations/preview", {
      method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body),
    }).then(r => r.json()),
    setAutomationEnabled: (id, on) => fetch("/api/v1/automations/" + id + "/enabled?value=" + on, { method: "POST" })
      .then(r => r.json()),
    runAutomation: id => fetch("/api/v1/automations/" + id + "/run", { method: "POST" }).then(r => r.json()),
    regenerateAutomationToken: id => fetch("/api/v1/automations/" + id + "/token", { method: "POST" })
      .then(r => r.json()),
    deleteAutomation: id => fetch("/api/v1/automations/" + id, { method: "DELETE" }).then(r => r.json()),
  });

  const ael2 = {
    listView: document.getElementById("autoListView"),
    detailView: document.getElementById("autoDetailView"),
    rows: document.getElementById("autoRows"),
    empty: document.getElementById("autoEmpty"),
    tabs: document.getElementById("autoTabs"),
    search: document.getElementById("autoSearch"),
    owner: document.getElementById("autoOwnerFilter"),
    addBtn: document.getElementById("autoAddBtn"),
    back: document.getElementById("autoBackBtn"),
    dTitle: document.getElementById("autoDetailTitle"),
    dMeta: document.getElementById("autoDetailMeta"),
    dActions: document.getElementById("autoDetailActions"),
    facts: document.getElementById("autoFacts"),
    prompt: document.getElementById("autoPrompt"),
    runRows: document.getElementById("autoRunRows"),
    runsEmpty: document.getElementById("autoRunsEmpty"),
    tokenBox: document.getElementById("autoTokenBox"),
    tokenValue: document.getElementById("autoTokenValue"),
    tokenCurl: document.getElementById("autoTokenCurl"),
    drawer: document.getElementById("autoDrawer"),
    form: document.getElementById("autoForm"),
    formTitle: document.getElementById("autoFormTitle"),
    fPrompt: document.getElementById("afPrompt"),
    fName: document.getElementById("afAutoName"),
    fType: document.getElementById("afType"),
    fModel: document.getElementById("afAutoModel"),
    fRecurring: document.getElementById("afRecurring"),
    fPreset: document.getElementById("afPreset"),
    fWeekday: document.getElementById("afWeekday"),
    fDay: document.getElementById("afDay"),
    fHour: document.getElementById("afHour"),
    fMinute: document.getElementById("afMinute"),
    fCron: document.getElementById("afCron"),
    fRunAt: document.getElementById("afRunAt"),
    fTimezone: document.getElementById("afTimezone"),
    fOverlap: document.getElementById("afOverlap"),
    fPreview: document.getElementById("afPreview"),
    fError: document.getElementById("afAutoError"),
    fSubmit: document.getElementById("afAutoSubmit"),
  };
  let automations = [];
  let autoFilter = "all";
  let autoDetailId = null;
  let autoEditing = null; // the automation being edited, or null for a new one

  const AUTO_TIMEZONES = ["Asia/Shanghai", "Asia/Hong_Kong", "Asia/Singapore", "Asia/Tokyo", "UTC",
    "Europe/London", "Europe/Berlin", "America/New_York", "America/Los_Angeles"];
  const pad2 = n => String(n).padStart(2, "0");

  function fillOptions(select, entries) {
    select.replaceChildren(...entries.map(([value, label]) => {
      const o = document.createElement("option");
      o.value = value;
      o.textContent = label;
      return o;
    }));
  }
  fillOptions(ael2.fMinute, Array.from({ length: 12 }, (_, i) => [String(i * 5), pad2(i * 5)]));
  fillOptions(ael2.fHour, Array.from({ length: 24 }, (_, i) => [String(i), pad2(i) + ":00"]));
  fillOptions(ael2.fDay, Array.from({ length: 28 }, (_, i) => [String(i + 1), String(i + 1)]));
  fillOptions(ael2.fWeekday, [1, 2, 3, 4, 5, 6, 0].map(d => [String(d), L("auto.wd" + d)]));
  (() => {
    let zones = AUTO_TIMEZONES.slice();
    try {
      const local = Intl.DateTimeFormat().resolvedOptions().timeZone;
      if (local && !zones.includes(local)) zones = [local].concat(zones);
    } catch (e) { /* keep the list */ }
    fillOptions(ael2.fTimezone, zones.map(z => [z, z]));
  })();

  // --- schedule <-> preset (the form's friendly controls are just a cron builder) ---
  function presetToCron() {
    const m = ael2.fMinute.value, h = ael2.fHour.value;
    switch (ael2.fPreset.value) {
      case "hourly": return m + " * * * *";
      case "daily": return m + " " + h + " * * *";
      case "weekdays": return m + " " + h + " * * 1-5";
      case "weekly": return m + " " + h + " * * " + ael2.fWeekday.value;
      case "monthly": return m + " " + h + " " + ael2.fDay.value + " * *";
      default: return ael2.fCron.value.trim();
    }
  }

  // Recognize the crons the presets produce; anything else is shown as custom.
  function cronToPreset(cron) {
    const f = (cron || "").trim().split(/\s+/);
    const num = v => /^\d+$/.test(v);
    const fiveMin = v => num(v) && Number(v) % 5 === 0 && Number(v) < 60;
    if (f.length === 5 && fiveMin(f[0])) {
      const [m, h, dom, mon, dow] = f;
      if (h === "*" && dom === "*" && mon === "*" && dow === "*") return { preset: "hourly", m };
      if (num(h) && mon === "*") {
        if (dom === "*" && dow === "*") return { preset: "daily", m, h };
        if (dom === "*" && dow === "1-5") return { preset: "weekdays", m, h };
        if (dom === "*" && /^[0-6]$/.test(dow)) return { preset: "weekly", m, h, wd: dow };
        if (num(dom) && Number(dom) <= 28 && dow === "*") return { preset: "monthly", m, h, d: dom };
      }
    }
    return { preset: "custom" };
  }

  function describeSchedule(a) {
    if (a.type === "api") return L("auto.typeApi");
    if (a.type === "once") return L("auto.descOnce", { time: formatDateTime(a.runAt) });
    const p = cronToPreset(a.cron);
    const hm = (p.h != null ? pad2(p.h) : "") + ":" + pad2(p.m);
    switch (p.preset) {
      case "hourly": return L("auto.descHourly", { m: pad2(p.m) });
      case "daily": return L("auto.descDaily", { time: hm });
      case "weekdays": return L("auto.descWeekdays", { time: hm });
      case "weekly": return L("auto.descWeekly", { wd: L("auto.wd" + p.wd), time: hm });
      case "monthly": return L("auto.descMonthly", { d: p.d, time: hm });
      default: return "cron " + a.cron;
    }
  }

  // --- list ---
  function showAutoList() {
    autoDetailId = null;
    ael2.listView.hidden = false;
    ael2.detailView.hidden = true;
  }

  async function loadAutomations() {
    try { const res = await api.listAutomations(); automations = (res && res.data) || []; } catch (e) { automations = []; }
    renderAutomations();
  }

  function autoMatches(a, filter) {
    switch (filter) {
      case "active": return a.enabled;
      case "once": case "recurring": case "api": return a.type === filter;
      default: return true;
    }
  }

  function renderAutomations() {
    const mine = ael2.owner.value === "mine";
    const q = ael2.search.value.trim().toLowerCase();
    const scoped = automations.filter(a => !mine || a.owner === currentUsername);
    ael2.tabs.querySelectorAll(".auto-tab").forEach(t => {
      t.classList.toggle("is-active", t.dataset.filter === autoFilter);
      t.querySelector("b").textContent = scoped.filter(a => autoMatches(a, t.dataset.filter)).length;
    });
    const shown = scoped.filter(a => autoMatches(a, autoFilter) && (!q || a.name.toLowerCase().includes(q)));
    ael2.rows.replaceChildren(...shown.map(autoRow));
    ael2.empty.hidden = shown.length > 0;
  }

  const canEditAuto = a => isAdmin || a.owner === currentUsername;

  function autoRow(a) {
    const tr = document.createElement("tr");

    const tdName = document.createElement("td");
    tdName.className = "c-name";
    tdName.appendChild(linkBtn(a.name, () => openAutomation(a.id)));

    const tdSched = document.createElement("td");
    tdSched.className = "auto-cell";
    tdSched.textContent = describeSchedule(a);
    if (a.type === "recurring") {
      const c = document.createElement("small");
      c.className = "auto-cron";
      c.textContent = a.cron + " · " + a.timezone;
      tdSched.appendChild(c);
    }

    const tdModel = document.createElement("td");
    tdModel.textContent = a.model || L("auto.modelDefault");
    const tdSource = document.createElement("td");
    tdSource.textContent = L(a.source === "chat" ? "auto.sourceChat" : "auto.sourceManual");

    const tdLast = document.createElement("td");
    if (a.lastRunAt) {
      tdLast.appendChild(document.createTextNode(formatDateTime(a.lastRunAt) + " "));
      if (a.lastStatus) tdLast.appendChild(taskStatusBadge(a.lastStatus));
    } else {
      tdLast.textContent = "—";
    }
    const tdNext = document.createElement("td");
    tdNext.textContent = a.nextRunAt ? formatDateTime(a.nextRunAt) : "—";
    const tdOwner = document.createElement("td");
    tdOwner.textContent = a.owner || "—";

    const tdOn = document.createElement("td");
    tdOn.appendChild(enabledSwitch(a));

    const tdActions = document.createElement("td");
    tdActions.className = "ctable__actions";
    if (canEditAuto(a)) {
      tdActions.append(
        linkBtn(L("auto.runNow"), () => runAutomationNow(a)),
        linkBtn(L("common.edit"), () => openAutoForm(a)),
        linkBtn(L("common.delete"), () => deleteAutomation(a), "link-btn--danger"));
    }
    tr.append(tdName, tdSched, tdModel, tdSource, tdLast, tdNext, tdOwner, tdOn, tdActions);
    return tr;
  }

  function taskStatusBadge(status) {
    const b = document.createElement("span");
    b.className = "status-badge is-" + status;
    b.textContent = status === "skipped" ? L("auto.skipped")
      : status === "deleted" ? L("auto.taskDeleted") : (TASK_STATUS_LABEL[status] || status);
    return b;
  }

  function enabledSwitch(a) {
    const label = document.createElement("label");
    label.className = "auto-switch";
    label.title = L(a.enabled ? "auto.pause" : "auto.resume");
    const input = document.createElement("input");
    input.type = "checkbox";
    input.checked = a.enabled;
    input.disabled = !canEditAuto(a);
    input.addEventListener("change", async () => {
      const res = await api.setAutomationEnabled(a.id, input.checked).catch(() => null);
      if (!res || !res.data) {
        input.checked = !input.checked;
        showToast((res && res.message) || L("auto.actionFailed"), true);
        return;
      }
      await refreshAutomationViews();
    });
    label.append(input, document.createElement("span"));
    return label;
  }

  async function refreshAutomationViews() {
    if (autoDetailId) await openAutomation(autoDetailId);
    await loadAutomations();
  }

  async function runAutomationNow(a) {
    const res = await api.runAutomation(a.id).catch(() => null);
    const run = res && res.data;
    if (!run) { showToast((res && res.message) || L("auto.actionFailed"), true); return; }
    showToast(run.taskId ? L("auto.started") : L("auto.notStarted", { why: run.skippedReason || "" }), !run.taskId);
    await refreshAutomationViews();
  }

  async function deleteAutomation(a) {
    if (!window.confirm(L("auto.deleteConfirm", { name: a.name }))) return;
    const res = await api.deleteAutomation(a.id).catch(() => null);
    if (!res || res.code !== "ok") { showToast((res && res.message) || L("auto.actionFailed"), true); return; }
    showAutoList();
    await loadAutomations();
  }

  ael2.tabs.addEventListener("click", e => {
    const t = e.target.closest(".auto-tab");
    if (t) { autoFilter = t.dataset.filter; renderAutomations(); }
  });
  ael2.search.addEventListener("input", renderAutomations);
  ael2.owner.addEventListener("change", renderAutomations);
  ael2.back.addEventListener("click", () => { showAutoList(); loadAutomations(); });

  // --- detail ---
  async function openAutomation(id, freshToken) {
    const res = await api.automationDetail(id).catch(() => null);
    const d = res && res.data;
    if (!d) { showAutoList(); return; }
    autoDetailId = id;
    const a = d.automation;
    ael2.listView.hidden = true;
    ael2.detailView.hidden = false;
    ael2.dTitle.textContent = a.name;
    ael2.dMeta.textContent = [L(a.source === "chat" ? "auto.sourceChat" : "auto.sourceManual"), a.owner,
      L(a.enabled ? "auto.enabled" : "auto.paused")].filter(Boolean).join(" · ");

    ael2.dActions.replaceChildren();
    ael2.dActions.appendChild(enabledSwitch(a));
    if (canEditAuto(a)) {
      const run = document.createElement("button");
      run.type = "button";
      run.className = "btn";
      run.textContent = L("auto.runNow");
      run.addEventListener("click", () => runAutomationNow(a));
      const edit = document.createElement("button");
      edit.type = "button";
      edit.className = "btn";
      edit.textContent = L("common.edit");
      edit.addEventListener("click", () => openAutoForm(a));
      ael2.dActions.append(run, edit);
      if (a.type === "api") {
        const regen = document.createElement("button");
        regen.type = "button";
        regen.className = "btn";
        regen.textContent = L("auto.regenToken");
        regen.addEventListener("click", async () => {
          if (!window.confirm(L("auto.regenConfirm"))) return;
          const r = await api.regenerateAutomationToken(a.id).catch(() => null);
          if (!r || !r.data) { showToast((r && r.message) || L("auto.actionFailed"), true); return; }
          openAutomation(a.id, r.data.apiToken);
        });
        ael2.dActions.appendChild(regen);
      }
    }

    // A token is only ever shown right after it is minted.
    ael2.tokenBox.hidden = !freshToken;
    if (freshToken) {
      ael2.tokenValue.textContent = freshToken;
      ael2.tokenCurl.textContent = "curl -X POST " + window.location.origin + a.triggerPath + " \\\n"
        + "  -H 'X-Automation-Token: " + freshToken + "' \\\n"
        + "  -H 'Content-Type: application/json' \\\n"
        + "  -d '{\"input\": \"" + L("auto.curlInput") + "\"}'";
    }

    const facts = [
      [L("auto.type"), L(a.type === "recurring" ? "auto.typeRecurringLong" : a.type === "once" ? "auto.typeOnceLong" : "auto.typeApi")],
      [L("auto.colSchedule"), describeSchedule(a) + (a.type === "recurring" ? "  (" + a.cron + ")" : "")],
      [L("auto.timezone"), a.timezone],
      [L("auto.colModel"), a.model || L("auto.modelDefault")],
      [L("auto.overlap"), L(a.overlap === "parallel" ? "auto.overlapParallel" : "auto.overlapSkip")],
      [L("auto.upcoming"), d.upcoming.length ? d.upcoming.map(formatDateTime).join("  ·  ") : "—"],
    ];
    if (a.type === "api") facts.push([L("auto.endpoint"), "POST " + a.triggerPath]);
    ael2.facts.replaceChildren(...facts.flatMap(([k, v]) => {
      const dt = document.createElement("dt");
      dt.textContent = k;
      const dd = document.createElement("dd");
      dd.textContent = v;
      return [dt, dd];
    }));
    ael2.prompt.textContent = a.prompt;

    ael2.runRows.replaceChildren(...d.runs.map(r => {
      const tr = document.createElement("tr");
      const tdAt = document.createElement("td");
      tdAt.textContent = formatDateTime(r.firedAt);
      const tdTrig = document.createElement("td");
      tdTrig.textContent = L("auto.trigger." + r.trigger);
      const tdTask = document.createElement("td");
      if (r.taskId) {
        tdTask.appendChild(linkBtn(L("auto.openTask"), async () => { setView("tasks"); await openTask(r.taskId); }));
      } else {
        tdTask.textContent = r.skippedReason || "—";
      }
      const tdStatus = document.createElement("td");
      tdStatus.appendChild(taskStatusBadge(r.status));
      tr.append(tdAt, tdTrig, tdTask, tdStatus);
      return tr;
    }));
    ael2.runsEmpty.hidden = d.runs.length > 0;
  }

  // --- form (drawer) ---
  async function loadAutoModelOptions(selected) {
    let options = [];
    try { const res = await api.taskAgents(); options = (res && res.data) || []; } catch (e) { /* default only */ }
    ael2.fModel.replaceChildren();
    const def = document.createElement("option");
    def.value = "";
    def.textContent = L("auto.modelDefault");
    ael2.fModel.appendChild(def);
    let found = !selected;
    options.forEach(o => {
      const ok = (o.models || []).filter(m => m.inferStatus === "ok"); // same rule as the server
      if (!ok.length) return;
      const g = document.createElement("optgroup");
      g.label = o.name;
      ok.forEach(m => {
        const opt = document.createElement("option");
        opt.value = m.id;
        opt.textContent = m.displayName && m.displayName !== m.id ? m.displayName + " (" + m.id + ")" : m.id;
        if (m.id === selected) found = true;
        g.appendChild(opt);
      });
      ael2.fModel.appendChild(g);
    });
    if (!found) { // keep a saved model selectable even if it stopped passing the check
      const opt = document.createElement("option");
      opt.value = selected;
      opt.textContent = selected + " · " + L("auto.modelUnhealthy");
      ael2.fModel.appendChild(opt);
    }
    ael2.fModel.value = selected || "";
  }

  function syncAutoForm() {
    const type = ael2.fType.value;
    const preset = ael2.fPreset.value;
    ael2.fRecurring.hidden = type !== "recurring";
    document.getElementById("afRunAtField").hidden = type !== "once";
    document.getElementById("afTimezoneField").hidden = type === "api";
    document.getElementById("afWeekdayField").hidden = preset !== "weekly";
    document.getElementById("afDayField").hidden = preset !== "monthly";
    document.getElementById("afHourField").hidden = preset === "hourly" || preset === "custom";
    document.getElementById("afMinuteField").hidden = preset === "custom";
    document.getElementById("afCronField").hidden = preset !== "custom";
    schedulePreview();
  }

  function autoFormBody() {
    const type = ael2.fType.value;
    return {
      name: ael2.fName.value.trim(),
      prompt: ael2.fPrompt.value,
      type,
      cron: type === "recurring" ? presetToCron() : null,
      runAt: type === "once" ? ael2.fRunAt.value || null : null,
      timezone: ael2.fTimezone.value,
      model: ael2.fModel.value,
      overlap: ael2.fOverlap.value,
    };
  }

  // Server-side preview: the same validation and time-zone math the scheduler uses.
  let previewTimer = null;
  function schedulePreview() {
    clearTimeout(previewTimer);
    previewTimer = setTimeout(async () => {
      const body = autoFormBody();
      if (body.type === "api") {
        ael2.fPreview.className = "field-hint auto-preview";
        ael2.fPreview.textContent = L("auto.apiHint");
        return;
      }
      if ((body.type === "once" && !body.runAt) || (body.type === "recurring" && !body.cron)) {
        ael2.fPreview.textContent = "";
        return;
      }
      const res = await api.previewAutomation(body).catch(() => null);
      const ok = res && Array.isArray(res.data);
      ael2.fPreview.className = "field-hint auto-preview" + (ok ? "" : " is-error");
      ael2.fPreview.textContent = ok
        ? (res.data.length ? L("auto.nextRuns", { times: res.data.map(formatDateTime).join("  ·  ") }) : "")
        : ((res && res.message) || L("auto.actionFailed"));
    }, 250);
  }

  function toLocalInput(iso, timezone) {
    if (!iso) return "";
    try {
      // en-CA formats as YYYY-MM-DD, HH:MM — close to what datetime-local wants.
      const parts = new Intl.DateTimeFormat("en-CA", {
        timeZone: timezone, year: "numeric", month: "2-digit", day: "2-digit",
        hour: "2-digit", minute: "2-digit", hourCycle: "h23",
      }).formatToParts(new Date(iso)).reduce((o, p) => (o[p.type] = p.value, o), {});
      return parts.year + "-" + parts.month + "-" + parts.day + "T" + parts.hour + ":" + parts.minute;
    } catch (e) { return ""; }
  }

  async function openAutoForm(a) {
    autoEditing = a || null;
    ael2.formTitle.textContent = L(a ? "auto.formEdit" : "auto.formNew");
    ael2.fError.hidden = true;
    ael2.fPrompt.value = a ? a.prompt : "";
    ael2.fName.value = a ? a.name : "";
    ael2.fType.value = a ? a.type : "recurring";
    ael2.fOverlap.value = a ? a.overlap : "skip";
    if (a && a.timezone) {
      if (![...ael2.fTimezone.options].some(o => o.value === a.timezone)) {
        const o = document.createElement("option");
        o.value = o.textContent = a.timezone;
        ael2.fTimezone.appendChild(o);
      }
      ael2.fTimezone.value = a.timezone;
    } else {
      ael2.fTimezone.value = ael2.fTimezone.options.length && [...ael2.fTimezone.options].some(o => o.value === "Asia/Shanghai")
        ? "Asia/Shanghai" : ael2.fTimezone.options[0].value;
    }
    const p = a && a.type === "recurring" ? cronToPreset(a.cron) : { preset: "daily", m: "0", h: "9" };
    ael2.fPreset.value = p.preset;
    ael2.fMinute.value = String(Number(p.m || 0));
    ael2.fHour.value = String(Number(p.h || 9));
    ael2.fWeekday.value = p.wd || "1";
    ael2.fDay.value = p.d || "1";
    ael2.fCron.value = a && a.type === "recurring" ? a.cron : "";
    ael2.fRunAt.value = a && a.type === "once" ? toLocalInput(a.runAt, a.timezone) : "";
    ael2.drawer.hidden = false;
    syncAutoForm();
    await loadAutoModelOptions(a ? a.model : null);
    ael2.fPrompt.focus();
  }

  function closeAutoForm() { ael2.drawer.hidden = true; clearTimeout(previewTimer); }

  ael2.addBtn.addEventListener("click", () => openAutoForm(null));
  ael2.drawer.querySelectorAll("[data-close]").forEach(x => x.addEventListener("click", closeAutoForm));
  [ael2.fType, ael2.fPreset].forEach(x => x.addEventListener("change", syncAutoForm));
  [ael2.fWeekday, ael2.fDay, ael2.fHour, ael2.fMinute, ael2.fTimezone, ael2.fRunAt]
    .forEach(x => x.addEventListener("change", schedulePreview));
  ael2.fCron.addEventListener("input", schedulePreview);

  ael2.form.addEventListener("submit", async e => {
    e.preventDefault();
    const body = autoFormBody();
    if (!body.prompt.trim()) {
      ael2.fError.textContent = L("auto.promptRequired");
      ael2.fError.hidden = false;
      return;
    }
    // Untouched one-off time on edit: leave it out so the server keeps it (and doesn't re-check "in the past").
    if (autoEditing && body.type === "once" && autoEditing.type === "once"
        && body.runAt === toLocalInput(autoEditing.runAt, autoEditing.timezone)
        && body.timezone === autoEditing.timezone) {
      body.runAt = null;
    }
    ael2.fSubmit.disabled = true;
    const res = await api.saveAutomation(autoEditing && autoEditing.id, body).catch(() => null);
    ael2.fSubmit.disabled = false;
    if (!res || !res.data) {
      ael2.fError.textContent = (res && res.message) || L("auto.actionFailed");
      ael2.fError.hidden = false;
      return;
    }
    closeAutoForm();
    await openAutomation(res.data.id, res.data.apiToken);
    loadAutomations();
  });

  // Guard first, then load the app only when authenticated. The OAuth-return handler
  // above may already have forced #connectors; otherwise restore the view from the hash
  // (now that admin visibility is known) so a refresh stays on the same page.
  initAuth().then(async ok => {
    if (!ok) return;
    await loadSystemSettings();  // before agents, so the agent panel shows the effective model
    loadAgents(true);
    setView(initialView());
  });
})();
