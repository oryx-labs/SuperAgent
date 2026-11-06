// SuperAgent UI — Sidebar routing between views.
// Classic script sharing one global scope with the others; index.html loads them in order.
"use strict";

// ---------- views (sidebar routing) ----------
const navItems = Array.from(document.querySelectorAll(".nav__item[data-view]"));
const chatPanes = Array.from(document.querySelectorAll('[data-pane="chat"]'));
const connectorsPane = document.querySelector('[data-pane="connectors"]');
const tasksPane = document.querySelector('[data-pane="tasks"]');
const usersPane = document.querySelector('[data-pane="users"]');
const modelsPane = document.querySelector('[data-pane="models"]');
const settingsPane = document.querySelector('[data-pane="settings"]');
const integrationsPane = document.querySelector('[data-pane="integrations"]');
const fileStoragePane = document.querySelector('[data-pane="file-storage"]');
const placeholderPane = document.querySelector('[data-pane="placeholder"]');
const automationPane = document.querySelector('[data-pane="automation"]');
const analyticsPane = document.querySelector('[data-pane="analytics"]');
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
const KNOWN_VIEWS = new Set(["todo", "chat", "files", "tasks", "messages", "skills", "automation", "connectors", "models",
  "users", "settings", "integrations", "file-storage", "analytics"]);
// The "Workspace" dropdown's pages, and the admin pages (shown with the admin sidebar).
const WORKSPACE_VIEWS = new Set(["skills", "connectors"]);
const ADMIN_VIEWS = new Set(["settings", "integrations", "file-storage", "analytics", "models", "users"]);
const DEFAULT_VIEW = "todo"; // the to-do list is home: the first thing to look at each day
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
  // Scheduled tasks are a tab of Tasks, so "Tasks" stays lit for both.
  const navView = view === "automation" ? "tasks" : view;
  navItems.forEach(a => a.classList.toggle("is-active", a.dataset.view === navView));
  // Top menu: the dropdown and "Admin" light up for any of their pages; the dropdown also says which one.
  const inWorkspace = WORKSPACE_VIEWS.has(view);
  const inAdmin = ADMIN_VIEWS.has(view);
  document.getElementById("workspaceMenu").classList.toggle("is-active", inWorkspace);
  const wsItem = inWorkspace ? navItems.find(a => a.dataset.view === view) : null;
  document.getElementById("workspaceCurrent").textContent = wsItem ? " · " + wsItem.textContent.trim() : "";
  document.getElementById("adminTopNav").classList.toggle("is-active", inAdmin);
  document.getElementById("adminSide").hidden = !inAdmin;
  document.querySelector(".layout").classList.toggle("layout--admin", inAdmin);
  closeWorkspaceMenu();
  const isChat = view === "chat";
  const isConnectors = view === "connectors";
  const isTasks = view === "tasks";
  const isUsers = view === "users";
  const isModels = view === "models";
  const isSettings = view === "settings";
  const isIntegrations = view === "integrations";
  const isFileStorage = view === "file-storage";
  const isAutomation = view === "automation";
  const isSkills = view === "skills";
  const isInbox = view === "messages"; // the message stream, opened from the SuperAgent panel
  const isAnalytics = view === "analytics";
  const isTodo = view === "todo";
  const isFiles = view === "files";
  todoPane.hidden = !isTodo;
  filesPane.hidden = !isFiles;
  if (!flel.drawer.hidden) closeFile(); // a preview belongs to the page it was opened from
  chatPanes.forEach(p => { p.hidden = !isChat; });
  analyticsPane.hidden = !isAnalytics;
  automationPane.hidden = !isAutomation;
  connectorsPane.hidden = !isConnectors;
  tasksPane.hidden = !isTasks;
  usersPane.hidden = !isUsers;
  modelsPane.hidden = !isModels;
  settingsPane.hidden = !isSettings;
  integrationsPane.hidden = !isIntegrations;
  fileStoragePane.hidden = !isFileStorage;
  skPane.hidden = !isSkills;
  if (!isSkills && !skel.drawer.hidden) closeSkillDrawer();
  ibPane.hidden = !isInbox;
  placeholderPane.hidden = isChat || isConnectors || isTasks || isUsers || isModels || isSettings || isIntegrations
    || isFileStorage || isAutomation || isSkills || isInbox || isAnalytics || isTodo || isFiles;
  if (!placeholderPane.hidden) placeholderText.textContent = L(placeholderLabels[view] || "placeholder.default");
  if (isConnectors) loadConnectors();
  if (isUsers) loadUsers();
  if (isSettings) loadSettingsForm();
  if (isIntegrations) loadIntegrations();
  if (isFileStorage) loadFileStorage();
  if (isSkills) loadSkills();
  if (isInbox) loadInbox();
  if (isAnalytics) loadAnalytics();
  if (isTodo) loadTodos();
  if (isFiles) loadFiles();
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
document.addEventListener("click", e => {
  if (!userMenu.contains(e.target)) closeUserMenu();
  if (!workspaceMenu.contains(e.target)) closeWorkspaceMenu();
});

// The "Workspace" dropdown in the top menu: same behaviour as the account menu (hover, or click on touch).
const workspaceMenu = document.getElementById("workspaceMenu");
const workspaceBtn = document.getElementById("workspaceBtn");
function closeWorkspaceMenu() {
  workspaceMenu.classList.remove("is-open");
  workspaceBtn.setAttribute("aria-expanded", "false");
  if (workspaceMenu.contains(document.activeElement)) document.activeElement.blur();
}
workspaceBtn.addEventListener("click", e => {
  e.stopPropagation();
  const open = !workspaceMenu.classList.contains("is-open");
  workspaceMenu.classList.toggle("is-open", open);
  workspaceBtn.setAttribute("aria-expanded", String(open));
});

// "Admin" opens the first admin page; the admin sidebar takes it from there.
document.getElementById("adminTopNav").addEventListener("click", e => { e.preventDefault(); setView("users"); });

// The logo goes home: the Agent page with the user's SuperAgent open.
document.getElementById("brandHome").addEventListener("click", e => {
  e.preventDefault();
  setView("chat");
  const home = agents.find(a => a.kind === "super") || agents[0];
  if (home && home.id !== activeAgentId) selectAgent(home);
});
document.addEventListener("keydown", e => { if (e.key === "Escape") { closeUserMenu(); closeWorkspaceMenu(); } });

// Back/forward (and manual hash edits) re-drive the view from the URL.
window.addEventListener("hashchange", () => setView(initialView()));
