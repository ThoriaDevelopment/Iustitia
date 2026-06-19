#!/usr/bin/env python3
"""Generate the Iustitia checks documentation.

Reads scripts/checks.json (one entry per check, authored from the Kotlin source)
and emits:
  docs/index.html       - the catalog (all 32 checks grouped)
  docs/<id>.html x 32    - one deep page per check, with prev/next nav

Pure stdlib (json, html, pathlib). Re-run after editing checks.json:
    python scripts/gen_docs.py
"""
import json
import html
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
CHECKS_JSON = ROOT / "scripts" / "checks.json"
DOCS_DIR = ROOT / "docs"

REPO = "https://github.com/ThoriaDevelopment/Iustitia"
# Never-stale install / release links — point at /releases/latest so we never
# have to bump a version string here when a new jar ships.
RELEASES_LATEST = f"{REPO}/releases/latest"
RELEASE_PAGE = f"{REPO}/releases/tag/v0.2.0"
JAR_URL = f"{REPO}/releases/download/v0.2.0/iustitia-v0.2.0.jar"
BRANCH = "main"

GROUP_ORDER = ["Combat", "Movement", "Rotation", "Packet"]
GROUP_DESC = {
    "Combat": "Hit / attack-time cheats — reach, auras, autoclicker, knockback manipulation.",
    "Movement": "Position / velocity cheats — speed, fly, fall, step, clip, water, elytra.",
    "Rotation": "Aim / rotation cheats — silent-aim, snap-back, aimbot snaps, scaffold.",
    "Packet": "Packet-rate cheats — blink / FakeLag, timer / boost.",
}
GROUP_ICON = {"Combat": "⚔", "Movement": "↗", "Rotation": "◎", "Packet": "■"}


def esc(s: str) -> str:
    return html.escape(str(s), quote=True)


def tier_badge(check: dict) -> str:
    if check.get("definitive"):
        return '<span class="badge badge-def"><span class="glyph">[X]</span> Definitive</span>'
    return '<span class="badge badge-inf"><span class="glyph">[!]</span> Inferential</span>'


def nav(active: str, depth: int) -> str:
    """Shared top nav. depth = number of '../' to reach repo root."""
    base = "../" * depth
    cls = lambda a: ' class="cur"' if a == active else ""
    return f"""  <nav class="nav">
    <div class="container nav-inner">
      <a class="brand" href="{base}index.html"><span class="diamond"></span>IUSTITIA</a>
      <div class="nav-links">
        <a href="{base}index.html#features"{cls("features")}>Features</a>
        <a href="index.html"{cls("checks")}>Docs</a>
        <a href="{base}index.html#philosophy"{cls("philosophy")}>Philosophy</a>
        <a href="{base}index.html#install"{cls("install")}>Install</a>
        <a href="{REPO}" target="_blank" rel="noopener">GitHub</a>
      </div>
      <span class="nav-spacer"></span>
      <span class="nav-cta"><a class="btn btn-primary" href="{RELEASES_LATEST}">Install Iustitia</a></span>
    </div>
  </nav>"""


def footer(depth: int) -> str:
    base = "../" * depth
    return f"""  <footer class="footer">
    <div class="container footer-inner">
      <div><span class="diamond" style="display:inline-block;width:14px;height:14px;background:var(--grad);transform:rotate(45deg);border-radius:3px;vertical-align:-2px;margin-right:7px"></span>Iustitia &mdash; client-sided Fabric anticheat</div>
      <div class="footer-links">
        <a href="{base}index.html">Home</a>
        <a href="index.html">Docs</a>
        <a href="{REPO}" target="_blank" rel="noopener">GitHub</a>
        <a href="{RELEASES_LATEST}" target="_blank" rel="noopener">Releases</a>
      </div>
      <div class="footer-disclaimer">
        v0.2.0 &middot; For Minecraft 1.21.11 (Fabric). Not affiliated with or endorsed by Mojang or Microsoft.
        Minecraft is a trademark of Mojang Synergies AB. No telemetry, no outgoing packets &mdash; the mod only observes.
      </div>
    </div>
  </footer>"""


