---
name: iustitia-contributor
description: Safely plan, implement, verify, and document changes to the Iustitia Minecraft Fabric client mod. Use for source changes, detector work, mixins, rendering, replay/clip behavior, configuration, persistence, documentation, tests, and pull-request preparation.
---

# Iustitia contributor skill

You are working on Iustitia, a Kotlin/JVM Fabric client mod for Minecraft 1.21.11. Treat this repository as a safety-sensitive observer and moderation tool, not as a generic application.

## Non-negotiable project rules

- Preserve client-only, local-only behavior.
- Do not add telemetry, uploads, analytics, remote reporting, ban/kick logic, or hidden network calls.
- Do not add outgoing gameplay packets unless the behavior is explicitly documented and approved. Replay/playclip suppression is an existing documented exception.
- Do not mutate the local player or live world outside the documented replay/playclip and camera behavior.
- Keep detection and presentation separate.
- Keep detection and rendering fail-open. A detector/render failure must not crash the client or leave the camera, input, or live-world view stuck.
- Treat an alert as inference, never as proof of cheating.
- Do not claim a live test unless you actually launched the client and observed the behavior — or ran the automated live-test suite (`scripts/live_selftest.py`) and pasted its result. `NOT RUN` is not `PASS`.
- Never weaken or delete a self-test expectation to make a run green; a failing cheat scenario is a bypass and a failing legit scenario is a false positive.
- Never hide a failing baseline build or unrelated dirty worktree change.
- Never push to the default branch.

## Read before editing

Read these files first:

1. `README.md`
2. `CONTRIBUTING.md`
3. `SUPPORT.md`
4. `SECURITY.md`
5. `docs/ai-assisted-development.md`
6. `docs/live-verification.md`
7. `docs/automated-live-testing.md`
8. `build.gradle.kts`
9. `gradle.properties`
10. `src/main/resources/fabric.mod.json`
11. `src/main/resources/iustitia.mixins.json`

Then inspect only the packages relevant to the requested change.

## Repository architecture

The core flow is:

```text
clientbound packets
  -> ClientPlayNetworkHandlerMixin
  -> typed PacketSignals / EventBus
  -> AttackInference for inferred attacks
  -> AttackEvent / SwingSignal / HurtSignal / EffectSignal
  -> checks and session/replay consumers

END_CLIENT_TICK
  -> EntityTrackerManager.poll
  -> TrackedPlayer snapshots
  -> movement checks
  -> replay/record capture
  -> alert batch flushing

Check.flag
  -> FlagHistory
  -> AlertManager
  -> local chat/audio/replay alert capture

render callbacks and mixins
  -> nametags, tab badges, HUD, overlays, replay ghosts, camera, captured chunk world
```

Important source-of-truth boundaries:

- `IustitiaClientMod.kt`: Fabric entrypoint, registration, lifecycle wiring.
- `Iustitia.kt`: singleton facade and tick/reset orchestration.
- `EntityTrackerManager.kt` and `TrackedPlayer.kt`: shared observed player state.
- `ClientPlayNetworkHandlerMixin.kt`: incoming packet observation.
- `AttackInference.kt`: inferred attacker/victim events.
- `checks/`: detection logic.
- `Check.kt`: violation-level, exemption, history, and alert chokepoint.
- `IustitiaConfig.kt` and `ConfigManager.kt`: configuration schema and JSON migration.
- `AlertManager.kt` and `FlagHistory.kt`: presentation routing and evidence/tiering.
- `replay/`: rolling buffer, clips, codecs, chunk capture, and playback state.
- `render/` and `mixin/`: client rendering and camera/input integration.
- `scripts/checks.json`: generated check-documentation source data.

## Required planning procedure

Before editing, write a short plan containing:

- User-visible goal.
- Files/packages to inspect.
- Files/packages to change.
- Data/control flow.
- Privacy and client-safety impact.
- Config/migration impact.
- Static tests.
- Live verification scenarios.
- Documentation impact.

If the request is ambiguous, ask a question instead of inventing scope.

## Change-specific instructions

### Detection checks

For a detector change, identify:

- Which observable client-side signal is used.
- Whether it is tick-driven, swing-driven, or attack-event-driven.
- Legitimate behavior that resembles the signal.
- Chunk/lag/teleport/vehicle/effect guards.
- Violation level, decay, threshold, and alert behavior.
- Tier classification: primary, corroborator, or tier-neutral.
- Evidence fields, if any.

