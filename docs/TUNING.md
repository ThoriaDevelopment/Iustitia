# Iustitia — Tuning & Calibration Worksheet (§8 step 14)

This is the calibration record for the 36-check suite. It has two halves:

1. **The empirical pass** — *point a reference cheat client at a test dummy and
   confirm each check fires; point legit gameplay at the same dummy and confirm no
   flags.* This half is **manual**: it needs a live Minecraft 1.21.11 client + a
   Fabric test server + a second account (the dummy) + the reference cheat clients.
   It cannot be run from the source tree alone. §2 below is the procedure.
2. **The reference half** (this document) — the consolidated per-check defaults,
   version-gates, tier metadata, and every constant explicitly flagged
   `Tuned in step 14` in the source, with its current value and a nudge direction.
   This is the worksheet the empirical pass fills in; it is derived from
   `IustitiaConfig.kt`, `FlagHistory.kt`, `checks.json`, and the check sources, so it
   stays in sync with the code.

> **Status:** the empirical pass is **not yet run**. Every `Tuned in step 14`
> constant below is an **initial value** reasoned from the reference cheat's
> published signature, not a value validated against a live cheat. Treat the
> defaults as a defensible starting point; the empirical pass is what makes them
> trustworthy. Until then, prefer the conservative (lower-VL / higher-threshold)
> side where a constant trades detection rate for FP rate.

---

## 1. Reference cheat clients per category

The suite was ported/researched against these clients; point the matching one at the
dummy to validate each check.

| Category | Reference cheat (module → check) |
|---|---|
| Reach / aura | LiquidBounce `Reach`, Vape `Reach` → `reach`; LB `KillAura`/`Aimbot`, Vape `Silent Aim`, Slinky aim → `killAura` (silent(snap/return/track), `drift`, `heuristic(gcd)`) |
| Crit / mace | LB `Criticals` (Packet/UpdatedNCP/OldNCP), Vape `Crits` → `criticals` (MicroY/Timing); LB `ModuleMaceKill` → `maceSmash` |
| KB | LB `AntiKB`/`Velocity`, Vape `AntiKB`, Slinky `KnockbackDisplace` → `noKnockback` (VelocityB/Vector); Vape `HitFlick` → `hitFlick`, `noKnockback(Vector)` |
| Click | LB `AutoClicker` (record/replay), Vape `AutoClicker`, LionClient `ClickPatternStore` → `clickStatistics` (Record-loop) |
| Move | LB `Speed`/`Fly`/`Spider`/`NoFall`/`NoSlow`/`AutoWalk`, Meteor `Flight`/`Speed`/`Scaffold`/`Tower`/`BridgeAuto`/`NoFall`, Raven `rps`/`tower`, Slinky `Movement Fix`/`Clutch` → the movement + rotation checks |
| Packet | LB `Blink`/`FakeLag` (Dynamic), Grim `FakeLag` → `packetGap` (Blink/LagCorr) |
| Scaffold | LB `Scaffold`/`AutoBridge`, Raven scaffold, Slinky `Clutch` snap-back → `scaffoldRotation` (Clutch) |

Grim is a **server-side** reference, not a cheat to point — its thresholds are the
portable kernels cited in each check's KDoc (`SprintE/F`, `MultiActionsE/A`,
`Hitboxes`, `Phase`, etc.).

---

## 2. Empirical-pass procedure

**Setup:** local Fabric server (1.21.11) + two accounts — *attacker* (you, running
the cheat) and *dummy* (a still/sandbox account). Run Iustitia on a **third** observer
account, or on the dummy's client (Iustitia observes *other* players, so install it on
the client that can see the attacker).

**For each check — cheat side (must flag):**
1. Enable only the cheat module under test (disable confounders — no Simultaneous
   `Sprint`+`Speed`, no `Criticals` while testing `reach`).
2. Reproduce the cheat's signature motion against the dummy for ~30–60 s.
3. Confirm via `/ius evidence <attacker>` (the flagged label + measurement) and
   `/ius hist <attacker>` (the VL climb). The check's label must appear; VL must
   cross `setbackVL` within the expected hit/episode count (see §4 nudge notes).

