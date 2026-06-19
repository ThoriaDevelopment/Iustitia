package dev.iustitia.config

/**
 * On-disk configuration for Iustitia. Persisted as `config/iustitia.json` (see
 * [ConfigManager]) and editable live via YACL + `/iustitia`. Serialized by hand
 * through Gson's JsonObject (see [ConfigManager]) so we avoid pulling in the
 * kotlinx.serialization compiler plugin and its Kotlin-constructor pitfalls.
 *
 * Each check owns a [CheckConfig] slice: enabled, setbackVL (alert threshold),
 * decay (per-tick VL reduction), and a generic [threshold] whose meaning is
 * check-specific (Reach→maxReach, MultiTarget→min victims, ClickStatistics→cps cap,
 * SpeedEnvelope→bps cap, FlyEnvelope→Δy tolerance, NoFall→fallAccum limit, …).
 *
 * CheckConfig(enabled, setbackVL, decay, threshold).
 */
data class IustitiaConfig(
    var enabled: Boolean = true,
    var verbose: Boolean = false,
    /** Min ticks between repeated alerts for the same (player, check). */
    var alertThrottleTicks: Int = 40,
    /** Ticks after a player joins before any alert fires (30s default). */
    var joinGraceTicks: Int = 600,
    /** Optional strict gates for ScaffoldRotation's LegitScaffold path (motion + cadence +
     *  5-short-crouch). Default off — the baseline Rain path is the true-positive detector;
     *  flip on only to A/B-test a hypothetical stationary-builder FP once one is observed. */
    var legitScaffoldStrictGates: Boolean = false,

    // --- usability / display ---
    /** Global chat-alerts switch (toggled by bare `/ius alerts`). When false, NO chat alert lines
     *  are shown, but detection + [FlagHistory] tiering/history + the nametag prefix keep running —
     *  this is a "silence the chat noise" mute, not a "stop detecting" switch. To stop detection
     *  entirely, flip [enabled] off (via `/ius config`). */
    var alertsEnabled: Boolean = true,
    /** Master switch for the nametag cheat-tier prefix (green [+] / yellow [!] / red [X]). */
    var nametagPrefixes: Boolean = true,
    /** Show the green [+] on clean/low-flag players. Flip off to only mark yellow/red when a
     *  lobby full of green ticks feels noisy. No effect on yellow/red. */
    var nametagGreenEnabled: Boolean = true,
    /** Check ids whose chat alerts are muted (`/ius alerts <check>`). Detection, history and
     *  the nametag tier keep running — only the chat line is suppressed. */
    var mutedChecks: MutableList<String> = mutableListOf(),
    /** Player uuids (as strings) whose chat alerts are muted (`/ius alerts <name>`). Muting by
     *  uuid survives rejoin. As with mutedChecks, only chat is silenced. */
    var mutedPlayers: MutableList<String> = mutableListOf(),

    // --- combat ---
    var reach: CheckConfig = CheckConfig(true, 10.0, 0.25, 3.0),
    var multiTarget: CheckConfig = CheckConfig(true, 2.0, 1.0, 2.0),
    var clickStatistics: CheckConfig = CheckConfig(true, 5.0, 0.05, 20.0),
    var throughWalls: CheckConfig = CheckConfig(true, 5.0, 0.5, 1.0),
    var criticals: CheckConfig = CheckConfig(true, 5.0, 0.1, 0.05),
    var noKnockback: CheckConfig = CheckConfig(true, 5.0, 1.0, 0.61),
    var keepSprint: CheckConfig = CheckConfig(true, 5.0, 0.5, 0.5),
    var wTap: CheckConfig = CheckConfig(true, 5.0, 0.5, 2.0),
    var jumpOnHurt: CheckConfig = CheckConfig(true, 5.0, 0.2, 0.4),
    var backtrack: CheckConfig = CheckConfig(true, 10.0, 0.25, 3.0),
    /** Rain-Anticheat killaura/silent-aim suite (7 sub-components, one VL pool). threshold unused. */
    var killAura: CheckConfig = CheckConfig(true, 5.0, 0.05, 0.0),
    /** Rain-Anticheat AutoBlock — sustained swing+use overlap tick limit. */
    var autoBlock: CheckConfig = CheckConfig(true, 5.0, 0.5, 10.0),
    /** HitFlick / KnockbackDisplace — min yaw-off-hitbox degrees at the attack tick. */
    var hitFlick: CheckConfig = CheckConfig(true, 5.0, 0.5, 30.0),
    /** Triggerbot — min fast-hit count (attacks within MAX_REACTION_TICKS of the crosshair first
     *  reaching a victim's hitbox) required in the rolling window, gated by a ≥0.75 ratio over
     *  ≥5 hits. Default 4 = "really blatant" (4 of 5 hits are sub-reaction). Raise to tune stricter. */
    var triggerbot: CheckConfig = CheckConfig(true, 5.0, 0.05, 4.0),

    // --- movement ---
    var speedEnvelope: CheckConfig = CheckConfig(true, 5.0, 1.0, 10.0),
    var flyEnvelope: CheckConfig = CheckConfig(true, 5.0, 1.0, 0.1),
    var noFallDamage: CheckConfig = CheckConfig(true, 4.0, 1.0, 8.0),
    var stepHeight: CheckConfig = CheckConfig(true, 5.0, 0.5, 0.6),
    var teleport: CheckConfig = CheckConfig(true, 5.0, 0.5, 1.5),
    var longJump: CheckConfig = CheckConfig(true, 5.0, 0.5, 0.6),
    var noSlow: CheckConfig = CheckConfig(true, 5.0, 0.5, 4.0),
    var backwardSprint: CheckConfig = CheckConfig(true, 5.0, 0.5, 1.0),
    var waterWalk: CheckConfig = CheckConfig(true, 5.0, 0.5, 1.0),
    var elytraSpeed: CheckConfig = CheckConfig(true, 5.0, 1.0, 40.0),

    // --- rotation / packet-flow / minor ---
    var rotationTracking: CheckConfig = CheckConfig(true, 5.0, 0.05, 0.92),
    var rotationSnapBack: CheckConfig = CheckConfig(true, 5.0, 0.5, 30.0),
    var phaseClip: CheckConfig = CheckConfig(true, 5.0, 0.5, 1.0),
    var packetGap: CheckConfig = CheckConfig(true, 5.0, 0.5, 2.0),
    var timerRate: CheckConfig = CheckConfig(true, 5.0, 0.5, 14.0),
    var aimWrap: CheckConfig = CheckConfig(true, 5.0, 0.5, 150.0),
    var pitchBound: CheckConfig = CheckConfig(true, 5.0, 0.0, 90.0),
    var scaffoldRotation: CheckConfig = CheckConfig(true, 5.0, 0.05, 78.0),
) {
    data class CheckConfig(
        var enabled: Boolean = true,
        var setbackVL: Double = 5.0,
        var decay: Double = 1.0,
        var threshold: Double = 0.0,
    )

    /** All check slices in a stable order (used by the registry + YACL screen). */
    fun checks(): List<Pair<String, CheckConfig>> = listOf(
        "reach" to reach,
        "multiTarget" to multiTarget,
        "clickStatistics" to clickStatistics,
        "throughWalls" to throughWalls,
        "criticals" to criticals,
        "noKnockback" to noKnockback,
        "keepSprint" to keepSprint,
        "wTap" to wTap,
        "jumpOnHurt" to jumpOnHurt,
        "backtrack" to backtrack,
        "killAura" to killAura,
        "autoBlock" to autoBlock,
        "hitFlick" to hitFlick,
        "triggerbot" to triggerbot,
        "speedEnvelope" to speedEnvelope,
        "flyEnvelope" to flyEnvelope,
        "noFallDamage" to noFallDamage,
        "stepHeight" to stepHeight,
        "teleport" to teleport,
        "longJump" to longJump,
        "noSlow" to noSlow,
        "backwardSprint" to backwardSprint,
        "waterWalk" to waterWalk,
        "elytraSpeed" to elytraSpeed,
        "rotationTracking" to rotationTracking,
        "rotationSnapBack" to rotationSnapBack,
        "phaseClip" to phaseClip,
        "packetGap" to packetGap,
        "timerRate" to timerRate,
        "aimWrap" to aimWrap,
        "pitchBound" to pitchBound,
        "scaffoldRotation" to scaffoldRotation,
    )

    /** Look up a check's slice by id (so checks always read the live config). */
    fun slice(id: String): CheckConfig = when (id) {
        "reach" -> reach
        "multiTarget" -> multiTarget
        "clickStatistics" -> clickStatistics
        "throughWalls" -> throughWalls
        "criticals" -> criticals
        "noKnockback" -> noKnockback
        "keepSprint" -> keepSprint
        "wTap" -> wTap
        "jumpOnHurt" -> jumpOnHurt
        "backtrack" -> backtrack
        "killAura" -> killAura
        "autoBlock" -> autoBlock
        "hitFlick" -> hitFlick
        "triggerbot" -> triggerbot
        "speedEnvelope" -> speedEnvelope
        "flyEnvelope" -> flyEnvelope
        "noFallDamage" -> noFallDamage
        "stepHeight" -> stepHeight
        "teleport" -> teleport
        "longJump" -> longJump
        "noSlow" -> noSlow
        "backwardSprint" -> backwardSprint
        "waterWalk" -> waterWalk
        "elytraSpeed" -> elytraSpeed
        "rotationTracking" -> rotationTracking
        "rotationSnapBack" -> rotationSnapBack
        "phaseClip" -> phaseClip
        "packetGap" -> packetGap
        "timerRate" -> timerRate
        "aimWrap" -> aimWrap
        "pitchBound" -> pitchBound
        "scaffoldRotation" -> scaffoldRotation
        else -> CheckConfig()
    }
}