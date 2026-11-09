// SuperAgent UI — An agent's prompts drawer: everything sent to it, across all its sessions.
// Classic script sharing one global scope with the others; index.html loads them in order.
"use strict";

// Newest first, one line each (click to read it whole); "Download all" saves them as one Markdown file, oldest first.
const pel = {
  drawer: document.getElementById("agentPromptDrawer"),
  title: document.getElementById("agentPromptTitle"),
  list: document.getElementById("agentPromptList"),
  count: document.getElementById("agentPromptCount"),
  download: document.getElementById("agentPromptDownload"),
  empty: document.getElementById("agentPromptEmpty"),
};
let promptAgent = null;
let agentPrompts = [];

async function openAgentPrompts() {
  if (!activeAgent) return;
  promptAgent = activeAgent;
  pel.title.textContent = L("log.promptsTitle", { name: promptAgent.name });
  pel.list.replaceChildren();
  pel.count.textContent = "";
  pel.empty.hidden = true;
  pel.drawer.hidden = false;
  const agent = promptAgent;
  const res = await api.agentHistory(agent.id).catch(() => null);
  if (promptAgent !== agent) return;
  agentPrompts = flattenHistory((res && res.data) || []).filter(m => m.role === "user" && (m.content || "").trim());
  pel.count.textContent = L("log.promptsCount", { n: agentPrompts.length });
  pel.download.disabled = !agentPrompts.length;
  pel.list.replaceChildren(...agentPrompts.slice().reverse().map(promptLine));
  pel.list.hidden = !agentPrompts.length;
  pel.empty.hidden = agentPrompts.length > 0;
}

function promptLine(m) {
  const li = document.createElement("li");
  const line = document.createElement("button");
  line.type = "button";
  line.className = "log-line prompt-line has-detail";
  const dot = document.createElement("span");
  dot.className = "log-dot";
  const time = document.createElement("time");
  time.className = "log-time";
  time.textContent = formatDateTime(m.createdAt).slice(5);
  const who = document.createElement("span");
  who.className = "log-cat";
  who.textContent = m.authorName || personName(m.author) || L("co.me");
  const text = document.createElement("span");
  text.className = "log-summary prompt-line__text";
  text.textContent = m.content.replace(/\s+/g, " ").trim();
  line.append(dot, time, who, text);
  const full = document.createElement("pre");
  full.className = "log-detail";
  full.textContent = m.content;
  full.hidden = true;
  line.addEventListener("click", () => { full.hidden = !full.hidden; line.classList.toggle("is-open", !full.hidden); });
  li.append(line, full);
  return li;
}

pel.download.addEventListener("click", () => {
  if (!agentPrompts.length || !promptAgent) return;
  const body = agentPrompts.map((m, i) => "## " + (i + 1) + ". " + formatDateTime(m.createdAt)
    + (m.authorName ? " · " + m.authorName : "") + "\n\n" + m.content.trim() + "\n").join("\n");
  const blob = new Blob(["# " + L("log.promptsFileTitle", { name: promptAgent.name }) + "\n\n" + body],
    { type: "text/markdown;charset=utf-8" });
  const a = Object.assign(document.createElement("a"), {
    href: URL.createObjectURL(blob),
    download: (promptAgent.name || "agent").replace(/[\\/:*?"<>|]+/g, "_") + "-prompts.md",
  });
  document.body.appendChild(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(a.href), 1000);
});

document.getElementById("agentPromptsBtn").addEventListener("click", openAgentPrompts);
pel.drawer.querySelectorAll("[data-close]").forEach(x => x.addEventListener("click", () => { pel.drawer.hidden = true; }));
