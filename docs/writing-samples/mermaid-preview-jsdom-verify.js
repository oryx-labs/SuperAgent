// Loads the real index.html and its scripts in jsdom (no real browser), renders a Markdown file the way the Files
// preview does, and reports what the Mermaid blocks became.
const path = require("path"), fs = require("fs");
const { JSDOM, ResourceLoader, VirtualConsole } = require("/private/tmp/sa-fold/node_modules/jsdom");
const STATIC = process.argv[2];
class Loader extends ResourceLoader {
  fetch(url) {
    const u = new URL(url);
    if (u.hostname !== "localhost") return Promise.reject(new Error("external address refused: " + url));
    const f = path.join(STATIC, u.pathname);
    return fs.existsSync(f) && fs.statSync(f).isFile() ? Promise.resolve(fs.readFileSync(f)) : Promise.resolve(Buffer.from(""));
  }
}
const external = [];
const vc = new VirtualConsole();
vc.on("jsdomError", e => { if (!/Could not load|not implemented/i.test(String(e.message))) console.log("[jsdom]", String(e.message).split("\n")[0]); });
const html = fs.readFileSync(path.join(STATIC, "index.html"), "utf8");
const dom = new JSDOM(html, { url: "http://localhost:18080/", runScripts: "dangerously", resources: new Loader(), pretendToBeVisual: true, virtualConsole: vc,
  beforeParse(w) {
    w.fetch = (u) => { if (!/^\/|localhost/.test(String(u))) external.push(String(u)); return Promise.resolve({ ok: false, status: 401, json: async () => ({}), text: async () => "" }); };
    // jsdom has no layout: give SVG text a size so Mermaid can place nodes.
    w.SVGElement.prototype.getBBox = function () { const t = (this.textContent || "").length; return { x: 0, y: 0, width: Math.max(10, t * 8), height: 18 }; };
    w.SVGElement.prototype.getComputedTextLength = function () { return (this.textContent || "").length * 8; };
    w.HTMLElement.prototype.getBoundingClientRect = function () { return { x: 0, y: 0, top: 0, left: 0, right: 100, bottom: 20, width: 100, height: 20 }; };
  } });
const w = dom.window;
w.addEventListener("error", e => console.log("[window error]", e.message));
setTimeout(async () => {
  try {
    const md = fs.readFileSync(process.argv[3], "utf8");
    const doc = w.markdownDocument(md);
    w.document.body.appendChild(doc);
    for (let i = 0; i < 100 && doc.querySelector("pre > code.language-mermaid:not(.done)") && !doc.querySelector(".mermaid-error"); i++) {
      await new Promise(r => setTimeout(r, 200));
      if (doc.querySelectorAll(".mermaid-diagram").length >= (md.match(/```mermaid/g) || []).length) break;
    }
    await new Promise(r => setTimeout(r, 500));
    const figs = [...doc.querySelectorAll(".mermaid-diagram")];
    console.log("mermaid blocks in source:", (md.match(/```mermaid/g) || []).length);
    console.log("rendered diagrams (.mermaid-diagram with <svg>):", figs.filter(f => f.querySelector("svg")).length);
    figs.forEach((f, i) => console.log("  diagram", i + 1, "svg nodes/texts:", f.querySelectorAll("svg text, svg tspan").length, "labels:", [...f.querySelectorAll("svg text")].map(t => t.textContent.trim()).filter(Boolean).slice(0, 8).join(" | ")));
    if (process.argv[4]) { const f = figs[0]; console.log("diagram 1 contains node names:", ["TaskService", "ArticleHarness", "SkillService"].map(n => n + "=" + f.innerHTML.includes(n)).join(" ")); }
    const errs = [...doc.querySelectorAll(".mermaid-error__note")].map(n => n.textContent);
    console.log("broken diagrams shown as source:", doc.querySelectorAll("pre.mermaid-error").length, errs);
    console.log("script tags with an external src:", [...w.document.querySelectorAll("script[src]")].filter(s => !s.getAttribute("src").startsWith("/")).length, "| external fetches:", external.length);
  } catch (e) { console.log("FAILED", e && e.stack || e); }
  process.exit(0);
}, 1500);
