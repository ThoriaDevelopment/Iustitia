package dev.iustitia.render

/**
 * Coarse block-name → ARGB tint for the terrain-shell overlay ([TerrainOverlay]). The shell renders
 * every visible (face-culled) block as a uniform cuboid outline; the tint is by *category* (stone,
 * wood, leaves, water, …), not per-block, so a clip's terrain reads as a color-coded ghostly map
 * without a texture atlas. Full alpha (high byte 0xFF) — the lines buffer's [VertexConsumer.color]
 * takes the int straight.
 *
 * [isTransparent] flags blocks you can see through (glass, leaves, ice, water, …) so [TerrainOverlay]
 * keeps the face *behind* them (otherwise a glass pane would cull the wall behind it and the shell
 * would have a hole). The set is intentionally small — anything not listed is treated as opaque.
 *
 * Block *registry ids* (`"minecraft:stone"`) are matched by substring so it's robust to namespace
 * prefixes and property-less names. Fail-open: an unrecognized name falls through to the default tint.
 */
object TerrainColors {

    private const val STONE = 0xFFA0A0A0.toInt()
    private const val WOOD = 0xFFC89060.toInt()
    private const val LEAF = 0xFF50C050.toInt()
    private const val WATER = 0xFF4080C0.toInt()
    private const val SAND = 0xFFE0D0A0.toInt()
    private const val DIRT = 0xFF8B6A3E.toInt()
    private const val GLASS = 0xFFA0E0E0.toInt()
    private const val DEFAULT = 0xFFB0B0B0.toInt()

    /** Tint for a block registry id, by coarse category (substring match, namespace-robust). */
    fun tint(name: String): Int {
        val n = if (name.startsWith("minecraft:")) name.substring(10) else name
        return when {
            "log" in n || "planks" in n || "wood" in n || "stem" in n || "bamboo" in n -> WOOD
            "leaf" in n || "leaves" in n || "plant" in n || "grass" in n || "flower" in n
                || "moss" in n || "fern" in n || "mushroom" in n || "vine" in n -> LEAF
            "water" in n || "lava" in n -> if ("lava" in n) 0xFFFFA040.toInt() else WATER
            "sand" in n || "gravel" in n || "concrete_powder" in n -> SAND
            "dirt" in n || "podzol" in n || "mycelium" in n || "farmland" in n || "coarse_dirt" in n -> DIRT
            "glass" in n || "ice" in n || "snow" in n -> if ("snow" in n) 0xFFE8F0FF.toInt() else GLASS
            "stone" in n || "brick" in n || "cobble" in n || "ore" in n || "granite" in n
                || "diorite" in n || "andesite" in n || "deepslate" in n || "bedrock" in n
                || "obsidian" in n || "netherrack" in n || "end_stone" in n || "terracotta" in n
                || "concrete" in n || "sandstone" in n || "prismarine" in n || "calcite" in n
                || "tuff" in n || "basalt" in n || "blackstone" in n -> STONE
            else -> DEFAULT
        }
    }

    /** True for see-through blocks (the face behind them is kept by the culler). Small set by design. */
    fun isTransparent(name: String): Boolean {
        val n = if (name.startsWith("minecraft:")) name.substring(10) else name
        return "glass" in n || "leaves" in n || "ice" in n || "water" in n || "slime" in n
            || "honey" in n || "tinted" in n || "scaffolding" in n || "iron_bars" in n
            || "trapdoor" in n || "fence" in n || "ladder" in n || "vine" in n
    }
}