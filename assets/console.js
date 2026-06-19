/* Iustitia — hero console typewriter. Pure cosmetic, fail-safe:
   the static fallback lines are already in the HTML; this just animates them.
   Respects prefers-reduced-motion (renders all lines instantly, no loop). */
(function () {
  const body = document.getElementById("console-body");
  if (!body) return;

  const lines = [
    { p: "> ", t: "iustitia --status", c: "" },
    { p: "",   t: "32 checks loaded across 4 groups", c: "ok" },
    { p: "",   t: "protocol: 1.21.11 + 1.8.9 combat ready", c: "ok" },
    { p: "",   t: "tracking 14 players on arch.mc", c: "dim" },
    { p: "",   t: "nametag tiers: 3 green, 1 yellow, 1 red", c: "dim" },
    { p: "> ", t: "flag KillAura thoria  silent/snap  vl=5.0", c: "flag" },
    { p: "",   t: "  -> definitive [X] nametag applied", c: "warn" },
    { p: "",   t: "0 outgoing packets sent. 0 telemetry.", c: "ok" },
    { p: "> ", t: "_", c: "prompt" }
  ];

  const CHAR_MS = 16;
  const LINE_MS = 200;
  const RESTART_MS = 2800;

  function renderAll() {
    body.innerHTML = "";
    lines.forEach(l => {
      const div = document.createElement("div");
      div.className = "line";
      if (l.p) {
        const ps = document.createElement("span");
        ps.className = "prompt";
        ps.textContent = l.p;
        div.appendChild(ps);
      }
      const ts = document.createElement("span");
      if (l.c) ts.className = l.c;
      ts.textContent = l.t;
      div.appendChild(ts);
      body.appendChild(div);
    });
  }

  const reduce = window.matchMedia &&
    window.matchMedia("(prefers-reduced-motion: reduce)").matches;
  if (reduce) { renderAll(); return; }

  const cursor = () => {
    const s = document.createElement("span");
    s.className = "cursor";
    return s;
  };

  let timer = null;

  function typeLine(i) {
    if (i >= lines.length) {
      timer = setTimeout(loop, RESTART_MS);
      return;
    }
    const l = lines[i];
    const div = document.createElement("div");
    div.className = "line";
    if (l.p) {
      const ps = document.createElement("span");
      ps.className = "prompt";
      ps.textContent = l.p;
      div.appendChild(ps);
    }
    const ts = document.createElement("span");
    if (l.c) ts.className = l.c;
    div.appendChild(ts);
    const cur = cursor();
    div.appendChild(cur);
    body.appendChild(div);
    body.scrollTop = body.scrollHeight;

    const text = l.t;
    let n = 0;
    function step() {
      if (n <= text.length) {
        ts.textContent = text.slice(0, n);
        n++;
        timer = setTimeout(step, CHAR_MS);
      } else {
        cur.remove();
        timer = setTimeout(() => typeLine(i + 1), LINE_MS);
      }
    }
    step();
  }

  function loop() {
    body.innerHTML = "";
    typeLine(0);
  }

  // start after a short beat
  timer = setTimeout(loop, 350);

  // pause when tab hidden, restart when visible
  document.addEventListener("visibilitychange", () => {
    if (document.hidden) {
      if (timer) clearTimeout(timer);
    } else {
      body.innerHTML = "";
      timer = setTimeout(loop, 200);
    }
  });
})();