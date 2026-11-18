// SuperAgent UI — A harness-run task's detail (coding): where it works, its steps, spec, changes, checks and review.
// Classic script sharing one global scope with the others; index.html loads it after tasks.js.
"use strict";

Object.assign(api, {
  taskDiff: (id, path) => fetch("/api/v1/tasks/" + encodeURIComponent(id) + "/diff?path=" + encodeURIComponent(path))
    .then(kbJson),
});

const hnEl = {
  box: document.getElementById("taskHarness"),
  env: document.getElementById("hnEnv"),
  pipe: document.getElementById("hnPipe"),
  spec: document.getElementById("hnSpec"),
  diff: document.getElementById("hnDiff"),
  verify: document.getElementById("hnVerify"),
  review: document.getElementById("hnReview"),
  tabs: { hspec: document.getElementById("hnTabSpec"), hdiff: document.getElementById("hnTabDiff"),
    hverify: document.getElementById("hnTabVerify"), hreview: document.getElementById("hnTabReview") },
};
let hnOpenFiles = new Set(); // diffs kept open across refreshes
let hnShown = null; // the harness last drawn, for the Output tab

const hnEl2 = (tag, cls, text) => {
  const e = document.createElement(tag);
  if (cls) e.className = cls;
  if (text != null) e.textContent = text;
  return e;
};
const hnShort = sha => sha ? sha.slice(0, 7) : "";
const hnDuration = (a, b) => {
  if (!a) return "";
  const ms = (b ? new Date(b) : new Date()) - new Date(a);
  return typeof tmDuration === "function" ? tmDuration(Math.max(0, ms)) : Math.round(ms / 1000) + "s";
};

/** Shows (or hides, for any other task) the coding view; {@code h} is the detail's harness, {@code t} the task. */
function renderHarness(h, t) {
  const on = !!h;
  hnShown = h || null;
  hnEl.box.hidden = !on;
  Object.values(hnEl.tabs).forEach(b => { b.hidden = !on; });
  if (!on) return;
  renderHarnessEnv(h);
  renderHarnessPipe(h);
  hnEl.tabs.hspec.textContent = L("hn.tabSpec") + (h.specApproved ? " ✓" : "");
  hnEl.tabs.hdiff.innerHTML = "";
  hnEl.tabs.hdiff.append(L("hn.tabDiff"), hnCount(h.files.length));
  hnEl.tabs.hverify.innerHTML = "";
  hnEl.tabs.hverify.append(L("hn.tabVerify"), hnCount(h.rounds.length || ""));
  hnEl.tabs.hverify.title = h.rounds.length ? L("hn.rounds", { n: h.rounds.length }) : "";
  hnEl.tabs.hreview.innerHTML = "";
  hnEl.tabs.hreview.append(L("hn.tabReview"), hnCount(h.findings.length || ""));
  renderHarnessSpec(h);
  renderHarnessDiff(h, t);
  renderHarnessVerify(h);
  renderHarnessReview(h);
  // The run log is where the agent's work shows: name it so for a harness-run task.
  tel.tabs.querySelector('[data-tab="run"]').textContent = L("hn.tabRun");
}

function hnCount(v) {
  const b = hnEl2("b", "hn-count", v === 0 || v === "" ? "" : String(v));
  return b;
}

