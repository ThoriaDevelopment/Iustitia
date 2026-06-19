<div align="center">

<img src="iustitia.png" alt="Iustitia" width="128" height="128">

# Iustitia

A purely client-sided anticheat for Minecraft Java **1.21.11** (Fabric).

Detects **both 1.8-era and 1.21.11-era cheats** by passively observing *other* players through the packets the server already rebroadcasts to you — no server component, no network transmission, no outgoing packets.

**Local-only chat alerts. No HUD. No bans. No interference.**

</div>

---

## What it is

Iustitia is a Fabric client mod that watches every *other* player on your server and flags impossible world/combat interactions — the kind of thing a reach hack, a killaura, a fly hack, or a timer cheat produces. It does this entirely on your client:

- **Read-only on incoming packets.** A single mixin (`ClientPlayNetworkHandlerMixin`) observes server packets and feeds them into a tracking pipeline. It never sends anything to the server and never mutates the local player or the camera.
- **Other players only.** It builds a server-space model of every other client player (position, yaw/pitch, sprint/sneak, hurt ticks, vehicle, deltas) from rebroadcast state, then runs 32 detection checks against that model each tick.
- **Fail-open everywhere.** Every check and mixin body is wrapped so a thrown exception is swallowed and skipped — a detection error never crashes your client and never produces a false positive. A chunk-unloaded player is a false *negative*, never a false *positive*.
- **Two streams, kept separate.** *Chat alerts* fire only when a check's violation level crosses its setback threshold. *Verbose console logging* (every flag, a pipeline heartbeat) is opt-in via `/ius verbose` for validation/debugging and is **off by default** — the release build is silent in `latest.log` unless you turn it on.

It is a detection/inspection tool, not an enforcement tool: it tells *you* who looks like a cheater. It does not kick, ban, or report anyone, and it sends nothing anywhere.

## Requirements

| Dependency | Version |
|---|---|
| Minecraft | 1.21.11 |
| Fabric Loader | 0.19.3+ |
| Fabric API | 0.141.3+1.21.11 (any compatible) |
| fabric-language-kotlin | 1.13.9+kotlin.2.3.10 (any compatible) |
| Yet Another Config Lib (YACL) | 3.8.2+1.21.11 (any compatible) |
| Java | 21 |
| **Recommended:** ViaFabricPlus | for playing on 1.8-era servers via protocol translation |

All three library mods (Fabric API, fabric-language-kotlin, YACL) are standard and can be installed alongside any other modpack. Iustitia is written to fail-open and not crash when other mods are present.

## Installation

1. Install Fabric Loader 0.19.3+ for Minecraft 1.21.11.
2. Drop **Fabric API**, **fabric-language-kotlin**, and **YACL** into your `mods/` folder.
3. Drop `iustitia-v0.1.0.jar` into your `mods/` folder.
4. (To detect cheats on 1.8-era servers) Install **ViaFabricPlus** so your 1.21.11 client can join them.
5. Launch. Join any server with other players.

That's it. Alerts appear in chat; other players get a colored tier prefix on their nametag (where the server allows it — see [Nametag prefixes](#nametag-prefixes)).

## Quick start

```
/ius                 # list checks + enabled state (alias of /iustitia)
/ius status          # health panel: master, tracked players, protocol, alerts
/ius help            # in-game command help
/ius help reach      # describe a check + its live config
/ius hist            # session top offenders
/ius hist <name>     # a player's recent flags
/ius alerts          # mute/unmute all chat alerts (detection keeps running)
/ius config          # open the YACL config screen
```

See **[USERMANUAL.md](USERMANUAL.md)** for a non-developer walkthrough.

## Architecture

```
                 ┌─────────────────────────────┐
   server packets │  ClientPlayNetworkHandler   │  (read-only mixin — no send path)
   ──────────────►│  Mixin → PacketSignals       │
                  └──────────────┬──────────────┘
                                 │  swing / hurt / position / metadata
                                 ▼
                      ┌──────────────────────┐
                      │   EntityTrackerManager │  server-space model of every other player
                      │   + ProtocolDetector    │  (1.8-era vs modern combat timing)
                      └──────────┬───────────┘
                                 │  TrackedPlayer (pos, yaw/pitch, deltas, sprint, hurtTick…)
                                 ▼
                      ┌──────────────────────┐   per tick:  ClientTickEvents.END_CLIENT_TICK
                      │   32 Checks (combat +  │   ──► Check.process() / onAttack() / onSwing()
                      │   movement/rotation/   │   ──► vl += level ; decay each clean tick
                      │   packet)              │
                      └──────────┬───────────┘
                                 │  flag() when vl > setbackVL
                                 ▼
                      ┌──────────────────────┐
                      │  AlertManager +       │  ──► chat alert (throttled, join-grace)
                      │  FlagHistory + CheckInfo│ ──► /ius hist, status, nametag tier
                      └──────────────────────┘

                 ┌─────────────────────────────┐
   render thread  │  PlayerEntityRendererMixin  │  (nametag prefix — render-only, no visibility hack)
                  └─────────────────────────────┘
```

