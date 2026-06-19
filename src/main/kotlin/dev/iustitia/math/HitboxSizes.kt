package dev.iustitia.math

import dev.iustitia.tracking.TrackedPlayer

/**
 * Hitbox / eye-height constants. MVP only targets players, so we hardcode the
 * player values (matching Grim `BoundingBoxSize` / vanilla EntityDimensions) rather
 * than reading them off live entities (which would couple us to Yarn entity-dimensions
 * APIs that vary across protocol versions). Other entity types are exempted upstream.
 *
 * [forPose] maps a [TrackedPlayer]'s polled pose flags to the matching width/height/
 * eyeHeight so combat checks build the *victim's* box from the pose the victim is
 * actually in — a sneaking victim is 0.6×1.5 (not 1.8) and a gliding/swimming/riptide
 * victim is 0.6×0.6 with a 0.4 eye. A standing-sized box on a crouched/elytra opponent
 * biased Reach/Backtrack (taller box → shorter measured distance → missed real reach)
 * and Triggerbot rising-edge (taller box → crosshair "on" early → false-fast reaction).
 */
data class Hitbox(val width: Double, val height: Double, val eyeHeight: Double)

object HitboxSizes {
    val PLAYER = Hitbox(0.6, 1.8, 1.62)
    val PLAYER_SNEAK = Hitbox(0.6, 1.5, 1.54)
    /** Gliding (FALL_FLYING), swimming, and riptide (SPIN_ATTACK) all collapse the player
     *  to a 0.6×0.6 prone box with a 0.4 eye. Matches vanilla EntityDimensions for those poses. */
    val PLAYER_PRONE = Hitbox(0.6, 0.6, 0.4)

    /** The hitbox matching [tp]'s current pose. Poses are mutually exclusive at the entity
     *  level; gliding/swimming/riptide are checked first (they collapse height most), then
     *  sneaking, then the standing default. */
    fun forPose(tp: TrackedPlayer): Hitbox = when {
        tp.gliding || tp.swimming || tp.riptide -> PLAYER_PRONE
        tp.sneaking -> PLAYER_SNEAK
        else -> PLAYER
    }
}