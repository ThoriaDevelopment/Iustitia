package dev.iustitia.selftest

/** Pass names exactly as the harness emits them; `scripts/live_selftest.py` matches these. */
object Pass {
    /** First pass: vanilla-accurate play. Any alert is a FALSE POSITIVE. */
    const val LEGIT = "LEGIT"

    /** Second pass: unfair advantage. A missed alert is a BYPASS. */
    const val CHEAT = "CHEAT"

    /** Observer tooling (replay, clips, freecam). */
    const val REPLAY = "REPLAY"
}

/**
 * The check inventory, grouped the way the checks live on disk.
 *
 * Kept as data (not derived) so a scenario can say "this behaviour must trip nothing in the
 * movement family" without listing twenty strings, and so the runner can diff its coverage
 * against a stable list. It is deliberately duplicated from
 * `Iustitia.allChecks` (which the harness also publishes) -- the registry list is the
 * authority at runtime, this one is the authoring convenience, and the runner's `--coverage`
 * compares the two so a drift is caught instead of silently shrinking coverage.
 */
object CheckIds {
    val COMBAT = listOf(
        "reach", "multiTarget", "clickStatistics", "throughWalls", "criticals", "noKnockback",
        "keepSprint", "wTap", "jumpOnHurt", "backtrack", "killAura", "autoBlock", "hitFlick",
        "triggerbot", "maceSmash", "hitsWithoutSwing",
    )

    val MOVEMENT = listOf(
        "speedEnvelope", "flyEnvelope", "teleport", "packetGap", "noFallDamage", "spider",
        "stepHeight", "longJump", "noSlow", "backwardSprint", "wallSprint", "sprintHack",
        "waterWalk", "elytraSpeed", "rotationTracking", "rotationSnapBack", "phaseClip",
        "aimWrap", "pitchBound", "scaffoldRotation",
    )

    val ALL: List<String> = COMBAT + MOVEMENT
}

/**
 * A deterministic pseudo-random source for human-looking variation.
 *
 * Scenarios that assert "a legitimate player must not be flagged" have to look like a
 * legitimate player, and the difference between a human and a script is *variance*: a bot whose
 * aim is byte-identical every tick is not a false positive when the aim checks flag it -- it is a
 * perfectly-executed aimbot, and the suite would be lying if it reported that as a detector bug
 * (verified live: a constant-pitch attacker tripped `rotationTracking [AimTrack]` continuously).
 *
 * Fixed seed + fixed call order keeps the whole run reproducible, which the suite depends on.
 */
class Jitter(seed: Long = 0x2545F4914F6CDD1DL) {
    private var state: Long = seed

    /** Uniform in [-1, 1]. */
    fun next(): Double {
        state = state * 6364136223846793005L + 1442695040888963407L
        return ((state ushr 11).toDouble() / (1L shl 53).toDouble()) * 2.0 - 1.0
    }

    /** Uniform in +/-[max] degrees. */
    fun deg(max: Double): Float = (next() * max).toFloat()

    /** Uniform in +/-[max] blocks. */
    fun units(max: Double): Double = next() * max
}

/** Coarse behaviour tags, kept to a closed set so runner filters stay predictable. */
object Tags {
    const val COMBAT = "combat"
    const val MOVEMENT = "movement"
    const val ROTATION = "rotation"
    const val PACKET = "packet"
    const val WORLD = "world"
    const val GUARD = "guard"
    const val REPLAY = "replay"
}

/**
 * Compact scenario form: metadata plus a behaviour body.
 *
 * The body declares its own expectations through the builder DSL, exactly like a
 * hand-written [SelfTest.Scenario], so there is no second source of truth to drift. What
 * [Spec] adds over a bespoke class is that metadata (pass, source client, tags) is part of
 * the constructor signature, which is what makes the library's provenance auditable at a
 * glance and lets a reviewer see the whole cheat-client matrix in one screen.
 *
 * A cheat scenario MUST name a [source]: a drive whose pattern cannot be attributed to a
 * real client is an invented pattern, and the runner prints its provenance next to every
 * result so a green row can always be traced back to the module it reproduces.
 */
class Spec(
    name: String,
    pass: String,
    source: String,
    tags: Set<String>,
    private val body: (SelfTest.ScenarioBuilder) -> Unit,
) : SelfTest.Scenario(name, pass, source, tags) {
    override fun run(builder: SelfTest.ScenarioBuilder) = body(builder)
}