### Mixin set (the only bytecode touched)

- `ClientPlayNetworkHandlerMixin` — the read-only packet observer. `defaultRequire: 1` (loud-fail if it ever drifts on a future MC build, because a silent packet miss means silent false negatives).
- `PlayerEntityRendererMixin` — the nametag prefix renderer. Cosmetic, render-only, fail-open (a future-build signature drift would at worst make prefixes silently not appear).
- `EntityRenderStateAccessor` — `@Accessor` for `displayName` on `EntityRenderState`.

No `@Redirect` or `@Overwrite` is used anywhere. No send-path mixins. No camera or local-player mutation.

### Protocol awareness

`ProtocolDetector` distinguishes 1.8-era combat (via ViaFabricPlus) from modern 1.21.11 combat and adjusts the hurt-confirmation lookback (3 ticks on 1.8 vs 2 on modern), so attack-inference timing is correct on both. Checks that depend on server ticking (timer/blinker, teleport) carry a server-lag exemption so a lagging server doesn't manufacture false positives.

## The 32 checks

Each check has its own config slice (`enabled`, `setbackVL`, `decay`, `threshold`) editable live via `/ius` or YACL. Checks marked **definitive** can prove cheating and drive the red nametag tier; the rest are inferential and drive yellow.

### Combat (14)

| id | detects | definitive |
|---|---|:--:|
| `reach` | Hit a victim beyond vanilla melee reach (lag-compensated). | ✓ |
| `multiTarget` | Struck ≥2 distinct victims in one tick (multi-aura). | ✓ |
| `clickStatistics` | Click cadence too uniform / too fast (autoclicker). | |
| `throughWalls` | Attacked a victim with no line-of-sight to the torso. | ✓ |
| `criticals` | Spoofed a grounded crit hop to crit while on the ground. | ✓ |
| `noKnockback` | Took a hit without the expected knockback (anti-KB). | |
| `keepSprint` | Kept sprinting through an attack instead of the legit slowdown. | |
| `wTap` | Reset sprint KB pattern mismatch (W-Tap / SuperKB cheat). | |
| `jumpOnHurt` | Jumped instantly on taking damage (anti-KB hop). | |
| `backtrack` | Hit a victim from a stale (backtracked) position. | |
| `killAura` | Silent-aim / aim-snap suite (7 sub-components, one VL pool). | ✓ |
| `autoBlock` | Swung while a shield was raised (auto-block / block-hit). | ✓ |
| `hitFlick` | Redirected aim off the hitbox at the attack tick (HitFlick). | ✓ |
| `triggerbot` | Auto-attacked the instant the crosshair reached a hitbox (sub-reaction). | |

`killAura` is a port of Rain-Anticheat's 1.8.9 silent-aim suite; `hitFlick` is a Vape/Slinky-style knockback-redirect detector; `triggerbot` is a lax, blatant-only rising-edge reaction-timing detector (deliberately **not** definitive — yellow tier — pending live validation).

### Movement / rotation / packet (18)

| id | detects | definitive |
|---|---|:--:|
| `speedEnvelope` | Moved horizontally faster than the vanilla speed envelope. | |
| `flyEnvelope` | Vertical motion broke vanilla physics (fly / hover / ascend). | ✓ |
| `noFallDamage` | Spoofed on-ground to avoid fall damage. | ✓ |
| `stepHeight` | Stepped up a block higher than the vanilla step height. | |
| `teleport` | Position jumped in a way that isn't a vanilla teleport/pearl. | ✓ |
| `longJump` | Covered too much horizontal distance in one air tick. | ✓ |
| `noSlow` | Moved at full speed while using an item that should slow you. | |
| `backwardSprint` | Sprinted backward (OmniSprint) — blatant-only, KB-exempt. | |
| `waterWalk` | Walked on water (Jesus / water-walk). | ✓ |
| `elytraSpeed` | Elytra glide exceeded the vanilla speed cap. | |
| `rotationTracking` | Aim rotated too uniformly while tracking a target. | |
| `rotationSnapBack` | Aim snapped back after an attack (aim-snap). | |
| `phaseClip` | Moved through a solid block (phase / no-clip). | ✓ |
| `packetGap` | Packet timing gap inconsistent with vanilla ticking (timer/blinker). | |
| `timerRate` | Game-tick rate inconsistent with vanilla (timer cheat). | ✓ |
| `aimWrap` | Aim rotated faster than a legit flick (>threshold°/tick). | |
| `pitchBound` | Pitch outside the vanilla [-90, 90] bound. | |
| `scaffoldRotation` | Scaffold placement rotation inconsistent with legit bridging. | ✓ |

