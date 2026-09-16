# Automated live testing

This is the standard path for verifying a change against the **real client** without a
human at the keyboard. If you are an AI agent contributing to Iustitia, this document
is part of your procedure: static checks prove the code compiles, this proves it
behaves — and it is runnable, headless, and repeatable.

The suite is a Fabric **client gametest**: it boots Minecraft 1.21.11 with Iustitia
loaded, creates a deterministic flat world, spawns scriptable bot players, drives them
through vanilla-accurate or cheat-accurate behavior, and then asserts on what the real
detection pipeline actually caught. Nothing leaves the machine; there is no server, no
account, and no packet transmission.

```bash
python scripts/live_selftest.py                  # full three-pass verification
python scripts/live_selftest.py --check reach    # only the scenarios for one check
python scripts/live_selftest.py --pass cheat     # only the unfair-advantage pass
python scripts/live_selftest.py --legit-only     # only the false-positive pass

./gradlew runClientGameTest                      # same thing, raw
./gradlew compileGametestKotlin                  # typecheck the harness only
```

---

## 1. The three-pass model

Every detection change is judged by three passes. The first two answer the detection
questions; the third guards the observer tooling, which changes on a different axis and fails
differently.

| Pass | Bot behavior | Question | Failure means |
|---|---|---|---|
| **LEGIT** (first pass) | Mirrors vanilla: walking, sprinting, gravity falls, human click cadence, Speed-potioned movement, honest reach combat, lag-freezes | *Does a legitimate player get flagged?* | **FALSE POSITIVE** — a missing guard or bad tuning in the detector |
| **CHEAT** (second pass) | Semi-blatant to ghost: glide-fly, sub-cap speed, 6-block reach, autoclicker, multi-aura, no-knockback | *Does a cheater get caught?* | **BYPASS** — the check does not see a pattern it is supposed to see |
| **REPLAY** | Camera/observer tooling driven directly | *Do replay/clip mechanics survive?* | Broken observer feature (state not restored, capture empty, export corrupt) |

A pass is only meaningful together with the others: a check that flags everything is
not "more secure", and a check that flags nothing is not "tuned". The runner prints
both detection verdict lines explicitly:

```text
  legit pass false positives : none
  cheat pass bypasses        : none
```

### Three outcomes, and they are not interchangeable

When a scenario does not alert, exactly one of three things is true, and the harness forces you to
say which. Reporting the wrong one is how a suite becomes decoration.

| Declaration | When it is the honest one |
|---|---|
| fix it and keep `expect(mustAlert = true)` | the drive is wrong or the check has a real bug — the default, and the only outcome that improves the project |
| `expectKnownOpen(...)` | the drive reached the check (it logged flags) and the **detector** cannot alert — a **detector gap** |
| `expectDriveGap(...)` | the check logged **no flag at all** — the **drive** never reached it, so no assertion exists either way |
| `expectDocumentedFp(...)` | a **legit** drive alerted — a **false positive**, i.e. a release-blocking bug |

The distinction is checked mechanically, not by vibes: `expectDriveGap` reports the observed peak
VL, so `0.00` (never asked) is visibly different from `1.00` (reached it, ceiling too low).

### Rules of honesty

- **A green run is the claim.** Never write "tested live" in a PR without pasting the
  runner output for the commit you are proposing.
- **NOT RUN is not PASS.** If the suite fails to launch, the runner prints `NOT RUN`
  guidance and exits nonzero. Report it as not run.
- **Do not weaken an expectation to get green.** If a cheat scenario misses, either the
  check is missing a pattern (fix the check) or the scenario under-drives it (raise the
  intensity to the minimal blatant level and note it in the coverage table). Never
  flip `mustAlert = true` to `false` to silence a bypass.
- **`expectDriveGap` is not a bin for undiagnosed failures.** It is only for a check that logged
  *zero* flags. If the check reacted at all, the drive works and the finding is about the detector
  (`expectKnownOpen`) — or about the detector being wrong on legit play (`expectDocumentedFp`).
- **A gap entry self-reports.** If a known-open starts alerting it prints `KNOWN-OPEN CLOSED`, and a
  documented FP that stops firing prints `FALSE POSITIVE (resolved?)`. Both mean "delete this entry
  and tighten the scenario", so gaps cannot quietly outlive their cause.
- **Never delete a failing legit scenario** because a check is false-positive-prone.
  That scenario is the bug report.

---

## 2. What the harness actually exercises

Iustitia's pipeline is driven by two inputs: **polled client-world state** and
**rebroadcast packet signals**. The harness reproduces both at the same seams:

| Real input | Harness equivalent | Why it is faithful |
|---|---|---|
| Another player's entity state polled each tick by `EntityTrackerManager.poll` | A real `OtherClientPlayerEntity` spawned in the client world and moved per tick | The tracker polls the live world; a bot in the world *is* a tracked player. Position/look deltas, metadata flags, and ring buffers are computed by the shipping code path. |
| `SwingSignal` from `EntityAnimationS2CPacket` | `BotHandle.swing()` → `Iustitia.bus.publish(SwingSignal)` | Same bus, same event object, same subscribers. |
| `HurtSignal` from the four hurt channels | `BotHandle.hurt()` → `Iustitia.bus.publish(HurtSignal)` | Attack inference correlates swing↔hurt for real, so `reach`, `multiTarget`, and the combat suite run on inferred attacks exactly as in production. |
| `VelocitySignal` / `EffectSignal` | `BotHandle.velocity(...)`, `BotHandle.speedEffect(...)` | Drives knockback windows and the Speed-potion cap raise. |
| A **no-damage burst** (wind charge / TNT-cannon): an impulse with no hurt packet and, on most servers, no velocity packet either | The real ballistic arc (`legit-windcharge`: Δy 1.5 at the onset with prevΔy 0, under the gravity+drag model) | No signal to fake: the tracker derives `burstTick` from the motion itself (`Δy > 0.8` with `prevΔy < 0.15`, armed once at the onset), so presenting the real arc exercises the same path the live client does -- and the 20-tick burst window it arms is what the checks must consult. |

Signals are **published from the client thread** (`BotHandle.publishFromClient`). That is part of
being faithful, not an implementation detail: the real packet path dispatches where
`MinecraftClient.getInstance()` is legal, and Fabric's gametest API *throws* from anywhere else -- so
a signal published from the test thread reaches a check whose world read throws inside its own
fail-open handler, and the check observes nothing while the suite reports a bypass. See the harness
lessons in §6.4; it is the single most expensive bug this suite has produced.

**What this does not cover, by design:** the mixin's packet *decode* (an
`EntityAnimationS2CPacket` becoming a `SwingSignal`) and anything render-related.
Those stay on the manual checklist in [live-verification.md](live-verification.md).
The distinction matters when you review evidence: this suite proves **detector and
observer logic**, not bytecode injection or pixels.

### Safety invariants

- The harness lives in the `gametest` source set and is **not part of the shipped
  mod jar**. Normal `runClient` never loads it.
- The only main-source addition is `dev.iustitia.selftest.SelfTestHooks`, a
  **dev-gated recording tap** (armed solely by the `fabric.selftest` JVM property the
  Gradle run task sets). Without it the tap is one boolean read and records nothing.
