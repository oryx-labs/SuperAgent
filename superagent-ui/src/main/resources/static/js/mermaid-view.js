// Mermaid diagrams in rendered Markdown (the Files preview, a writing task's final draft). The library is the local
// copy under /vendor, loaded the first time a document has a diagram: no external address is ever called, and a
// document without diagrams costs nothing. A diagram that doesn't parse stays as its source, with the reason above it.

let mermaidLoading = null;
let mermaidSeq = 0;

function loadMermaid() {
  if (window.mermaid) return Promise.resolve(window.mermaid);
  if (!mermaidLoading) {
    mermaidLoading = new Promise((resolve, reject) => {
      const s = document.createElement("script");
      s.src = "/vendor/mermaid.min.js";
      s.onload = () => {
        // Plain SVG text (no HTML labels) so the result can be sanitized; black, white and grey with the brand blue.
        window.mermaid.initialize({
          startOnLoad: false, securityLevel: "strict", theme: "neutral", flowchart: { htmlLabels: false },
          themeVariables: { primaryColor: "#f4f4f5", primaryBorderColor: "#52525b", lineColor: "#52525b", primaryTextColor: "#18181b",
            secondaryColor: "#e4e4e7", tertiaryColor: "#fafafa", noteBkgColor: "#eff6ff", noteBorderColor: "#2563eb" },
        });
        resolve(window.mermaid);
      };
      s.onerror = () => { mermaidLoading = null; reject(new Error("mermaid.min.js did not load")); };
      document.head.appendChild(s);
    });
  }
  return mermaidLoading;
}

/** Turns each ```mermaid code block under {@code root} into its diagram. Resolves when all are done. */
async function renderMermaidBlocks(root) {
  const blocks = [...root.querySelectorAll("pre > code.language-mermaid")];
  if (!blocks.length) return;
  let mermaid;
  try {
    mermaid = await loadMermaid();
  } catch (e) {
    blocks.forEach(code => mermaidFailed(code.parentElement, e));
    return;
  }
  for (const code of blocks) {
    const pre = code.parentElement;
    const id = "mmd-" + (++mermaidSeq);
    try {
      const { svg } = await mermaid.render(id, code.textContent);
      const fig = document.createElement("div");
      fig.className = "mermaid-diagram";
      fig.innerHTML = window.DOMPurify.sanitize(svg, { USE_PROFILES: { svg: true, svgFilters: true } });
      pre.replaceWith(fig);
    } catch (e) {
      mermaidFailed(pre, e);
    } finally {
      document.getElementById("d" + id)?.remove(); // what a failed render leaves in the page
    }
  }
}

function mermaidFailed(pre, error) {
  const note = document.createElement("p");
  note.className = "mermaid-error__note";
  note.textContent = L("mermaid.error", { msg: String(error && error.message || error).split("\n")[0] });
  pre.classList.add("mermaid-error");
  pre.before(note);
}