Add pure tests when the logic can be separated from Minecraft objects. If it cannot, build a small deterministic helper rather than making the whole check untestable.

Also add (or extend) the automated live scenarios for the check — `legit-<check>` in the
first pass and `cheat-<check>` in the second — per
`docs/automated-live-testing.md` §4. The cheat scenario is what proves the detector still
catches the pattern you touched; the legit scenario is what proves you did not introduce a
false positive.

Never silently change calibration defaults. If a check's defaults change, review `IustitiaConfig.CONFIG_VERSION` and the migration behavior.

### Config and presets

When adding a config field:

1. Add the field with a safe default.
2. Serialize it in `ConfigManager.toJson`.
3. Read it conditionally in `fromJson` for additive fields.
4. Add it to YACL if user-facing.
5. Add it to preset copying only if it is preset content.
6. Document whether it belongs to persistent personal state or behavior profile.
7. Add migration tests where possible.

Check parity matters. Every registered check must have a config slice and every config slice must correspond to a registered check.

### Mixins

For every mixin change, document:

- Target class.
- Target method and descriptor when overloads exist.
- Injection point.
- Whether it observes, renders, cancels, redirects, or mutates.
- Normal-path behavior when the feature is inactive.
- Failure behavior.
- Live test required.

Prefer Fabric callbacks or accessors over new mixins. Be especially careful with:

- `ClientPlayNetworkHandler`
- `ClientConnection`
- `Camera`
- `WorldRenderer`
- `SectionRenderState`
- `GameRenderer`
- `EntityRenderer`
- `PlayerEntityRenderer`
- `OrderedRenderCommandQueue`
- GPU buffer/render-pass APIs

The mixin configuration uses `defaultRequire: 1`; target drift can prevent launch. Do not weaken this merely to make a build pass.

### Replay, clip, camera, and input

Verify every entry and exit path. State must restore on:

- Normal stop.
- End-of-playback behavior.
- Seek and mode changes.
- World/dimension change.
- Disconnect.
- Exception/failure paths.

Preserve the distinction between:

- `/ius replay`: same-world, absolute coordinates, no bundled map.
- `/ius playclip`: portable clip, optional relocation and captured world.
- Legacy playclip mode: intentionally retains older behavior.

### Persistence and imported data

Keep local data bounded and paths sanitized. For JSON/binary input:

- Reject malformed data safely.
- Avoid unbounded allocation from counts/lengths.
- Preserve supported backwards compatibility.
- Do not expose private data in logs.
- Document file locations and retention behavior.

### Documentation

Update the nearest authoritative document. Keep versions, command names, check counts, keybind counts, codec versions, and feature claims synchronized with source. If generated check pages are involved, update `scripts/checks.json` and explain whether generation was run.

## Verification commands

From the repository root (the directory containing `build.gradle.kts`), run:

```bash
python scripts/verify_contribution.py --static
./gradlew test
./gradlew build
```

Then, for anything that runs in the client, run the automated live tests (below) and
`./gradlew runClient` only for what the automated suite cannot see. Use
`docs/live-verification.md` and report exactly what was tested. If a command fails,
capture the relevant error and fix it or disclose it. Do not replace a failed test with
a claim that the code is obvious.

## Automated live verification (run this yourself — no human needed)

The live suite boots a real Minecraft client in a deterministic flat world, drives
scriptable bot players, and asserts on what the real detector caught. It needs no
account, no server, and no human at the keyboard. Full guide:
`docs/automated-live-testing.md`.

```bash
python scripts/live_selftest.py                    # full two-pass verification
python scripts/live_selftest.py --check reach      # the scenarios for one check
python scripts/live_selftest.py --legit-only       # false-positive pass only
python scripts/live_selftest.py --cheat-only       # bypass pass only
python scripts/live_selftest.py --source Meteor    # one reference client's drives
python scripts/live_selftest.py --shard 1/3        # one shard of the full suite
python scripts/live_selftest.py --coverage         # which checks are gated, offline
python scripts/live_selftest.py --matrix           # check x client catch matrix

./gradlew compileGametestKotlin                    # typecheck the harness only
```

