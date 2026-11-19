// SuperAgent UI — A subtitle task's detail: a summary card and seven steps above, then the subtitles as they stand,
// every disputed span side by side (what each speech model heard, what each text model wrote), the doubts to fix
// (a fix is learnt into the glossary), and the terms they were spelt by.
// Classic script sharing one global scope with the others; index.html loads it after task-harness.js.
"use strict";

Object.assign(api, {
  taskSubtitle: id => fetch("/api/v1/tasks/" + encodeURIComponent(id) + "/subtitle").then(kbJson),
  fixSubtitle: (id, span, text) => fetch("/api/v1/tasks/" + encodeURIComponent(id) + "/subtitle/disputes/"
    + encodeURIComponent(span), { method: "PUT", headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ text }) }).then(kbJson),
});

const sbEl = {
  tabs: { ssub: document.getElementById("hnTabSubs"), scmp: document.getElementById("hnTabCompare"),
    sdoubt: document.getElementById("hnTabDoubts"), sterm: document.getElementById("hnTabTerms") },
  subs: document.getElementById("sbSubs"),
  compare: document.getElementById("sbCompare"),
  doubts: document.getElementById("sbDoubts"),
  terms: document.getElementById("sbTerms"),
};
const sb = { task: null, key: null, data: null, onlyDisputed: true, busy: false };

function sbShowTabs(on) {
  Object.values(sbEl.tabs).forEach(b => { b.hidden = !on; });
}

/** Draws the card and steps now, and the four tabs once the result is fetched (redrawn only when it changed). */
function renderSubtitle(h, t) {
  renderHarnessPipe(h);
  if (sb.task !== t.id) { sb.task = t.id; sb.key = null; sb.data = null; }
  sbCard(h, t, sb.data);
  sbCounts(sb.data);
  if (!sb.data) sbEmpty();
  api.taskSubtitle(t.id).then(d => {
    if (sb.task !== t.id) return;
    const key = JSON.stringify(d);
    // A redraw would throw away a fix being typed.
    if (key === sb.key || sb.busy || sbEl.doubts.contains(document.activeElement)) return;
    sb.key = key;
    sb.data = d;
    sbCard(h, t, d);
    sbCounts(d);
    sbDraw(d);
  }).catch(() => {});
}

function sbEmpty() {
  [sbEl.subs, sbEl.compare, sbEl.doubts, sbEl.terms].forEach(p => p.replaceChildren(hnEl2("p", "kb-empty", L("sb.notYet"))));
}

function sbCard(h, t, d) {
  hnEl.env.replaceChildren();
  const kv = (label, value, tip) => {
    const div = hnEl2("div", "hn-kv");
    div.append(hnEl2("span", null, label), hnEl2("b", null, value || "—"));
    if (tip) div.dataset.tip = tip;
    hnEl.env.appendChild(div);
  };
  const current = h.steps.find(s => s.status !== "done");
  kv(L("hn.g.status"), (TASK_STATUS_LABEL[t.status] || t.status)
    + (current && current.status !== "pending" ? " · " + hnStepName(h, current) : ""));
  kv(L("sb.audio"), d && d.audio);
  kv(L("sb.refs"), d ? (d.references.length ? d.references.join("、") : L("sb.none")) : "");
  kv(L("sb.speech"), d && d.models.length ? L("sb.count", { n: d.models.length }) : "",
    d && d.models.length ? d.models.map(m => m === d.skeleton ? m + " · " + L("sb.skeleton") : m).join("\n")
      + (d.failed.length ? "\n✕ " + d.failed.join("\n✕ ") : "") : null);
  kv(L("sb.calibrators"), d && d.calibrators.length ? L("sb.count", { n: d.calibrators.length }) : d ? L("sb.none") : "",
    d && d.calibrators.length ? d.calibrators.join("\n") : null);
  kv(L("tasks.spend"), "$" + Number(t.spend || 0).toFixed(2));
}