- Bots are client-side display entities; the suite never sends a packet, never boots a
  server, and never touches the roaming persistence store (it is disabled for the run
  and the developer's config is captured and restored around each scenario).
- Every scenario runs in a throwaway flat world and cleans up its clips.
- **The run is silent.** The client is muted as the very first action of the entrypoint
  (`SelfTest.silenceClientAudio`), before anything that is allowed to throw -- a run that dies on a
  bad filter or a world-creation timeout is still quiet. Modern Minecraft has no `--nosound`
  argument, so all 11 `SoundCategory` volumes are driven to `0.0`, the mixer is refreshed and
  playing sounds (including music) are stopped. It is **in-memory only** -- `options.txt` is never
  written, so a contributor's own client settings are untouched -- and it is idempotent,
  fail-open, and logs one line when it fires. Use it if you add another entrypoint: a suite that
  blares audio for several minutes cannot be run unattended.

---

## 3. Harness architecture

```text
src/gametest/kotlin/dev/iustitia/selftest/
  SelfTestEntrypoint.kt   fabric-client-gametest entrypoint; scenario order + filtering
  SelfTest.kt             the engine: BotHandle, ScenarioBuilder DSL, scenario evaluation
  Spec.kt                 the pass names (LEGIT/CHEAT/REPLAY) + the check inventory
  Scenarios.kt            the LEGIT scenario library (incl. the pipeline smoke test)
  CheatCombat.kt          the combat cheat drives
  CheatMovement.kt        the movement/rotation cheat drives
  ReplayScenarios.kt      replay/clip scenarios
  PresetScenarios.kt      preset/config-management scenarios (REPLAY pass)
  Assertions.kt           PR-ready failure messages (bypass / false positive / tracking)
  SelfTestHooks.kt        (main source set) the dev-gated flag tap
  SelfTestReport.kt       (main source set) the report model
  TestConfigSnapshot.kt   captures + restores config/exemptions around each scenario
  ClientThread.kt         gametest-thread → client-thread bridge
src/gametest/resources/fabric.mod.json    the test mod (entrypoint declaration)
scripts/live_selftest.py                  the runner an agent actually invokes
```

Scenario execution: one world per scenario, `LEGIT` pass first, `CHEAT` pass second,
`REPLAY` last. A scenario that throws is recorded as a failure with its exception
instead of aborting the suite, so one broken scenario never hides the rest.

### Scenario DSL

```kotlin
class CheatReach : SelfTest.Scenario("cheat-reach", "CHEAT") {
    override fun run(b: SelfTest.ScenarioBuilder) {
        val bot = b.bot("Reach", 0.0, 100.0, 0.0)          // spawn a tracked bot
        val victim = b.bot("Victim", 0.0, 100.0, 6.0)
        b.expect(bot, "reach", mustAlert = true)           // the pass expectation

        var since = 0
        b.everyTick {                                      // runs before each client tick
            bot.look(0f, 0f)
            if (since++ >= 5) { bot.swing(); victim.hurt(); since = 0 }
        }
        b.runFor(120)                                      // 120 ticks = 6 seconds
    }
}
```

| Helper | Drives |
|---|---|
| `b.bot(name, x, y, z)` | Spawns an `OtherClientPlayerEntity` and waits one tick so the tracker sees it |
| `bot.teleportTo(x, y, z)` | Position for this tick (small steps = walking; one big jump = teleport) |
| `bot.look(yaw, pitch)` | Yaw/pitch (rotations, aim, pitch bounds) |
| `bot.swing()` | A swing signal (+ the visible animation) |
| `bot.hurt()` | A hurt for this bot (damage received) |
| `bot.velocity(vx, vy, vz)` | Knockback impulse. **Fidelity gap:** this publishes the `VelocitySignal` only. A real `EntityVelocityUpdate` also sets `TrackedPlayer.velocityTick` (via `EntityTrackerManager.markVelocity`, called from the packet mixin and from nowhere else), and no signal subscriber does that here -- so on a harness bot `velocityTick` is never set and the `kbHop`/velocity exemptions in `jumpOnHurt`, `flyEnvelope`, `longJump`, `speedEnvelope` and `teleport` are **unreachable**. A drive that would lean on them must not; see the `legit-nokb-airborne` KDoc for the case that hit this. Closing it is its own workstream -- it would change what every existing velocity-carrying scenario already means. |
| `bot.speedEffect(added, amplifier)` | Speed effect add/remove (cap-raise path) |
| `b.expect(bot, checkId, mustAlert)` | The pass expectation for a check |
| `b.expectAlertCount(bot, checkId, label, atLeast)` | The **recurrence** expectation: the check must produce that many distinct alert **episodes** under `label`. Crossings less than 30 ticks apart count as one episode. This is the only assertion that can see an episode latch which never re-arms (see §4). |
| `b.everyTick(everyN) { … }` | Per-tick (or every-N-tick) script |
| `b.runFor(ticks)` | Advance the world; assertions evaluate afterwards |

Expectations are declared per scenario and evaluated by the engine, so the failure
message always contains the observed peak VL and the alerted set — the evidence a
reviewer needs.

---

## 4. Adding a scenario (the standard recipe)

Do this whenever your change touches detection, tracking, inference, alerts, replay,
or clips. It is part of the definition of done for those areas.

1. **Name it after the check**: `legit-<check>` and `cheat-<check>`, pass `"LEGIT"` /
   `"CHEAT"`. Observer tooling uses `"REPLAY"`.
2. **State what you are faking**, in the class KDoc: the reference behavior
   (`References/Cheats/…` or a real-world client), and the exact knob you set.
3. **Drive the minimal blatant pattern.** For the cheat pass, pick the *smallest*
   deviation a real cheat implies (e.g. reach 4.2, not 12) — a scenario that only
   passes at absurd values documents nothing.
4. **Declare both directions where possible.** If a check has a guard (lag, teleport,
   vehicle, water, effect), add the guard's benign twin to the legit pass in the same
   change. Guards without a legit scenario are unverified guards.
5. **Assert the verdict, not the internals.** `expect(bot, "reach", mustAlert = true)`
   is right; asserting a specific VL number is brittle and is not the contract.
   The one part of the *latch* that is still a contract is **recurrence**: a check that
   transition-gates one flag per episode (`flagEpisode`) must alert *again* when the
   pattern genuinely breaks and returns, and `expectAlertCount(bot, checkId, label,
   atLeast = 2)` asserts exactly that. It is not an internals assertion -- it counts
   alert *events*, not a latch bit or a VL number -- and it is the only form that can
   catch a latch which never re-arms, because `expect(mustAlert = true)` is already
   satisfied by the first episode. Pair it with a drive that actually breaks the
   pattern in between: a scenario whose two "episodes" are closer together than the
   check's own episode gate will chain them into one and false-green.
6. **Name the reference client.** Cheat scenarios pass a `source` (`"Meteor"`,
   `"LiquidBounce"`, …) that must match a real client under `References/Cheats`. An
   unattributed drive is an invented pattern, and `--matrix` labels its row with it.
   Where a check is important (combat/movement), add a second client's drive: a detector is
   only as good as the worst drive it misses, which is how `reach`'s 3.8-block floor surfaced.
7. **Register it** in `SelfTestEntrypoint` (`legitPass()` / `cheatPass()` /
   `replayPass()`).
8. **Update the coverage table below** in the same commit, and run the suite:

```bash
python scripts/live_selftest.py --check <check>
```

### Calibration loop (first run of a new scenario)

Scenarios are calibrated on the first run against the shipping check:

1. Run the scenario; read the report's `peakVL` for the check.
2. **The check logged no flag at all** → the drive never reached it. That is not a bypass and not a
   detector finding: it is this file's problem. Record it as a harness gap
   (`b.expectDriveGap(bot, checkId, note = "…")`) naming what the drive does not yet reproduce, add
   it to the §5 work queue, and come back to it. Do not declare an alert assertion you cannot
   support — and do not leave a red row that blames the detector for a broken bot.
   `--verbose-log` is the tool: whatever *did* trip tells you what behaviour you are actually
   driving (a `reach` drive that trips `rotationTracking` 116 times is driving an aim, not a reach).
3. **Missed alert but the check did react** (`peakVL > 0`, below `setbackVL`). Decide which side is
   wrong:
   - the drive is too weak or too sparse (raise the intensity/density — document the new value), or
   - the check's decay/threshold cannot accumulate under this pattern (a real
     detection finding — fix the check, then re-run; otherwise record it as a detector gap).
