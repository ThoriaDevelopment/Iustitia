# Iustitia 1.4.0

This one is a replay/clip overhaul plus a detection-accuracy pass. Your settings carry over, and every old clip still plays.

## Clips: bigger, faster, and whole-scene

The world around a `/ius clip` used to be swept all at once the moment you saved, which froze the client while ~300 chunk snapshots went by in a single tick. It's now rolled up in the background while the scene is still live (16 chunks a tick, nearest first), so `/ius clip` returns instantly. A window that spans a teleport records one segment per place, and the replay shows both.

Clips also capture the whole scene now: nearby mobs, animals, boats and minecarts, plus every block edit observed during the window. Replays draw them through their own vanilla entity models, so what you replay is what you saw. Four new options in `/ius config` back this: **Clip captures entities**, **Entity capture cap** (default 64), **Rolling world budget** (default 24,000 sections, roughly 20–95 MB of live captured world — a *fully captured* 17×17-radius segment is ~3,500 sections ≈ 14 MB and about seven of those fit the default), and **New-segment distance**. If you're running a long session on a tight machine, the budget is the one to turn down.

Clip format is now v13: per-segment worlds, block-edit deltas, entity capture, and body/head yaw. Every older clip (v2 through v12, including SnapClip's v9-v12) still loads here. The reverse is not true: an older Iustitia build can't read a v13 clip, so re-export one if you need to open it there.

## Detection pass

The per-hit combat checks (reach, killAura, wTap, keepSprint, autoBlock, criticals, hitFlick, multiTarget and friends) now share a sustained-episode gate: a rolling window of per-hit verdicts, a required pattern, one flag per episode at a level that actually clears the setback threshold, and a re-arm only when the pattern breaks. Before, several of them could flag one hit per burst and lose the accumulated VL to decay.

That gate had a second bug of its own. Two of the checks using it, `hitsWithoutSwing` and killAura's combat-ward drift, could never re-arm after their first episode, which made them effectively one-shot for a whole session. Both are fixed, and each now carries a regression scenario that drives a second episode and requires a second alert.

`reach` catches the quiet tier now. The old check gave every attack 0.8 blocks of headroom, which is honest while either player is moving (client interpolation error is real) but pure slack when neither one has moved. On a motionless pair the check now compares the vanilla reach metric itself against a 3.2-block ceiling. Koid-style 3.6-block reach and LiquidBounce's 4.2 both alert.

`throughWalls` had a raycast bug that made its occlusion verdicts effectively random. Fixed, so hits that land behind walls are seen for what they are.

`noFallDamage` was rebuilt around a launch-floor re-base, which fixes two false positives and closes a spoof gap in one move. A fall caught on a water bucket, hay block or powder snow reduces damage the vanilla way and doesn't accumulate VL, and falling onto a wind-charge launch (or jumping on one) reads as a reset. But descent that starts above the launch floor never got a floor in the first place, so burst-spoof falls from 35+ blocks above a wind charge still flag.

## False positives

Six known false positives are gone. Ladder climbs no longer trip `flyEnvelope`, and legitimate water walking no longer trips `waterWalk` — both guarded by live tests in the harness now, so they stay gone. Four more were closed in the release-audit pass, each one a legitimate shape that a detector misread as a cheat:

- a **slab/stairs ramp** no longer trips `flyEnvelope` (a step-up is a single-tick Δy spike on a raw position delta, and the old streak counters spanned the level ground between two steps);
- a **strafe across a held crosshair** no longer trips `triggerbot` (a crosshair-to-hitbox edge can be created by either party moving; the check now requires the attacker's own aim to have moved to call it a reaction);
- an **already-airborne victim** no longer trips `noKnockback`'s VelocityB (the upward-KB comparison was never defined for a victim who was not on the ground at the hit); and
- a **vanilla sword sweep** no longer trips `multiTarget`'s pair gate (the gate's window counted attack *events*, so one sweep's packets satisfied a gate whose whole point is repetition — it is tick-keyed now).

Each ships with the legitimate shape as a permanent harness regression, and the corresponding cheat scenario is re-run against the narrowed detector in the same pass, so the fixes narrow the false positive without opening a bypass.

## For contributors

Iustitia is now an open-collaboration project. `CONTRIBUTING.md`, `SECURITY.md`, `SUPPORT.md` and a code of conduct are in the repo, along with issue and PR templates and CI workflows. The centerpiece for anyone hacking on detection: a three-pass live-test harness (`python scripts/live_selftest.py`) that runs 84 automated scenarios in a real game client. The legit pass plays like a vanilla player and asserts silence (false-positive guards); the cheat pass reproduces modules from 9 reference cheat clients and 4 reference anticheats and asserts alerts (bypass guards); the replay pass covers the observer tooling. Every scenario names the client or anticheat it mirrors.

## Verification

Every number below was produced at `ca12ec8`, the source tree this release is cut from.

- `python scripts/verify_contribution.py --static`: pass. Six detector defaults in `scripts/checks.json` had drifted from the code they describe, so the verifier now compares them value by value against `IustitiaConfig.kt`; perturbing one makes it exit 1 instead of passing quietly.
- `./gradlew test --no-daemon`: 21 tests, 0 failures.
- `python scripts/live_selftest.py`: the full three-pass suite in a real game client. 84 scenarios, all green, 0 false positives, 0 bypasses. The 4 recorded detector gaps and 6 drive gaps are unchanged and still listed.

The two re-arm regressions were observed in both directions: one alert each against the pre-fix checks, where two were required, and two each against the fixed ones.

The suite cannot see everything. Mixin packet decode, rendering and screenshots, ViaFabricPlus 1.8 behaviour and multiplayer server interaction stay manual, and `docs/live-verification.md` is the checklist for them.

## Install

1. Drop `iustitia-1.4.0.jar` into your `mods` folder (grab it from the [latest release](https://github.com/ThoriaDevelopment/Iustitia/releases/latest)).
2. You need Fabric Loader, Fabric API, Fabric Language Kotlin, and Minecraft 1.21.11.

Settings and clips carry straight over from 1.3.x. The detection pipeline and config format are unchanged. Run `/ius help` in game for the command list, or `/ius config` to tune every check.

Links: [Source](https://github.com/ThoriaDevelopment/Iustitia) | [Issues](https://github.com/ThoriaDevelopment/Iustitia/issues) | [Documentation](https://thoria.fyi/iustitia)

---

*Not affiliated with or endorsed by Mojang or Microsoft. Minecraft is a trademark of Mojang Synergies AB.*
