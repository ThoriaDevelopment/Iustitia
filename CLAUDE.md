# Iustitia — project notes for Claude Code

Iustitia is a client-sided Fabric anticheat for Minecraft 1.21.11 (Kotlin). ~36 checks run per
tracked player per client tick on the render thread. Detection and render behavior are
**runtime-only-verifiable** on a live client + cheat clients — there is no unit-test harness; the
build gate is `./gradlew.bat compileKotlin` → BUILD SUCCESSFUL. Fail-open throughout.

## Build / run

- Compile gate: `./gradlew.bat compileKotlin`
- Jar: `./gradlew.bat build` → `build/libs/iustitia-<version>.jar`
- Windows PowerShell + Git Bash both available; `gradlew.bat` is the wrapper.

## Standing constraints

- `oldversion/` is a local v1.1.0 reference copy, gitignored — **never** committed, **never** referenced at runtime.
- Never `git add -A` when the tree has unrelated files — stage only the specific files each task touches.
- Commit messages end with `Co-Authored-By: Claude <noreply@anthropic.com>` (use a `.commit-msg.txt` + `git commit -F` to avoid shell quoting issues, then delete it).
- `/ius replay` MUST stay unchanged (passes `legacy=false`).
- No `CONFIG_VERSION` bump for additive config fields — use `if (o.has(...))` back-compat guards in `ConfigManager`.
- Do NOT push commits or create releases without explicit user authorization.

## Release procedure

1. `./gradlew.bat build` → fresh `build/libs/iustitia-<version>.jar`.
2. Commit any pending finalization; ensure `main` is clean.
3. `git push origin main`.
4. Tag + GitHub release:
   ```
   git tag <version>          # e.g. v1.2.0
   git push origin <version>
   gh release create <version> build/libs/iustitia-<version>.jar --title "<version>" --notes "<release notes>"
   ```
5. **Sync the docs website to the Portfolio repo** (below) — the public site must reflect the new check set.

## Docs website → Portfolio sync (on every release)

The public docs site is served from the **Portfolio** repo at **https://thoria.fyi/iustitia**
(GitHub Pages, CNAME `thoria.fyi`, root-served, **push-to-main = deploy**, no build step). The
website files are **not** committed to this repo (gitignored: `/index.html`, `/docs/*.html`,
`/assets/`); only the **generator** lives here:

- `scripts/checks.json` — the ~36-check definitions (source of truth for the catalog + pages).
- `scripts/gen_docs.py` — generates `docs/index.html` (the catalog) + one `docs/<id>.html` per
  check, from `checks.json`. (The root `index.html` + `assets/` are **static** — hand-authored,
  referenced via `../assets/`, and maintained **directly in Portfolio**; they don't change with
  releases, so they aren't part of the sync.)

On each release (or whenever the check set changes):

1. In this repo: `python scripts/gen_docs.py` → regenerates `docs/*.html` into this repo's
   gitignored `docs/` (so a run never clutters git).
2. Copy the generated files into the Portfolio site, overwriting:
   ```
   cp docs/*.html ../Portfolio/iustitia/docs/
   ```
3. If `scripts/checks.json` changed the check list in a way that affects the landing page (new
   feature count, renamed group), also review Portfolio's `iustitia/index.html` + `assets/` for
   any static edits (rare — the generator does not touch them).
4. In the Portfolio repo (`../Portfolio`): `git add iustitia/`, commit, `git push origin main`
   → deploys to `thoria.fyi/iustitia` (~1–2 min GitHub Pages propagation).

Portfolio remote: `github.com/ThoriaDevelopment/Portfolio`. Install CTAs in the site point to
`modrinth.com/mod/iustitia`; repo/source links stay on this GitHub.

## Dense-player FPS investigation (v1.2.0)

The sensitivity substrate (`SensitivityProcessor`) was the profiled dense-crowd FPS hog. It is
now gated behind `config.sensitivitySubstrate` (default **OFF**) — dropped for v1.1.0 FPS parity;
flip on via `/ius config` to re-enable the `KillAura` + `RotationTracking` GCD sub-flags (accepting
the FPS cost). The in-mod render-thread profiler is `/ius debugfps` (verbose-gated; writes reports
to `%APPDATA%/.iustitia/debugfps/`). See memory `live-render-profiler.md` for the pass #3→#7 history.