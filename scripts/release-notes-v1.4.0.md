# Iustitia 1.4.0

This one is a replay/clip overhaul plus a detection-accuracy pass. Your settings carry over, and every old clip still plays.

## Clips: bigger, faster, and whole-scene

The world around a `/ius clip` used to be swept all at once the moment you saved, which froze the client while ~300 chunk snapshots went by in a single tick. It's now rolled up in the background while the scene is still live (16 chunks a tick, nearest first), so `/ius clip` returns instantly. A window that spans a teleport records one segment per place, and the replay shows both.

Clips also capture the whole scene now: nearby mobs, animals, boats and minecarts, plus every block edit observed during the window. Replays draw them through their own vanilla entity models, so what you replay is what you saw. Four new options in `/ius config` back this: **Clip captures entities**, **Entity capture cap** (default 64), **Rolling world budget** (default 24,000 sections, roughly 20-95 MB of live captured world; a *fully captured* 17x17-radius segment is ~3,500 sections, about 14 MB, and about seven of those fit the default), and **New-segment distance**. If you're running a long session on a tight machine, the budget is the one to turn down.

Clip format is now v13: per-segment worlds, block-edit deltas, entity capture, and body/head yaw. Clips from v2 through v12, including SnapClip's v9-v12, are still read here, though the older layouts are coded from the format's shape rather than from a reference clip, since none exist in this repo to test against, so treat that path as best-effort. A file that does not match is refused rather than half-loaded: the reader checks that it consumed exactly the bytes on disk and reports a truncated or unrecognized clip. The reverse is not true: an older Iustitia build can't read a v13 clip, so re-export one if you need to open it there.

One thing was missing from every replay until now: you. The rolling buffer recorded everyone else, so a freecam replay of a fight showed all the other players and an empty patch of ground where you had been standing. Your own body is captured like any other one now, drawn at its recorded position, and your live body is hidden for the duration so you appear once instead of twice, in freecam, pov, follow and free alike. There is no format bump for this. Your body is stored as an ordinary player, so it survives the clip round trip untouched and works under both **Modern** and **Legacy** playclip. An older clip carries no body of yours, and there the live one stays visible exactly as it did before.

## Detection pass

The per-hit combat checks (reach, killAura, wTap, keepSprint, autoBlock, criticals, hitFlick, multiTarget and friends) now share a sustained-episode gate: a rolling window of per-hit verdicts, a required pattern, one flag per episode at a level that actually clears the setback threshold, and a re-arm only when the pattern breaks. Before, several of them could flag one hit per burst and lose the accumulated VL to decay.

That gate had a second bug of its own. Two of the checks using it, `hitsWithoutSwing` and killAura's combat-ward drift, could never re-arm after their first episode, which made them effectively one-shot for a whole session. Both are fixed, and each now carries a regression scenario that drives a second episode and requires a second alert.

`reach` catches the quiet tier now. The old check gave every attack 0.8 blocks of headroom, which is honest while either player is moving (client interpolation error is real) but pure slack when neither one has moved. On a motionless pair the check now compares the vanilla reach metric itself against a 3.2-block ceiling. Koid-style 3.6-block reach and LiquidBounce's 4.2 both alert.

`throughWalls` had a raycast bug that made its occlusion verdicts effectively random. Fixed, so hits that land behind walls are seen for what they are.

`noFallDamage` was rebuilt around a launch-floor re-base, which fixes two false positives and closes a spoof gap in one move. A fall caught on a water bucket, hay block or powder snow reduces damage the vanilla way and doesn't accumulate VL, and falling onto a wind-charge launch (or jumping on one) reads as a reset. But descent that starts above the launch floor never got a floor in the first place, so burst-spoof falls from 35+ blocks above a wind charge still flag.

Attack attribution got a source it never had. `reach`, `multiTarget` and every other check that reads a correlated attack used to accept a hurt from whoever happened to be swinging nearby, because the correlation was proximity and timing alone. A hurt is now attributed only to the player the server names, and on a server that names damage causes at all, a hurt that names nobody is attributed to no one. Mining, or simply standing, next to a teammate who takes an arrow, a fall or a mob hit no longer lands on whoever swung nearby. The knockback that follows such a hit is credited only when the damage behind it was never observed, which is what keeps older servers, where nothing gets named, working exactly as before.

## False positives

Ten known false positives are gone. Ladder climbs no longer trip `flyEnvelope`, and legitimate water walking no longer trips `waterWalk`, both guarded by live tests in the harness now, so they stay gone. Four more were closed in the release-audit pass, two in the swing-source audit and two in the attribution follow-up, each one a legitimate shape that a detector misread as a cheat:

- a **slab/stairs ramp** no longer trips `flyEnvelope` (a step-up is a single-tick Δy spike on a raw position delta, and the old streak counters spanned the level ground between two steps);
- a **strafe across a held crosshair** no longer trips `triggerbot` (a crosshair-to-hitbox edge can be created by either party moving; the check now requires the attacker's own aim to have moved to call it a reaction);
- an **already-airborne victim** no longer trips `noKnockback`'s VelocityB (the upward-KB comparison was never defined for a victim who was not on the ground at the hit); and
- a **vanilla sword sweep** no longer trips `multiTarget`'s pair gate (the gate's window counted attack *events*, so one sweep's packets satisfied a gate whose whole point is repetition; it is tick-keyed now);
- **digging a block that cannot break** no longer trips `clickStatistics` (holding left click on bedrock swings the arm every tick, and the server relays that animation on its own fixed clock, so the interval stream is exactly constant, which is the StDev fingerprint of a fixed-delay autoclicker: 35 flags and a peak VL of 28.20 against a 5.0 setback); and
- **mining near a damaged teammate** no longer trips `reach` (attack attribution was proximity alone, so a digger's stride-4 swing stream made them a permanently eligible attacker for a hurt that names nobody, including falls, fire and mob damage);
- **standing near damage that names nobody** no longer trips `reach` or `hitsWithoutSwing` (a swing anywhere near a teammate's fall, fire, arrow or TNT damage was recorded as the attack, and the knockback that followed a mob hit was read as a hit of its own); and
- **swinging near a teammate taking non-player damage** no longer trips `hitsWithoutSwing` (the check's fallback attributed a hurt that named no tracked player to the nearest player who had swung recently, so an innocent bystander inside melee range accumulated a no-swing episode from somebody else's damage).

The two dig-driven ones share a root cause, and it is the interesting part: while a dig is live, the swings a client observes are the *server's*, not the player's. Both fixes read a new dig-state observation and exempt it, and both are bounded so the exemption cannot be used to hide. Swings relayed on the server's dig clock are excluded from the cadence windows only at an interval of 2 ticks or more, as fast as a relay runs in vanilla, so a fast clicker is still evaluated. A hurt that names its attacker can only be claimed by that attacker, and a named hit clears the dig state for that attacker, so a client that fakes a dig loses the exemption the moment it lands a hit the observer can see. Both are covered by permanent harness regressions on the legitimate shapes, plus a new cheat row that holds a fake dig open while a reach module runs.

The other two are a second, wider root cause: a hurt the server did not blame on anyone was attributed locally, by proximity alone. Two separate consumers did it, the correlator underneath every attack-driven check and `hitsWithoutSwing`'s own fallback, and the damage that arrives naming nobody is everyday vanilla. Fall, fire, drowning, mob hits, arrows and TNT all reach an observer that way. Attribution now follows the server. A hurt is credited to the player the server named, and on a protocol that names damage causes at all, a hurt that names nobody is credited to no one. The knockback a mob hit applies arrives as a separate unnamed impulse, so an impulse counts only when the damage behind it was never observed, which is the legacy case where nothing is named and proximity is all there is. That legacy path is unchanged, and it is the one thing the harness cannot reach.

Each fix ships with the legitimate shape as a permanent harness regression, and the cheat scenarios that reach the narrowed detector are re-run in the same pass (the whole cheat pass, for the attribution change), so a fix narrows the false positive without opening a bypass.

## Presets: five profiles, one detector

The mod used to ship a single built-in profile. There are five now, and the only detection number that differs between them is `setbackVL`, the VL level at which an alert fires. `decay` and `threshold` keep their stock values in all five, so a scaled profile is the same detector with a different trip point rather than a different detector. Nothing here recalibrates a default, so the config schema version is not bumped and your settings carry over.

- `standard` is the everyday profile and the recommended one: stock sensitivity, alerts from the orange band up, the `⚠ lag` indicator as its only visual.
- `lenient` doubles the trip point and reports the red band only, so a chat line needs roughly six times the stock deviation. It catches blatant combat modules and lets ghost cheats pass.
- `strict` turns every check on and halves the trip point, with compact one-line alerts.
- `moderation` scales the trip point by 0.75, reports every severity band as its own line, plays audio cues, opens the transcript panel, and shortens the join grace to 100 ticks and the alert throttle to 20.
- `debug` sets every boolean in the config true, turns every check on, halves the trip point and zeroes both suppression timers. It is a diagnostic profile, and the setup wizard does not offer it; apply it with `/ius preset debug`.

`standard` also changed what it detects, in the one place an everyday player would notice. It now ships seven checks off: `hitsWithoutSwing`, `speedEnvelope`, `flyEnvelope`, `noFallDamage`, `phaseClip`, `longJump` and `teleport`. Every one of them reads a behaviour a minigame server is free to implement itself, so on a server with dash pads, lobby flight, damage-free arenas or warps they fire on honest players, and the moderator reading the alert has no evidence to weigh it against. The checks stay implemented, they stay switchable in `/ius config`, and `strict` and `debug` turn them all on. `lenient` and `moderation` ship them off, as `standard` does.

An apply writes every check's `enabled` flag, which is what makes a preset a profile rather than a patch: applying `strict` turns a check back on even if you had switched it off by hand. Your mute list, your moderator notes and your persistence setting are not preset content and survive an apply untouched.

`/ius presets` describes each built-in now, so you can read what a profile costs before applying it, and the setup wizard's four buttons apply the presets themselves: Standard, Lenient, Strict and Moderation. The old buttons were General, Moderation and Ranked Player. General already applied `standard`, so it is just Standard. Ranked Player only ever changed display fields, so it is gone, and `lenient` is the closest thing to its quiet while `strict` is the closest to its compact one-liners. The wizard is also the one place persistence is set from a preset, because no preset is allowed to write it: only Moderation turns it on.

The live suite could not see any of this before. The harness turns every check on and overwrites the alert and display fields before each scenario, so a profile that shipped the wrong detection scope, or the wrong display settings, ran green in every pass. The new `preset-builtin-coverage` scenario applies all five and asserts the config each one lands, scaling included, and a JVM test holds the full profile table.

## For contributors

Iustitia is now an open-collaboration project. `CONTRIBUTING.md`, `SECURITY.md`, `SUPPORT.md` and a code of conduct are in the repo, along with issue and PR templates and CI workflows. The centerpiece for anyone hacking on detection: a three-pass live-test harness (`python scripts/live_selftest.py`) that runs 92 automated scenarios in a real game client. The legit pass plays like a vanilla player and asserts silence (false-positive guards); the cheat pass reproduces modules from 9 reference cheat clients and 4 reference anticheats and asserts alerts (bypass guards); the replay pass covers the observer tooling. Every scenario names the client or anticheat it mirrors.

## Verification

Every number below was produced at `2c07601`, the tree the live suite last passed on in full. The commits after it change preset content and check prose, and the detection passes observe neither: the harness turns every check on and overwrites the alert and display fields before each scenario, so a profile's scope and display settings never reach a flag. The suite is 92 scenarios now, one more than the pass below, and the addition is the last bullet of this list.

- `python scripts/verify_contribution.py --static`: pass. Six detector defaults in `scripts/checks.json` had drifted from the code they describe, so the verifier now compares them value by value against `IustitiaConfig.kt`; perturbing one makes it exit 1 instead of passing quietly.
- `./gradlew test --no-daemon`: 21 tests, 0 failures.
- `python scripts/live_selftest.py`: the full three-pass suite in a real game client. 91 scenarios, all green, 0 false positives, 0 bypasses. The 4 recorded detector gaps and 5 harness-gap entries are unchanged and still listed, and the whole cheat pass was re-run against the narrowed attribution, so no bypass opened behind the fix.
- `python scripts/live_selftest.py --check preset`: a scoped boot of the three preset scenarios plus the smoke test, run at `b51bf22`. 4 of 4 green, so all five built-in profiles land their enable contract, their `setbackVL` scaling and their display settings on the live config object, and a name that is not a preset still changes nothing.
- `./gradlew test --no-daemon` at `b51bf22`: 33 tests, 0 failures. `./gradlew build --no-daemon` and `python scripts/verify_contribution.py --static` both pass on the same tree.

The two re-arm regressions were observed in both directions: one alert each against the pre-fix checks, where two were required, and two each against the fixed ones.

The suite cannot see everything. Mixin packet decode, rendering and screenshots, ViaFabricPlus 1.8 behaviour, the legacy v2-v12 clip layouts (there are no reference clips in the repo to test them against) and multiplayer server interaction stay manual, and `docs/live-verification.md` is the checklist for them. The two entries on that checklist the suite structurally cannot reach, the dig pass behind the clickStatistics fix and the legacy attribution pass behind this one, were both worked by hand on 2026-09-16 and came back clean, so neither the dig-driven false positive nor a dead attribution path on a 1.8 server is left to assumption.

## Install

1. Drop `iustitia-1.4.0.jar` into your `mods` folder (grab it from the [latest release](https://github.com/ThoriaDevelopment/Iustitia/releases/latest)).
2. You need Fabric Loader, Fabric API, Fabric Language Kotlin, and Minecraft 1.21.11.

Settings and clips carry straight over from 1.3.x. The detection pipeline and config format are unchanged. Run `/ius help` in game for the command list, or `/ius config` to tune every check.

Links: [Source](https://github.com/ThoriaDevelopment/Iustitia) | [Issues](https://github.com/ThoriaDevelopment/Iustitia/issues) | [Documentation](https://thoria.fyi/iustitia)

---

*Not affiliated with or endorsed by Mojang or Microsoft. Minecraft is a trademark of Mojang Synergies AB.*