function renderHarnessEnv(h) {
  const w = h.workspace;
  hnEl.env.replaceChildren();
  if (!w) return;
  // {@code shown}: a short form for a long value; {@code full}: what the tooltip shows and a click copies.
  const kv = (label, shown, mono, full) => {
    const d = hnEl2("div", "hn-kv" + (full ? " is-copy" : ""));
    d.append(hnEl2("span", null, label));
    const b = hnEl2("b");
    if (mono) b.append(hnEl2("code", null, shown || "—")); else b.textContent = shown || "—";
    d.dataset.tip = (full || shown || "—") + (full ? "\n— " + L("hn.clickCopy") : "");
    // Near the right edge the tip opens leftwards, so it stays on screen.
    d.addEventListener("mouseenter", () => {
      d.classList.toggle("tip-left", d.getBoundingClientRect().left > window.innerWidth / 2);
    });
    if (full) {
      b.addEventListener("click", () => navigator.clipboard.writeText(full).then(() => showToast(L("hn.copied"))).catch(() => {}));
    }
    d.appendChild(b);
    hnEl.env.appendChild(d);
  };
  const cmds = h.verifyCommands || [];
  kv(L(w.mode === "local" ? "hn.repoLocal" : "hn.repoRemote"), w.repo, true, w.repo);
  kv(L("hn.base"), w.baseBranch ? w.baseBranch + (w.baseSha ? " @ " + hnShort(w.baseSha) : "") : "", true);
  kv(L("hn.branch"), w.branch, true, w.branch);
  // The task's own copy lives deep under the server's data directory: what tells it apart is its last folder.
  kv(L("hn.dir"), w.dir ? "…/" + w.dir.split("/").filter(Boolean).slice(-2).join("/") : "", true, w.dir);
  kv(L("hn.delivery"), L(w.delivery === "pr" ? "hn.deliveryPr" : "hn.deliveryMerge", { base: w.baseBranch || "" }));
  kv(L("hn.verifyCmds"), cmds.length ? L("hn.cmdCount", { n: cmds.length }) + " · " + cmds[0] : L("hn.fromSpec"),
    cmds.length > 0, cmds.length ? cmds.join("\n") : null);
}

function renderHarnessPipe(h) {
  hnEl.pipe.replaceChildren(...h.steps.map((s, i) => {
    const li = hnEl2("li", "hn-step is-" + s.status);
    const top = hnEl2("div", "hn-step__top");
    const dot = hnEl2("span", "hn-dot", s.status === "done" ? "✓" : s.status === "failed" ? "✕" : String(i + 1));
    const name = L("hn.step." + s.key);
    top.append(dot, hnEl2("span", "hn-step__name", name === "hn.step." + s.key ? s.name : name));
    const meta = hnEl2("span", "hn-step__meta");
    meta.textContent = s.status === "waiting" ? L("hn.waiting")
      : s.status === "running" ? L("hn.running") + " · " + hnDuration(s.startedAt)
      : s.status === "done" ? hnDuration(s.startedAt, s.finishedAt)
      : s.status === "failed" ? L("hn.failed") : "";
    if (s.note) li.title = s.note;
    if (s.status === "done" && s.finishedAt) li.title = (s.note ? s.note + "\n" : "") + formatDateTime(s.finishedAt);
    li.append(top, meta);
    return li;
  }));
}

function renderHarnessSpec(h) {
  hnEl.spec.replaceChildren();
  if (!h.spec) {
    hnEl.spec.append(hnEl2("p", "kb-empty", L("hn.noSpec")));
    return;
  }
  hnEl.spec.append(hnEl2("p", h.specApproved ? "hn-approved" : "hn-pending",
    L(h.specApproved ? "hn.specApproved" : "hn.specWaiting")));
  if (h.criteria.length) {
    const sec = hnEl2("section", "hn-section");
    const met = h.criteria.filter(c => c.met).length;
    const head = hnEl2("div", "hn-section__head");
    head.append(hnEl2("h3", null, L("hn.criteria")), hnEl2("span", "hn-section__sum", L("hn.criteriaMet", { met, n: h.criteria.length })));
    const ul = hnEl2("ul", "hn-list");
    h.criteria.forEach(c => {
      const li = hnEl2("li", "hn-ac");
      li.append(hnEl2("span", "hn-ac__mark " + (c.met ? "is-ok" : "is-wait"), c.met ? "✓" : "…"));
      const txt = hnEl2("div", "hn-ac__text", c.text);
      if (c.evidence) txt.append(hnEl2("span", "hn-ac__evidence", c.evidence));
      li.appendChild(txt);
      ul.appendChild(li);
    });
    sec.append(head, ul);
    hnEl.spec.appendChild(sec);
  }
  const body = hnEl2("div", "markdown hn-spec__body");
  body.innerHTML = renderMarkdown(h.spec);
  hnEl.spec.appendChild(body);
}

