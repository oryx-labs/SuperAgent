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

  let activeSessionId = null;
  let streaming = false;

  // ---------- helpers ----------
  function clearEmptyState() { if (el.emptyState) el.emptyState.hidden = true; }
  function scrollToBottom() { el.messages.scrollTop = el.messages.scrollHeight; }

  // ---------- stored messages (with inline edit) ----------
  function renderStoredMessage(role, content, id) {
    clearEmptyState();
    const msg = document.createElement("div");
    msg.className = "msg " + (role === "user" ? "msg--user" : "msg--agent");
    if (id) msg.dataset.mid = id;
    const bubble = document.createElement("div");
    bubble.className = "bubble";
    bubble.textContent = content;
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
    const bubble = msgEl.querySelector(".bubble");
    const original = bubble.textContent;
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

    cancel.addEventListener("click", () => bubble.replaceChildren(document.createTextNode(original)));
    save.addEventListener("click", async () => {
      const val = ta.value;
      save.disabled = true;
      try {
        await api.editMessage(activeSessionId, mid, val);
        bubble.replaceChildren(document.createTextNode(val));
      } catch (e) {
        bubble.replaceChildren(document.createTextNode(original));
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

  // ---------- threads ----------
  async function loadSessions(autoOpen) {
    const res = await api.listSessions();
    const sessions = res.data || [];
    renderThreads(sessions);
    if (autoOpen && !activeSessionId && sessions.length > 0) selectSession(sessions[0].id);
  }

  function renderThreads(sessions) {
    el.threadList.innerHTML = "";
    sessions.forEach(s => {
      const li = document.createElement("li");
      li.className = "thread" + (s.id === activeSessionId ? " is-active" : "");
      li.dataset.id = s.id;

      const name = document.createElement("span");
      name.className = "thread__name";
      name.textContent = s.title || "New chat";

      const time = document.createElement("span");
      time.className = "thread__time";
      time.textContent = relativeTime(s.updatedAt);

      const actions = document.createElement("span");
      actions.className = "thread__actions";
      const rename = document.createElement("button");
      rename.className = "thread__act";
      rename.title = L("chat.rename");
      rename.textContent = "✎";
      rename.addEventListener("click", e => { e.stopPropagation(); beginRename(li, s.id, name); });
      const del = document.createElement("button");
      del.className = "thread__act";
      del.title = L("common.delete");
      del.textContent = "×";
      del.addEventListener("click", e => { e.stopPropagation(); deleteThread(li, s.id); });
      actions.append(rename, del);

      li.append(name, time, actions);
      li.addEventListener("click", () => selectSession(s.id));
      el.threadList.appendChild(li);
    });
  }

  function beginRename(li, id, nameEl) {
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
        try { await api.rename(id, value); span.textContent = value; }
        catch (e) { span.textContent = original; }
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

  async function deleteThread(li, id) {
    if (streaming) return;
    if (!window.confirm(L("chat.deleteConfirm"))) return;
    try { await api.remove(id); } catch (e) { /* ignore */ }
    li.remove();
    if (activeSessionId === id) {
      activeSessionId = null;
      el.messages.innerHTML = '<div class="empty-state"><p>' + L("chat.deletedState") + '</p></div>';
    }
  }

  function markActiveThread() {
    el.threadList.querySelectorAll(".thread").forEach(t =>
      t.classList.toggle("is-active", t.dataset.id === activeSessionId));
  }

  async function selectSession(id) {
    if (streaming) return;
    activeSessionId = id;
    markActiveThread();
    el.messages.innerHTML = "";
    const res = await api.getSession(id);
    const messages = (res.data && res.data.messages) || [];
    if (messages.length === 0) {
      el.messages.innerHTML = '<div class="empty-state"><p>' + L("chat.startState") + '</p></div>';
    }
    messages.forEach(m => renderStoredMessage(m.role, m.content, m.id));
    scrollToBottom();
  }

  async function newChat() {
    if (streaming) return;
    const res = await api.createSession();
    activeSessionId = res.data.id;
    el.messages.innerHTML = '<div class="empty-state"><p>' + L("chat.newCreated") + '</p></div>';
    el.emptyState = el.messages.querySelector(".empty-state");
    await loadSessions();
    markActiveThread();
    el.input.focus();
  }

  // ---------- send / stream / stop ----------
  async function sendMessage(text) {
    if (!activeSessionId) await newChat();
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
      loadSessions();
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
        handles.text.textContent += ev.payload;
        break;
      case "tool_use": {
        handles.tools.hidden = false;
        const chip = document.createElement("span");
        chip.className = "tool-chip";
        chip.textContent = "⚙ " + ev.payload;
        handles.tools.appendChild(chip);
        break;
      }
      case "result":
        if (ev.payload) handles.text.textContent = ev.payload;
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
  el.newChatBtn.addEventListener("click", newChat);

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

  el.input.addEventListener("keydown", e => {
    if (e.key === "Enter" && !e.shiftKey) {
      e.preventDefault();
      el.composer.requestSubmit();
    }
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
  const placeholderPane = document.querySelector('[data-pane="placeholder"]');
  const placeholderText = document.getElementById("placeholderText");
  const placeholderLabels = {
    automation: "placeholder.automation",
    tokens: "placeholder.tokens",
  };

  function setView(view) {
    navItems.forEach(a => a.classList.toggle("is-active", a.dataset.view === view));
    const isChat = view === "chat";
    const isConnectors = view === "connectors";
    const isTasks = view === "tasks";
    const isUsers = view === "users";
    chatPanes.forEach(p => { p.hidden = !isChat; });
    connectorsPane.hidden = !isConnectors;
    tasksPane.hidden = !isTasks;
    usersPane.hidden = !isUsers;
    placeholderPane.hidden = isChat || isConnectors || isTasks || isUsers;
    if (!placeholderPane.hidden) placeholderText.textContent = L(placeholderLabels[view] || "placeholder.default");
    if (isConnectors) loadConnectors();
    if (isUsers) loadUsers();
    if (isTasks) { showTaskList(); loadTasks(); } else { stopTaskPolling(); }
  }

  navItems.forEach(a => a.addEventListener("click", e => { e.preventDefault(); setView(a.dataset.view); }));

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
    history.replaceState({}, document.title, window.location.pathname);
  })();

  // ---------- tasks ----------
  Object.assign(api, {
    listTasks: () => fetch("/api/v1/tasks").then(r => r.json()),
    createTask: body => fetch("/api/v1/tasks", {
      method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body),
    }).then(r => r.json()),
    taskDetail: id => fetch("/api/v1/tasks/" + id).then(r => r.json()),
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
    submit: document.getElementById("tfSubmit"),
    back: document.getElementById("taskBackBtn"),
    dTitle: document.getElementById("taskDetailTitle"),
    dMeta: document.getElementById("taskDetailMeta"),
    dStatus: document.getElementById("taskDetailStatus"),
    stopBtn: document.getElementById("taskStopBtn"),
    deleteBtn: document.getElementById("taskDeleteBtn"),
    stream: document.getElementById("taskStream"),
    tabs: document.getElementById("taskTabs"),
    artifacts: document.getElementById("taskArtifacts"),
    artifactsEmpty: document.getElementById("taskArtifactsEmpty"),
    progress: document.querySelector("#taskDetailView .progress"),
    progressBar: document.getElementById("taskProgressBar"),
    progressPct: document.getElementById("taskProgressPct"),
    facts: document.getElementById("taskFacts"),
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

  async function loadTasks() {
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

    tr.append(tdTask, tdSource, tdCreated, tdSpend, tdStatus, tdActions);
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
    tel.stream.dataset.filter = "all";
    setActiveTab("all");
    loadArtifacts(t.id);
    if (isActiveStatus(t.status)) startTaskPolling(); else stopTaskPolling();
  }

  function setActiveTab(filter) {
    tel.tabs.querySelectorAll(".task-tab").forEach(b => b.classList.toggle("is-active", b.dataset.filter === filter));
  }
  tel.tabs.addEventListener("click", e => {
    const btn = e.target.closest(".task-tab");
    if (!btn) return;
    tel.stream.dataset.filter = btn.dataset.filter;
    setActiveTab(btn.dataset.filter);
  });

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

  function appendStreamItem(item) {
    const type = item.kind;
    if (type === "system") return; // bootstrap noise
    const mergeable = type === "text" || type === "thinking";
    if (mergeable && task.mergeType === type && task.mergeEl) {
      task.mergeEl.querySelector(".ev__body").textContent += item.payload || "";
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
    body.textContent = item.payload || "";
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

  function scrollStream() { tel.stream.scrollTop = tel.stream.scrollHeight; }

  function updateStatusUi(t) {
    tel.dStatus.className = "status-badge is-" + t.status;
    tel.dStatus.textContent = TASK_STATUS_LABEL[t.status] || t.status;
    tel.progress.className = "progress" + (t.status === "error" ? " is-error" : isActiveStatus(t.status) ? " is-running" : "");
    tel.progressBar.style.width = (t.progress || 0) + "%";
    tel.progressPct.textContent = (t.progress || 0) + "%";
    const busy = isActiveStatus(t.status);
    tel.steerInput.disabled = busy;
    tel.steerBtn.disabled = busy;
    tel.steerInput.placeholder = busy ? L("tasks.steerBusy") : L("tasks.steer");
    tel.stopBtn.disabled = !busy;
  }

  function renderFacts(t) {
    const facts = [
      [L("tasks.created"), formatDateTime(t.createdAt)],
      [L("tasks.by"), t.createdBy || "—"],
      [L("tasks.model"), t.model || "default"],
      [L("tasks.spend"), "$" + Number(t.spend || 0).toFixed(2)],
      [L("tasks.elapsed"), formatElapsed(t)],
    ];
    if (t.error) facts.push([L("tasks.error"), t.error]);
    tel.facts.innerHTML = "";
    facts.forEach(([k, v]) => {
      const dt = document.createElement("dt"); dt.textContent = k;
      const dd = document.createElement("dd"); dd.textContent = v;
      tel.facts.append(dt, dd);
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
      if (!isActiveStatus(t.status)) { stopTaskPolling(); loadArtifacts(id); }
    } catch (e) { /* ignore transient poll errors */ }
  }

  // steering
  tel.steerForm.addEventListener("submit", async e => {
    e.preventDefault();
    const text = tel.steerInput.value.trim();
    if (!text || !task.id) return;
    tel.steerInput.value = "";
    autoGrowSteer();
    appendStreamItem({ kind: "cmd", payload: text });
    try { await api.taskMessage(task.id, text); } catch (err) { /* ignore */ }
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
  function openTaskModal(prefill) {
    tel.title.value = (prefill && prefill.title) || "";
    tel.brief.value = (prefill && prefill.brief) || "";
    tel.repo.value = "";
    tel.branch.value = "";
    tel.modal.hidden = false;
    tel.title.focus();
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
    };
    if (!body.title || !body.brief) return;
    tel.submit.disabled = true;
    try {
      const res = await api.createTask(body);
      closeTaskModal();
      await loadTasks();
      if (res && res.data) openTask(res.data.id);
    } catch (err) { /* ignore */ } finally { tel.submit.disabled = false; }
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
    adminNav.hidden = !showAdmin;
    adminSep.hidden = !showAdmin;
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
  initAuth().then(ok => { if (ok) loadSessions(true); });
})();
