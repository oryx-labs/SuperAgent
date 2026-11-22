// SuperAgent UI — A harness-run task's detail. Coding: where it works, its steps, spec, changes, checks, review and
// acceptance. General (and a complex task's conclusion): a summary card, four steps, the checklist, the checks and what it produced.
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
  accept: document.getElementById("hnAccept"),
  resolve: document.getElementById("hnResolve"),
  stop: document.getElementById("hnStop"),
  tabs: { hspec: document.getElementById("hnTabSpec"), hdiff: document.getElementById("hnTabDiff"),
    hverify: document.getElementById("hnTabVerify"), hreview: document.getElementById("hnTabReview"),
    haccept: document.getElementById("hnTabAccept"), hresolve: document.getElementById("hnTabResolve") },
  criteria: document.getElementById("hnCriteria"),
  check: document.getElementById("hnCheck"),
  gtabs: { gcrit: document.getElementById("hnTabCriteria"), gcheck: document.getElementById("hnTabCheck") },
};
// The tabs in the order the page declares them; a general task puts what it produced and how it was checked first.
const hnTabOrder = [...tel.tabs.children];
const HN_GENERAL_FIRST = ["output", "gcrit", "run", "gcheck"];
const SB_FIRST = ["ssub", "scmp", "sdoubt", "sterm", "run"];
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
  const general = !!(h && h.general);
  const subtitle = !!(h && h.harness === "subtitle");
  const article = !!(h && h.harness === "article");
  hnShown = h || null;
  hnEl.box.hidden = !on;
  hnEl.box.classList.toggle("hn--general", general || subtitle || article);
  Object.values(hnEl.tabs).forEach(b => { b.hidden = !on || general || subtitle || article; });
  Object.values(hnEl.gtabs).forEach(b => { b.hidden = !general; });
  sbShowTabs(subtitle);
  atShowTabs(article);
  hnArrangeTabs(general, subtitle, article);
  tel.tabs.querySelector('[data-tab="output"]').textContent = L(general ? "hn.g.tabOutput" : "tasks.tabOutput");
  if (!on) return;
  if (general) { hnEl.stop.hidden = true; renderGeneral(h, t); return; }
  if (subtitle) { hnEl.stop.hidden = true; renderSubtitle(h, t); return; }
  if (article) { hnEl.stop.hidden = true; renderArticle(h, t); return; }
  const reviews = h.reviews || [];
  // Tasks from before acceptance had no such step.
  hnEl.tabs.haccept.hidden = !h.steps.some(s => s.key === "accept");
  // Only a delivery that met conflicts has this step.
  const conflicts = h.conflicts || [];
  hnEl.tabs.hresolve.hidden = !h.steps.some(s => s.key === "resolve") && !conflicts.length;
  hnEl.stop.hidden = !h.stopReason;
  hnEl.stop.textContent = h.stopReason ? L("hn.stopped", { reason: h.stopReason }) : "";
  renderHarnessEnv(h);
  renderHarnessPipe(h);
  hnEl.tabs.hspec.textContent = L("hn.tabSpec") + (h.specApproved ? " ✓" : "");
  hnEl.tabs.hdiff.innerHTML = "";
  hnEl.tabs.hdiff.append(L("hn.tabDiff"), hnCount(h.files.length));
  hnEl.tabs.hverify.innerHTML = "";
  hnEl.tabs.hverify.append(L("hn.tabVerify"), hnCount(h.rounds.length || ""));
  hnEl.tabs.hverify.title = h.rounds.length ? L("hn.rounds", { n: h.rounds.length }) : "";
  hnEl.tabs.hreview.innerHTML = "";
  const looks = kind => reviews.filter(r => r.kind === kind);
  hnEl.tabs.hreview.append(L("hn.tabReview"), hnCount(reviews.length ? looks("review").length : (h.findings.length || "")));
  hnEl.tabs.haccept.innerHTML = "";
  hnEl.tabs.haccept.append(L("hn.tabAccept"), hnCount(looks("accept").length || ""));
  hnEl.tabs.hresolve.innerHTML = "";
  hnEl.tabs.hresolve.append(L("hn.tabResolve"), hnCount(conflicts.length || ""));
  renderHarnessSpec(h);
  renderHarnessDiff(h, t);
  renderHarnessVerify(h);
  renderHarnessReview(h);
  renderHarnessAccept(h);
  renderHarnessResolve(h);
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
    top.append(dot, hnEl2("span", "hn-step__name", hnStepName(h, s)));
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