function sbCounts(d) {
  const doubts = d ? d.disputes.filter(x => x.doubt).length : "";
  sbEl.tabs.ssub.replaceChildren(L("sb.tabSubs"), hnCount(d ? d.cues.length : ""));
  sbEl.tabs.scmp.replaceChildren(L("sb.tabCompare"), hnCount(d ? d.disputes.length : ""));
  sbEl.tabs.sdoubt.replaceChildren(L("sb.tabDoubts"), hnCount(doubts));
  sbEl.tabs.sterm.replaceChildren(L("sb.tabTerms"), hnCount(d ? d.terms.length : ""));
}

function sbDraw(d) {
  if (!d) { sbEmpty(); return; }
  sbSubs(d);
  sbCompare(d);
  sbDoubts(d);
  sbTerms(d);
}

const sbTime = s => {
  const ms = Math.round(s * 1000);
  const p = (n, w) => String(n).padStart(w, "0");
  return p(Math.floor(ms / 3600000), 2) + ":" + p(Math.floor(ms / 60000) % 60, 2) + ":" + p(Math.floor(ms / 1000) % 60, 2)
    + "," + p(ms % 1000, 3);
};

function sbSubs(d) {
  sbEl.subs.replaceChildren();
  const bar = hnEl2("div", "sb-bar");
  if (d.srtFileId) {
    const a = hnEl2("a", "btn btn--primary btn--sm", L("sb.download"));
    a.href = "/api/v1/files/" + encodeURIComponent(d.srtFileId) + "/content?download=true";
    bar.append(a, hnEl2("span", "sb-muted", L("sb.inFiles")));
  } else {
    bar.append(hnEl2("span", "sb-muted", L("sb.preview")));
  }
  sbEl.subs.appendChild(bar);
  if (!d.cues.length) { sbEl.subs.appendChild(hnEl2("p", "kb-empty", L("sb.noCues"))); return; }
  const table = hnEl2("table", "sb-table");
  const head = hnEl2("tr");
  ["#", L("sb.time"), L("sb.text")].forEach(x => head.appendChild(hnEl2("th", null, x)));
  table.appendChild(head);
  d.cues.forEach(c => {
    const tr = hnEl2("tr");
    tr.append(hnEl2("td", "sb-n", String(c.n)), hnEl2("td", "sb-tc", sbTime(c.start) + " → " + sbTime(c.end)),
      hnEl2("td", "sb-cue", c.text));
    table.appendChild(tr);
  });
  sbEl.subs.appendChild(table);
}

/** Each disputed span: what every speech model heard (differences marked), what was taken and on whose word. */
function sbCompare(d) {
  sbEl.compare.replaceChildren();
  const bar = hnEl2("div", "sb-bar");
  bar.appendChild(hnEl2("span", "sb-muted", L(d.single ? "sb.compareSingle" : "sb.compareHint")));
  sbEl.compare.appendChild(bar);
  if (!d.disputes.length) { sbEl.compare.appendChild(hnEl2("p", "kb-empty", L("sb.noDisputes"))); return; }
  const table = hnEl2("table", "sb-table sb-compare");
  const head = hnEl2("tr");
  [L("sb.time"), ...d.models, L("sb.taken")].forEach(x => head.appendChild(hnEl2("th", null, x)));
  table.appendChild(head);
  d.disputes.forEach(x => {
    const tr = hnEl2("tr");
    tr.appendChild(hnEl2("td", "sb-tc", sbTime(x.start)));
    const key = s => (s || "").toLowerCase().replace(/[\s\p{P}]/gu, "");
    x.heard.forEach(h => {
      const td = hnEl2("td");
      const same = key(h) === key(x.userText != null ? x.userText : x.text);
      td.appendChild(hnEl2("span", same ? "sb-same" : "sb-diff", h || L("sb.nothing")));
      tr.appendChild(td);
    });
    const taken = hnEl2("td");
    taken.appendChild(hnEl2("span", "sb-taken", x.userText != null ? x.userText : x.text || L("sb.nothing")));
    taken.appendChild(hnEl2("span", "sb-pill" + (x.doubt && x.userText == null ? " is-doubt" : ""),
      x.userText != null ? L("sb.fixedByUser") : x.doubt ? L("sb.doubt") : L("sb.adopted")));
    const why = hnEl2("div", "sb-why", x.reason);
    const cal = Object.entries(x.calibrated || {});
    if (cal.length) why.title = cal.map(([m, v]) => m + "：" + v).join("\n");
    taken.appendChild(why);
    tr.appendChild(taken);
    table.appendChild(tr);
  });
  sbEl.compare.appendChild(table);
}