def sidebar(checks: list, cur_id: str | None) -> str:
    """Sticky docs sidebar grouped by category; highlights the current check."""
    parts = []
    for g in GROUP_ORDER:
        in_g = [c for c in checks if c["group"] == g]
        parts.append(f'      <h4>{esc(GROUP_ICON[g])} {esc(g)} <span style="color:var(--text-faint);font-weight:400">({len(in_g)})</span></h4>')
        for c in in_g:
            cur = ' class="cur"' if c["id"] == cur_id else ""
            tier = '<span class="badge-t">' + ("●" if c.get("definitive") else "○") + '</span>'
            parts.append(f'      <a href="{esc(c["id"])}.html"{cur}>{esc(c["name"])}{tier}</a>')
    return "\n".join(parts)


def cfg_table(check: dict) -> str:
    d = check["defaults"]
    rows = [
        ("enabled", "true" if d["enabled"] else "false"),
        ("setbackVL", d["setbackVL"]),
        ("decay", d["decay"]),
        ("threshold", d["threshold"]),
    ]
    head = """    <table class="cfg-table">
      <thead><tr><th>Field</th><th>Default</th></tr></thead>
      <tbody>
"""
    body = "".join(f"        <tr><td>{esc(k)}</td><td class=\"val\">{esc(v)}</td></tr>\n" for k, v in rows)
    consts = ""
    for c in check.get("constants", []):
        consts += f"        <tr class=\"const-row\"><td>{esc(c['k'])}</td><td class=\"val\">{esc(c['v'])}</td></tr>\n"
    return head + body + consts + "      </tbody>\n    </table>"


def fp_list(check: dict) -> str:
    if not check.get("fpGuards"):
        return "    <p><em>None.</em></p>"
    items = "".join(f"      <li>{esc(g)}</li>\n" for g in check["fpGuards"])
    return f"    <ul>\n{items}    </ul>"


def check_page(check: dict, checks: list, idx: int) -> str:
    prev_c = checks[idx - 1] if idx > 0 else None
    next_c = checks[idx + 1] if idx < len(checks) - 1 else None
    src_url = f"{REPO}/blob/{BRANCH}/{check['source']}"

    prev_html = ""
    if prev_c:
        prev_html = f"""      <a href="{esc(prev_c['id'])}.html" class="prev"><div class="dir">← Previous</div><div class="who">{esc(prev_c['name'])}</div></a>"""
    else:
        prev_html = """      <span></span>"""
    next_html = ""
    if next_c:
        next_html = f"""      <a href="{esc(next_c['id'])}.html" class="next"><div class="dir">Next →</div><div class="who">{esc(next_c['name'])}</div></a>"""
    else:
        next_html = """      <span></span>"""

    return f"""<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>{esc(check['name'])} &mdash; Iustitia checks</title>
  <meta name="description" content="{esc(check['what'])}">
  <link rel="stylesheet" href="../assets/iustitia.css">
</head>
<body>
{nav("checks", 1)}
  <div class="container docs-layout">
    <aside class="docs-side">
      <a href="index.html" style="display:block;margin-bottom:14px;color:var(--accent);font-size:13px;font-weight:600">&larr; All checks</a>
{sidebar(checks, check['id'])}
    </aside>
    <main>
      <div class="breadcrumb"><a href="../index.html">Iustitia</a> <span class="sep">/</span> <a href="index.html">Docs</a> <span class="sep">/</span> <a href="index.html#{esc(check['group'].lower())}">{esc(check['group'])}</a> <span class="sep">/</span> {esc(check['name'])}</div>
      <div class="check-head">
        <div class="check-title">
          <h1>{esc(check['name'])}</h1>
          {tier_badge(check)}
        </div>
        <div class="check-sub">{esc(check['what'])}</div>
        <div class="meta" style="margin-top:10px;color:var(--text-faint);font-family:var(--mono);font-size:13px">id: <span style="color:var(--accent)">{esc(check['id'])}</span> &nbsp;&middot;&nbsp; class: {esc(check['class'])} &nbsp;&middot;&nbsp; group: {esc(check['group'])}</div>
      </div>
      <div class="doc-nav">
{prev_html}
{next_html}
      </div>

      <section class="doc-section fade-in-scroll">
        <h2>What it detects</h2>
        <p>{esc(check['what'])}</p>
      </section>

      <section class="doc-section slide-in-bottom">
        <h2>Observer signature</h2>
        <div class="sig">{esc(check['signature'])}</div>
      </section>

      <section class="doc-section fade-in-scroll">
        <h2>False-positive guards</h2>
{fp_list(check)}
      </section>

      <section class="doc-section slide-in-bottom">
        <h2>Configuration</h2>
        <p style="color:var(--text-dim);font-size:14px;margin-bottom:14px">Defaults (editable in-game via the YACL config screen or <code style="font-family:var(--mono)">/ius config</code>):</p>
{cfg_table(check)}
      </section>

      <section class="doc-section fade-in-scroll">
        <h2>Source</h2>
        <a class="src-link" href="{src_url}" target="_blank" rel="noopener">{esc(check['source'])} &nbsp;&nearr;</a>
      </section>

      <div class="doc-nav" style="margin-top:40px">
{prev_html}
{next_html}
      </div>
    </main>
  </div>
{footer(1)}
  <script src="../assets/reveal.js"></script>
</body>
</html>"""


