// SuperAgent login — username/password (live), phone code, SSO, WeChat.
(function () {
  "use strict";

  const t = (k, v) => window.I18N.t(k, v);

  // language switcher
  const langSwitch = document.getElementById("langSwitch");
  if (langSwitch) {
    const cur = window.I18N.getLang();
    langSwitch.querySelectorAll("[data-lang]").forEach(b => {
      b.classList.toggle("is-active", b.dataset.lang === cur);
      b.addEventListener("click", () => window.I18N.switchTo(b.dataset.lang));
    });
  }

  const tabs = document.getElementById("methodTabs");
  const panels = document.querySelectorAll("[data-panel]");
  const err = document.getElementById("authError");

  function showError(m) { err.textContent = m || ""; err.hidden = !m; }

  function setMethod(method) {
    tabs.querySelectorAll(".tab").forEach(t => t.classList.toggle("is-active", t.dataset.method === method));
    panels.forEach(p => { p.hidden = p.dataset.panel !== method; });
    showError("");
  }
  tabs.addEventListener("click", e => {
    const b = e.target.closest(".tab");
    if (b) setMethod(b.dataset.method);
  });

  // --- username / password, within an organization (remembered on this browser) ---
  const ORG_KEY = "sa.org";
  const orgInput = document.getElementById("pwOrg");
  const remembered = (() => { try { return localStorage.getItem(ORG_KEY) || ""; } catch (e) { return ""; } })();
  orgInput.value = new URLSearchParams(window.location.search).get("org") || remembered;
  (orgInput.value ? document.getElementById("pwUsername") : orgInput).focus();

  document.getElementById("passwordForm").addEventListener("submit", async e => {
    e.preventDefault();
    showError("");
    const btn = document.getElementById("pwSubmit");
    btn.disabled = true;
    const org = orgInput.value.trim().toLowerCase();
    const body = new URLSearchParams();
    body.set("org", org);
    body.set("username", document.getElementById("pwUsername").value.trim());
    body.set("password", document.getElementById("pwPassword").value);
    try {
      const res = await fetch("/api/v1/auth/login", {
        method: "POST",
        headers: { "Content-Type": "application/x-www-form-urlencoded" },
        body,
      });
      if (res.ok) {
        try { localStorage.setItem(ORG_KEY, org); } catch (e) { /* not remembered: typed again next time */ }
        window.location.href = "/";
        return;
      }
      const d = await res.json().catch(() => ({}));
      showError(d.message || t("login.errFailed"));
    } catch (e) {
      showError(t("login.errNetwork"));
    } finally {
      btn.disabled = false;
    }
  });

  // --- phone code ---
  const phSend = document.getElementById("phSend");
  phSend.addEventListener("click", async () => {
    showError("");
    const phone = document.getElementById("phNumber").value.trim();
    if (!phone) { showError(t("login.errNoPhone")); return; }
    try {
      const res = await fetch("/api/v1/auth/phone/send", {
        method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ phone }),
      });
      if (res.ok) { startCountdown(phSend); }
      else { const d = await res.json().catch(() => ({})); showError(d.message || t("login.errSendFailed")); }
    } catch (e) { showError(t("login.errNetwork")); }
  });

  document.getElementById("phoneForm").addEventListener("submit", async e => {
    e.preventDefault();
    showError("");
    const phone = document.getElementById("phNumber").value.trim();
    const code = document.getElementById("phCode").value.trim();
    try {
      const res = await fetch("/api/v1/auth/phone/verify", {
        method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ phone, code }),
      });
      if (res.ok) { window.location.href = "/"; return; }
      const d = await res.json().catch(() => ({}));
      showError(d.message || t("login.errCode"));
    } catch (e) { showError(t("login.errNetwork")); }
  });

  // --- registering an organization ---
  const views = { signIn: document.getElementById("signInView"), register: document.getElementById("registerView"),
    done: document.getElementById("registeredView") };
  const show = name => Object.entries(views).forEach(([k, v]) => { v.hidden = k !== name; });
  const regErr = document.getElementById("regError");
  const regName = document.getElementById("regName");
  const regShort = document.getElementById("regShort");
  function regError(m) { regErr.textContent = m || ""; regErr.hidden = !m; }

  document.getElementById("toRegister").addEventListener("click", e => { e.preventDefault(); show("register"); regName.focus(); });
  document.getElementById("toSignIn").addEventListener("click", e => { e.preventDefault(); show("signIn"); });
  // The server may have registration turned off: then there's no way in from here but an account.
  const regFoot = document.getElementById("toRegister").closest(".card__foot");
  regFoot.hidden = true;
  fetch("/api/v1/orgs/registration").then(r => r.json()).then(d => {
    const open = !!(d && d.data && d.data.open);
    regFoot.hidden = !open;
    if (open && window.location.hash === "#register") show("register");
  }).catch(() => { /* leave it hidden */ });
  regShort.addEventListener("input", () => { regShort.value = regShort.value.toLowerCase(); });

  document.getElementById("registerForm").addEventListener("submit", async e => {
    e.preventDefault();
    regError("");
    const btn = document.getElementById("regSubmit");
    btn.disabled = true;
    try {
      const res = await fetch("/api/v1/orgs", {
        method: "POST", headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ name: regName.value.trim(), englishName: regShort.value.trim() }),
      });
      const d = await res.json().catch(() => ({}));
      if (!res.ok || !d.data) { regError(d.message || t("reg.failed")); return; }
      const r = d.data;
      document.getElementById("doneOrg").textContent = r.org;
      document.getElementById("doneUser").textContent = r.username;
      document.getElementById("donePassword").textContent = r.password;
      views.done.dataset.org = r.org;
      views.done.dataset.user = r.username;
      views.done.dataset.copy = t("login.org") + ": " + r.org + "\n" + t("login.username") + ": " + r.username + "\n"
        + t("login.password") + ": " + r.password;
      show("done");
    } catch (e) {
      regError(t("login.errNetwork"));
    } finally {
      btn.disabled = false;
    }
  });

  document.getElementById("doneCopy").addEventListener("click", async e => {
    try { await navigator.clipboard.writeText(views.done.dataset.copy || ""); e.target.textContent = t("reg.copied"); }
    catch (err) { /* select the text by hand */ }
  });
  document.getElementById("doneSignIn").addEventListener("click", () => {
    orgInput.value = views.done.dataset.org || "";
    document.getElementById("pwUsername").value = views.done.dataset.user || "";
    document.getElementById("donePassword").textContent = ""; // shown once
    show("signIn");
    document.getElementById("pwPassword").focus();
  });

  function startCountdown(btn) {
    let n = 60;
    btn.disabled = true;
    const original = t("login.sendCode");
    const timer = setInterval(() => {
      btn.textContent = n + "s";
      if (--n < 0) { clearInterval(timer); btn.disabled = false; btn.textContent = original; }
    }, 1000);
  }
})();