4. **False positive** → a legit scenario alerted. Find the missing guard, add it to the
   check, and keep the legit scenario as its regression test. If you are not fixing it in this
   change, record it with `expectDocumentedFp` and a note that names the drive and the mechanism.
5. **Correct drive, detector cannot satisfy it** → record it as a detector gap
   (`b.expectKnownOpen(bot, checkId, note = "…")`) with the observed VL and the reason, and
   add it to §6. Known-open entries never fail a run, print on every run, and flip to
   `KNOWN-OPEN CLOSED` the moment the detector starts catching it — at which point promote
   it to a real `expect(mustAlert = true)`.
6. Re-run both passes. Record the final intensity in the scenario KDoc.

The engine prints the observed `peakVL` map on every failure, so calibration never
requires guessing. To attribute flags to the scenario that produced them, grep the game log
(`build/run/clientGameTest/logs/latest.log`) — the `[iustitia-selftest] PASS/FAIL` marker between
two blocks of flag lines names the scenario they belong to.

---

## 5. Coverage matrix

**This table is a snapshot. The authoritative live views are the runner's own reports** --
`--coverage` (per check: legit/cheat gates and which clients drive it), `--matrix` (check x client
catch state) and `--list`. Read them before claiming a check is covered; they are generated from
the run, this table is maintained by hand.

Last full run: **88 scenarios** at `4f9545a` (2026-09-16) -- 19 legit incl. the smoke test, 60 cheat, 9 replay incl. the two preset gates. All 88 green in a single unsharded pass, **0 false positives, 0 bypasses**, with the 4 detector gaps and 6 drive gaps below still open. The raw report is `build/selftest-report.json`.

That pass is the first to cover `replay-show-self` and the three scenarios added by the swing-source audit (`legit-mining-cadence`, `legit-mining-near-hurt`, `cheat-reach-digging-koid`); the previously recorded pass was 84 at `ca12ec8`. `--list` prints the live inventory, which is the authoritative count.

| | count |
|---|---|
| cheat scenarios | 60 |
| distinct reference sources driven | **13** — 9 cheat clients (Fusion, Itami, Koid, LionClient, LiquidBounce, Meteor, Raven, Slinky, Vape) + 4 anticheats (AvA, Grim, NCM, Rain-Anticheat) |
| checks with an established cheat gate (the drive alerts) | **29 / 36** |
| checks driven by >=2 clients | 10 (`flyEnvelope` 4, `clickStatistics` 3, `killAura` 3, `reach` 3, `criticals` 2, `multiTarget` 2, `noFallDamage` 2, `speedEnvelope` 2, `sprintHack` 2, `autoBlock` 2) |
| detector gaps (drive reaches it, detector cannot alert) | **4** checks carry a `knownOpen` entry (`speedEnvelope`, `packetGap`, `stepHeight`, `longJump`) |
| harness gaps (a drive does not reach it) | **5** checks across 6 scenarios; 4 of the 5 are ungated (`sprintHack` alerts on two other drives, so its water variant is a drive gap, not a detector gap) |
| verified detector false positives | **0** (four resolved this cycle -- §6.3) |
| FP-direction regressions (a legit drive guarding a detector that was narrowed) | **6** -- `legit-fly-ramp`, `legit-triggerbot-strafe`, `legit-nokb-airborne`, `legit-multitarget-sweep`, `legit-mining-cadence`, `legit-mining-near-hurt` |
| checks carrying any documented finding | 12 |

Every check below has a drive; what differs is whether that drive **establishes a gate**. The four
outcomes are deliberately distinct and the runner reports them in separate sections:

| State | Meaning | Whose bug |
|---|---|---|
| caught | the cheat drive alerted | none -- gate established |
| **detector gap** (`knownOpen`) | the drive reached the check and it cannot alert | Iustitia's detector |
| **harness gap** (`driveGaps`) | the check logged **no flag at all** -- never asked | this suite's drive |
| **false positive** (`documentedFp`) | a legit drive alerted | Iustitia's detector, release-blocking |

### Established gates (29)

| Check | Cheat clients that trip it |
|---|---|
| `flyEnvelope` | Fusion, Itami, LiquidBounce, Meteor (four sub-signals; also guarded by the `legit-fly-ramp` FP regression) |
| `clickStatistics` | Koid, LionClient, Meteor (also guarded by the `legit-mining-cadence` FP regression) |
| `speedEnvelope` | Koid, LiquidBounce |
| `elytraSpeed` | LiquidBounce |
| `killAura` | LiquidBounce, Raven, Vape (snap, rate-capped drift, and on-target track; the drift path also carries a **re-arm regression**, `cheat-killaura-drift-rearm-raven`) |
| `reach` | Koid (3.6 ghost), LiquidBounce (4.2), Vape (6.0); also Koid holding a fake dig open (`cheat-reach-digging-koid`, the bypass proof for the dig exemption) and guarded by the `legit-mining-near-hurt` FP regression |
| `criticals` | Meteor, Slinky |
| `maceSmash` | LiquidBounce |
| `multiTarget` | Meteor (3 same-tick victims), LiquidBounce (2-victim pair path; also guarded by the `legit-multitarget-sweep` FP regression) |
| `noFallDamage` | Vape (landed-no-hurt); Meteor (faked-burst evasion attempt, still caught); Vape (mace swing with **no** confirmed hit -- the smash exemption's own evasion attempt, still caught) |
| `phaseClip` | Koid |
| `teleport` | Koid (both the vclip and the slyport drive) |
| `spider` | AvA |
| `sprintHack` | Itami (blind), LiquidBounce (sneak) |
| `wallSprint` | Grim |
| `waterWalk` | Slinky |
| `throughWalls` | Vape (occluded hits behind a wall) |
| `backtrack` | Vape (stale-position snap) |
| `noKnockback` | Rain-Anticheat (also guarded by the `legit-nokb-airborne` FP regression) |
| `keepSprint` | LiquidBounce |
| `wTap` | Vape |
| `hitFlick` | Vape |
| `triggerbot` | Vape (also guarded by the `legit-triggerbot-strafe` FP regression) |
| `hitsWithoutSwing` | Slinky (plus a **re-arm regression**, `cheat-hitsswing-rearm-slinky`) |
| `jumpOnHurt` | Rain-Anticheat |
| `autoBlock` | Grim, Rain-Anticheat |
| `aimWrap` | LiquidBounce |
| `rotationSnapBack` | LiquidBounce |
| `rotationTracking` | Vape |

### Harness gaps (5 checks -- the work queue)

These five have a drive but the check logs **zero flags**, so no alert assertion can be made yet.
Writing them as ordinary expectations would leave the suite permanently red for a reason that is
about the test, not about Iustitia -- which is how a suite trains people to ignore it.

| Check | What the drive is missing |
|---|---|
| `backwardSprint` | sprint metadata on a genuinely backward-moving bot (`speedEnvelope` reacts, sub-threshold) |
| `noSlow` | the using-item + movement metadata pair |
| `pitchBound` | an out-of-range pitch actually presented to the tracker |
| `scaffoldRotation` | repetition: the snap-and-return fires exactly once (peakVL 1.0 vs setback 5.0) |
| `sprintHack` (water variant) | sprint while the feet are in liquid (the sneak and blind variants alert) |

Two lessons this queue encodes: a drive that trips *unrelated* checks loudly while its target stays
silent is the tell that it is driving the wrong behaviour, and `--verbose-log` plus attributing
flags to scenarios is how you see it. When a drive looked complete but the check logged nothing,
the cause has so far always been one of two things -- the **signal is not reaching the check at
all** (see the signal-threading bug in the harness lessons, which cost the suite a whole family of
false bypasses) or the **state change is written a tick later than it is observed** (see the
tracker-lag lesson). Check those two before rewriting a drive.

### FP-direction regressions (6 -- the guards on this cycle's narrowed detectors)

A detector narrowed to stop a false positive is only half-verified by that: the fix could equally
have been a bypass. Each of the six detectors hardened this cycle therefore keeps the *legitimate*
shape it used to misread as a cheat, as a permanent legit-pass scenario. Each asserts with
`expectQuiet` **and** a bounded `expectFlagCount` on the specific flag site, because the FP was
invisible to an alert-level assertion in every one of these cases -- the flag rate never reached
`setbackVL`, which is exactly why the audit and the suite both missed them.

| Scenario | Legitimate shape presented | What a naive narrowing would break | Observed pre-fix |
|---|---|---|---|
| `legit-fly-ramp` | a 20-step slab/stairs ramp climbed one step per 4 ticks (0.5/tick rise as a single-tick Δy spike, then three flat grounded ticks) | `cheat-fly-ascend-liquidbounce`, `cheat-fly-hover-meteor` | `Fly` 17 flags, peakVL 1.00 |
| `legit-triggerbot-strafe` | a strafing victim crossing a **held** crosshair, clicking on each crossing (0-1 tick "reactions") | `cheat-triggerbot-vape` | `Triggerbot` alert, peakVL 6.00 |
| `legit-nokb-airborne` | a victim already airborne on its own jump arc when the hit lands | `cheat-no-kb-rain` | `NoKB(VelocityB)` 18 flags, peakVL 1.00 |
| `legit-multitarget-sweep` | two opponents jittering in and out of a vanilla sword sweep's arc, one swept per crossing | `cheat-multi-aura-meteor`, `cheat-multi-aura-pair-liquidbounce` | `MultiTarget` `pair-sustained` alert, peakVL 4.00 |
| `legit-mining-cadence` | holding left click on a block that cannot break (bedrock): the server relays the arm animation on its own clock, a constant 4-tick interval | the `ClickStats(StDev)` bar, which a constant interval sits at exactly 0.0 | `ClickStats(StDev)` 35 flags, peakVL 28.20 |
| `legit-mining-near-hurt` | a stationary miner 6 blocks from a teammate taking damage that carries no attacker id | attack attribution for the id-less hurt channels | `Reach` `motionless` 1 flag, peakVL 11.00 |

The six cheat-direction rows above were re-run against the narrowed detectors in the same pass and
all still alert: the fixes removed only the legitimate shapes. The last two rows are the swing-source
audit's, and their bypass proof is the extra cheat row `cheat-reach-digging-koid` (a reach module that
also holds a fake dig open, driven in the same pass). Mechanisms and the arithmetic behind each are in
§6.3.