**For each check — legit side (must stay silent):**
1. Play the matching legit activity for ~3–5 min: PvP dueling (reach/killaura/crit/KB),
   parkour + bridge-building (scaffold/fly/step/longJump), eating while fighting
   (consume/autoBlock), ice/boat/slime (speed/fly envelopes), KitPvP arena drops
   (noFall).
2. Confirm **no** alert fires for that check. If one does, record the label + the
   `/ius evidence` measurement — that's a FP to tune out (raise threshold / tighten
   an exemption / lower VL / increase decay).

**Targets:** every DEFINITE check flags on its cheat and stays silent on legit; every
CORROBORATOR flags on its cheat (it corroborating is acceptable even if standalone
noisy, but it must not FP on the legit side); every NEUTRAL check flags on its cheat
but **must not** mark a legit player suspect (its alerts are tier-neutral by design).

**Fill §6** with the result for each check before promoting a constant off "initial".

---

## 3. Consolidated per-check defaults

`CheckConfig(enabled, setbackVL, decay, threshold)` from `IustitiaConfig.kt`. Tier
from `FlagHistory` (D=DEFINITIVE primary, C=CORROBORATOR, ·=NEUTRAL). Version-gate
from the check source (`ProtocolDetector` / `MaceItem`).

### Combat (16)

| id | tier | enabled | setbackVL | decay | threshold | version-gate |
|---|:-:|:-:|---:|---:|---:|---|
| `reach` | D | ✓ | 10.0 | 0.25 | 3.0 (maxReach) | `is1_8OrLess` → +0.1 margin |
| `multiTarget` | D | ✓ | 2.0 | 1.0 | 2.0 (min victims) | — |
| `clickStatistics` | · | ✓ | 5.0 | 0.05 | 20.0 (cps cap) | — |
| `throughWalls` | D | ✓ | 8.0 | 0.5 | 1.0 (reserved) | — |
| `criticals` | D | ✓ | 5.0 | 0.1 | 0.05 (MicroY floor) | `MaceItem` mace-exempt (auto-fail-open pre-1.21) |
| `noKnockback` | · | ✓ | 5.0 | 1.0 | 0.61 (VelocityC ratio) | — |
| `keepSprint` | · | ✓ | 5.0 | 0.5 | 0.8 (retained-speed ratio) | — |
| `wTap` | · | ✓ | 5.0 | 0.5 | 2.0 | — |
| `jumpOnHurt` | · | ✓ | 5.0 | 0.2 | 0.3 (min Δy ±1t of hit) | — |
| `backtrack` | · | ✓ | 10.0 | 0.25 | 3.0 (stale-pos blocks) | — |
| `killAura` | C | ✓ | 5.0 | 0.10 | 0.0 (unused) | `heuristic(gcd)` needs `fullFloatLook && sensitivity.valid` (1.21.2+); `drift` portable to 1.8 |
| `autoBlock` | D | ✓ | 5.0 | 0.5 | 10.0 (overlap ticks) | `is1_8OrLess` → +30 tick legacy bonus (block path) |
| `hitFlick` | D | ✓ | 5.0 | 0.5 | 30.0 (min ° off hitbox) | — |
| `triggerbot` | · | ✓ | 5.0 | 0.05 | 4.0 (fast-hit count) | `is1_8OrLess` → +0.1 margin |
| `maceSmash` | D | ✓ | 5.0 | 0.05 | 1.5 (\|Δy\| floor) | `is1_8OrLess` skip + `MaceItem` held-mace (1.21+) |
| `hitsWithoutSwing` | C | ✓ | 5.0 | 0.5 | 3.0 (no-swing hurts/episode) | — |

### Movement (14)

