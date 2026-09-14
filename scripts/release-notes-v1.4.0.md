# Iustitia 1.4.0

This one is a replay/clip overhaul plus a detection-accuracy pass. Your settings carry over, and every old clip still plays.

## Clips: bigger, faster, and whole-scene

The world around a `/ius clip` used to be swept all at once the moment you saved, which froze the client while ~300 chunk snapshots went by in a single tick. It's now rolled up in the background while the scene is still live (16 chunks a tick, nearest first), so `/ius clip` returns instantly. A window that spans a teleport records one segment per place, and the replay shows both.

Clips also capture the whole scene now: nearby mobs, animals, boats and minecarts, plus every block edit observed during the window. Replays draw them through their own vanilla entity models, so what you replay is what you saw. Four new options in `/ius config` back this: **Clip captures entities**, **Entity capture cap** (default 64), **Rolling world budget** (default 24,000 sections, roughly 14 MB), and **New-segment distance**. If you're running a long session on a tight machine, the budget is the one to turn down.

Clip format is now v13: per-segment worlds, block-edit deltas, entity capture, and body/head yaw. Every older clip (v2 through v12, including SnapClip's v9-v12) still loads here. The reverse is not true: an older Iustitia build can't read a v13 clip, so re-export one if you need to open it there.

## Detection pass

The per-hit combat checks (reach, killAura, wTap, keepSprint, autoBlock, criticals, hitFlick, multiTarget and friends) now share a sustained-episode gate: a rolling window of per-hit verdicts, a required pattern, one flag per episode at a level that actually clears the setback threshold, and a re-arm only when the pattern breaks. Before, several of them could flag one hit per burst and lose the accumulated VL to decay.

`reach` catches the quiet tier now. The old check gave every attack 0.8 blocks of headroom, which is honest while either player is moving (client interpolation error is real) but pure slack when neither one has moved. On a motionless pair the check now compares the vanilla reach metric itself against a 3.2-block ceiling. Koid-style 3.6-block reach and LiquidBounce's 4.2 both alert.

`throughWalls` had a raycast bug that made its occlusion verdicts effectively random. Fixed, so hits that land behind walls are seen for what they are.

`noFallDamage` was rebuilt around a launch-floor re-base, which fixes two false positives and closes a spoof gap in one move. A fall caught on a water bucket, hay block or powder snow reduces damage the vanilla way and doesn't accumulate VL, and falling onto a wind-charge launch (or jumping on one) reads as a reset. But descent that starts above the launch floor never got a floor in the first place, so burst-spoof falls from 35+ blocks above a wind charge still flag.

## False positives

The last two known false positives are gone: ladder climbs no longer trip `flyEnvelope`, and legitimate water walking no longer trips `waterWalk`. Both are guarded by live tests in the harness now, so they stay gone.

## For contributors

Iustitia is now an open-collaboration project. `CONTRIBUTING.md`, `SECURITY.md`, `SUPPORT.md` and a code of conduct are in the repo, along with issue and PR templates and CI workflows. The centerpiece for anyone hacking on detection: a two-pass live-test harness (`python scripts/live_selftest.py`) that runs 74 automated scenarios in a real game client. The legit pass plays like a vanilla player and asserts silence (false-positive guards); the cheat pass reproduces modules from 11 reference cheat clients and asserts alerts (bypass guards). Detection and replay scenarios are both covered, and every scenario names the client or anticheat it mirrors.

## Install

1. Drop `iustitia-1.4.0.jar` into your `mods` folder (grab it from the [latest release](https://github.com/ThoriaDevelopment/Iustitia/releases/latest)).
2. You need Fabric Loader, Fabric API, Fabric Language Kotlin, and Minecraft 1.21.11.

Settings and clips carry straight over from 1.3.x. The detection pipeline and config format are unchanged. Run `/ius help` in game for the command list, or `/ius config` to tune every check.

Links: [Source](https://github.com/ThoriaDevelopment/Iustitia) | [Issues](https://github.com/ThoriaDevelopment/Iustitia/issues) | [Documentation](https://thoria.fyi/iustitia)

---

*Not affiliated with or endorsed by Mojang or Microsoft. Minecraft is a trademark of Mojang Synergies AB.*