### Observer tooling

| Feature | Scenario |
|---|---|
| Rolling replay buffer capture | `replay-buffer-capture` |
| Replay start / frame advance / pause / seek / stop / live-view restore | `replay-playback-lifecycle` |
| Playback controls | `replay-playback-controls` |
| Camera modes (incl. freecam enter/exit restore) | `replay-camera-modes` |
| `.iusclip` export -> metadata -> load -> play -> delete | `replay-clip-roundtrip` |
| `/ius playclip` relocation + captured-world rendering | `replay-playclip-relocation` |
| Show-self: your own snap in the buffer, its survival through the clip round trip, and the live-body hide | `replay-show-self` |
| Disabled-check gate (toggled-off check = zero VL; re-enabled = alerts) | `preset-disabled-check-gate` |
| Preset apply (schema-derived coverage + documented exclusions + standard semantics) | `preset-apply-coverage` |

### Scenarios that cannot live here

| Area | Why | Where it is verified |
|---|---|---|
| Mixin packet decode (signal -> pipeline) | The harness publishes the signal, not the packet | [live-verification.md](live-verification.md) packet section |
| Dig-state production (`onBlockBreakingProgress` -> `DiggingSignal`) | Same reason, and worse: the bots are client-side display entities, so the server never sends a `BlockBreakingProgressS2CPacket` for them at all. The suite drives the consumers via `bot.dig()` (`cheat-reach-digging-koid`, `legit-mining-cadence`, `legit-mining-near-hurt`); the inject itself is the manual dig step in [live-verification.md](live-verification.md) | manual, on a test server |
| 1.8-era protocol behavior (ViaFabricPlus) | Requires a 1.8 server + protocol translation | manual, on a test server |
| Rendering: nametags, ghosts, HUD, screenshots | Needs pixel/comparison work, not logic | [live-verification.md](live-verification.md) rendering section |
| Alert chat text/format | Presentation; the suite asserts on the alert *event* | manual + `/ius hist` |

---

## 6. Open calibration findings

Everything here was produced by a live run, is reproduced on every run, and is printed by the
runner. Nothing is silenced: a finding either stays visible as a non-blocking entry (`--strict`
makes it blocking) or it is fixed.

### 6.1 The systemic one: a flag rate that cannot beat the decay

Fifteen detector gaps had the **same arithmetic**, and it is worth reading as one bug rather than
fifteen:

> A check that flags at `level = 1.0`, at most once per event, behind a gate that requires a
> second tick to re-arm, has a maximum flag rate of **0.5 or less per tick**. Where `decay` is
> 0.5 or 1.0 the VL has no steady state above ~1.0 -- so a cheat that trips it forever still never
> reaches `setbackVL`.

`decayAll()` runs before a tick's checks (`Iustitia.onClientTick`), so a check only alerts when its
*average* gain per tick exceeds its decay.

**The fix is implemented** (`Check.sustained` + `Check.flagEpisode` in `checks/Check.kt`, state on
`CheckContext`): an event-driven check keeps a rolling window of per-event verdicts, requires a
**pattern** (`N` of the last `M` events violated), and then flags the episode **once**, at a level
that clears `setbackVL` in a single flag. The latch re-arms only when the pattern genuinely breaks,
so a cheater who keeps doing it is reported once -- not silenced and not spammed -- while one stray
event on a legitimate player never alerts. `flagEpisode` records `sub`/measured evidence like any
other flag, so the report stays intact.

| Check | Why its rate could never beat the decay | State |
|---|---|---|
| `hitFlick` | one flag per flick, and the return needs its own tick (0.5 vs decay 0.5) | **fixed** |
| `rotationSnapBack` | snap on the attack tick, snap back on the next (0.5 vs 0.5) | **fixed** |
| `aimWrap` | a snap out of a near-still tick must be followed by a still tick to be judged again (0.5 vs 0.5) | **fixed** |
| `backtrack` | the victim-freeze gate needs >=3 static samples before the snap (<=0.25 vs 0.25) | **fixed** |
| `noKnockback` | one evaluation per hit, and a hit is at best every few ticks (<=0.25 vs 1.0) | **fixed** |
| `hitsWithoutSwing` | transition-gated to one flag per 60-tick episode (~0.017 vs 0.5) | **fixed**, re-arm regression in §5 |
| `keepSprint` | one flag per attack, and the attack cadence is ~1 per 12 ticks | **fixed** |
| `wTap` | per-attack cadence behind a 3-of-4 pattern gate | **fixed** |
| `jumpOnHurt` | one flag per hurt/reset pair | **fixed** |
| `killAura` (drift) | one flag per sustained episode by construction | **fixed**, re-arm regression in §5 |
| `triggerbot` | one flag per observed hit behind a 4-of-5 consistency gate | **fixed** |
| `reach` (ghost tier) | a sub-blatant hit is `ceil((3.3-3.0)*2) = 1.0` of level against 0.5/tick | **fixed** |
| `stepHeight` | a step's flag needs the previous tick grounded, and a step tick is not grounded | open |
| `teleport` | the continuity gate means a clip flags only the tick after a level tick | open |
| `packetGap` | needs a >=5-tick freeze before the snap (>=6-tick cycle) | open |
| `longJump` | one flag per boosted launch, and a launch needs a ~12-tick arc | open |
| `elytraSpeed` | level 1.0 with decay 1.0: flagging *every* tick nets exactly zero | open |