function renderHarnessDiff(h, t) {
  hnEl.diff.replaceChildren();
  if (!h.files.length) {
    hnEl.diff.append(hnEl2("p", "kb-empty", L("hn.noFiles")));
    return;
  }
  const add = h.files.reduce((n, f) => n + f.added, 0), del = h.files.reduce((n, f) => n + f.removed, 0);
  const head = hnEl2("div", "hn-section__head");
  const sum = hnEl2("span", "hn-delta");
  sum.append(hnEl2("span", "a", "+" + add), hnEl2("span", "d", "−" + del));
  head.append(hnEl2("h3", null, L("hn.filesN", { n: h.files.length })), sum);
  const ul = hnEl2("ul", "hn-list");
  h.files.forEach(f => {
    const li = hnEl2("li");
    const btn = hnEl2("button", "hn-file");
    btn.type = "button";
    const slash = f.path.lastIndexOf("/");
    const path = hnEl2("span", "hn-path");
    if (slash >= 0) path.append(hnEl2("span", "hn-dir", f.path.slice(0, slash + 1)));
    path.append(f.path.slice(slash + 1));
    const d = hnEl2("span", "hn-delta");
    d.append(hnEl2("span", "a", "+" + f.added), hnEl2("span", "d", "−" + f.removed));
    btn.append(hnEl2("span", "hn-chev", "›"), path, d);
    const pre = hnEl2("pre", "hn-diff");
    pre.hidden = true;
    const open = async () => {
      btn.setAttribute("aria-expanded", "true");
      pre.hidden = false;
      if (!pre.dataset.loaded) {
        pre.textContent = L("hn.loading");
        const text = await api.taskDiff(t.id, f.path).catch(() => "");
        paintDiff(pre, text || L("hn.noDiff"));
        pre.dataset.loaded = "1";
      }
    };
    btn.addEventListener("click", () => {
      if (pre.hidden) { hnOpenFiles.add(f.path); open(); }
      else { hnOpenFiles.delete(f.path); pre.hidden = true; btn.setAttribute("aria-expanded", "false"); }
    });
    if (hnOpenFiles.has(f.path)) open();
    li.append(btn, pre);
    ul.appendChild(li);
  });
  hnEl.diff.append(head, ul);
}

function paintDiff(pre, text) {
  pre.replaceChildren(...text.split("\n").map(line => {
    const cls = line.startsWith("@@") ? "h" : line.startsWith("+") && !line.startsWith("+++") ? "p"
      : line.startsWith("-") && !line.startsWith("---") ? "m" : "";
    return hnEl2("span", cls, line || " ");
  }));
}

