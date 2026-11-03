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

  // --- username / password ---
  document.getElementById("passwordForm").addEventListener("submit", async e => {
    e.preventDefault();
    showError("");
    const btn = document.getElementById("pwSubmit");
    btn.disabled = true;
    const body = new URLSearchParams();
    body.set("username", document.getElementById("pwUsername").value.trim());
    body.set("password", document.getElementById("pwPassword").value);
    try {
      const res = await fetch("/api/v1/auth/login", {
        method: "POST",
        headers: { "Content-Type": "application/x-www-form-urlencoded" },
        body,
      });
      if (res.ok) { window.location.href = "/"; return; }
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
