// Port JS de VoiceParser.kt (phrase dictée -> tâche avec échéance)
const KIS = (() => {
  const DEFAULT_HOUR = 9;
  const WD = { lundi: 1, mardi: 2, mercredi: 3, jeudi: 4, vendredi: 5, samedi: 6, dimanche: 0 };
  const MONTHS = ["janvier","février","mars","avril","mai","juin","juillet","août","septembre","octobre","novembre","décembre"];
  const FRACTION = "(?:\\s+(et quart|et demie?|moins le quart|moins (\\d{1,2})))?";
  const prefixes = /^(rappelle[- ]moi|rappel|n'oublie pas|pense à|tâche|ajoute|nouvelle tâche)\s*(de |d'|qu'|que |:)?\s*/i;
  const relative = /\b(?:dans|d'ici)\s+(?:(une demi-heure)|(un quart d'heure)|(\d+|une?)\s+(minutes?|min|heures?|jours?|semaines?|mois)(\s+et demie?)?)\b/i;
  const dayKeywords = /\b(après[- ]demain|demain|aujourd'hui)\b/i;
  const nextWeek = /\b(?:(?:la|pour la)\s+)?semaine prochaine\b/i;
  const weekend = /\b(?:(?:ce|le|pour le)\s+)?week[- ]?end(?:\s+prochain)?\b/i;
  const weekday = new RegExp(`\\b(?:(?:ce|le|pour|jusqu'à)\\s+)?(${Object.keys(WD).join("|")})(?:\\s+prochain)?\\b`, "i");
  const dateNumeric = /\b(?:le\s+)?(\d{1,2})\/(\d{1,2})(?:\/(\d{2,4}))?\b/i;
  const dateText = new RegExp(`\\b(?:le\\s+)?(\\d{1,2}|1er)\\s+(${MONTHS.join("|")})(?:\\s+(\\d{4}))?(?![\\wéû])`, "i");
  const time = new RegExp("(?:(?:\\b(?:vers|pour|avant|dès)|à)\\s+(\\d{1,2})\\s*(?:h|heures?|:)(?:\\s*(\\d{2}))?" +
    "|\\b(\\d{1,2})\\s*heures?(?:\\s*(\\d{2}))?|\\b(\\d{1,2})\\s*[h:]\\s*(\\d{2}))\\b" + FRACTION, "i");
  const namedTime = new RegExp("(?:à\\s+)?\\b(midi|minuit)\\b" + FRACTION + "|(?:\\b(ce|cet|le|du)\\s+|dans l')?\\b(matin|après-midi|soir)\\b", "i");
  const recurrence = new RegExp("\\b(?:tous les|toutes les|chaque)\\s+(?:(\\d+)\\s+)?(jours?|semaines?|mois|ans|années?|matins?|soirs?|midis?|" +
    `(?:${Object.keys(WD).join("|")})s?)\\b|\\b(quotidiennement|quotidien|hebdomadaire|mensuellement|mensuel|annuellement|annuel)\\b`, "i");

  const monthDay = new RegExp(`\\ble\\s+(\\d{1,2}|1er)\\b(?!\\s*/)(?!\\s+(?:${MONTHS.join("|")}))`, "i");
  const day0 = d => new Date(d.getFullYear(), d.getMonth(), d.getDate());
  const addDays = (d, n) => { const r = new Date(d); r.setDate(r.getDate() + n); return r; };
  const nextDow = (d, dow, orSame) => { let n = (dow - d.getDay() + 7) % 7; if (n === 0 && !orSame) n = 7; return addDays(day0(d), n); };
  const cut = (t, m) => t.slice(0, m.index) + t.slice(m.index + m[0].length);
  const g = (m, i) => m[i] || "";

  function nextRepeat(d, r) {
    const n = parseInt(r.slice(1)) || 1, x = new Date(d);
    if (r[0] === "D") x.setDate(x.getDate() + n);
    else if (r[0] === "W") x.setDate(x.getDate() + 7 * n);
    else if (r[0] === "M") x.setMonth(x.getMonth() + n);
    else x.setFullYear(x.getFullYear() + n);
    return x;
  }
  function repeatLabel(r) {
    const n = parseInt(r.slice(1)) || 1, u = { D: "jour", W: "semaine", M: "mois", Y: "an" }[r[0]];
    if (n === 1) return r[0] === "W" ? "Chaque semaine" : "Chaque " + u;
    return r[0] === "W" ? `Toutes les ${n} semaines` : r[0] === "M" ? `Tous les ${n} mois` : `Tous les ${n} ${u}s`;
  }
  function withFraction(h, min, frac, minus) {
    const t = h * 60 + min, f = frac.toLowerCase();
    const v = !f ? t : f === "et quart" ? t + 15 : f.startsWith("et demi") ? t + 30 : f === "moins le quart" ? t - 15 : t - parseInt(minus);
    return (v + 1440) % 1440;
  }
  function buildDate(now, day, month, year) {
    const d = new Date(year ?? now.getFullYear(), month - 1, day);
    if (d.getDate() !== day) return null;
    return year == null && d < day0(now) ? new Date(d.getFullYear() + 1, month - 1, day) : d;
  }
  function clean(t) {
    t = t.replace(/\s+/g, " ").trim().replace(prefixes, "").replace(/^[ ,.\-]+|[ ,.\-]+$/g, "");
    return t ? t[0].toUpperCase() + t.slice(1) : "Tâche";
  }

  function parse(input, now = new Date()) {
    let text = input.trim(), date = null, mins = null, repeat = null, m;
    if ((m = recurrence.exec(text))) {
      const n = parseInt(g(m, 1)) || 1, u = (g(m, 2) || g(m, 3)).toLowerCase();
      if (u.startsWith("quotid") || u.startsWith("jour")) repeat = "D" + n;
      else if (u.startsWith("matin")) { mins = 540; repeat = "D" + n; }
      else if (u.startsWith("soir")) { mins = 1140; repeat = "D" + n; }
      else if (u.startsWith("midi")) { mins = 720; repeat = "D" + n; }
      else if (u.startsWith("hebdo") || u.startsWith("semaine")) repeat = "W" + n;
      else if (u.startsWith("mens") || u === "mois") repeat = "M" + n;
      else if (u.startsWith("ann") || u === "ans") repeat = "Y" + n;
      else { date = nextDow(now, WD[u.replace(/s$/, "")], true); repeat = "W" + n; }
      text = cut(text, m);
      if (repeat[0] === "M" && (m = monthDay.exec(text))) {
        const d = m[1] === "1er" ? 1 : parseInt(m[1]);
        let x = new Date(now.getFullYear(), now.getMonth(), d);
        if (x.getDate() === d) { if (x < day0(now)) x = new Date(now.getFullYear(), now.getMonth() + 1, Math.min(d, new Date(now.getFullYear(), now.getMonth() + 2, 0).getDate())); date = x; text = cut(text, m); }
      }
    }
    if ((m = relative.exec(text))) {
      const u = g(m, 4).toLowerCase(), n = parseInt(g(m, 3)) || 1, due = new Date(now);
      if (m[1]) due.setMinutes(due.getMinutes() + 30);
      else if (m[2]) due.setMinutes(due.getMinutes() + 15);
      else if (u.startsWith("min")) due.setMinutes(due.getMinutes() + n);
      else if (u.startsWith("h")) due.setMinutes(due.getMinutes() + n * 60 + (m[5] ? 30 : 0));
      else if (u.startsWith("jour")) due.setDate(due.getDate() + n);
      else if (u === "mois") due.setMonth(due.getMonth() + n);
      else due.setDate(due.getDate() + 7 * n);
      text = cut(text, m);
      if (m[1] || m[2] || u.startsWith("min") || u.startsWith("h")) { due.setSeconds(0, 0); return { title: clean(text), due: due.getTime(), repeat: null }; }
      date = day0(due);
    }
    if (!date && (m = dayKeywords.exec(text))) {
      const k = m[1].toLowerCase();
      date = addDays(day0(now), k.startsWith("après") ? 2 : k === "demain" ? 1 : 0); text = cut(text, m);
    }
    if (!date && (m = nextWeek.exec(text))) { date = nextDow(now, 1, false); text = cut(text, m); }
    if (!date && (m = weekend.exec(text))) { date = nextDow(now, 6, true); text = cut(text, m); }
    if (!date && (m = weekday.exec(text))) { date = nextDow(now, WD[m[1].toLowerCase()], false); text = cut(text, m); }
    if (!date && (m = dateNumeric.exec(text))) {
      let y = m[3] ? parseInt(m[3]) : null; if (y != null && y < 100) y += 2000;
      date = buildDate(now, parseInt(m[1]), parseInt(m[2]), y); if (date) text = cut(text, m);
    }
    if (!date && (m = dateText.exec(text))) {
      date = buildDate(now, m[1] === "1er" ? 1 : parseInt(m[1]), MONTHS.indexOf(m[2].toLowerCase()) + 1, m[3] ? parseInt(m[3]) : null);
      if (date) text = cut(text, m);
    }
    if ((m = time.exec(text))) {
      const h = parseInt(g(m, 1) || g(m, 3) || g(m, 5)), mi = parseInt(g(m, 2) || g(m, 4) || g(m, 6) || "0");
      if (h <= 23 && mi <= 59) { mins = withFraction(h, mi, g(m, 7), g(m, 8)); text = cut(text, m); }
    }
    if ((m = namedTime.exec(text))) {
      const k = (g(m, 1) || g(m, 5)).toLowerCase();
      if (mins == null) mins = k === "midi" ? withFraction(12, 0, g(m, 2), g(m, 3)) : k === "minuit" ? withFraction(0, 0, g(m, 2), g(m, 3))
        : k === "matin" ? 540 : k === "après-midi" ? 840 : 1140;
      else if (k !== "matin" && mins < 720) mins += 720;
      if (!date && (k === "soir" || ["ce", "cet"].includes(g(m, 4).toLowerCase()))) date = day0(now);
      text = cut(text, m);
    }
    let due = null;
    if (date) due = new Date(date.getFullYear(), date.getMonth(), date.getDate(), 0, mins ?? DEFAULT_HOUR * 60);
    else if (mins != null) { due = new Date(now.getFullYear(), now.getMonth(), now.getDate(), 0, mins); if (due < now) due = addDays(due, 1); }
    if (repeat) {
      let base = due || new Date(now.getFullYear(), now.getMonth(), now.getDate(), DEFAULT_HOUR);
      while (base <= now) base = nextRepeat(base, repeat);
      due = base;
    }
    return { title: clean(text), due: due ? due.getTime() : null, repeat };
  }

  const NUM = ["zéro","un","deux","trois","quatre","cinq","six","sept","huit","neuf","dix","onze","douze","treize","quatorze","quinze","seize","dix-sept","dix-huit","dix-neuf","vingt"];
  const numberRef = new RegExp(`^(?:(?:la|le|tâche|numéro|n°|no|#)\\s*)*(\\d{1,3}|une|${NUM.join("|")})$`, "i");
  function taskNumber(w) {
    const m = numberRef.exec(w.trim().toLowerCase()); if (!m) return null;
    const v = m[1]; return /^\d+$/.test(v) ? parseInt(v) : v === "une" ? 1 : (NUM.indexOf(v) > 0 ? NUM.indexOf(v) : null);
  }
  return { parse, taskNumber, nextRepeat, repeatLabel };
})();
if (typeof module !== "undefined") module.exports = KIS;
