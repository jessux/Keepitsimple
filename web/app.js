// KIS web : mêmes commandes vocales que l'app Android, tâches stockées sur l'appareil
const $ = id => document.getElementById(id);
const store = {
  load() { try { return JSON.parse(localStorage.getItem("kis_tasks")) || []; } catch { return []; } },
  save(t) { try { localStorage.setItem("kis_tasks", JSON.stringify(t)); } catch {} }
};
let tasks = store.load();
const fmtDay = new Intl.DateTimeFormat("fr-FR", { weekday: "long", day: "numeric", month: "long" });
const fmtTime = new Intl.DateTimeFormat("fr-FR", { hour: "2-digit", minute: "2-digit" });

function sameDay(a, b) { return a.toDateString() === b.toDateString(); }
function dueLabel(ts) {
  const d = new Date(ts), now = new Date(), tom = new Date(now); tom.setDate(now.getDate() + 1);
  const day = sameDay(d, now) ? "Aujourd'hui" : sameDay(d, tom) ? "Demain" : fmtDay.format(d).replace(/^./, c => c.toUpperCase());
  return `${day} à ${fmtTime.format(d).replace(":", "h")}`;
}
function spoken(t) { return t.title + (t.due ? ", " + dueLabel(t.due).toLowerCase() : ""); }
const open = () => tasks.filter(t => !t.done).sort((a, b) => (a.due ?? Infinity) - (b.due ?? Infinity));

function save() { store.save(tasks); render(); }
function render() {
  const list = open(), done = tasks.filter(t => t.done);
  list.forEach((t, i) => t.num = i + 1);
  $("list").innerHTML = list.length ? "" : '<div class="empty">Aucune tâche</div>';
  list.forEach(t => $("list").append(item(t)));
  $("done").innerHTML = ""; done.slice(-10).reverse().forEach(t => $("done").append(item(t)));
  $("doneTitle").hidden = !done.length;
}
function item(t) {
  const li = document.createElement("li"), now = Date.now();
  if (t.done) li.className = "done";
  else if (t.due && t.due < now) li.className = "late";
  else if (t.due && sameDay(new Date(t.due), new Date())) li.className = "today";
  const due = t.due ? dueLabel(t.due) : "Sans échéance";
  li.innerHTML = `<div class="num"></div><div class="body"><div class="title"></div><div class="due"></div></div>
    <button data-a="done" title="${t.done ? "Rouvrir" : "Terminer"}">${t.done ? "↺" : "✓"}</button><button data-a="del" title="Supprimer">✕</button>`;
  li.querySelector(".num").textContent = t.done ? "✓" : t.num;
  li.querySelector(".title").textContent = t.title;
  li.querySelector(".due").textContent = due + (t.repeat ? " · " + KIS.repeatLabel(t.repeat) : "");
  li.querySelector('[data-a="done"]').onclick = () => toggle(t);
  li.querySelector('[data-a="del"]').onclick = () => remove(t);
  li.querySelector(".body").onclick = () => edit(t);
  return li;
}

function toggle(t, announce) {
  if (!t.done && t.repeat && t.due) {
    let d = new Date(t.due); do d = KIS.nextRepeat(d, t.repeat); while (d <= new Date());
    t.due = d.getTime(); t.notified = false;
    if (announce) say(`${t.title} terminée, prochaine fois ${dueLabel(t.due).toLowerCase()}`);
  } else { t.done = !t.done; if (announce) say(`${t.title} terminée`); }
  save();
}
function remove(t, announce) { tasks = tasks.filter(x => x !== t); save(); if (announce) say(`${t.title} supprimée`); }

function edit(t) {
  $("eTitle").value = t.title;
  $("eDue").value = t.due ? new Date(t.due - new Date(t.due).getTimezoneOffset() * 60000).toISOString().slice(0, 16) : "";
  $("edit").onclose = () => {
    if ($("edit").returnValue !== "ok") return;
    t.title = $("eTitle").value.trim() || t.title;
    t.due = $("eDue").value ? new Date($("eDue").value).getTime() : null; t.notified = false;
    save();
  };
  $("edit").showModal();
}

// Trouve une tâche par numéro (« la 3 », « numéro trois ») ou par mots du titre
function find(words) {
  const n = KIS.taskNumber(words);
  if (n != null) return open().find(t => t.num === n);
  const w = words.toLowerCase().trim(); if (!w) return null;
  return open().find(t => t.title.toLowerCase().includes(w)) || tasks.find(t => t.title.toLowerCase().includes(w));
}

