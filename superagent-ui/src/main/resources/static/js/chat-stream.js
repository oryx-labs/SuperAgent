// SuperAgent UI — Sending a chat message and consuming the SSE stream; stop.
// Classic script sharing one global scope with the others; index.html loads them in order.
"use strict";

// ---------- send / stream / stop ----------
async function sendMessage(text, images, imageUrls) {
  if (!activeSessionId) { openAgentModal(); return; }
  const userMsg = addUserBubble(text, imageUrls);
  const handles = addAssistantBubble();
  setStreaming(true);
  try {
    const resp = await fetch("/api/v1/sessions/" + activeSessionId + "/messages", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ content: text, images: images || [] }),
    });
    if (resp.status === 409) {
      // Another member's turn is running: nothing was sent, so give the text back.
      const err = await resp.json().catch(() => null);
      userMsg.remove();
      handles.text.closest(".msg").remove();
      el.input.value = text;
      showToast((err && err.message) || L("co.busy"), true);
      return;
    }
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