| id | tier | enabled | setbackVL | decay | threshold | version-gate |
|---|:-:|:-:|---:|---:|---:|---|
| `speedEnvelope` | · | ✓ | 5.0 | 1.0 | 10.0 (bps cap) | — |
| `flyEnvelope` | D | ✓ | 5.0 | 0.5 | 0.1 (Δy tol) | — |
| `spider` | D | ✓ | 5.0 | 0.5 | 10.0 (ascend ticks) | — |
| `noFallDamage` | · | ✓ | 4.0 | 1.0 | 8.0 (fallAccum) | — (demoted from D: arena-drop FP) |
| `stepHeight` | · | ✓ | 5.0 | 0.5 | 0.6 (step) | `is1_8OrLess` → cap threshold at 0.5 |
| `teleport` | D | ✓ | 5.0 | 0.5 | 1.5 (jump blocks) | — |
| `longJump` | D | ✓ | 5.0 | 0.5 | 0.6 (air-tick distance) | — |
| `noSlow` | · | ✓ | 5.0 | 0.5 | 4.0 (use-slowdown ticks) | — |
| `backwardSprint` | D | ✓ | 5.0 | 0.5 | 1.0 (ticks) | — |
| `wallSprint` | D | ✓ | 5.0 | 0.5 | 5.0 (sustain ticks) | — |
| `sprintHack` | D | ✓ | 5.0 | 0.5 | 3.0 (sustain ticks) | blind sub-flag needs broadcast `EntityStatusEffect` (observer-feasible 1.21) |
| `waterWalk` | D | ✓ | 5.0 | 0.5 | 1.0 (ticks) | — |
| `elytraSpeed` | · | ✓ | 5.0 | 1.0 | 40.0 (bps cap) | `ElytraSpeed(Sprint)` fold — elytra cancels sprint across versions |

### Rotation / Packet (6)

| id | tier | enabled | setbackVL | decay | threshold | version-gate |
|---|:-:|:-:|---:|---:|---:|---|
| `rotationTracking` | · | ✓ | 5.0 | 0.10 | 0.92 (match-rate) | `gcd` sub-flag needs `fullFloatLook && sensitivity.valid` (1.21.2+) |
| `rotationSnapBack` | · | ✓ | 5.0 | 0.5 | 30.0 (snap °) | — |
| `phaseClip` | D | ✓ | 5.0 | 0.5 | 1.0 (samples) | — |
| `packetGap` | · | ✓ | 5.0 | 0.5 | 2.0 (gap ticks) | — |
| `aimWrap` | · | ✓ | 5.0 | 0.5 | 165.0 (°/tick) | — |
| `pitchBound` | D | ✓ | 5.0 | 0.0 (instant) | 90.0 (°) | — |
| `scaffoldRotation` | D | ✓ | 5.0 | 0.05 | 78.0 (pitch lock) | — |

Totals: 16 DEFINITIVE + 2 CORROBORATOR = 18 red-capable; 18 NEUTRAL. `CONFIG_VERSION = 4`.

---

## 4. Priority calibration targets — constants flagged `Tuned in step 14`

These are the **initial values** to validate in the empirical pass. Grouped by check;
each row: constant · current value · meaning · nudge (raise → effect / lower → effect).

### Combat

**`reach`** (`ReachCheck.kt`)
- `VL_HITBOX_MISS` = 2.0 — hitbox-miss sub-flag level. Raise → fewer hits to alert (stricter silent-aim); lower → more lenient.
- `LAG_CORR_WINDOW` = 8 ticks — Lag-Range amplifier window. Widen → more lag-cheats amplified; narrow → fewer FPs on legit lag.
- `VL_LAG_CORR` = 1.0 — amplifier "weight up". Raise → lag-range hits alert faster.

**`backtrack`** (`BacktrackCheck.kt`)
- `LAG_CORR_WINDOW` = 8, `MIN_LAG_FREEZE` = 2, `VL_LAG_CORR` = 1.0 — same amplifier family. Backtrack is a low-VL corroborator; keep `VL_LAG_CORR` ≤ 1.0.