function handle(text) {
  const t = text.trim(); if (!t) return;
  $("heard").textContent = "« " + t + " »";
  let m;
  if (/^(lis|lire|liste|quelles sont)\b/i.test(t)) {
    const l = open(); say(l.length ? `${l.length} tâches. ` + l.map(x => `${x.num} : ${spoken(x)}`).join(". ") : "Aucune tâche");
  } else if (/^(aide|comment ça marche)/i.test(t)) {
    say("Dites une tâche avec sa date, par exemple appeler Paul demain à 18 heures. Dites lis mes tâches, terminé la 2, ou supprime la 3.");
  } else if ((m = /^(supprime|efface|enlève|retire)\s*(la tâche\s*)?(.*)$/i.exec(t))) {
    const x = find(m[3]); x ? remove(x, true) : say("Je n'ai pas trouvé cette tâche");
  } else if ((m = /^(terminé|termine|j'ai fini|j'ai fait|fait|valide)(?=\s|$)\s*(la tâche\s*)?(.*)$/i.exec(t)) && find(m[3])) {
    toggle(find(m[3]), true);
  } else {
    const p = KIS.parse(t);
    tasks.push({ id: Date.now(), title: p.title, due: p.due, repeat: p.repeat, done: false });
    save();
    say(`Ajouté : ${p.title}` + (p.due ? ", " + dueLabel(p.due).toLowerCase() : "") + (p.repeat ? ", " + KIS.repeatLabel(p.repeat).toLowerCase() : ""));
  }
}

// Voix
function say(text) {
  $("heard").textContent = text;
  if (!("speechSynthesis" in window)) return;
  speechSynthesis.cancel();
  const u = new SpeechSynthesisUtterance(text); u.lang = "fr-FR";
  u.onstart = () => $("mic").classList.add("speaking");
  u.onend = u.onerror = () => $("mic").classList.remove("speaking");
  speechSynthesis.speak(u);
}
const SR = window.SpeechRecognition || window.webkitSpeechRecognition;
let rec = null;
$("mic").onclick = () => {
  if (!SR) return say("La reconnaissance vocale n'est pas disponible ici, tapez votre tâche");
  if (rec) { rec.stop(); return; }
  speechSynthesis?.cancel();
  rec = new SR(); rec.lang = "fr-FR"; rec.interimResults = true; rec.maxAlternatives = 1;
  let final = "";
  rec.onstart = () => { $("mic").classList.add("listening"); $("heard").textContent = "Je vous écoute…"; };
  rec.onresult = e => { const r = e.results[e.results.length - 1]; $("heard").textContent = r[0].transcript; if (r.isFinal) final = r[0].transcript; };
  rec.onerror = e => { if (e.error === "not-allowed") say("Autorisez le micro pour KIS dans les réglages"); };
  rec.onend = () => { $("mic").classList.remove("listening"); rec = null; if (final) handle(final); };
  rec.start();
};
$("form").onsubmit = e => { e.preventDefault(); handle($("input").value); $("input").value = ""; $("input").blur(); };

// Rappels : vérifiés tant que l'app est ouverte (les rappels app fermée demanderont un serveur)
let swReg = null;
if ("serviceWorker" in navigator) navigator.serviceWorker.register("sw.js").then(r => swReg = r).catch(() => {});
$("notif").onclick = async () => {
  if (!("Notification" in window)) return say("Installez d'abord KIS sur l'écran d'accueil pour activer les rappels");
  const p = await Notification.requestPermission();
  say(p === "granted" ? "Rappels activés" : "Rappels refusés");
};
function checkDue() {
  const now = Date.now(); let changed = false;
  for (const t of tasks) if (!t.done && t.due && t.due <= now && !t.notified) {
    t.notified = changed = true;
    if (window.Notification?.permission === "granted") swReg ? swReg.showNotification("KIS", { body: t.title, icon: "icon-192.png", tag: String(t.id) }) : new Notification("KIS", { body: t.title });
  }
  if (changed) store.save(tasks);
  render();
}
setInterval(checkDue, 30000);
document.addEventListener("visibilitychange", () => { if (!document.hidden) checkDue(); });

// Astuce d'installation sur iPhone/iPad dans Safari
const ios = /iphone|ipad|ipod/i.test(navigator.userAgent) || (navigator.platform === "MacIntel" && navigator.maxTouchPoints > 1);
if (ios && !navigator.standalone) $("install").style.display = "block";
render(); checkDue();