/** A step's name; in a general or writing flow that went back, the round it is on ("执行 · 第 2 轮"). */
function hnStepName(h, s) {
  const key = h.general ? (h.harness === "complex" && s.key === "execute" ? "g.sum" : "g." + s.key)
    : h.harness === "subtitle" ? "s." + s.key : h.harness === "article" ? "a." + s.key : s.key;
  const name = L("hn.step." + key) === "hn.step." + key ? s.name : L("hn.step." + key);
  const round = h.general && h.general.round > 1 && (s.key === "execute" || s.key === "check")
    && s.status !== "pending" ? h.general.round : h.article && s.status !== "pending" ? hnArticleRound(h.article, s) : 0;
  return round ? name + " · " + L("hn.g.round", { n: round }) : name;
}

/** How many times a writing task's check, review or revise ran (counting the one under way); 0 for the first. */
function hnArticleRound(a, s) {
  const list = s.key === "check" ? a.checks : s.key === "review" ? a.reviews : s.key === "revise" ? a.revisions : null;
  if (!list) return 0;
  const n = list.length + (s.status === "running" ? 1 : 0);
  return n > 1 ? n : 0;
}

/**
 * A general task leads with what it produced and how it was checked, a subtitle task with its subtitles and their
 * disputes; any other task keeps the page's order.
 */