**`clickStatistics`** (`ClickStatisticsCheck.kt`)
- `RECORD_MIN_CYCLES` = 5 — min exact period repetitions for the Record-loop fingerprint. Raise → stricter (fewer legit replay FPs); a hand never sustains ≥5 exact, so 5 is the floor.

**`criticals`** (`CriticalsCheck.kt`)
- `LAG_CORR_WINDOW` = 4 (tighter than reach/backtrack — crit timing is tight), `VL_LAG_CORR` = 1.0.
- `VL_MICRO_Y` = 1.0 — Micro-Y sub-flag level (spoofed-airborne bit; high-confidence).
- `MIN_PHASE` = 8 — min fall-attacks for the fixed-phase cluster (Crits(Timing)). A hand never sustains ≥8 attacks at near-identical fall-arc phase; 8 is the floor.

**`noKnockback`** (`NoKnockbackCheck.kt`)
- `LAG_CORR_WINDOW` = 6, `VL_LAG_CORR` = 1.0 — KnockbackDelay self-blink amplifier.
- `VELOCITYB_RATIO` = 0.995 — VelocityB upward-KB ratio floor. Lower (e.g. 0.99) → more lenient (tolerates interpolation noise); raise → stricter.
- `JUMP_LO`/`JUMP_HI` = 0.38 / 0.46 — vanilla 0.42-jump exempt band. Widen → more legit jumps exempt; narrow → moreVelocityB sensitivity.
- `VECTOR_MISMATCH` = 35.0° — KB-vector vs attacker-yaw mismatch flag point. Lower → stricter redirect detection (more FPs on legit curved KB); raise → looser.
- `VL_VELOCITYB` = 1.0, `VL_VECTOR` = 1.0 — sub-flag levels.

**`killAura`** (`KillAuraCheck.kt`)
- `VL_GCD` = 1.5 — constant-rotation GCD sub-flag level (1.21.2+ only).
- `VL_DRIFT` = 1.5 — Axis C combat-ward drift sub-flag level. Raise → a drift episode alerts faster; the 70% ratio gate is the real FP control, so VL is secondary.
- `DRIFT_RATIO` = 0.70 / `DRIFT_RESET_RATIO` = 0.50 / `DRIFT_WINDOW_TICKS` = 50 / `DRIFT_MIN_SAMPLES` = 15 — drift sign-match gate. Lower `DRIFT_RATIO` (e.g. 0.65) → stricter (catches weaker aim-assist, more legit-tracker FP risk); raise → looser. The hysteresis (`RESET` 0.50) makes the flag one-shot per episode.

**`autoBlock`** (`AutoBlockCheck.kt`)
- `VL_ATTACK_USE` = 1.0 — AutoBlock(AttackUse) level per attack-while-using event. One event is the cheat; keep at 1.0 (3 attacks clear setbackVL 5 with decay 0.5).
- (block + SwingUse paths use `cfg.threshold` = 10.0 + the 1.8 +30 legacy bonus.)

**`maceSmash`** (`MaceSmashCheck.kt`)
- `VL_MACE` = 1.5 — per-attack Y-warp level. decay 0.05 (slow) — a blatant every-hit MaceKill (~12–20t apart) nets `+1.5 − 0.05·interval` per flag → alerts in ~6–15 smashes; a single rubberband never alerts.

**`hitsWithoutSwing`** (`HitsWithoutSwingCheck.kt`)
- threshold = 3.0 (no-swing hurts per attacker in `EPISODE=60` ticks) — transition-gated. Raise → fewer disabler FPs; lower → catches more but noisier (CORROBORATOR, so noise is tolerable).

### Movement