function renderHarnessVerify(h) {
  hnEl.verify.replaceChildren();
  const head = hnEl2("div", "hn-section__head");
  head.append(hnEl2("h3", null, L("hn.tabVerify")), hnEl2("span", "hn-section__sum", L("hn.verifyHint")));
  hnEl.verify.appendChild(head);
  if (!h.rounds.length) {
    hnEl.verify.append(hnEl2("p", "kb-empty", L("hn.noRounds")));
    return;
  }
  const box = hnEl2("div", "hn-box");
  [...h.rounds].reverse().forEach(r => {
    const round = hnEl2("div", "hn-round");
    const failed = r.checks.filter(c => c.exitCode !== 0).length;
    round.append(hnEl2("div", "hn-round__head " + (r.passed ? "is-ok" : "is-bad"),
      L(r.passed ? "hn.roundPassed" : "hn.roundFailed", { n: r.round, time: formatDateTime(r.at).slice(11, 16), failed })));
    r.checks.forEach(c => {
      const row = hnEl2("div", "hn-check");
      const cmd = hnEl2("code", null, c.command);
      const exit = hnEl2("span", "hn-exit " + (c.exitCode === 0 ? "is-ok" : "is-bad"),
        L(c.exitCode === 0 ? "hn.passed" : "hn.exitFailed", { code: c.exitCode }));
      row.append(cmd, exit, hnEl2("span", "hn-check__time", tmDuration(c.millis)));
      round.appendChild(row);
      if (c.exitCode !== 0 && c.logTail) round.appendChild(hnEl2("pre", "hn-log", c.logTail));
    });
    if (r.fix) round.appendChild(hnEl2("div", "hn-fix", L("hn.fixed", { what: r.fix })));
    box.appendChild(round);
  });
  hnEl.verify.appendChild(box);
}

function renderHarnessReview(h) {
  hnEl.review.replaceChildren();
  const head = hnEl2("div", "hn-section__head");
  head.append(hnEl2("h3", null, L("hn.tabReview")), hnEl2("span", "hn-section__sum", L("hn.reviewHint")));
  hnEl.review.appendChild(head);
  if (!h.findings.length) {
    const reviewed = h.steps.some(s => s.key === "review" && s.status === "done");
    hnEl.review.append(hnEl2("p", "kb-empty", L(reviewed ? "hn.reviewClean" : "hn.noReview")));
  } else {
    const ul = hnEl2("ul", "hn-list");
    h.findings.forEach(f => {
      const li = hnEl2("li", "hn-finding");
      li.append(hnEl2("span", "hn-sev is-" + f.severity, L("hn.sev." + f.severity)));
      const body = hnEl2("div", "hn-finding__body", f.text);
      if (f.where) body.append(hnEl2("span", "hn-finding__where", f.where));
      li.append(body, hnEl2("span", "hn-state is-" + f.state, L("hn.state." + f.state)));
      ul.appendChild(li);
    });
    hnEl.review.appendChild(ul);
  }
  if (h.delivery) {
    const d = hnEl2("div", "hn-deliver");
    d.append(hnEl2("h3", null, L("hn.delivered")), hnEl2("p", null, h.delivery.note || ""));
    if (h.delivery.result && /^https?:/.test(h.delivery.result)) {
      const a = hnEl2("a", "link-btn", h.delivery.result);
      a.href = h.delivery.result;
      a.target = "_blank";
      a.rel = "noopener";
      d.appendChild(a);
    }
    hnEl.review.appendChild(d);
  }
}

/**
 * The Output tab of a coding task: what was delivered (the merge commit or the PR), then what changed, what it was
 * held to and how the platform checked it. Without a delivery yet, false (the caller shows its usual text).
 */
