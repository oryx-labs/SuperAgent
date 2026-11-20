// SuperAgent UI — A writing task's detail: a summary card and eight steps above, then the draft as it stands (终稿),
// the brief and the outline the user confirmed (提纲), the first draft next to the current one (初稿), and every
// platform check and review round (评审意见).
// Classic script sharing one global scope with the others; index.html loads it after task-harness.js.
"use strict";

const atEl = {
  tabs: { afinal: document.getElementById("hnTabFinal"), aoutline: document.getElementById("hnTabOutline"),
    adraft: document.getElementById("hnTabDraft"), areview: document.getElementById("hnTabReviews") },
  final: document.getElementById("atFinal"),
  outline: document.getElementById("atOutline"),
  draft: document.getElementById("atDraft"),
  review: document.getElementById("atReview"),
};
const AT_FIRST = ["afinal", "aoutline", "adraft", "areview", "run"];
const at = { task: null, key: null, text: null };

function atShowTabs(on) {
  Object.values(atEl.tabs).forEach(b => { b.hidden = !on; });
}

/** Han characters and Latin words, counted the way the platform counts them. */
function atCount(text) {
  const prose = (text || "").replace(/```[\s\S]*?```/g, "").split("\n").filter(l => !/^#{1,6}\s/.test(l)).join("\n");
  return (prose.match(/\p{Script=Han}|[\p{L}\p{N}]+/gu) || []).length;
}

function renderArticle(h, t) {
  const a = h.article || {};
  renderHarnessPipe(h);
  atCard(h, t, a);
  const reviews = a.reviews || [];
  atEl.tabs.afinal.replaceChildren(L(t.status === "done" ? "hn.a.tabFinal" : "hn.a.tabCurrent"));
  atEl.tabs.aoutline.replaceChildren(L("hn.a.tabOutline"), hnCount(a.outlineApproved ? "✓" : ""));
  atEl.tabs.adraft.replaceChildren(L("hn.a.tabDraft"));
  atEl.tabs.areview.replaceChildren(L("hn.a.tabReview"), hnCount(reviews.length || ""));
  tel.tabs.querySelector('[data-tab="run"]').textContent = L("hn.a.tabRun");
  if (at.task !== t.id) { at.task = t.id; at.key = null; at.text = null; }
  renderArticleOutline(a);
  renderArticleReview(a);
  const key = [a.draftFileId, (a.checks || []).length, (a.revisions || []).length, t.status].join("|");
  if (!a.draftFileId) {
    at.text = null;
    renderArticleFinal(a, t, null);
    renderArticleDraft(a, null);
    return;
  }
  renderArticleFinal(a, t, at.text);
  renderArticleDraft(a, at.text);
  if (key === at.key) return;
  fetch(fileContentUrl(a.draftFileId)).then(r => r.ok ? r.text() : Promise.reject(r.status)).then(text => {
    if (at.task !== t.id) return;
    at.key = key;
    at.text = text;
    renderArticleFinal(a, t, text);
    renderArticleDraft(a, text);
  }).catch(() => {});
}

/** Status, models (the cheaper one that checked too), time, spend and the step it is on. */
function atCard(h, t, a) {
  hnEl.env.replaceChildren();
  const kv = (label, value, tip) => {
    const d = hnEl2("div", "hn-kv");
    d.append(hnEl2("span", null, label), hnEl2("b", null, value || "—"));
    if (tip) d.dataset.tip = tip;
    hnEl.env.appendChild(d);
  };
  const reviews = a.reviews || [];
  const checker = [...reviews].reverse().map(r => r.checkerModel).find(Boolean);
  const current = h.steps.find(s => s.status !== "done");
  const b = a.brief;
  kv(L("hn.g.status"), TASK_STATUS_LABEL[t.status] || t.status);
  kv(L("tasks.model"), taskModelLabel(t) + (checker ? " · " + L("hn.g.checkedOn", { m: checker }) : ""),
    checker ? L("hn.a.checkerTip") : null);
  if (b) kv(L("hn.a.genre"), (b.genre || "—") + (b.words ? " · " + L("hn.a.words", { n: b.words }) : ""));
  kv(L("tasks.elapsed"), formatElapsed(t));
  kv(L("tasks.spend"), "$" + Number(t.spend || 0).toFixed(2));
  kv(L("hn.g.current"), current ? hnStepName(h, current) + (current.status === "waiting" ? " · " + L("hn.waiting") : "")
    : L("hn.g.delivered"));
}

function atHead(box, title, sum) {
  const head = hnEl2("div", "hn-section__head");
  head.append(hnEl2("h3", null, title));
  if (sum) head.append(hnEl2("span", "hn-section__sum", sum));
  box.appendChild(head);
}

function atMarkdown(text) {
  const d = hnEl2("div", "markdown at-doc");
  d.innerHTML = renderMarkdown(text || "");
  renderMermaidBlocks(d);
  return d;
}

// --- 终稿 ---

function renderArticleFinal(a, t, text) {
  const box = atEl.final;
  box.replaceChildren();
  const checks = a.checks || [], reviews = a.reviews || [];
  const lastCheck = checks[checks.length - 1], lastReview = reviews[reviews.length - 1];
  const warns = lastCheck ? lastCheck.items.filter(i => i.level !== "pass").length : 0;
  const sum = [text != null ? L("hn.a.words", { n: atCount(text) }) : null,
    lastReview ? L("hn.a.score", { n: lastReview.total }) : null,
    lastCheck ? L("hn.a.warnings", { n: warns }) : null].filter(Boolean).join(" · ");
  atHead(box, L(t.status === "done" ? "hn.a.final" : "hn.a.current"), sum);
  if (!a.draftFileId) {
    box.append(hnEl2("p", "kb-empty", L("hn.a.noDraft")));
    return;
  }
  if (typeof fileCard === "function") {
    const cards = hnEl2("div", "file-cards");
    cards.appendChild(fileCard({ id: a.draftFileId, name: a.draftName || "article.md" }));
    box.appendChild(cards);
  }
  box.appendChild(text == null ? hnEl2("p", "kb-empty", L("hn.a.loading")) : atMarkdown(text));
}

// --- 提纲 ---

function renderArticleOutline(a) {
  const box = atEl.outline;
  box.replaceChildren();
  const b = a.brief;
  atHead(box, L("hn.a.brief"));
  if (!b) {
    box.append(hnEl2("p", "kb-empty", L("hn.a.noBrief")));
  } else {
    const dl = hnEl2("dl", "at-facts");
    [["hn.a.genre", b.genre], ["hn.a.audience", b.audience], ["hn.a.knows", b.knows], ["hn.a.purpose", b.purpose],
      ["hn.a.target", b.words ? L("hn.a.words", { n: b.words }) : null], ["hn.a.style", b.style],
      ["hn.a.exclude", b.exclude]].forEach(([k, v]) => {
      if (!v) return;
      dl.append(hnEl2("dt", null, L(k)), hnEl2("dd", null, v));
    });
    box.appendChild(dl);
  }

  const approvals = a.approvals || [];
  atHead(box, L("hn.a.outline"), a.outline ? (a.outlineApproved
    ? L("hn.a.approved", { by: a.approvedBy || "", time: formatDateTime(approvals[approvals.length - 1]), n: approvals.length })
    : L("hn.a.notApproved")) : "");
  if (!a.outline) {
    box.append(hnEl2("p", "kb-empty", L("hn.a.noOutline")));
  } else {
    if (a.thesis) box.append(hnEl2("p", "at-thesis", L("hn.a.thesis") + "：" + a.thesis));
    if ((a.titles || []).length) {
      const ul = hnEl2("ul", "at-list");
      a.titles.forEach(x => ul.appendChild(hnEl2("li", null, x)));
      box.append(hnEl2("div", "at-label", L("hn.a.titles")), ul);
    }
    const table = hnEl2("table", "ctable at-table");
    const head = hnEl2("tr");
    ["hn.a.section", "hn.a.point", "hn.a.evidence", "hn.a.budget"].forEach(k => head.appendChild(hnEl2("th", null, L(k))));
    table.appendChild(hnEl2("thead")).appendChild(head);
    const body = table.appendChild(hnEl2("tbody"));
    (a.sections || []).forEach(s => {
      const tr = hnEl2("tr");
      tr.append(hnEl2("td", null, s.title), hnEl2("td", null, s.point || ""), hnEl2("td", null, (s.sources || []).join(", ")),
        hnEl2("td", "at-num", String(s.words || "")));
      body.appendChild(tr);
    });
    box.appendChild(table);
  }

  const sources = a.sources || [];
  atHead(box, L("hn.a.sources"), sources.length ? String(sources.length) : "");
  if (!sources.length) {
    box.append(hnEl2("p", "kb-empty", L("hn.a.noSources")));
    return;
  }
  const list = hnEl2("div", "hn-box");
  sources.forEach(s => {
    const row = hnEl2("div", "hn-check");
    row.append(hnEl2("span", "hn-exit " + (s.status === "已读" ? "is-ok" : "is-bad"), s.status === "已读" ? "✅" : "⚠"),
      hnEl2("b", null, s.id + " " + (s.title || "")), hnEl2("span", "hn-check__time", [s.status, s.ref, s.summary].filter(Boolean).join(" · ")));
    list.appendChild(row);
  });
  box.appendChild(list);
}

// --- 初稿 ---

function renderArticleDraft(a, text) {
  const box = atEl.draft;
  box.replaceChildren();
  atHead(box, L("hn.a.tabDraft"), L("hn.a.sideHint"));
  if (!a.draftSnapshot) {
    box.append(hnEl2("p", "kb-empty", L("hn.a.noDraft")));
    return;
  }
  const grid = hnEl2("div", "at-side");
  const col = (title, body, n) => {
    const c = hnEl2("div", "at-side__col");
    c.append(hnEl2("div", "at-label", title + (n != null ? " · " + L("hn.a.words", { n }) : "")), body);
    return c;
  };
  grid.append(col(L("hn.a.snapshot"), atMarkdown(a.draftSnapshot), atCount(a.draftSnapshot)),
    col(L("hn.a.current"), text == null ? hnEl2("p", "kb-empty", L("hn.a.loading")) : atMarkdown(text),
      text == null ? null : atCount(text)));
  box.appendChild(grid);
}

// --- 评审意见 ---

const AT_LEVEL = { pass: "✅", warn: "⚠", fail: "❌" };

function renderArticleReview(a) {
  const box = atEl.review;
  box.replaceChildren();
  const reviews = a.reviews || [], checks = a.checks || [], revisions = a.revisions || [];
  atHead(box, L("hn.a.reviewRounds"), L("hn.a.returns", { review: a.reviewReturns || 0, fix: a.fixRounds || 0, max: a.returns || 2 }));
  if (!reviews.length) box.append(hnEl2("p", "kb-empty", L("hn.a.noReviews")));
  [...reviews].reverse().forEach((r, i) => {
    const d = hnEl2("details", "at-round");
    d.open = i === 0;
    d.appendChild(hnEl2("summary", "hn-round__head " + (r.passed ? "is-ok" : "is-bad"), L("hn.a.reviewRound", {
      n: r.round, time: formatDateTime(r.at).slice(5, 16), score: r.total }) + " · " + L("hn.a.route_" + r.route)));
    d.append(atScores(r), atItems(L("hn.a.editorIssues"), r.editor), atFacts(r), atReader(r),
      atItems(L("hn.a.merged"), r.merged), atResponses(revisionAfter(revisions, r.at, reviews, checks)));
    box.appendChild(d);
  });

  atHead(box, L("hn.a.checkRounds"), L("hn.a.checkHint"));
  if (!checks.length) box.append(hnEl2("p", "kb-empty", L("hn.noRounds")));
  [...checks].reverse().forEach((c, i) => {
    const d = hnEl2("details", "at-round");
    d.open = i === 0 && !reviews.length;
    const fails = c.items.filter(x => x.level === "fail").length, warns = c.items.filter(x => x.level === "warn").length;
    d.appendChild(hnEl2("summary", "hn-round__head " + (c.passed ? "is-ok" : "is-bad"), L("hn.a.checkRound", {
      n: c.round, time: formatDateTime(c.at).slice(5, 16), fails, warns })));
    const list = hnEl2("div");
    [...c.items].sort((x, y) => "fwp".indexOf(x.level[0]) - "fwp".indexOf(y.level[0])).forEach(x => {
      const row = hnEl2("div", "hn-check");
      row.append(hnEl2("span", "hn-exit " + (x.level === "pass" ? "is-ok" : "is-bad"), AT_LEVEL[x.level] || x.level),
        hnEl2("b", null, x.check), hnEl2("span", "hn-check__time", x.note + (x.where ? "（" + x.where + "）" : "")));
      list.appendChild(row);
    });
    d.append(list, atResponses(revisionAfter(revisions, c.at, reviews, checks)));
    box.appendChild(d);
  });
}

/** The revision that answered what was found at {@code at}: the first one after it, before anything else was judged. */
function revisionAfter(revisions, at, reviews, checks) {
  const t = new Date(at);
  const next = [...reviews.map(r => new Date(r.at)), ...checks.map(c => new Date(c.at))].filter(x => x > t).sort((x, y) => x - y)[0];
  return revisions.find(v => new Date(v.at) > t && (!next || new Date(v.at) < next)) || null;
}

function atScores(r) {
  const wrap = hnEl2("div", "at-block");
  wrap.appendChild(hnEl2("div", "at-label", L("hn.a.editor") + " · " + L("hn.a.score", { n: r.total })
    + (r.editorModel ? " · " + r.editorModel : "")));
  const table = hnEl2("table", "ctable at-table");
  const body = table.appendChild(hnEl2("tbody"));
  (r.scores || []).forEach(s => {
    const tr = hnEl2("tr");
    tr.append(hnEl2("td", null, s.name), hnEl2("td", "at-num" + (s.score <= 2 ? " is-bad" : ""), s.score + "/5"),
      hnEl2("td", null, s.quote || ""), hnEl2("td", null, s.advice || ""));
    body.appendChild(tr);
  });
  wrap.appendChild(table);
  if ((r.titles || []).length) wrap.appendChild(hnEl2("p", "at-note", L("hn.a.titles") + "：" + r.titles.join(" / ")));
  return wrap;
}

function atItems(title, items) {
  const wrap = hnEl2("div", "at-block");
  wrap.appendChild(hnEl2("div", "at-label", title + " · " + (items || []).length));
  const ul = hnEl2("ul", "hn-issues");
  (items || []).forEach(i => ul.appendChild(hnEl2("li", "at-sev at-sev--" + (i.severity === "严重" ? "high" : i.severity === "一般" ? "mid" : "low"),
    (i.id ? i.id + " " : "") + "[" + i.severity + "][" + i.type + "] " + i.text + (i.where ? "（" + i.where + "）" : "")
      + (i.from ? " · " + i.from : ""))));
  if (!(items || []).length) ul.appendChild(hnEl2("li", null, L("hn.a.none")));
  wrap.appendChild(ul);
  return wrap;
}

function atFacts(r) {
  const wrap = hnEl2("div", "at-block");
  const facts = r.facts || [];
  const ok = facts.filter(f => f.verdict === "成立").length;
  wrap.appendChild(hnEl2("div", "at-label", L("hn.a.facts") + " · " + ok + "/" + facts.length + (r.checkerModel ? " · " + r.checkerModel : "")));
  const table = hnEl2("table", "ctable at-table");
  const body = table.appendChild(hnEl2("tbody"));
  facts.forEach(f => {
    const tr = hnEl2("tr");
    tr.append(hnEl2("td", null, f.statement), hnEl2("td", null, f.sources || ""),
      hnEl2("td", f.verdict === "成立" ? "is-ok" : "is-bad", f.verdict), hnEl2("td", null, f.evidence || ""));
    body.appendChild(tr);
  });
  if (!facts.length) body.appendChild(hnEl2("tr")).appendChild(hnEl2("td", null, L("hn.a.none")));
  wrap.appendChild(table);
  return wrap;
}

function atReader(r) {
  const wrap = hnEl2("div", "at-block");
  wrap.appendChild(hnEl2("div", "at-label", L("hn.a.reader")));
  const ol = hnEl2("ol", "at-list");
  (r.reader || []).forEach(x => ol.appendChild(hnEl2("li", null, x || "—")));
  wrap.appendChild(ol);
  if (r.retelling) {
    wrap.appendChild(hnEl2("p", "at-note", L("hn.a.retelling") + "：" + r.retelling
      + (r.consistent === false ? " · " + L("hn.a.inconsistent") : r.consistent ? " · " + L("hn.a.consistent") : "")));
  }
  return wrap;
}

function atResponses(v) {
  const wrap = hnEl2("div", "at-block");
  if (!v) return wrap;
  wrap.appendChild(hnEl2("div", "at-label", L("hn.a.responses") + (v.changed ? "" : " · " + L("hn.a.unchanged"))));
  const ul = hnEl2("ul", "hn-issues");
  (v.responses || []).forEach(x => ul.appendChild(hnEl2("li", null, x.id + " " + x.action + (x.note ? "：" + x.note : ""))));
  if (!(v.responses || []).length) ul.appendChild(hnEl2("li", null, L("hn.a.none")));
  wrap.appendChild(ul);
  return wrap;
}