The run is **silent by design**: the client is muted as the first action of the entrypoint (all 11
`soundCategory` volumes driven to 0, in memory only — your own `options.txt` is never touched). Do
not add a launch argument or write `options.txt` to achieve this, and keep any new entrypoint
calling `SelfTest.silenceClientAudio(ctx)` first, before anything that can throw.

### When it is required

Run the full suite (and report it) for every change that touches: `checks/`,
`tracking/`, `inference/`, `alert/`, `history/`, `session/`, `replay/`, `config/`
detection semantics, or `SelfTestHooks`/`Check.flag`. For a change scoped to one
check, `--check <id>` is enough for iteration, but run the full suite before you claim
the change verified.

### The two passes and what a failure means

- **LEGIT pass** — vanilla-accurate bots. Any alert is a **FALSE POSITIVE**: fix the
  missing guard or the tuning in the check, never the scenario.
- **CHEAT pass** — semi-blatant to ghost bots. A missed alert is a **BYPASS**: fix the
  detector, or raise the scenario's intensity to the minimal blatant level and say so.
- **REPLAY pass** — replay/clip mechanics; a failure means an observer feature broke
  (state not restored, capture empty, export corrupt).

### When a scenario does not alert: pick the honest bucket

The runner reports four distinct outcomes. Choosing the wrong one is the main way this
suite becomes decoration, so decide it from evidence, not from what makes the run green.

| Situation | Declaration |
|---|---|
| the drive is wrong, or the check has a real bug | fix it, keep `expect(bot, id, mustAlert = true)` |
| the check **logged flags** but stayed under `setbackVL` | `expectKnownOpen(...)` — a **detector gap** (Iustitia's bug) |
| the check logged **no flag at all** | `expectDriveGap(...)` — a **harness gap** (this drive's bug) |
| a **legit** drive alerted | `expectDocumentedFp(...)` — a **false positive** (release-blocking) |

`expectDriveGap` prints the observed peak VL, which is how you tell the two apart mechanically:
`0.00` means the check was never asked; anything above means it reacted and the drive needs
intensity/sequencing, not a rewrite. **Do not use it to retire a bypass you have not diagnosed.**

To see what a drive is *actually* driving, arm the per-flag log and read it:

```bash
python scripts/live_selftest.py --check <scenario> --verbose-log
# then, in build/run/clientGameTest/logs/latest.log, the flag lines between two
# [iustitia-selftest] PASS/FAIL markers belong to that scenario.
```

A drive that trips *unrelated* checks loudly while its target stays silent is the signature of
driving the wrong behaviour. Example found live: the `reach` drive tripped `rotationTracking` 116
times, and the `rotationTracking` drive tripped `speedEnvelope` 328 times — both drives are wrong.

### Diagnosing a drive that should alert but does not (in this order)

Two causes account for every "the drive looks right but the check is silent" case found so far, and
both produce exactly the same symptom — **zero flags** — so you must disambiguate before editing a
drive. Add a temporary `println` at the check's entry point (guard it on
`System.getProperty("fabric.selftest") != null` so it can never ship), run the single scenario, and
read the log.

1. **Is the check even being called?** If your print never fires, the signal is not reaching it.
   - Bots must publish signals **from the client thread**. `BotHandle` routes every publisher through
     `publishFromClient` for this reason: Fabric's gametest API makes `MinecraftClient.getInstance()`
     *throw* on the test thread, and every detector is fail-open — so a check that reads the world in
     its event handler observes nothing, and the suite calls it a bypass while the check is throwing a
     few frames away. If you add a bot signal publisher, use the same helper.
   - If your print fires but the check returns early, print the value at each early-return and read it.
2. **Is the state written a tick too late?** `ScenarioBuilder.runFor` runs the action and *then* calls
   `waitTick()`, so the tracker's snapshot **trails a scenario action by one tick**. A drive that
   writes a position/yaw on the same tick it expects the check to see it has written it one tick too
   late. Write state one tick before the tick it must be observed on, and when the drive needs to aim
   or measure, read the tracker's own value (`EntityTrackerManager.get(uuid)`) rather than the value
   it just wrote — that is what the check will be comparing against.
3. **Only then suspect the drive's pattern.** A component defined by a *relationship* (closing,
   matching, returning, sustained) is not exercised by holding that relationship constant: the
   `killAura` drift component wants a rate-capped assist that *closes* on the bearing, not a fixed
   20° lag that follows it forever.
4. **Then suspect the flag economy** — see the next section.

