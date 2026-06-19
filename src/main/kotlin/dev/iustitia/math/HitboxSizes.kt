package dev.iustitia.math

/**
 * Hitbox / eye-height constants. MVP only targets players, so we hardcode the
 * player values (matching Grim `BoundingBoxSize` / vanilla EntityDimensions) rather
 * than reading them off live entities (which would couple us to Yarn entity-dimensions
 * APIs that vary across protocol versions). Other entity types are exempted upstream.
 */
data class Hitbox(val width: Double, val height: Double, val eyeHeight: Double)

object HitboxSizes {
    val PLAYER = Hitbox(0.6, 1.8, 1.62)
    val PLAYER_SNEAK = Hitbox(0.6, 1.5, 1.54)
}