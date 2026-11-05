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
const placeholderPane = document.querySelector('[data-pane="placeholder"]');
const automationPane = document.querySelector('[data-pane="automation"]');
const analyticsPane = document.querySelector('[data-pane="analytics"]');
const placeholderText = document.getElementById("placeholderText");
const placeholderLabels = {
  automation: "placeholder.automation",
};