## Alerts

The fixed alert layout:

```
§8[§diustitia§8] §f(Name) §<sev>(Check) §<sev>(VL)
```

Severity color scales with the violation ratio `vl / setbackVL`: **<2× yellow** (`§e`), **<3× orange** (`§6`), **≥3× red** (`§c`). The trailing number is the violation count (ceiling of VL).

The line is self-documenting and interactive (both are local chat-component events — no packet is sent):
- **Hover** → the check's one-line description, this player's session alert count, and the severity legend.
- **Click** → runs `/ius hist <name>` to open that player's flag history.

Alerts are throttled per (player, check) and suppressed during a join-grace window (default 30s) so a player who just rendered in doesn't burst-fire.

## Nametag prefixes

Other players get a tier prefix drawn on their nametag (vanilla visibility is respected — no wallhack; the prefix only appears when vanilla would show the nametag):

| prefix | tier | meaning |
|---|---|---|
| `§a[+]§r` | green | no chat alerts this session (clean / low-flag) |
| `§e[!]§r` | yellow | ≥1 alert but no definitive check has fired (suspect) |
| `§c[X]§r` | red | a definitive check has proven cheating (sticky for the session) |

The prefix is written at the HEAD of `PlayerEntityRenderer.renderLabelIfPresent` (the draw method), so it survives label batching.

**Server coverage caveat:** the prefix only appears on servers that populate the vanilla nametag field (`displayName`). This works on most servers, including **minemen.club** and 1.8-era servers. Some servers suppress the vanilla nametag and render their own server-side name hologram instead — on those, Iustitia has no `displayName` to attach to and the prefix will not appear. This is by design (the alternative would be a wallhack-style visibility hack, which Iustitia refuses to do). Confirmed-affected: **stray.gg** and **mcpvp.club** (the latter shows a black-background label that is actually the server's BELOW_NAME health indicator, not the vanilla name).

## Configuration

- **In-game:** `/ius config` opens a YACL screen with a toggle per check plus the global switches (master, chat alerts, nametag prefixes, green-tick display).
- **Commands:** `/ius toggle <check>`, `/ius threshold <check> <value>`, `/ius alerts <check|name> [on|off]`, `/ius verbose`, `/ius reload`, `/ius reset`.
- **On disk:** `config/iustitia.json` (hand-rolled JSON via Gson; no extra serialization dependency). Edited live values are saved automatically.

Each check's `threshold` is check-specific (Reach→max reach, MultiTarget→min victims, ClickStatistics→CPS cap, SpeedEnvelope→bps cap, Triggerbot→min fast-hits, etc.). `/ius help <check>` prints the live config + description for any check.

## Building from source

```bash
git clone https://github.com/ThoriaDevelopment/Iustitia.git
cd Iustitia
./gradlew build
```

The built mod jar is at `build/libs/iustitia-<version>.jar`. A sources jar is also produced. Requires JDK 21 and internet access on first build (Loom downloads Minecraft + mappings).

```bash
./gradlew runClient   # launch a dev client with Iustitia loaded
```

## Privacy

Iustitia is **purely client-sided**. It reads incoming server packets that your client already receives, runs detection locally, and writes to your local chat and your local config file. It does **not** transmit, upload, or report anything to any server, endpoint, or third party. There is no telemetry, no analytics, no network code beyond reading what the server sends you. Muting, tiering, and history are all in-memory and cleared on restart (or `/ius reset`).

## Known limitations

- **False positives are possible.** Iustitia infers cheating from rebroadcast state, which is lossy. Every borderline (yellow) check is a heuristic. Treat yellow as "worth watching," not "definitely cheating." Red (definitive) checks are tuned to be high-confidence but should still be corroborated via `/ius hist` before acting.
- **Server-side nametags** (stray.gg, mcpvp.club, and any server that hides the vanilla nametag) will not show the prefix — see [Nametag prefixes](#nametag-prefixes).
- **Chunk-unloaded players** are false negatives (we can't observe what the server stops telling us), never false positives.
- **It does not stop cheaters.** It only tells you who looks like one. There is no enforcement.

## License

MIT — see [LICENSE](LICENSE).