def catalog_page(checks: list) -> str:
    sections = []
    for g in GROUP_ORDER:
        in_g = [c for c in checks if c["group"] == g]
        cards = []
        for i, c in enumerate(in_g):
            delay = (i % 4) + 1
            cards.append(f"""        <a class="check-card slide-in-bottom delay-{delay}" href="{esc(c['id'])}.html">
          <div class="top"><span class="name">{esc(c['name'])}</span>{tier_badge(c)}</div>
          <div class="desc">{esc(c['what'])}</div>
        </a>""")
        sections.append(f"""      <div id="{esc(g.lower())}">
        <div class="group-head fade-in-scroll"><h2>{esc(GROUP_ICON[g])} {esc(g)}</h2><span class="gtag">{len(in_g)} checks</span></div>
        <p class="fade-in-scroll" style="color:var(--text-dim);max-width:640px;margin-bottom:18px">{esc(GROUP_DESC[g])}</p>
        <div class="check-grid">
{chr(10).join(cards)}
        </div>
      </div>""")

    return f"""<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>Checks &mdash; Iustitia</title>
  <meta name="description" content="All 32 Iustitia anticheat checks, grouped by category: what each detects, its observer signature, false-positive guards, and configuration.">
  <link rel="stylesheet" href="../assets/iustitia.css">
</head>
<body>
{nav("checks", 1)}
  <div class="container" style="padding:46px 24px 10px">
    <div class="section-label fade-in-scroll">Documentation</div>
    <h1 class="section-title fade-in-scroll"><span class="grad">32 checks</span>, four categories.</h1>
    <p class="section-lead fade-in-scroll">Every check Iustitia ships &mdash; what it detects, the observer signature it keys on, the false-positive guards it applies, and its configuration defaults. Each card opens a deep page with the full detail.</p>
  </div>
  <div class="container" style="padding:0 24px 70px">
    <div class="docs-layout" style="grid-template-columns:240px 1fr">
      <aside class="docs-side">
        <a href="../index.html" style="display:block;margin-bottom:14px;color:var(--accent);font-size:13px;font-weight:600">&larr; Home</a>
{sidebar(checks, None)}
      </aside>
      <main>
{chr(10).join(sections)}
      </main>
    </div>
  </div>
{footer(1)}
  <script src="../assets/reveal.js"></script>
</body>
</html>"""


def main() -> None:
    checks = json.loads(CHECKS_JSON.read_text(encoding="utf-8"))
    # validate
    ids = [c["id"] for c in checks]
    assert len(checks) == 32, f"expected 32 checks, got {len(checks)}"
    assert len(set(ids)) == 32, "duplicate check id"
    for c in checks:
        for f in ("id", "class", "name", "group", "definitive", "what", "signature", "fpGuards", "defaults", "source"):
            assert f in c, f"check {c.get('id')} missing field {f}"
        for f in ("enabled", "setbackVL", "decay", "threshold"):
            assert f in c["defaults"], f"check {c['id']} missing default {f}"

    DOCS_DIR.mkdir(parents=True, exist_ok=True)
    (DOCS_DIR / "index.html").write_text(catalog_page(checks), encoding="utf-8")
    for i, c in enumerate(checks):
        (DOCS_DIR / f"{c['id']}.html").write_text(check_page(c, checks, i), encoding="utf-8")
    print(f"Generated {DOCS_DIR / 'index.html'} + {len(checks)} check pages ({len(ids)} ids).")
    # group counts
    for g in GROUP_ORDER:
        print(f"  {g}: {sum(1 for c in checks if c['group'] == g)}")
    print(f"  Definitive: {sum(1 for c in checks if c.get('definitive'))}")


if __name__ == "__main__":
    main()