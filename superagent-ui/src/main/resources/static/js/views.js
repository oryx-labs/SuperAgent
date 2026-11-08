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
const KNOWN_VIEWS = new Set(["todo", "chat", "files", "tasks", "messages", "projects", "skills", "automation", "connectors", "models",
  "users", "settings", "integrations", "file-storage", "analytics", "usage", "company-usage", "model-prices"]);
// The "Workspace" dropdown's pages, and the admin pages (shown with the admin sidebar).
const WORKSPACE_VIEWS = new Set(["projects", "skills", "connectors", "usage"]);
const ADMIN_VIEWS = new Set(["settings", "integrations", "file-storage", "analytics", "company-usage", "models", "model-prices", "users"]);
const DEFAULT_VIEW = "todo"; // the to-do list is home: the first thing to look at each day
let isAdmin = false;

function initialView() {
  const view = (window.location.hash || "").replace(/^#/, "");
  if (!KNOWN_VIEWS.has(view)) return DEFAULT_VIEW;
  if (ADMIN_VIEWS.has(view) && !isAdmin) return DEFAULT_VIEW;
  return view;
}

function setView(view) {
  if (view === "messages") { // the message stream is a tab of the to-do page (old links still land on it)
    todoFilter = "messages";
    view = "todo";
  }
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
  document.querySelector(".layout").classList.toggle("layout--admin-collapsed", inAdmin && adminSideCollapsed);
  if (inAdmin) renderNavGroups();
  closeWorkspaceMenu();
  const isChat = view === "chat";
  const isConnectors = view === "connectors";
  const isTasks = view === "tasks";
  const isUsers = view === "users";
  const isModels = view === "models";
  const isModelPrices = view === "model-prices";
  const isSettings = view === "settings";
  const isIntegrations = view === "integrations";
  const isFileStorage = view === "file-storage";
  const isAutomation = view === "automation";
  const isSkills = view === "skills";
  const isProjects = view === "projects";
  const isAnalytics = view === "analytics";
  const isUsage = view === "usage" || view === "company-usage"; // one page: mine, or (admins) the company's
  const isTodo = view === "todo";
  const isFiles = view === "files";
  todoPane.hidden = !isTodo;
  filesPane.hidden = !isFiles;
  if (!flel.drawer.hidden) closeFile(); // a preview belongs to the page it was opened from
  chatPanes.forEach(p => { p.hidden = !isChat; });
  analyticsPane.hidden = !isAnalytics;
  document.querySelector('[data-pane="usage"]').hidden = !isUsage;
  automationPane.hidden = !isAutomation;
  connectorsPane.hidden = !isConnectors;
  tasksPane.hidden = !isTasks;
  usersPane.hidden = !isUsers;
  modelsPane.hidden = !isModels;
  document.querySelector('[data-pane="model-prices"]').hidden = !isModelPrices;
  settingsPane.hidden = !isSettings;
  integrationsPane.hidden = !isIntegrations;
  fileStoragePane.hidden = !isFileStorage;
  skPane.hidden = !isSkills;
  projPane.hidden = !isProjects;
  if (!isSkills && !skel.drawer.hidden) closeSkillDrawer();
  placeholderPane.hidden = isChat || isConnectors || isTasks || isUsers || isModels || isModelPrices || isSettings || isIntegrations
    || isFileStorage || isAutomation || isSkills || isProjects || isAnalytics || isTodo || isFiles || isUsage;
  if (!placeholderPane.hidden) placeholderText.textContent = L(placeholderLabels[view] || "placeholder.default");
  if (isConnectors) loadConnectors();
  if (isUsers) loadUsers();
  if (isSettings) loadSettingsForm();
  if (isIntegrations) loadIntegrations();
  if (isFileStorage) loadFileStorage();
  if (isSkills) loadSkills();
  if (isProjects) loadProjects();
  if (isAnalytics) loadAnalytics();
  if (isUsage) { usScope = view === "company-usage" ? "all" : "me"; loadUsage(); }
  if (isTodo) loadTodos();
  if (isFiles) loadFiles();
  if (isAutomation) { showAutoList(); loadAutomations(); }
  if (isTasks) { showTaskList(); loadTasks(); } else { stopTaskPolling(); }
  if (isModels) { showModelList(); startModelPolling(); } else { stopModelPolling(); }
  if (isModelPrices) mpLoad();
}

// The admin sidebar folds down to a narrow strip (open by default); the choice is remembered on this browser.
const ADMIN_SIDE_KEY = "superagent.adminSideCollapsed";
let adminSideCollapsed = (() => { try { return localStorage.getItem(ADMIN_SIDE_KEY) === "1"; } catch (e) { return false; } })();
const adminSideToggle = document.getElementById("adminSideToggle");
function renderAdminSide() {
  const layout = document.querySelector(".layout");
  layout.classList.toggle("layout--admin-collapsed", layout.classList.contains("layout--admin") && adminSideCollapsed);
  adminSideToggle.setAttribute("aria-expanded", String(!adminSideCollapsed));
  adminSideToggle.title = L(adminSideCollapsed ? "nav.expand" : "nav.collapse");
  adminSideToggle.firstElementChild.textContent = adminSideCollapsed ? "»" : "«";
}
adminSideToggle.addEventListener("click", () => {
  adminSideCollapsed = !adminSideCollapsed;
  try { localStorage.setItem(ADMIN_SIDE_KEY, adminSideCollapsed ? "1" : "0"); } catch (e) { /* not remembered */ }
  renderAdminSide();
});
renderAdminSide();

// Each group in the admin sidebar folds its sub-items; the page on screen keeps its group open.
const NAV_GROUPS_KEY = "superagent.navGroupsFolded";
const foldedGroups = new Set((() => { try { return JSON.parse(localStorage.getItem(NAV_GROUPS_KEY) || "[]"); } catch (e) { return []; } })());
function renderNavGroups() {
  document.querySelectorAll(".nav__group").forEach(g => {
    const holdsCurrent = !!g.querySelector(".nav__item.is-active");
    const folded = foldedGroups.has(g.dataset.group) && !holdsCurrent;
    g.classList.toggle("is-folded", folded);
    g.querySelector(".nav__group-label").setAttribute("aria-expanded", String(!folded));
  });
}
document.querySelectorAll(".nav__group-label").forEach(label => label.addEventListener("click", () => {
  const g = label.closest(".nav__group");
  if (g.classList.contains("is-folded")) foldedGroups.delete(g.dataset.group); else foldedGroups.add(g.dataset.group);
  try { localStorage.setItem(NAV_GROUPS_KEY, JSON.stringify([...foldedGroups])); } catch (e) { /* not remembered */ }
  renderNavGroups();
}));
renderNavGroups();

navItems.forEach(a => a.addEventListener("click", e => {
  e.preventDefault();
  setView(a.dataset.view);
  closeUserMenu();
  if (a.dataset.view === "chat") scrollToBottom();
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