**`elytraSpeed` remains the clearest open case**: at default tuning a cheater gliding at 48 b/s
cannot accumulate any VL at all, no matter how long they fly. The remaining five all want the same
treatment; the difference is only that a glide/step/clip has no natural "event" to count, so each
needs the level to scale with its measured excess (the pattern `noFallDamage`'s landed-no-hurt
branch and `reach` already use) or a window over its own tick cadence.

### 6.2 Individual detector gaps

| Finding | Evidence |
|---|---|
| **~~`reach` has a ~3.8-block floor~~** -- **fixed** | the 6.0-block Vape drive measured 5.60 and alerted, while the 4.2- and 3.6-block drives produced **no `reach` flag at all** across 180 ticks: the flat `maxReach + 0.8` headroom absorbed the whole sub-blatant tier. Fixed by adding a **motionless-pair** path (see below), which now gates Koid's 3.6 ghost, LiquidBounce's 4.2 and Vape's 6.0. |
| **`multiTarget` cannot alert on two victims** | level is `victims - 1`, so two same-tick victims is a **1.0** flag against `setbackVL` 2.0 with decay 1.0. The option is documented as "min victims 2", but two alone can never alert; only a third same-tick victim crosses it. |
| **`noFallDamage`'s ground-spoof sub-flag is unreachable** | level 1.0 with decay 1.0 and at most one evaluation per tick. The landed-no-hurt branch is the one that can alert (covered by `cheat-nofall-vape`). Note the drive itself under-tests this: `cheat-nofall-spoof-meteor` reports `onGround = false` for the whole descent, so the spoof branch's condition never even opens (peakVL 0.0, not "fired but sub-threshold"). Presenting the spoof properly is a drive fix worth doing -- with the touchdown test widened it would likely alert through the landed-no-hurt branch at the surface, which would promote this from a detector gap to a covered vector. |
| **sub-cap `speedEnvelope` did not fire** | 1.35x sprint (~7.6 b/s) is inside the flat 10 bps cap, so detection depends on the sub-cap momentum model, which did not fire for a synthetic drive. Open question: harness artifact (the model reads real movement state) or a genuine sub-cap gap. |
| **`multiTarget` needs a third victim** | level is `victims - 1`, so two same-tick victims is a 1.0 flag against setback 2.0. The check now has a **pair path** that requires the pair to repeat (2 of the last 6 same-tick pairs) instead of firing on arithmetic alone, and `cheat-multi-aura-pair-liquidbounce` drives it. |

### 6.2b Fixed this cycle -- the motionless-pair reach path

Ghost reach was the one gap that could not be closed by tuning the existing geometry, because the
0.8 headroom is not slack -- it is the measured size of a real error. Client-side interpolation
lags the server's position while a player **moves**, so a ray measurement can read up to ~0.7 blocks
long during fast/dash combat, and the older calibration recorded five Polar-clean players alerted at
a 0.4 headroom for exactly that reason.