**`flyEnvelope`** (`FlyEnvelopeCheck.kt`) — all sub-flags are "initial values"
- `FLYB_TOL` = 0.05 — Fly(FlyB) tolerance above gravity-predicted Δy. Nemesis server-side 0.005 (ground truth); 0.05 client-side (no ground truth). Lower → stricter hover/float; raise → more lag/interpolation tolerance.
- `FLYB_SUSTAIN` = 4 — Fly(FlyB) sustained-breach ticks. Raise → fewer lag-catch-up FPs; lower → faster flag.
- AntiKick band/cadence: `ANTIKICK_BAND=0.05`, `ANTIKICK_MIN_DURATION=40`, `ANTIKICK_BLIP_MIN/MAX=-0.04/-0.02`, `ANTIKIP_PERIOD_MIN/MAX=15/25`, `ANTIKICK_CYCLES=2`, `ANTIKICK_DESCEND=0.05` — Meteor AntiKick signature. The `~20t` dip cadence is the discriminator; if a legit hover/viola player FPs, raise `ANTIKICK_CYCLES` or widen the period band.
- StrafeHop: `STRAFE_HOP_Y=0.40123128`, `STRAFE_HOP_BAND=0.006`, `STRAFE_HOP_WINDOW=12` — Meteor Speed strafe-hop Y impulse. The band excludes vanilla 0.42 (Δ=0.019) and JB 0.62+; do not widen past 0.019 or vanilla jumps enter.

**`speedEnvelope`** (`SpeedEnvelopeCheck.kt`)
- `MIN_MOVE` = 0.2 — min offsetH (blocks/tick) to apply the momentum model (~4 bps floor). Below this the model is noise (Nemesis `offsetH>0.2` guard).
- Grim offset-accumulation: `IMMEDIATE_OFFSET=0.1`, `ADVANTAGE_FLAG=1.0`. The existing 3-of-6 bps `Speed` blatancy flag (>10 bps) is retained as a floor.
- Friction/slip: `GROUND_SLIP=0.6`, `ICE_SLIP=0.98`, `SLIME_SLIP=0.8` — vanilla values, do not tune.

**`spider`** (`SpiderCheck.kt`)
- threshold = 10.0 — Spider sub-flag ascend ticks (ConstantClimb uses fixed bands `CLIMB_MIN_DY=0.1`/`CLIMB_MAX_DY=0.6`/`CLIMB_BAND=0.08`/`CLIMB_WINDOW=8`). Raise → fewer bubble-column/levitation FPs; lower → faster flag.

**`wallSprint`** (`WallSprintCheck.kt`)
- threshold = 5.0 — sprint-into-wall sustain ticks. `FORWARD_MAX=0.05` (forward speed gate distinguishing pressed-against-wall from running-at-wall-to-jump). Raise threshold → fewer sprint-jump-at-wall FPs.

**`sprintHack`** (`SprintHackCheck.kt`)
- threshold = 3.0 — sustained sprint-while-water/sneak/blind ticks. The sustain gate absorbs metadata-lag edges; 3 is the floor (water/sneak/blind sprint is vanilla-impossible, so a clean 3-tick run is already the cheat).

### Rotation / Packet

**`packetGap`** (`PacketGapCheck.kt`)
- `LAG_CORR_WINDOW` = 4 (tight — FakeLag-Dynamic flush is ~80–150ms ≈ 4–7 ticks), `VL_LAG_CORR` = 1.0 — Blink(LagCorr) amplifier.

**`scaffoldRotation`** (`ScaffoldRotationCheck.kt`)
- `CLUTCH_SNAP` = 50.0° — min single-tick yaw excursion to start a clutch round-trip. Lower → catches smaller snaps (more FPs on legit bridging re-centres); 50° is "a meaningful rotation to a place target".
- `CLUTCH_RETURN` = 3.0° — the snap-back must return yaw to within this of pre-excursion heading. **The return-identity tell.** A hand returns a ≥50° excursion to ~5–10°, not <3°. Raise to ~5° only if legit clutch FPs appear; the <3° vs ~5–10° gap is the discriminator, so do not raise past 5°.

> **Note on the `VL_LAG_CORR` family:** reach/backtrack/criticals/noKnockback/packetGap
> all share the Axis B lag-correlation amplifier at level 1.0 and window 4–8 ticks.
> These are deliberately uniform. Tune them **together** (same level/window) unless a
> specific check's FP profile demands otherwise — the amplifier is one mechanism.

---

