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
    createAgent: body =>
      fetch("/api/v1/agents", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(body),
      }).then(r => r.json()),
    agentAvatars: () => fetch("/api/v1/agents/avatars").then(r => r.json()),
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
    status: document.getElementById("progressStatus"),
    search: document.getElementById("searchInput"),
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

  // ---------- stored messages (with inline edit) ----------
  function renderStoredMessage(role, content, id) {
    clearEmptyState();
    const msg = document.createElement("div");
    msg.className = "msg " + (role === "user" ? "msg--user" : "msg--agent");
    if (id) msg.dataset.mid = id;
    const bubble = document.createElement("div");
    bubble.className = "bubble";
    setBubbleContent(bubble, role, content);
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
        await api.editMessage(activeSessionId, mid, val);
        setBubbleContent(bubble, role, val);
      } catch (e) {
        setBubbleContent(bubble, role, original);
      }
    });
  }

  // ---------- streaming bubbles ----------
  function addUserBubble(text) {
    clearEmptyState();
    const msg = document.createElement("div");
    msg.className = "msg msg--user";
    const bubble = document.createElement("div");
    bubble.className = "bubble";
    bubble.textContent = text;
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
          await api.updateAgent(agent.id, { name: value, persona: agent.persona, model: agent.model });
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
    document.getElementById("agentInfoPersona").textContent = agent.persona || "—";
    const model = agent.model || systemSettings.agentDefaultModel || "default";
    document.getElementById("agentInfoModel").textContent = model;
    renderComposerModel(agent);
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
        name: agent.name, avatar: agent.avatar, persona: agent.persona, model: composerModel.value || null,
      });
      activeAgent = res.data;
      renderAgentPanel(res.data);
    } catch (e) {
      showToast(L("chat.modelSaveFailed"), true);
      composerModel.value = agent.model || "";
    }
  });

  async function selectSession(id) {
    activeSessionId = id;
    el.messages.innerHTML = "";
    const res = await api.getSession(id);
    const messages = (res.data && res.data.messages) || [];
    if (messages.length === 0) {
      el.messages.innerHTML = '<div class="empty-state"><p>' + L("chat.startState") + '</p></div>';
    }
    messages.forEach(m => renderStoredMessage(m.role, m.content, m.id));
    scrollToBottom();
  }

  // ---------- send / stream / stop ----------
  async function sendMessage(text) {
    if (!activeSessionId) { openAgentModal(); return; }
    addUserBubble(text);
    const handles = addAssistantBubble();
    setStreaming(true);
    el.status.textContent = L("chat.statusRunning");
    try {
      const resp = await fetch("/api/v1/sessions/" + activeSessionId + "/messages", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ content: text }),
      });
      await consumeSse(resp, handles);
    } catch (e) {
      handles.text.classList.remove("is-streaming");
      handles.text.textContent = L("chat.connFailed", { msg: e.message });
    } finally {
      handles.text.classList.remove("is-streaming");
      setStreaming(false);
      el.status.textContent = L("chat.statusIdle");
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
  }
  function closeAgentModal() { ael.modal.hidden = true; }

  ael.modal.querySelectorAll("[data-close]").forEach(x => x.addEventListener("click", closeAgentModal));
  ael.form.addEventListener("submit", async e => {
    e.preventDefault();
    const name = ael.name.value.trim();
    if (!name) return;
    // No model: new agents chat with the admin-set system default (System Settings).
    const body = { name, persona: ael.persona.value.trim(), model: editingAgentId ? editingAgentModel : null,
      avatar: chosenAvatar };
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

  el.composer.addEventListener("submit", e => {
    e.preventDefault();
    if (streaming) { stopGeneration(); return; }
    const text = el.input.value.trim();
    if (!text) return;
    el.input.value = "";
    autoGrow();
    if (text.startsWith("/task")) { handleTaskCommand(text.slice(5).trim()); return; }
    sendMessage(text);
  });

  el.input.addEventListener("input", autoGrow);

  el.search.addEventListener("input", () => {
    const q = el.search.value.toLowerCase();
    el.threadList.querySelectorAll(".thread").forEach(t => {
      const name = t.querySelector(".thread__name").textContent.toLowerCase();
      t.hidden = q && !name.includes(q);
    });
  });

  // ---------- views (sidebar routing) ----------
  const navItems = Array.from(document.querySelectorAll(".nav__item[data-view]"));
  const chatPanes = Array.from(document.querySelectorAll('[data-pane="chat"]'));
  const connectorsPane = document.querySelector('[data-pane="connectors"]');
  const tasksPane = document.querySelector('[data-pane="tasks"]');
  const usersPane = document.querySelector('[data-pane="users"]');
  const modelsPane = document.querySelector('[data-pane="models"]');
  const settingsPane = document.querySelector('[data-pane="settings"]');
  const placeholderPane = document.querySelector('[data-pane="placeholder"]');
  const placeholderText = document.getElementById("placeholderText");
  const placeholderLabels = {
    automation: "placeholder.automation",
  };

  // Declared before setView: setView can run during load (OAuth return) and calls stopModelPolling.
  let modelPollTimer = null;
  let modelDetailId = null;

  // View state lives in the URL hash (#tasks, #connectors, …) so a refresh or
  // back/forward restores the same view. Admin-only views fall back to the default
  // for non-admins. See initialView().
  const KNOWN_VIEWS = new Set(["chat", "tasks", "automation", "connectors", "models", "users", "settings"]);
  const ADMIN_VIEWS = new Set(["connectors", "models", "users", "settings"]);
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
    chatPanes.forEach(p => { p.hidden = !isChat; });
    connectorsPane.hidden = !isConnectors;
    tasksPane.hidden = !isTasks;
    usersPane.hidden = !isUsers;
    modelsPane.hidden = !isModels;
    settingsPane.hidden = !isSettings;
    placeholderPane.hidden = isChat || isConnectors || isTasks || isUsers || isModels || isSettings;
    if (!placeholderPane.hidden) placeholderText.textContent = L(placeholderLabels[view] || "placeholder.default");
    if (isConnectors) loadConnectors();
    if (isUsers) loadUsers();
    if (isSettings) loadSettingsForm();
    if (isTasks) { showTaskList(); loadTasks(); } else { stopTaskPolling(); }
    if (isModels) { showModelList(); startModelPolling(); } else { stopModelPolling(); }
  }

  navItems.forEach(a => a.addEventListener("click", e => { e.preventDefault(); setView(a.dataset.view); }));

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
    actions.appendChild(cell);

    tr.append(name, type, status, method, actions);
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
    listTasks: () => fetch("/api/v1/tasks").then(r => r.json()),
    createTask: body => fetch("/api/v1/tasks", {
      method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body),
    }).then(r => r.json()),
    taskDetail: id => fetch("/api/v1/tasks/" + id).then(r => r.json()),
    taskAgents: () => fetch("/api/v1/tasks/agents").then(r => r.json()),
    taskStatus: id => fetch("/api/v1/tasks/" + id + "/status").then(r => r.json()),
    taskEvents: (id, offset) => fetch("/api/v1/tasks/" + id + "/events?offset=" + offset).then(r => r.json()),
    taskArtifacts: id => fetch("/api/v1/tasks/" + id + "/artifacts").then(r => r.json()),
    taskMessage: (id, content) => fetch("/api/v1/tasks/" + id + "/messages", {
      method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ content }),
    }),
    stopTask: id => fetch("/api/v1/tasks/" + id + "/stop", { method: "POST" }),
    deleteTask: id => fetch("/api/v1/tasks/" + id, { method: "DELETE" }),
  });

  const TASK_STATUS_LABEL = {
    pending: L("status.pending"), running: L("status.running"), needs_confirmation: L("status.needs_confirmation"),
    done: L("status.done"), error: L("status.error"), stopped: L("status.stopped"),
  };
  const TASK_SOURCE_LABEL = { web_chat: L("tasks.srcWebChat"), task_form: L("tasks.srcTaskForm") };

  const tel = {
    listView: document.getElementById("taskListView"),
    detailView: document.getElementById("taskDetailView"),
    rows: document.getElementById("taskRows"),
    empty: document.getElementById("tasksEmpty"),
    newBtn: document.getElementById("newTaskBtn"),
    modal: document.getElementById("taskModal"),
    form: document.getElementById("taskForm"),
    title: document.getElementById("tfTitle"),
    brief: document.getElementById("tfBrief"),
    repo: document.getElementById("tfRepo"),
    branch: document.getElementById("tfBranch"),
    kind: document.getElementById("tfKind"),
    agent: document.getElementById("tfAgent"),
    model: document.getElementById("tfModel"),
    modelHint: document.getElementById("tfModelHint"),
    submit: document.getElementById("tfSubmit"),
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

  let agentOptions = [];  // [{key, name, models:[ProviderModelDto]}] from /tasks/agents

  async function loadTasks() {
    if (!agentOptions.length) await loadAgentOptions();
    let list = [];
    try { const res = await api.listTasks(); list = (res && res.data) || []; } catch (e) { /* ignore */ }
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
    (d.messages || []).forEach(m => { if (m.role === "user") timeline.push({ kind: "cmd", payload: m.content, at: m.createdAt }); });
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
      li.appendChild(link);
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
    const body = document.createElement("div");
    body.className = "ev__body";
    setEvBody(body, type, item.payload || "");
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
  tel.steerForm.addEventListener("submit", async e => {
    e.preventDefault();
    const text = tel.steerInput.value.trim();
    if (!text || !task.id) return;
    let res;
    try { res = await api.taskMessage(task.id, text); } catch (err) { res = null; }
    if (!res || !res.ok) {
      showToast(L("tasks.steerFailed"), true); // keep the text so it can be resent
      return;
    }
    tel.steerInput.value = "";
    autoGrowSteer();
    setTaskTab("run");
    appendStreamItem({ kind: "cmd", payload: text });
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
  tel.stopBtn.addEventListener("click", async () => { if (task.id) { try { await api.stopTask(task.id); } catch (e) { /* ignore */ } } });
  tel.deleteBtn.addEventListener("click", async () => {
    if (!task.id || !window.confirm(L("tasks.deleteConfirm2"))) return;
    const id = task.id;
    try { await api.deleteTask(id); } catch (e) { /* ignore */ }
    showTaskList();
    await loadTasks();
  });

  // create

  async function loadAgentOptions() {
    try { const res = await api.taskAgents(); agentOptions = (res && res.data) || []; } catch (e) { agentOptions = []; }
    return agentOptions;
  }

  function agentLabel(key) {
    const opt = agentOptions.find(a => a.key === key);
    return opt ? opt.name : (key || "—");
  }

  function fillAgentSelect() {
    tel.agent.innerHTML = "";
    agentOptions.forEach(a => {
      const o = document.createElement("option");
      o.value = a.key;
      o.textContent = a.name;
      tel.agent.appendChild(o);
    });
    fillModelSelect();
  }

  // Native models first, then models a gateway serves through this CLI (listed, not verified to run).
  function fillModelSelect() {
    const agent = agentOptions.find(a => a.key === tel.agent.value);
    const models = (agent && agent.models) || [];
    tel.model.innerHTML = "";
    const def = document.createElement("option");
    def.value = "";
    def.textContent = L("tasks.modelDefault");
    tel.model.appendChild(def);
    const addGroup = (label, list) => {
      if (!list.length) return;
      const g = document.createElement("optgroup");
      g.label = label;
      list.forEach(m => {
        const o = document.createElement("option");
        o.value = m.id;
        o.textContent = m.displayName && m.displayName !== m.id ? m.id + " · " + m.displayName : m.id;
        g.appendChild(o);
      });
      tel.model.appendChild(g);
    };
    addGroup(L("tasks.modelsNative"), models.filter(m => m.nativeModel));
    addGroup(L("tasks.modelsNonNative"), models.filter(m => !m.nativeModel));
    tel.modelHint.hidden = models.length > 0;
    tel.modelHint.textContent = models.length ? "" : L("tasks.modelsMissing");
  }
  tel.agent.addEventListener("change", fillModelSelect);

  async function openTaskModal(prefill) {
    tel.title.value = (prefill && prefill.title) || "";
    tel.brief.value = (prefill && prefill.brief) || "";
    tel.repo.value = "";
    tel.branch.value = "";
    tel.modal.hidden = false;
    tel.title.focus();
    await loadAgentOptions();
    fillAgentSelect();
  }
  function closeTaskModal() { tel.modal.hidden = true; }
  tel.newBtn.addEventListener("click", () => openTaskModal(null));
  tel.modal.querySelectorAll("[data-close]").forEach(x => x.addEventListener("click", closeTaskModal));
  tel.form.addEventListener("submit", async e => {
    e.preventDefault();
    const body = {
      title: tel.title.value.trim(),
      brief: tel.brief.value.trim(),
      source: "task_form",
      repo: tel.repo.value.trim() || null,
      branch: tel.branch.value.trim() || null,
      kind: tel.kind ? tel.kind.value : "auto",
      agentType: tel.agent.value || null,
      model: tel.model.value || null,
    };
    if (!body.title || !body.brief) return;
    tel.submit.disabled = true;
    try {
      const res = await api.createTask(body);
      if (!res || !res.data) {
        showToast((res && res.message) || L("tasks.createFailed"), true);
        return;
      }
      closeTaskModal();
      await loadTasks();
      openTask(res.data.id);
    } catch (err) {
      showToast(L("tasks.createFailed"), true);
    } finally { tel.submit.disabled = false; }
  });

  // /task slash-command from the chat composer
  async function handleTaskCommand(rest) {
    setView("tasks");
    if (!rest) { openTaskModal(null); return; }
    const title = rest.split("\n")[0].slice(0, 48);
    try {
      const res = await api.createTask({ title, brief: rest, source: "web_chat" });
      await loadTasks();
      if (res && res.data) openTask(res.data.id);
    } catch (e) { /* ignore */ }
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

  async function initAuth() {
    let res;
    try { res = await api.me(); } catch (e) { window.location.href = "/login"; return false; }
    if (res.status === 401) { window.location.href = "/login"; return false; }
    const me = (await res.json()).data;
    document.getElementById("currentUser").textContent = me.displayName || me.username;
    // Admin-only sections: hide for non-admins.
    const showAdmin = !!me.admin;
    isAdmin = showAdmin;
    adminNav.hidden = !showAdmin;
    adminSep.hidden = !showAdmin;
    document.getElementById("connectorsNav").hidden = !showAdmin; // its API is admin-only
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