The error is identically **zero** when neither fighter has moved, so `ReachCheck` now branches on a
new `motionlessPair` test (both players' position rings flat within 2 cm over up to four samples)
and, on that branch only, drops the ray entirely and compares the **vanilla reach metric itself** --
eye to the closest point of the victim's unexpanded hitbox, exactly what the server measures --
against the interaction range plus the 0.1 hitbox margin plus 0.1 of headroom (= 3.2). A ghost-tier
3.6-block bite has a true closest-point distance of 3.3 and now clears it; the legitimate ceiling
is 3.1. The variant is closed with `Check.sustained`/`flagEpisode` for the same economy reason as the
rest of this section. A motionless pair cannot be a dash-lag false positive **by construction**,
which is why this does not re-open the FPs the headroom was introduced to close -- and the moving
paths keep their 0.8 untouched.

### 6.3 Verified detector false positives (release-blocking)

These are wrong flags on legitimate play. They are printed loudly on every run, counted, and made
fatal by `--strict`. **There are currently none** -- every entry recorded so far has been fixed
rather than silenced, and the suite fails if one reappears (the `expectQuiet` assertion it replaced
is the regression guard, so a deleted `expectDocumentedFp` still fails loudly if the check fires).

**Resolved: `flyEnvelope` on a ladder climb** (was peakVL 24.5, `legit-ladder`). Two bugs, in this
order:

- The check had a climbable exemption on its **ascend** branch only, so `Fly(FlyB)` -- whose tight
  friction band a 0.2 b/t ladder ascent matches exactly -- and the physics-breach prediction both
  flagged the entire first 20 ticks of the climb, before the sustained-Levitation guard took over.
  The exemption is now applied to every vertical sub-flag, and the counters are cleared so a climb
  leaves no partial streak behind.
- The exemption itself was **dead code**: it sampled only the player's own column, but a climbing
  player occupies the block *beside* the ladder (the ladder is attached to a wall in a neighbouring
  column, and the climber's own column is air -- that is what "can climb" means). It now samples the
  player's column plus the four horizontal neighbours at foot level and one below.

**Resolved: `waterWalk` on a body in water** (was peakVL 50.5, `legit-water`). The only "held up by
water, not a block" exemptions were lily pad, climbable and boat, and the `tp.swimming` gate never
opens for a non-sprint surface swimmer -- so a legit swimmer matched the signature exactly. The
check now exempts a body whose **feet sit inside the liquid** rather than resting on its surface,
either because the next block up is also liquid (submerged to the waist) or because the feet are
strictly below the liquid block's top face by more than 0.05. A WaterWalk/Jesus player stands ON the
surface -- feet exactly at the top face, air above -- so `cheat-waterwalk-jesus` still alerts
(peakVL 14).

**Resolved (2026-09, earlier cycle).** Two `noFallDamage` entries were fixed rather than silenced:

- **bunny-hop chain** (was peakVL 37): the touchdown test was widened from `groundedProxy` alone
  (`|Δy| < 0.01 && solidBelow`) to *`groundedProxy` **or** the tick the feet reach a surface*
  (`onGroundPacket && isSolidBelow(0.05)`), evaluated **before** anything is cleared. A continuous
  hop chain produces exactly that one support event per hop and no `|Δy| < 0.01` tick at all, so
  the accumulator reset every hop instead of summing ~1.1 per hop across the chain.
- **wind-charge jump** (was peakVL 19): the accumulator is now **re-based** on the no-damage
  burst's own launch point (`pos.y − Δy` at the tracker's `burstTick` onset), keeping the highest
  such point of the flight, and only the descent *below* that floor counts. Vanilla negates fall
  damage above the point the burst was armed at (MC-268383 / MC-272821), so the 12.1-block descent
  above it is no longer evidence of anything.

The evasion twin `cheat-nofall-burst-spoof-meteor` keeps the fix honest: it fakes the same impulse
(a level tick followed by a jump-sized Δy, then sustained >0.5 b/t mid-air drift so the tracker's
horizontal branch re-arms the burst every tick) and falls 35 blocks below the point it fired it —
still flagged at peakVL 65.

**Resolved (2026-09, this cycle): `noFallDamage` on an aerial mace smash** (`legit-mace-smash`, was
the compare.txt live-log audit's headline: NoFall was 34% of all events on 43 players, 43% of them
on players the server-side anticheat never flagged). Vanilla 1.21 **negates all fall damage
accumulated prior to a successful mace smash attack** ("resets the player's fall height"), so an
aerial smash on a Spear-Mace-style FFA lands with **no hurt signal for the faller** — byte-for-byte
the `landed-no-hurt` signature. The fix re-bases the accumulator at the *confirmed smash event*
rather than at the landing: an `[AttackEvent]` (swing + victim hurt via `AttackInference`) while the
attacker is **descending** with a mace in hand on a real fall (`fallAccum > 1.5`, the smash's own
vanilla minimum) re-bases `fallAccum` to 0 and clears the burst floor, exactly like the vanilla
fall-height counter — the landing is then judged on the ~2-block post-smash descent only, which is
under vanilla's damage threshold, so the hurtless landing is correctly quiet. The confirmation is
the attack correlation itself, which bounds evasion: a swing with **no** victim hurt produces no
event and clears nothing (`cheat-nofall-mace-evade`, peakVL 40 — still caught), a missed smash takes
the fall damage normally (the hurt channel resets the accumulator the usual way), and the descent +
real-fall gates keep a grounded or hop-apex mace attack from ever arming the re-base. Same
precedent as `CriticalsCheck`'s mace exemption. `legit-mace-smash` drives two real smashes from a
24-block tower fall (the second proves the accumulator re-arms) and stays at **zero VL**.

**Resolved (2026-09, this cycle): the v1.4.0-audit four.** These are the ones the audit named as
FP-risk, and they share a signature worth stating once: **none of them could ever be seen by an
alert-level assertion.** In all four the flag rate sits structurally below the decay, so the VL
never approaches `setbackVL` and `expectQuiet` passes on the buggy code. They were only observable
through a *flag count* on a specific flag site -- which is why the suite missed them too, and why
the fix for each ships with an `expectFlagCount` regression in the same commit (§5). Each entry
below records the audit's claim, the mechanism as measured, and the discriminator.

- **`flyEnvelope` on a slab/stairs ramp** (was 17 `Fly` flags over a 20-step climb, peakVL 1.00;
  `legit-fly-ramp`). The audit pointed at the **ascend** block ("a long continuous slab ramp
  sustains dy ≈ 0.5 for 6+ ticks"). That is **not** the mechanism: on a fixed cadence `ascendTicks`
  can never exceed **1**. The jump recognizer (`prevDeltaY < 0.15 && 0.3 < dy < 1.0`, re-armed only
  after 6 quiet ticks) fires on a step spike and re-arms on every *other* step; on a re-arm tick
  `lastJumpTick` is set to that very tick, so the ascend gate's own `tick - lastJumpTick > 2` reads
  `0 > 2` and zeroes the counter, and on the steps where it does increment the three level ticks
  zero it again (their `dy` is 0). Six consecutive ascending ticks are unreachable, so the streak
  *length* is not what separates a ramp from a fly. The real mechanism is the **physics-breach**
  counter: `deltaY` is a raw position delta (`Vec3d(e.getX(), e.getY(), e.getZ())` -- entity
  fields, *not* render interpolation), so a 0.5 step-up is a **single-tick** spike followed by
  three flat grounded ticks. The spike clears `expectedY + 0.1` easily -- `prevDeltaY ≈ 0` gives
  `expectedY = (0 - 0.08)·0.98 ≈ -0.078` -- while the flat ticks returned early at the
  `groundedProxy` branch. Only `hoverTicks` was cleared there, so `breachTicks` stood across the
  level ground and **every step after the first was the second consecutive breach**. Two blocks of
  hill satisfied a gate documented as "2 consecutive ticks". The fix is to clear all four streak
  counters on a grounded tick, which is what makes each sub-signal a claim about a *continuous*
  rise. Note the fix is **not** a threshold change: no length separates a long ramp from a fly,
  because at a fixed cadence the ramp's rise is numerically the same signal. What separates them is
  that a ramp is made of steps -- each rise is followed by level ground.
- **`triggerbot` on a strafing victim under a held crosshair** (alerted at peakVL 6.00;
  `legit-triggerbot-strafe`). The crosshair-to-hitbox rising edge is created by *whichever party
  moved*. A player holding an aim while the target strafes across -- or walks into -- the crosshair
  produces an edge on every crossing, and clicking as it crosses is exactly how a human hits a
  moving target: 0-1 tick "reactions" on every hit, 4-of-5 in the window, and the check flagged
  them. Fixed with a **held-aim discriminator** at the rising-edge site: an engagement clock is
  started only when the **attacker's own aim** moved within `AIM_TURN_WINDOW = 3` ticks
  (`AIM_TURN_EPS = 0.25`°/tick, the worst of yaw/pitch; the first sighting of an attacker *seeds*
  rather than records a turn, so a bot spawned facing 90° does not read as a 90° sweep). An edge
  created by the victim's motion gets no clock. The check's own premise is about the attacker
  *sweeping onto* the target, which is what the aim-motion test measures, and a triggerbot is
  unaffected because the user aims manually -- the Vape drive sweeps 90° onto the target on the
  edge tick. Deliberately fail-open, like the rest of this LAX check: a triggerbot whose user holds
  the mouse perfectly still is indistinguishable from a legit player clicking a target that moved
  into them, and is left to `killAura`/`hitFlick` rather than guessed at here.
- **`noKnockback` (`VelocityB`) on an already-airborne victim** (18 flags, peakVL 1.00;
  `legit-nokb-airborne`). `VelocityB` captures `firstAirborneDy` without ever checking the
  precondition its own KDoc states -- "the upward KB launches a **grounded** victim". An airborne
  victim's first-airborne Δy is *their own* motion (jump arc, fall speed, mid-air strafe), all
  outside the `[JUMP_LO, JUMP_HI]` band that exempts a vanilla jump, so dividing it by `kbVy` reads
  as a vertical-KB cancel: on a broadcast-velocity server a mid-air victim hit by a sprinting
  attacker false-flagged on every such hit. Fixed by snapshotting `kbVictimGrounded =
  victim.groundedProxy || victim.onGroundPacket` **at the hit** (the launch has already happened by
  capture time) and requiring it at the capture site. An anti-KB victim standing in combat still
  has `groundedProxy` true at the hit and is still caught (`cheat-no-kb-rain` re-verified).
- **`multiTarget`'s pair gate on a vanilla sword sweep** (alerted, peakVL 4.00; label
  `MultiTarget`/`pair-sustained`; `legit-multitarget-sweep`). The pair gate's ring was
  **event**-keyed while the gate and the class doc both said "the last PAIR_WINDOW **ticks**". A
  vanilla 1.9+ sword sweep damages every entity in the arc on the *same* tick, so one sweep's
  packets filled the ring by themselves -- two separate sweeps ten ticks apart were enough to
  satisfy a gate whose entire point is *repetition*, and `flagEpisode` then added `setbackVL + 1`
  on top of the sweep's own same-tick level and alerted a legitimate player. Fixed by making the
  ring **tick-keyed** (one sample per tick, upgraded in place by that tick's later victims -- the
  first attack of a sweep lands before the second victim is known, so an append-per-event ring
  records `false` for the very tick about to become a pair) and pruned by *tick distance* rather
  than by ring length. The distance pruning is load-bearing: the ring is advanced by attack ticks
  only, so a size-bounded ring fills over any span of time -- a legitimate 2v1 player sweeping two
  adjacent opponents once per vanilla sword cooldown (~12 ticks) would satisfy "2 of the last 4"
  after four such sweeps, a minute into the fight, on nothing but ordinary melee.

The cheat-direction drives above (`cheat-fly-hover-meteor`, `cheat-fly-ascend-liquidbounce`,
`cheat-triggerbot-vape`, `cheat-no-kb-rain`, `cheat-multi-aura-meteor`,
`cheat-multi-aura-pair-liquidbounce`) were re-run against the narrowed detectors in the same pass
and every one still alerts -- the fixes removed only the legitimate shapes.

**Resolved (2026-09, this cycle): the swing-source pair.** A player holding left click on a block
that never breaks swings on a clock the *server* sets, not one they choose. Nemesis guards its
autoclicker and attack-inference paths against exactly this; the Iustitia port had no digging
awareness at all, so the porting gap below went unnoticed until the audit. Both entries were
falsified *and* measured before being fixed: the two drives below were written first, run against the
unfixed tree, and recorded as RED.

The mechanism is one chain, verified in the 1.21.11 bytecode. `MinecraftClient.handleBlockBreaking`
calls `player.swingHand(MAIN_HAND)` whenever `updateBlockBreakingProgress` returns true, and the
in-progress branch ends in an unconditional `return true` -- so a digger whose progress can never
reach 1.0 (bedrock, or obsidian under a too-weak tool) swings every tick. `LivingEntity.swingHand`
relays `EntityAnimationS2CPacket` only once `handSwingTicks >= getHandSwingDuration() / 2`, so the
observer sees a fixed interval instead, `floor(duration / 2) + 1`, where the duration is the held
item's swing animation (6 by default) adjusted by effect: 4 ticks at rest, 3 under Haste I or II, 5
under Mining Fatigue I, 7 under an elder guardian's Mining Fatigue III, and 2 from Haste III/IV or a
custom `SWING_ANIMATION` duration of 3 or 2. Nothing about *why* the arm moved reaches that gate.

- **`clickStatistics` on a continuous dig** (35 `ClickStats(StDev)` flags over 300 ticks, peakVL
  28.20; `legit-mining-cadence`). A constant tick-delta makes `populationStDev` exactly 0.0 against a
  0.45 bar, so the sub-signal flags at level 1.0 per swing -- +0.25/tick gross against a 0.05/tick
  decay, crossing the 5.0 setback about 25 ticks after the 40-sample window fills. There is no
  equilibrium: the VL grows unbounded for as long as the button is held. The two other sub-signals
  cannot own this shape and were read-verified rather than asserted: `MathUtil.excessKurtosis` returns
  0.0 on zero variance against a -0.7 bar, and `detectLoop` rejects a constant prefix outright. Fixed
  by excluding server-relayed dig swings from the cadence windows; the exclusion is bounded to
  intervals at or above `DIG_RELAY_MIN_TICK_DELTA = 2`, as fast as a relay can run in vanilla, so a
  fast click stream stays detectable while a dig is claimed. CPS still counts dig swings (a 4-tick
  relay is 5 CPS against a 20 cap, so counting costs nothing and suppressing it could shield a
  clicker that also digs). The swing clock is advanced before the skip so ending a dig leaves no
  outlier interval behind to gate `KURT_STRICT` off for the next 600 swings.
- **`reach` on a miner standing near a damaged teammate** (1 `Reach`/`motionless` flag, peakVL
  11.00; `legit-mining-near-hurt`). `AttackInference.correlate` is proximity and timing alone: it
  never read `HurtSource`, never read `HurtSignal.attackerEntityId`, and tested no facing, line of
  sight or reach. A stride-4 swing stream places exactly one swing in any 4-tick window, so a digger
  is a *permanently* eligible attacker, and a hurt with no attacker id has no real candidate to
  outrank them. `ClientPlayNetworkHandlerMixin` turns `EntityStatusS2CPacket` byte 2 into an id-less
  hurt for every tracked player who takes damage, and that packet is broadcast to every client
  tracking them -- so mining beside a teammate who takes fall, fire, drown or mob damage was enough
  to earn a Reach flag in the wild, not just in the harness. Fixed with two guards in `correlate`: an
  id-carrying hurt can only be claimed by the attacker it names, and an id-less non-`VELOCITY` hurt
  cannot be claimed by a candidate the server currently has digging. The `VELOCITY` carve-out is
  deliberate -- a knockback impulse is evidence of a real hit, and silencing it for diggers would hand
  a damage-suppressing aura a bypass. The first guard also closes a wider misattribution class the
  audit found on the way: mob, arrow, potion and TNT damage names an entity that is not a tracked
  player, and that previously fell through to proximity and landed on whoever swung nearby.
  `cheat-reach-digging-koid` is the bypass proof (a reach module that re-arms a dig every tick is
  still attributed and still alerts, peakVL clears the bar).

Both fixes share a residual, stated rather than buried: a client that fakes a dig by re-sending
`START_DESTROY_BLOCK` and clicks at 2-tick intervals or slower evades the cadence windows, and one
that also lands silent id-less hits evades the id-less proximity attribution. The named-attribution
path is never gated, and a named hurt clears the dig state for that attacker, so a faking client
loses the exemption the moment it lands a hit the observer can see.

### 6.4 Harness lessons (encoded in the helpers)

- Bots must spawn **standing on the ground**. A floating bot is legitimately flaggable as hover/fly
  (the first run produced a `flyEnvelope` false positive at peakVL 15.5) — and so is a bot that
  *settles* in mid-air before its drive starts: `legit-mace-smash`'s first version spawned the
  faller 24 blocks up and idled there for the pre-launch ticks, which a stationary `dy = 0` player
  matches the `Fly(FlyB)` sustained-hover signature exactly (peakVL 5.5). Spawn grounded and
  teleport up when the fall actually begins.
- Publish a victim's hurt the tick **after** the swing on a fast-moving attacker. The tracker's
  snapshot trails the scenario's written state by one tick, so a same-tick hurt makes the attack
  correlation read the attacker's *previous* position — one tick higher on a ~2 b/t descent, which
  inflated `legit-mace-smash`'s measured hit distance past reach's tolerance (sub-threshold vl 5.0).
  A one-tick swing→hurt separation is also the realistic shape (a server round-trip).
- A legitimate fall must inject the **landing hurt** -- a long fall with no hurt *is* the
  no-fall-damage signature.
- But not every innocent fall *has* one. Some vanilla behavior is legitimately damage-free by
  design (a wind charge negates fall damage up to its launch height), so a missing hurt is not by
  itself evidence of a cheat. Before injecting a hurt to quiet a scenario, ask whether a real
  server would have sent one -- injecting it here would have fabricated the very signal that
  decides the assertion, and hidden a genuine false positive (`legit-windcharge`).
- **A recorded false positive can be half a drive bug.** `legit-bunnyhop`'s 37-VL entry was real --
  the accumulator did sum across hops -- but the drive *also* sent a packet shape no vanilla client
  sends: it reported `onGround = false` on the very tick its feet reached the surface, when
  `Entity.move()` sets that bit from the collision (so a real landing tick says grounded). The
  detector was keying its reset on a proxy that can never fire mid-chain, and the misleading input
  made the whole thing look unfixable. Before recording `expectDocumentedFp`, check that the drive
  reproduces the real packet shape -- and when you fix a finding, fix the drive too.
- **An exemption must re-base, never clear.** A no-damage burst exemption is safe only because it
  re-bases the accumulator on the burst's own launch point and keeps the *highest* such point: an
  impulse fired while descending sits above the falling player (`Δy < 0`), so it can only raise the
  floor, and no burst ever clears accumulated fall. The tempting shortcut -- "exempt a landing
  within N ticks of a burst" -- would have handed that same cheater a free 35-block fall, which is
  exactly what `cheat-nofall-burst-spoof-meteor` drives. When you add a guard for a legitimate
  signal, write the drive that abuses it in the same change.
- A jumping bot must clear **on-ground** while airborne. Leaving it true reads as a bot standing in
  mid-air and trips the ground-spoof path.
- A legit drive must not be a cheat drive by accident: a bot holding a byte-identical aim is an
  aimbot by construction, so the legit combat drive carries hand variance (`Jitter`) -- verified
  live, a constant-pitch attacker tripped `rotationTracking` continuously.
- The lane must stay **inside the loaded region**; `groundedProxy` goes false once chunks unload, so
  a long walk reads as airborne at the far end.
- Sparse high-value events cannot accumulate against decay. Density is the first thing to check when
  a cheat scenario misses, after the flag subject.
- Some checks flag the **victim**, not the attacker (`noKnockback`, `jumpOnHurt`, `backtrack`).
- A shared scenario helper must take the expectation as a parameter when its variants differ: the
  `reach` and `sprintHack` helpers each have one variant that alerts and one that does not, and
  flattening that would retire a working assertion to hide a broken drive.
- **Publish every bot signal from the client thread.** This was the single most expensive bug in the
  harness. `swing()` hopped to the client thread; `hurt()`/`velocity()`/`speedEffect()` published
  straight from the gametest thread. Fabric's gametest API instruments `MinecraftClient.getInstance()`
  to **throw** on that thread, and every detector is fail-open -- so `ThroughWallsCheck`, which reads
  the world inside its `AttackEvent` handler, threw on every single hit and was silently disabled. The
  suite reported `cheat-throughput-vape` as a **bypass** while the check's own world read was raising
  `IllegalStateException` a few frames away. Any event-driven check that touches client state is at
  risk from this, so `BotHandle` now routes every publisher through one `publishFromClient` helper.
  The lesson generalizes: **a fail-open detector plus a harness that cannot deliver its input looks
  exactly like a bypass.** When a check logs *zero* flags while the drive demonstrably lands hits,
  instrument the check's entry point before you touch the drive.
- **The tracker's snapshot trails a scenario action by one tick.** `ScenarioBuilder.runFor` runs the
  action and *then* calls `waitTick()`, so a position/yaw written this tick is not what the tracker
  samples this tick. Three drives were wrong because of it: `cheat-backtrack-vape` hit on the snap
  tick (the check saw the victim still in reach, pushed `false`, and the one-tick-later hit was eaten
  by attack inference's 2-tick dedup), `cheat-rotsnapback-liquidbounce` presented the *travel*
  bearing at the attack tick and so never primed, and `cheat-rotationtracking-vape` aimed at a
  position the tracker had not sampled yet. The fix is always the same shape: **write the state one
  tick before the tick it must be observed on**, and read the tracker's own value when the drive
  needs to aim at what the check will compare against.
- **A drive that keeps two entities a fixed distance apart must add displacement, not an offset.**
  `legit-combat`'s knockback excursion was first written as `victimZ += step + kbPush`, which adds
  `kbPush` every tick instead of its *change*; the victim gained ~0.2 blocks per attack and the gap
  walked from 2.2 out past 7 blocks within one encounter, at which point `reach` correctly flagged a
  scenario that exists to prove reach stays quiet. Apply the delta, and let the burst and the unwind
  cancel.
- **A held lag is not a drift.** `killAura`'s drift component correlates the *direction* of the yaw
  change with the direction of the remaining bearing error -- "is the reticle closing on the target".
  Holding `bearing - 20` while the target orbits makes the yaw follow the bearing without ever
  closing, so the turn's sign matches the sweep rather than the error and the component is never
  exercised (one flag in 140 ticks). A rate-capped assist that actually closes is both the honest
  reproduction and the only shape the signal exists for. More generally: when a component is defined
  by a *relationship* (closing, matching, returning), a drive that holds the relationship constant
  tests nothing.

## 7. Evidence for a pull request

Paste the runner's table. That is the whole requirement for the automated portion:

```text
Automated live tests (python scripts/live_selftest.py --check <substring>):
- scenarios: 5   passed: 5
- legit pass false positives : none
- cheat pass bypasses        : none
- documented findings: none new
- harness gaps: 2 (pre-existing, unrelated to this change)
- scenarios added/changed: cheat-mycheck-vape (new), legit-mycheck (new guard)
- not covered: mixin packet decode, rendering -- see live-verification.md
report: build/selftest-report.json
```

State the three counts that matter: **false positives**, **bypasses**, and whether you **added or
closed** a documented finding or harness gap. A PR that closes a finding must move it out of §6 and
turn its `expectKnownOpen` into a real `expect(...)` — the entry will otherwise self-report as
`KNOWN-OPEN CLOSED`.

Then add anything the suite cannot see (rendering, packets, ViaFabricPlus) using the
manual checklist in [live-verification.md](live-verification.md), and say explicitly
what you did not run.

---

## 8. Running it in CI

The client gametest runs headless on Linux with a virtual framebuffer; Loom enables
XVFB automatically when `CI` is set, or explicitly:

```yaml
- name: Live tests
  strategy:
    matrix: { shard: [1, 2, 3] }
  run: python scripts/live_selftest.py --shard ${{ matrix.shard }}/3 --timeout 2400
```

`--shard I/N` keeps every Nth scenario of a stable sort, so the shards partition the suite exactly:
no overlap, nothing dropped, and each shard boots one client and reports its own verdict. Use it
whenever the full run exceeds a CI job's budget (the suite grew past 70 scenarios, which is past a
single comfortable timeout). A *full* run replaces the cached report; a filtered or sharded run
unions into it, so the evidence accumulates instead of shrinking.

Useful narrower runs while working (each boots a client, so keep the filter tight):

| Command | What it runs |
|---|---|
| `--check <substring>` | only scenarios whose name contains it (comma-separated) |
| `--source <Client>` | only one reference client's drives |
| `--tag combat\|movement\|rotation\|packet\|world\|guard\|replay` | one behaviour family |
| `--legit-only` / `--cheat-only` | one pass |
| `--fast` | everything except the replay pass |
| `--verbose-log` | arm the mod's per-flag log (sub-flag label + measured value); written asynchronously, so read it after the client exits (the drain is flushed on shutdown) |
| `--verbose` | print peak VL evidence for every scenario |
| `--coverage` / `--matrix` / `--list` | offline: no client boot |
| `--strict` | make documented findings fatal (use before a release) |
| `--report-md` | also write `build/selftest-report.md` to paste into a PR |

Keep it out of the required path only if your CI cannot allocate a GPU/display; in
that case run it on a schedule and treat failures as blocking before release. On a
developer machine the suite opens a game window briefly -- no interaction is needed,
so agents can run it unattended.

---

## 9. Troubleshooting

| Symptom | Likely cause |
|---|---|
| Runner says `NOT RUN` / no report | Client failed to launch: check for mixin apply errors, a stale `run/` lock, or a world-creation failure in the log |
| Every scenario fails with a tracking error | Bots are not reaching the tracker: the client world was not ready (chunk load) or spawn coordinates are outside loaded chunks |
| Cheat scenario misses on every run | Check the flag *subject* first (some checks flag the victim, not the attacker), then density. Then look at what the drive **did** trip: if unrelated checks fired loudly and your target logged nothing, the drive is wrong (`expectDriveGap`). If your target logged sub-threshold flags, it is a detector finding (`expectKnownOpen`) |
| Legit scenario alerts | A real false-positive regression — treat as a bug in the check |
| A check is marked `[harness gap]` in `--coverage` | The drive never reached it. Run `--check <its scenario> --verbose-log` and read the game log: the flags your drive *does* produce tell you what behaviour it is actually driving |
| `--coverage` looks emptier than the last run | You ran a filtered or sharded run and expected a reset, or a full run reset the union. A full (unfiltered) run replaces the cache and is authoritative |
| Filter matched nothing | The runner fails on purpose; list valid names with `--check <substring>` of an existing scenario |
| `--help` prints nothing useful / crashes | Fixed: the runner forces UTF-8 and emits ASCII-only text. If you see a `UnicodeEncodeError`, a new non-ASCII glyph crept into `live_selftest.py` — replace it |
| The run is audible | The mute should make this impossible. If it happens, grep the log for `audio mute failed` or `audio mute skipped`: it prints the exception class instead of failing the run, deliberately. `audio muted` should appear exactly once per run |