function hnArrangeTabs(general, subtitle, article) {
  const first = general ? HN_GENERAL_FIRST : subtitle ? SB_FIRST : article ? AT_FIRST : null;
  const order = first
    ? [...first.map(k => hnTabOrder.find(b => b.dataset.tab === k)),
      ...hnTabOrder.filter(b => !first.includes(b.dataset.tab))]
    : hnTabOrder;
  order.forEach(b => tel.tabs.appendChild(b));
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
  const reviews = h.reviews || [];
  const head = hnEl2("div", "hn-section__head");
  head.append(hnEl2("h3", null, L("hn.tabReview")), hnEl2("span", "hn-section__sum", L(reviews.length ? "hn.reviewHintLoop" : "hn.reviewHint")));
  hnEl.review.appendChild(head);
  if (reviews.length) {
    hnEl.review.appendChild(hnRounds(reviews, "review"));
  } else if (!h.findings.length) {
    const reviewed = h.steps.some(s => s.key === "review" && s.status === "done");
    hnEl.review.append(hnEl2("p", "kb-empty", L(reviewed ? "hn.reviewClean" : "hn.noReview")));
  } else {
    hnEl.review.appendChild(hnFindings(h.findings)); // a task from before the review loop
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

function renderHarnessAccept(h) {
  hnEl.accept.replaceChildren();
  const head = hnEl2("div", "hn-section__head");
  head.append(hnEl2("h3", null, L("hn.tabAccept")), hnEl2("span", "hn-section__sum", L("hn.acceptHint")));
  hnEl.accept.appendChild(head);
  const reviews = h.reviews || [];
  if (!reviews.some(r => r.kind === "accept")) {
    hnEl.accept.append(hnEl2("p", "kb-empty", L("hn.noAccept")));
    return;
  }
  hnEl.accept.appendChild(hnRounds(reviews, "accept"));
}

const HN_RESOLVE_CHECKS = { "冲突标记": "markers", "继续变基": "continue", "验证命令": "verify", "审查": "review" };

/**
 * The goes at resolving the conflicts of the rebase before delivery, newest first: which commit, which files, what the
 * developer said it did, and the platform's checks of it (markers, the rebase going on, the verify commands, the review).
 */
function renderHarnessResolve(h) {
  hnEl.resolve.replaceChildren();
  const head = hnEl2("div", "hn-section__head");
  head.append(hnEl2("h3", null, L("hn.tabResolve")), hnEl2("span", "hn-section__sum", L("hn.resolveHint")));
  hnEl.resolve.appendChild(head);
  const rounds = h.conflicts || [];
  if (!rounds.length) {
    hnEl.resolve.append(hnEl2("p", "kb-empty", L("hn.noResolve")));
    return;
  }
  const box = hnEl2("div", "hn-box");
  [...rounds].reverse().forEach(r => {
    const round = hnEl2("div", "hn-round hn-look");
    const top = hnEl2("div", "hn-round__head hn-look__head " + (r.passed ? "is-ok" : "is-bad"));
    top.append(hnEl2("span", null, L("hn.roundN", { n: r.round }) + " · " + formatDateTime(r.at).slice(11, 16) + " · "
      + L(r.passed ? "hn.lookPassed" : "hn.resolveFailed")));
    if (r.model) top.append(hnEl2("span", "hn-model", r.model));
    if (r.verdict) top.append(hnEl2("span", "hn-verdict is-" + (r.verdict === "PASS" ? "ok" : "bad"), "VERDICT: " + r.verdict));
    round.appendChild(top);
    const meta = hnEl2("div", "hn-check");
    meta.append(hnEl2("b", null, L(r.commit ? "hn.resolveCommit" : "hn.resolveAfter")),
      hnEl2("span", "hn-check__time", (r.commit ? r.commit + " · " : "") + L("hn.resolveFiles", { files: (r.files || []).join("、") })));
    round.appendChild(meta);
    if (r.summary) round.appendChild(hnEl2("div", "hn-fix", L("hn.resolveSummary", { what: r.summary })));
    (r.checks || []).forEach(c => {
      const row = hnEl2("div", "hn-check");
      const key = HN_RESOLVE_CHECKS[c.name];
      row.append(hnEl2("span", "hn-exit " + (c.passed ? "is-ok" : "is-bad"), c.passed ? "✅" : "❌"),
        hnEl2("b", null, key ? L("hn.resolveCheck." + key) : c.name), hnEl2("span", "hn-check__time", c.detail || ""));
      round.appendChild(row);
    });
    if (!r.passed && r.reason) round.appendChild(hnEl2("pre", "hn-log", r.reason));
    box.appendChild(round);
  });
  hnEl.resolve.appendChild(box);
}

function hnFindings(list) {
  const ul = hnEl2("ul", "hn-list");
  list.forEach(f => {
    const li = hnEl2("li", "hn-finding");
    li.append(hnEl2("span", "hn-sev is-" + f.severity, L("hn.sev." + f.severity)));
    const body = hnEl2("div", "hn-finding__body");
    const text = hnEl2("span", null, f.text);
    if (f.id && !/^AC/.test(f.id)) text.prepend(hnEl2("span", "hn-finding__id", "#" + f.id));
    body.appendChild(text);
    if (f.where) body.append(hnEl2("span", "hn-finding__where", f.where));
    li.append(body, hnEl2("span", "hn-state is-" + f.state, L("hn.state." + f.state)));
    ul.appendChild(li);
  });
  return ul;
}

/**
 * The rounds of one kind (review | accept), newest first: who looked, what it found and how the count moved since the
 * look before (of either kind), the developer's objections and the rulings on them; for acceptance, each criterion.
 */
function hnRounds(all, kind) {
  const box = hnEl2("div", "hn-box");
  const rows = [];
  all.forEach((r, i) => { if (r.kind === kind) rows.push({ r, before: i > 0 ? all[i - 1] : null }); });
  rows.reverse().forEach(({ r, before }) => {
    const round = hnEl2("div", "hn-round hn-look");
    const top = hnEl2("div", "hn-round__head hn-look__head " + (r.passed ? "is-ok" : "is-bad"));
    top.append(hnEl2("span", null, L("hn.roundN", { n: r.round }) + " · " + L(r.passed ? "hn.lookPassed" : "hn.lookIssues", { n: r.blocking })));
    if (r.model) top.append(hnEl2("span", "hn-model", r.model));
    if (before) top.append(hnEl2("span", "hn-trend", L("hn.delta", { from: before.blocking, to: r.blocking })));
    top.append(hnEl2("span", "hn-verdict is-" + (r.verdict === "PASS" ? "ok" : "bad"), "VERDICT: " + (r.verdict || "—")));
    round.appendChild(top);
    if (r.reverted) round.appendChild(hnEl2("div", "hn-fix", L("hn.reverted")));
    (r.rebuttals || []).forEach(b => {
      const d = hnEl2("div", "hn-rebuttal");
      d.append(hnEl2("span", "hn-rebuttal__who", L("hn.rebuttal", { id: b.id.replace(/^AC/, L("hn.criterionShort")) })),
        hnEl2("span", null, b.reason));
      const rule = hnEl2("span", "hn-ruling is-" + (b.ruling || "none"),
        L("hn.ruling." + (b.ruling || "none")) + (b.rulingReason ? "：" + b.rulingReason : ""));
      d.appendChild(rule);
      round.appendChild(d);
    });
    if (kind === "accept" && r.criteria && r.criteria.length) {
      const ul = hnEl2("ul", "hn-list");
      r.criteria.forEach((c, i) => {
        const li = hnEl2("li", "hn-ac");
        li.append(hnEl2("span", "hn-ac__mark " + (c.met ? "is-ok" : "is-bad"), c.met ? "✓" : "✕"));
        const t = hnEl2("div", "hn-ac__text", (i + 1) + ". " + c.text);
        if (c.evidence) t.append(hnEl2("span", "hn-ac__evidence", c.evidence));
        li.appendChild(t);
        ul.appendChild(li);
      });
      round.appendChild(ul);
    } else if (r.findings.length) {
      round.appendChild(hnFindings(r.findings));
    } else {
      round.appendChild(hnEl2("p", "hn-look__none", L("hn.noIssues")));
    }
    box.appendChild(round);
  });
  return box;
}

/**
 * The Output tab of a coding task: what was delivered (the merge commit or the PR), then what changed, what it was
 * held to and how the platform checked it. Without a delivery yet, false (the caller shows its usual text).
 */
function renderHarnessOutput(box, said, t) {
  const h = hnShown;
  if (h && h.general) return renderGeneralOutput(box, h, said, t);
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

// --- a general task (and a complex task's conclusion) ---

function renderGeneral(h, t) {
  renderGeneralCard(h, t);
  renderHarnessPipe(h);
  const g = h.general;
  const met = h.criteria.filter(c => c.met).length;
  hnEl.gtabs.gcrit.replaceChildren(L("hn.g.tabCriteria"), hnCount(h.criteria.length ? met + "/" + h.criteria.length : ""));
  hnEl.gtabs.gcheck.replaceChildren(L("hn.g.tabCheck"), hnCount(g.checks.length || ""));
  tel.tabs.querySelector('[data-tab="run"]').textContent = L("hn.g.tabRun");
  renderGeneralCriteria(h);
  renderGeneralChecks(h);
}

/** Status, model (and the one that checked), time, spend and the step it is on. */
function renderGeneralCard(h, t) {
  hnEl.env.replaceChildren();
  const kv = (label, value, tip) => {
    const d = hnEl2("div", "hn-kv");
    d.append(hnEl2("span", null, label), hnEl2("b", null, value || "—"));
    if (tip) d.dataset.tip = tip;
    hnEl.env.appendChild(d);
  };
  const checks = h.general.checks;
  const checker = [...checks].reverse().map(c => c.checker).find(Boolean);
  const current = h.steps.find(s => s.status !== "done");
  kv(L("hn.g.status"), TASK_STATUS_LABEL[t.status] || t.status);
  kv(L("tasks.model"), taskModelLabel(t) + (checker ? " · " + L("hn.g.checkedOn", { m: checker }) : ""),
    checker ? L("hn.g.checkerTip") : null);
  kv(L("tasks.elapsed"), formatElapsed(t));
  kv(L("tasks.spend"), "$" + Number(t.spend || 0).toFixed(2));
  kv(L("hn.g.current"), current ? hnStepName(h, current) + (current.status === "waiting" ? " · " + L("hn.waiting") : "")
    : L("hn.g.delivered"));
}

function hnCriterionRow(c, i) {
  const li = hnEl2("li", "hn-ac");
  const judged = !!c.evidence;
  li.append(hnEl2("span", "hn-ac__mark " + (c.met ? "is-ok" : judged ? "is-bad" : "is-wait"), c.met ? "✅" : judged ? "❌" : "…"));
  const txt = hnEl2("div", "hn-ac__text", (i + 1) + ". " + c.text);
  if (c.agentAdded) txt.append(hnEl2("span", "hn-added", L("hn.g.agentAdded")));
  if (c.evidence) txt.append(hnEl2("span", "hn-ac__evidence", c.evidence));
  li.appendChild(txt);
  return li;
}

function renderGeneralCriteria(h) {
  const box = hnEl.criteria;
  box.replaceChildren();
  const g = h.general;
  if (!h.criteria.length) {
    box.append(hnEl2("p", "kb-empty", L("hn.g.noCriteria")));
    return;
  }
  if (g.deliverables.length) {
    const sec = hnEl2("section", "hn-section");
    const head = hnEl2("div", "hn-section__head");
    head.append(hnEl2("h3", null, L("hn.g.deliverables")));
    const ul = hnEl2("ul", "hn-list");
    g.deliverables.forEach(d => {
      const li = hnEl2("li", "hn-ac");
      li.append(hnEl2("span", "hn-ac__mark", "·"),
        hnEl2("div", "hn-ac__text", d.name + " · " + (d.type === "reply" ? L("hn.g.typeReply") : d.type)));
      ul.appendChild(li);
    });
    sec.append(head, ul);
    box.appendChild(sec);
  }
  const sec = hnEl2("section", "hn-section");
  const head = hnEl2("div", "hn-section__head");
  const met = h.criteria.filter(c => c.met).length;
  const added = h.criteria.filter(c => c.agentAdded).length;
  head.append(hnEl2("h3", null, L("hn.criteria")), hnEl2("span", "hn-section__sum",
    L("hn.criteriaMet", { met, n: h.criteria.length }) + (added ? " · " + L("hn.g.addedN", { n: added }) : "")));
  const ul = hnEl2("ul", "hn-list");
  h.criteria.forEach((c, i) => ul.appendChild(hnCriterionRow(c, i)));
  sec.append(head, ul);
  box.appendChild(sec);
}

function renderGeneralChecks(h) {
  const box = hnEl.check;
  box.replaceChildren();
  const head = hnEl2("div", "hn-section__head");
  head.append(hnEl2("h3", null, L("hn.g.tabCheck")), hnEl2("span", "hn-section__sum", L("hn.g.checkHint")));
  box.appendChild(head);
  const checks = h.general.checks;
  if (!checks.length) {
    box.append(hnEl2("p", "kb-empty", L("hn.noRounds")));
    return;
  }
  const list = hnEl2("div", "hn-box");
  [...checks].reverse().forEach(c => {
    const round = hnEl2("div", "hn-round");
    const verdict = c.verdict ? " · " + L("hn.g.verdict", { v: c.verdict }) + (c.checker ? "（" + c.checker + "）" : "")
      : " · " + L("hn.g.noModel");
    round.append(hnEl2("div", "hn-round__head " + (c.passed ? "is-ok" : "is-bad"),
      L(c.passed ? "hn.g.roundPassed" : "hn.g.roundFailed", { n: c.round, time: formatDateTime(c.at).slice(11, 16) }) + verdict));
    c.platform.forEach(p => {
      const row = hnEl2("div", "hn-check");
      row.append(hnEl2("span", "hn-exit " + (p.passed ? "is-ok" : "is-bad"), p.passed ? (p.warning ? "⚠️" : "✅") : "❌"),
        hnEl2("b", null, p.name), hnEl2("span", "hn-check__time", p.detail));
      round.appendChild(row);
    });
    if (!c.passed && c.issues.length) {
      const ul = hnEl2("ul", "hn-issues");
      c.issues.forEach(i => ul.appendChild(hnEl2("li", null, i)));
      round.appendChild(ul);
    }
    list.appendChild(round);
  });
  box.appendChild(list);
}

/** What it produced: file cards (preview, download), then its closing note; a stop for the user says why first. */
function renderGeneralOutput(box, h, text, t) {
  box.replaceChildren();
  const out = hnEl2("div", "hn-out");
  const waiting = h.steps.find(s => s.status === "waiting");
  if (waiting && waiting.note) {
    const note = hnEl2("div", "hn-gnote is-wait markdown");
    note.innerHTML = renderMarkdown(waiting.note);
    out.appendChild(note);
  }
  const files = task.filesList || [];
  if (files.length) {
    const sec = hnEl2("section");
    const sh = hnEl2("div", "hn-section__head");
    sh.append(hnEl2("h3", null, L("hn.g.files", { n: files.length })));
    const grid = hnEl2("div", "hn-cards");
    files.forEach(f => {
      const card = hnEl2("div", "hn-card");
      const name = hnEl2("div", "hn-card__name");
      name.append(hnEl2("span", "hn-card__icon", fileIcon(f)), hnEl2("span", null, f.name));
      const meta = hnEl2("div", "hn-card__meta", f.kind === "link" ? L("hn.g.link") : formatBytes(f.size));
      const acts = hnEl2("div", "hn-card__acts");
      const view = hnEl2("button", "link-btn", L("hn.g.preview"));
      view.type = "button";
      view.addEventListener("click", () => openFile(f));
      acts.appendChild(view);
      if (f.kind !== "link") {
        const dl = hnEl2("a", "link-btn", L("hn.g.download"));
        dl.href = fileContentUrl(f.id, true);
        acts.appendChild(dl);
      }
      card.append(name, meta, acts);
      grid.appendChild(card);
    });
    sec.append(sh, grid);
    out.appendChild(sec);
  }
  const sec = hnEl2("section");
  const sh = hnEl2("div", "hn-section__head");
  sh.append(hnEl2("h3", null, L("hn.g.note")));
  sec.appendChild(sh);
  if (text) {
    const body = hnEl2("div", "hn-gnote markdown");
    body.innerHTML = renderMarkdown(text);
    sec.appendChild(body);
  } else {
    sec.appendChild(hnEl2("p", "task-output__empty",
      L(t && (t.status === "error" || t.status === "stopped") ? "tasks.outputNone" : "tasks.outputWaiting")));
  }
  out.appendChild(sec);
  box.appendChild(out);
  return true;
}