/** The doubts, each editable: saving rewrites the subtitles and teaches the glossary. */
function sbDoubts(d) {
  sbEl.doubts.replaceChildren();
  const doubts = d.disputes.filter(x => x.doubt);
  sbEl.doubts.appendChild(hnEl2("div", "sb-note", L("sb.doubtsHint")));
  if (!doubts.length) { sbEl.doubts.appendChild(hnEl2("p", "kb-empty", L("sb.noDoubts"))); return; }
  doubts.forEach(x => {
    const box = hnEl2("div", "sb-doubt" + (x.userText != null ? " is-fixed" : ""));
    const top = hnEl2("div", "sb-doubt__head");
    top.append(hnEl2("span", "sb-tc", sbTime(x.start) + " → " + sbTime(x.end)),
      hnEl2("span", "sb-muted", x.slide ? L("sb.slide", { s: x.slide }) : ""));
    const heard = hnEl2("div", "sb-doubt__line");
    heard.append(hnEl2("b", null, L("sb.heard") + "："),
      d.models.map((m, i) => m + "「" + (x.heard[i] || "") + "」").join(" · "));
    const cal = Object.entries(x.calibrated || {});
    const calLine = hnEl2("div", "sb-doubt__line");
    calLine.append(hnEl2("b", null, L("sb.calibrated") + "："), cal.length ? cal.map(([m, v]) => m + "「" + v + "」").join(" · ") : "—");
    const input = hnEl2("input", "sb-doubt__input");
    input.value = x.userText != null ? x.userText : x.text;
    const why = hnEl2("div", "sb-why", L("sb.kept") + "：" + x.reason);
    const save = hnEl2("button", "btn btn--primary btn--sm", L("common.save"));
    save.type = "button";
    const status = hnEl2("span", "sb-muted", x.userText != null ? L("sb.fixedByUser") : "");
    save.addEventListener("click", async () => {
      save.disabled = true;
      sb.busy = true;
      try {
        const res = await api.fixSubtitle(sb.task, x.id, input.value);
        sb.busy = false;
        sb.key = JSON.stringify(res);
        sb.data = res;
        showToast(L("sb.saved"));
        sbCounts(res);
        sbDraw(res);
      } catch (e) {
        sb.busy = false;
        save.disabled = false;
        status.textContent = e.message;
      }
    });
    const actions = hnEl2("div", "sb-doubt__actions");
    actions.append(save, status);
    box.append(top, heard, calLine, input, why, actions);
    sbEl.doubts.appendChild(box);
  });
}

function sbTerms(d) {
  sbEl.terms.replaceChildren();
  sbEl.terms.appendChild(hnEl2("div", "sb-bar", L("sb.termsHint", { n: d.termsFound, added: d.termsAdded })));
  if (!d.terms.length) { sbEl.terms.appendChild(hnEl2("p", "kb-empty", L("sb.noTerms"))); return; }
  const table = hnEl2("table", "sb-table");
  const head = hnEl2("tr");
  [L("sb.term"), L("sb.aliases"), L("sb.category"), L("sb.scope"), L("sb.source")].forEach(x => head.appendChild(hnEl2("th", null, x)));
  table.appendChild(head);
  d.terms.forEach(x => {
    const tr = hnEl2("tr");
    const src = L("sb.src." + x.source) === "sb.src." + x.source ? x.source : L("sb.src." + x.source);
    tr.append(hnEl2("td", "sb-term", x.term), hnEl2("td", "sb-muted", (x.aliases || []).join("、") || "—"),
      hnEl2("td", null, x.category ? (L("sb.cat." + x.category) === "sb.cat." + x.category ? x.category : L("sb.cat." + x.category)) : "—"),
      hnEl2("td", null, L(x.projectId ? "sb.scopeProject" : "sb.scopeCompany")),
      hnEl2("td", "sb-muted", src + (x.sourceRef ? " · " + x.sourceRef : "")));
    table.appendChild(tr);
  });
  sbEl.terms.appendChild(table);
}
