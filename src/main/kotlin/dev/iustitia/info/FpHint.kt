package dev.iustitia.info

/**
 * One-line benign-cause hints per check id, shown in the alert hover tooltip (Phase 2 #18 text
 * version) so users learn when a flag is likely a false positive and not a hackusation target:
 * "lag can inflate distance", "ice/boat/slime bounce", "Jump Boost raises step", etc.
 *
 * This is the textual version of the false-positive-reminder feature; the standalone HUD note near
 * alerts is a deferred Phase B render piece. Display-only — changes no check logic.
 */
object FpHint {

    fun hint(checkId: String): String? = HINTS[checkId]

    private val HINTS: Map<String, String> = mapOf(
        "reach" to "lag can inflate distance; check the server-lag indicator before calling reach.",
        "multiTarget" to "multi-mob farms / cleave can hit several targets in one tick legitly.",
        "clickStatistics" to "drag-click mice + jitter-bursts can look like an autoclicker.",
        "throughWalls" to "partial occlusion / corner peeks can block all 3 sample rays on a legit hit.",
        "criticals" to "slime/fence-edge bob while attacking can look like a grounded crit hop.",
        "maceSmash" to "slime-bounce / wind-charge launches / lag-rubberband can produce a Y spike near an attack.",
        "noKnockback" to "Velocity/sprint-reset knockback reduction is client-side noisy; intermittent is normal.",
        "keepSprint" to "sprint-reset (W-tap) legit PvP deliberately resets the slowdown.",
        "wTap" to "legit sprint-reset PvP mimics the W-tap pattern.",
        "jumpOnHurt" to "jumping on damage is common in parkour/bridge PvP.",
        "backtrack" to "high-latency legit players hit from a slightly stale position.",
        "killAura" to "fast legit flicks + high CPS can trip the silent-aim suite standalone.",
        "autoBlock" to "block-hit + sprint-jitter can overlap swing/use on legit players.",
        "hitFlick" to "fast aim re-centring after a hit can flick off the hitbox.",
        "triggerbot" to "low-latency + high-CPS players can hit at the sub-reaction threshold.",
        "hitsWithoutSwing" to "an out-of-view / untracked attacker or packet ordering can drop the swing — weak signal, corroborator-only.",
        "speedEnvelope" to "Speed pots, dash, ice, riptide, dolphin, vehicle all raise the speed cap.",
        "flyEnvelope" to "ice/boat/slime bounce, levitation, slow-fall, wind-charges look like vertical breaks.",
        "spider" to "bubble columns / levitation near a wall / lag-rubberband can momentarily ascend against a wall.",
        "noFallDamage" to "arena-drop / water / ladder / chunk-load glitches all avoid fall damage legitly.",
        "stepHeight" to "Jump Boost raises the vanilla step ceiling — a JB II step is normal.",
        "teleport" to "pearls, chorus fruit, end-teleport, server teleports, lag-rubberband all jump position.",
        "longJump" to "Jump Boost + sprint-jump + momentum covers extra distance; ice/packed-ice more.",
        "noSlow" to "some server versions don't apply use-slowdown consistently.",
        "backwardSprint" to "some minigame servers permit backward sprint via plugins.",
        "wallSprint" to "a sprint-jump at a wall can briefly hold sprint metadata against it before vanilla cancels.",
        "sprintHack" to "metadata-lag edges can briefly co-set sprint with sneak/water; the sustain gate absorbs these.",
        "waterWalk" to "frost walker / boat dismount / lag-rubberband can momentarily walk on water.",
        "elytraSpeed" to "firework boosts + trident riptide glide exceed the baseline cap legitly.",
        "rotationTracking" to "intense PvP tracking can look uniform; tier-neutral (won't yellow on its own).",
        "rotationSnapBack" to "fast re-centring after a hit snaps aim back.",
        "phaseClip" to "lag-rubberband / chunk-load can momentarily place a player inside a block.",
        "packetGap" to "lag bursts / blink-style server hiccups gap the packet stream.",
        "aimWrap" to "a fast legit flick can exceed the per-tick yaw threshold.",
        "pitchBound" to "rare server mods permit extended pitch; usually a blatant client though.",
        "scaffoldRotation" to "legit bridging on some servers has looser rotation constraints.",
    )
}