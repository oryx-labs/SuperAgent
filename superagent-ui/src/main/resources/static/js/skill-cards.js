// SuperAgent UI — Skill deletions proposed in conversation: the confirm card under the reply that proposed one.
// Classic script sharing one global scope with the others; index.html loads them in order.
"use strict";

// An agent can only propose deleting a skill; the person who asked confirms on this card (every agent using it
// loses it). Shown in agents' chats and the SuperAgent's alike, looking like the invitation card.
Object.assign(api, {
  skillDeletions: agentId => fetch("/api/v1/skills/deletions?agentId=" + encodeURIComponent(agentId)).then(kbJson),
  skillDeletionAction: (id, action) =>
    kbPost("/api/v1/skills/deletions/" + encodeURIComponent(id) + "/" + action, {}),
});

async function attachSkillDeletionCards(agentId) {
  if (!agentId) return;
  let list = [];
  try { list = (await api.skillDeletions(agentId)) || []; } catch (e) { return; }
  if (activeAgentId !== agentId) return;
  list.forEach(d => {
    const msg = d.messageId && el.messages.querySelector('.msg[data-mid="' + CSS.escape(d.messageId) + '"]');
    if (!msg) return;
    const old = msg.querySelector('.skdel-card[data-did="' + CSS.escape(d.id) + '"]');
    const card = skillDeletionCard(d);
    if (old) old.replaceWith(card); else (msg.querySelector(".bubble") || msg).appendChild(card);
  });
}

function skillDeletionCard(d) {
  const card = document.createElement("div");
  card.className = "invite-card skdel-card is-" + d.status;
  card.dataset.did = d.id;
  const text = document.createElement("div");
  text.className = "invite-card__text";
  const title = document.createElement("b");
  title.textContent = L("skdel.title", { name: d.skillName || d.skillId });
  const hint = document.createElement("span");
  hint.textContent = L("skdel." + d.status);
  text.append(title, hint);
  card.appendChild(text);
  if (d.status === "pending") {
    const actions = document.createElement("div");
    actions.className = "invite-card__actions";
    const act = (label, action, cls) => {
      const b = document.createElement("button");
      b.type = "button";
      b.className = "btn btn--sm " + cls;
      b.textContent = L(label);
      b.addEventListener("click", async () => {
        actions.querySelectorAll("button").forEach(x => { x.disabled = true; });
        try {
          card.replaceWith(skillDeletionCard(await api.skillDeletionAction(d.id, action)));
          if (action === "confirm") showToast(L("skdel.done", { name: d.skillName || "" }), false);
        } catch (e) {
          showToast(e.message, true);
          actions.querySelectorAll("button").forEach(x => { x.disabled = false; });
        }
      });
      return b;
    };
    actions.append(act("skdel.cancel", "cancel", ""), act("skdel.confirm", "confirm", "btn--danger"));
    card.appendChild(actions);
  }
  return card;
}