## 5. Cross-cutting calibration levers (not per-check)

- **`alertThrottleTicks`** (40) — min ticks between repeated same-(player,check) alerts. Raise to quiet spam; lower to surface repeated flags.
- **`joinGraceTicks`** (600 = 30s) — no alerts fire for a freshly-joined player. Raise if join-relocation FPs appear; lower to flag faster after join.
- **`alertLevel`** (1 = normal) — 0 quiet (red only), 2 verbose (all yellow-band). Display-only.
- **`alertBatching`** + `alertBatchWindowTicks` (100 = 5s) — collapse rapid flags into one line. Display-only.
- **`lagSuppressAlerts`** (true) — prefix `[lag]` + drop non-red during a server-lag burst. Display-only.
- **`CONFIG_VERSION`** (4) — **bump on every recalibration round** so `ConfigManager` resets stale persisted calibration fields to the new code defaults (user prefs preserved). See §7.

The standard **lag gate** (8-tick `lastServerLagTick` window / 3-tick `lastLagBurstTick`
window) is shared across the movement + GCD + drift checks (§8 step 0 posture). It is
not per-check tunable; if lag FPs appear globally, the gate is the single lever.

---

## 6. Empirical sign-off checklist

Fill per check after the §2 procedure. `Cheat fires?` and `Legit silent?` must both be
✓ before the constant is promoted off "initial".

| id | cheat module tested | Cheat fires? (label + VL→setbackVL in N hits) | Legit silent? (activity + 3–5min result) | tuned-constant outcome (keep / nudged-to) |
|---|---|---|---|---|
| `reach` | | | | |
| `multiTarget` | | | | |
| `clickStatistics` | | | | |
| `throughWalls` | | | | |
| `criticals` | | | | |
| `noKnockback` | | | | |
| `keepSprint` | | | | |
| `wTap` | | | | |
| `jumpOnHurt` | | | | |
| `backtrack` | | | | |
| `killAura` | | | | |
| `autoBlock` | | | | |
| `hitFlick` | | | | |
| `triggerbot` | | | | |
| `maceSmash` | | | | |
| `hitsWithoutSwing` | | | | |
| `speedEnvelope` | | | | |
| `flyEnvelope` | | | | |
| `spider` | | | | |
| `noFallDamage` | | | | |
| `stepHeight` | | | | |
| `teleport` | | | | |
| `longJump` | | | | |
| `noSlow` | | | | |
| `backwardSprint` | | | | |
| `wallSprint` | | | | |
| `sprintHack` | | | | |
| `waterWalk` | | | | |
| `elytraSpeed` | | | | |
| `rotationTracking` | | | | |
| `rotationSnapBack` | | | | |
| `phaseClip` | | | | |
| `packetGap` | | | | |
| `aimWrap` | | | | |
| `pitchBound` | | | | |
| `scaffoldRotation` | | | | |

---

## 7. Applying a recalibration

When the empirical pass moves a constant or default:

1. **Source** — edit the constant in the check's `companion object`, or the
   `CheckConfig(enabled, setbackVL, decay, threshold)` default in `IustitiaConfig.kt`.
2. **`CONFIG_VERSION`** — bump in `IustitiaConfig.kt` if a *default*
   setbackVL/decay/threshold changed (so old persisted configs reset to the new
   defaults; user prefs are preserved). Constant-only changes (internal thresholds not
   exposed as `cfg.threshold`) do **not** need a bump. Document the round in the
   `configVersion` KDoc.
3. **`checks.json`** — update the matching `defaults` and/or `constants` entry so the
   docs stay in sync (gen_docs.py renders them).
4. **Regen docs** — `python scripts/gen_docs.py`.
5. **Build** — `.\gradlew.bat compileKotlin` clean; `Iustitia.verifyCheckRegistry`
   logs no drift at startup.
6. **Re-run the §2 cheat + legit passes** for the touched check to confirm the new
   value still fires-on-cheat / silent-on-legit.

A constant is "promoted off initial" once its row in §6 is fully signed off.