function renderHarnessOutput(box) {
  const h = hnShown;
  if (!h || !h.delivery) return false;
  box.replaceChildren();
  const out = hnEl2("div", "hn-out");
  const merged = h.delivery.result && !/^https?:/.test(h.delivery.result);
  const head = hnEl2("div", "hn-out__head");
  head.append(hnEl2("span", "hn-out__icon", "✓"));
  const text = hnEl2("div");
  text.append(hnEl2("p", "hn-out__title", L("hn.outDelivered")));
  const note = hnEl2("p", "hn-out__note", h.delivery.note || "");
  if (h.delivery.result && /^https?:/.test(h.delivery.result)) {
    note.append(" · ");
    const a = hnEl2("a", null, L("hn.openPr"));
    a.href = h.delivery.result;
    a.target = "_blank";
    a.rel = "noopener";
    note.appendChild(a);
  }
  text.appendChild(note);
  const add = h.files.reduce((n, f) => n + f.added, 0), del = h.files.reduce((n, f) => n + f.removed, 0);
  const met = h.criteria.filter(c => c.met).length;
  const last = h.rounds[h.rounds.length - 1];
  const stats = hnEl2("div", "hn-out__stats");
  const stat = (label, value) => {
    const s = hnEl2("span", null, label + " ");
    s.appendChild(hnEl2("b", null, value));
    stats.appendChild(s);
  };
  stat(L("hn.tabDiff"), L("hn.filesShort", { n: h.files.length }) + " · +" + add + " −" + del);
  if (h.criteria.length) stat(L("hn.criteria"), met + " / " + h.criteria.length);
  if (last) stat(L("hn.tabVerify"), L(last.passed ? "hn.outPassed" : "hn.outFailed", { n: last.round }));
  if (merged) stat(L("hn.commit"), hnShort(h.delivery.result));
  text.appendChild(stats);
  head.appendChild(text);
  out.appendChild(head);

  if (h.criteria.length) {
    const sec = hnEl2("section");
    const sh = hnEl2("div", "hn-section__head");
    sh.append(hnEl2("h3", null, L("hn.criteria")), hnEl2("span", "hn-section__sum", L("hn.criteriaMet", { met, n: h.criteria.length })));
    const ul = hnEl2("ul", "hn-list");
    h.criteria.forEach(c => {
      const li = hnEl2("li", "hn-ac");
      li.append(hnEl2("span", "hn-ac__mark " + (c.met ? "is-ok" : "is-wait"), c.met ? "✓" : "…"));
      const t = hnEl2("div", "hn-ac__text", c.text);
      if (c.evidence) t.append(hnEl2("span", "hn-ac__evidence", c.evidence));
      li.appendChild(t);
      ul.appendChild(li);
    });
    sec.append(sh, ul);
    out.appendChild(sec);
  }
  if (h.files.length) {
    const sec = hnEl2("section");
    const sh = hnEl2("div", "hn-section__head");
    const sum = hnEl2("span", "hn-delta");
    sum.append(hnEl2("span", "a", "+" + add), hnEl2("span", "d", "−" + del));
    sh.append(hnEl2("h3", null, L("hn.filesN", { n: h.files.length })), sum);
    const ul = hnEl2("ul", "hn-list");
    h.files.forEach(f => {
      const li = hnEl2("li", "hn-file hn-file--static");
      const slash = f.path.lastIndexOf("/");
      const path = hnEl2("span", "hn-path");
      if (slash >= 0) path.append(hnEl2("span", "hn-dir", f.path.slice(0, slash + 1)));
      path.append(f.path.slice(slash + 1));
      const d = hnEl2("span", "hn-delta");
      d.append(hnEl2("span", "a", "+" + f.added), hnEl2("span", "d", "−" + f.removed));
      li.append(hnEl2("span", "hn-chev", "·"), path, d);
      ul.appendChild(li);
    });
    sec.append(sh, ul);
    out.appendChild(sec);
  }
  if (last) {
    const sec = hnEl2("section");
    const sh = hnEl2("div", "hn-section__head");
    sh.append(hnEl2("h3", null, L("hn.lastChecks", { n: last.round })), hnEl2("span", "hn-section__sum", L("hn.verifyHint")));
    const box2 = hnEl2("div", "hn-box");
    last.checks.forEach((c, i) => {
      const row = hnEl2("div", "hn-check");
      if (i === 0) row.style.borderTop = "0";
      row.append(hnEl2("code", null, c.command),
        hnEl2("span", "hn-exit " + (c.exitCode === 0 ? "is-ok" : "is-bad"), L(c.exitCode === 0 ? "hn.passed" : "hn.exitFailed", { code: c.exitCode })),
        hnEl2("span", "hn-check__time", tmDuration(c.millis)));
      box2.appendChild(row);
    });
    sec.append(sh, box2);
    out.appendChild(sec);
  }
  box.appendChild(out);
  return true;
}
