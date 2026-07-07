package dev.iustitia.info

/**
 * Human-readable one-line descriptions for every check id, used to make Iustitia self-documenting:
 * the alert hover tooltip shows the description of the check that fired, and `/ius help <check>`
 * prints it alongside the live config. Keeps users out of the docs — the alert explains itself.
 *
 * Also exposes the severity legend string and [isDefinitive] (delegates to the canonical set in
 * [dev.iustitia.history.FlagHistory]) so the nametag tier logic and help share one source of truth.
 */
object CheckInfo {

    /** One-line description for a check id, or a generic fallback. */
    fun describe(checkId: String): String = DESCRIPTIONS[checkId]
        ?: "Iustitia check '$checkId'."

    /** Whether a check is primary red-capable: its alert can self-standing mark a player YELLOW
     *  (and contributes toward RED, which needs ≥2 distinct red-capable checks — see
     *  [dev.iustitia.history.FlagHistory.tierFor]). killAura is a corroborator, not primary. */
    fun isDefinitive(checkId: String): Boolean = dev.iustitia.history.FlagHistory.DEFINITIVE.contains(checkId)

    /** Severity color legend shown in alert hover + `/ius help` + `/ius status`. */
    val SEVERITY_LEGEND: String = "§7sev: §e<2× §6<3× §c≥3× §7setbackVL"

    private val DESCRIPTIONS: Map<String, String> = mapOf(
        "reach" to "Hit a victim beyond vanilla melee reach (lag-compensated).",
        "multiTarget" to "Struck ≥2 distinct victims in one tick (multi-aura).",
        "clickStatistics" to "Click cadence too uniform / too fast (autoclicker).",
        "throughWalls" to "Attacked a victim with no line-of-sight to their torso.",
        "criticals" to "Spoofed a grounded crit hop to score a crit while on the ground.",
        "maceSmash" to "Faked a fall-height Y warp around a mace smash-attack (MaceKill).",
        "noKnockback" to "Took a hit without the expected knockback (anti-KB).",
        "keepSprint" to "Kept sprinting through an attack instead of the legit slowdown.",
        "wTap" to "Reset sprint KB pattern mismatch (W-Tap / SuperKB cheat).",
        "jumpOnHurt" to "Jumped instantly on taking damage (anti-KB hop).",
        "backtrack" to "Hit a victim from a stale (backtracked) position.",
        "killAura" to "Silent-aim / aim-snap suite: attacked without facing the victim.",
        "autoBlock" to "Swung while a shield was raised (auto-block / block-hit).",
        "hitFlick" to "Redirected aim off the hitbox at the attack tick (HitFlick).",
        "triggerbot" to "Auto-attacked the instant the crosshair reached a hitbox (sub-reaction).",
        "hitsWithoutSwing" to "Dealt damage with no swing animation in the window (no-swing attack).",
        "speedEnvelope" to "Moved horizontally faster than the vanilla speed envelope.",
        "flyEnvelope" to "Vertical motion broke vanilla physics (fly / hover / ascend).",
        "spider" to "Climbed a wall without a ladder/vine (spider / constant-climb).",
        "noFallDamage" to "Spoofed on-ground to avoid fall damage.",
        "stepHeight" to "Stepped up a block higher than the vanilla step height.",
        "teleport" to "Position jumped in a way that isn't a vanilla teleport/pearl.",
        "longJump" to "Covered too much horizontal distance in one air tick.",
        "noSlow" to "Moved at full speed while using an item that should slow you.",
        "backwardSprint" to "Sprinted backward (impossible in vanilla).",
        "wallSprint" to "Held sprint metadata while pressed against a wall (OmniSprint).",
        "sprintHack" to "Sprinted in water / while sneaking / while blind (all vanilla-blocked).",
        "waterWalk" to "Walked on water (Jesus / water-walk).",
        "elytraSpeed" to "Elytra glide exceeded the vanilla speed cap.",
        "rotationTracking" to "Aim rotated too uniformly while tracking a target.",
        "rotationSnapBack" to "Aim snapped back after an attack (aim-snap).",
        "phaseClip" to "Moved through a solid block (phase / no-clip).",
        "packetGap" to "Packet timing gap inconsistent with vanilla ticking (timer/blinker).",
        "aimWrap" to "Aim rotated faster than a legit flick (>threshold°/tick).",
        "pitchBound" to "Pitch outside the vanilla [-90, 90] bound.",
        "scaffoldRotation" to "Scaffold placement rotation inconsistent with legit bridging.",
    )
}