### Flag economy: per-event flags cannot beat a per-tick decay

`decayAll()` runs before a tick's checks, so a check alerts only when its *average gain per tick*
exceeds its decay. A check that flags `level = 1.0` at most once per **event** (a hit, a landing, a
snap) behind a gate that needs a second tick to re-arm has a maximum rate of 0.5/tick — and where
the decay is 0.5 or 1.0 its VL has no steady state above ~1.0, so a cheat that trips it forever
still never reaches `setbackVL`. If your check's flags are visible in `--verbose-log` and the report
shows a peak VL stuck at ~1.0, this is what you are looking at.

Use the shared pattern instead of inventing a level: `sustained(ctx, violating, window, minViolations)`
feeds a rolling window of per-event verdicts, and `flagEpisode(tp, ctx, label, tick, evidence)` flags
the confirmed episode **once** at `setbackVL + 1.0`; `rearmEpisode(ctx, sustainedNow)` releases the
latch when the pattern genuinely stops. A cheater who keeps doing it is reported once — not silenced
and not spammed — while a single stray event on a legitimate player never alerts. **Push a verdict for
the counter-example too** (the hit that was *not* stale, the swing that *was* aimed), or a 1v1
cheater's every event looks like a violation and "2 of the last 6" means nothing.

### Add a scenario in the same change

A detector change without a scenario is unverified. Follow §4 of
`docs/automated-live-testing.md`: name it `legit-<check>` / `cheat-<check>`, declare
`expect(bot, checkId, mustAlert)`, drive the *minimal* blatant pattern, name the reference client
(`source`), register it in `SelfTestEntrypoint`, and update the coverage table. Add the benign guard
twin to the legit pass whenever the check has a new guard. For an important combat/movement check,
add a **second client's** drive in the same change — a detector is only as good as the worst drive
it misses, which is how the sub-blatant `reach` tier (`cheat-reach-ghost`, Koid 3.6 blocks) sat
undetected behind a single 6.0-block Vape drive until a second client's drive was added.

If a shared helper serves several variants (like `reach` or `sprintHack`), take the expectation as a
parameter: flattening it retires a working assertion to hide one broken drive.

### Honest reporting

- Paste the runner's summary block (scenario counts + both verdict lines) into the PR,
  and state whether you added or closed a documented finding or a harness gap.
- `NOT RUN` (no report produced) is not a pass — say so and explain why.
- Never flip an expectation to green-wash a failure; never delete a legit scenario that
  exposes a false positive. `expectDriveGap` is for a check that logged *zero* flags, not a
  bin for undiagnosed bypasses.
- Never claim a check is covered because a scenario mentions it — verify with
  `python scripts/live_selftest.py --coverage` and read that row.
- State what the suite cannot see (mixin packet decode, rendering, ViaFabricPlus,
  server-side behavior) and cover it with `docs/live-verification.md` or mark it untested.

## Final diff audit

Before preparing a PR:

```bash
git diff --check
git diff --stat
git diff
```

Confirm:

- Only intended files changed.
- No generated output or local state is included.
- No secrets or private server/player data appear.
- No unapproved network behavior exists.
- No check/config registration mismatch exists.
- New fields are serialized and documented.
- Mixin changes have runtime verification notes.
- The PR states AI assistance and honest test results.

## Pull-request response format

When the implementation is complete, summarize:

```text
Summary:
- ...

Verification:
- python scripts/verify_contribution.py --static: PASS/FAIL
- ./gradlew test: PASS/FAIL/NOT RUN
- ./gradlew build: PASS/FAIL/NOT RUN
- Automated live tests (scripts/live_selftest.py): PASS/FAIL/NOT RUN
  - scenarios: N passed/N total; pass 1 (legit) false positives: ...; pass 2 (cheat) bypasses: ...
  - documented findings (detector gaps / false positives): added, closed, or unchanged
  - harness gaps: added, closed, or unchanged (checks whose drive does not reach them yet)
  - scenarios added/changed: ...
  - coverage check ids touched and their `--coverage` row (gated / detector gap / harness gap)
- Live client (manual, what the suite cannot see): exact scenarios and result, or NOT RUN

AI assistance:
- Tool used:
- What it contributed:
- What the human reviewed:

Known limitations:
- ...
```

Do not create a commit or push unless the user explicitly requests it. If asked to prepare a PR, a draft PR is preferred until human review and live verification are complete.