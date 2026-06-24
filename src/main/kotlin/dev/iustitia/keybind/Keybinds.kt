package dev.iustitia.keybind

import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper
import net.minecraft.client.option.KeyBinding
import net.minecraft.client.util.InputUtil
import org.lwjgl.glfw.GLFW

/**
 * The Iustitia keybind registry (Phase 2 #14). Each bind is a vanilla [KeyBinding] in the
 * "Miscellaneous" category (rebindable in Controls → Miscellaneous), registered through Fabric's
 * [KeyBindingHelper] so it shows up in the vanilla keybinds screen and coexists with other mods.
 *
 * **Decoupled design:** the [Bind] metadata (id / label / description / default key) is constructed
 * at object-init time with a `null` [KeyBinding]; the actual [KeyBinding] is created and registered
 * lazily in [register] (called from `onInitializeClient`), per-bind and fail-open. This way a
 * registration failure (or a thrown category/keybinding ctor) can NEVER poison the [Keybinds] object
 * or leave [all] unusable — the keybind hub still lists every bind, and the broken one just shows
 * "unregistered". (The prior single-construct-at-init design threw during init and was swallowed by
 * `register`'s try/catch, which left `Keybinds.all` throwing `NoClassDefFoundError` → empty hub +
 * dead keybinds + nothing in Controls.)
 *
 * Polling is driven from `ClientTickEvents.END_CLIENT_TICK` via [poll] — a bind only fires on its
 * rising edge (`wasPressed`), and dispatch goes through [dev.iustitia.Iustitia.onKeybind], which is
 * fail-open. Nothing here sends packets or touches the local player's state.
 *
 * Default keys are deliberately uncommon (mostly unbound in vanilla) to avoid hijacking existing
 * binds; [dev.iustitia.ui.KeybindHubScreen] highlights any conflict red so the user can rebind.
 *
 * NOTE: a custom "Iustitia" category (`KeyBinding.Category.create(...)`) was attempted first but did
 * not surface in the Controls screen at runtime in 1.21.11, so we use the vanilla `MISC` category —
 * the binds appear under "Miscellaneous" and are fully rebindable. A dedicated "Iustitia" group is a
 * future probe item, not worth blocking working keybinds on.
 */
object Keybinds {

    /** A registered bind: metadata always present; [keyBinding] is null until [register] succeeds. */
    data class Bind(
        val id: String,
        val defaultKey: Int,
        var keyBinding: KeyBinding?,
        val label: String,
        val description: String,
    )

    private val binds: List<Bind> = listOf(
        bind("snapshot", GLFW.GLFW_KEY_K, "Snapshot",
            "Post + copy a one-line evidence summary of your crosshair target."),
        bind("transcript", GLFW.GLFW_KEY_J, "Transcript panel",
            "Toggle the live side-panel transcript for your crosshair target."),
        bind("session", GLFW.GLFW_KEY_HOME, "Session screen",
            "Open the dense session summary screen (tier counts, peak, totals)."),
        bind("keybinds", GLFW.GLFW_KEY_END, "Keybind hub",
            "Open this screen — lists every bind, its key, and conflicts."),
        bind("config", GLFW.GLFW_KEY_F8, "Config",
            "Open the YACL config screen."),
        bind("note", GLFW.GLFW_KEY_N, "Note target",
            "Read the moderator note on your crosshair target (if any)."),
        bind("compact", GLFW.GLFW_KEY_F7, "Compact mode",
            "Toggle compact one-line alerts + condensed screens."),
        bind("watch", GLFW.GLFW_KEY_F9, "Watch (Phase B)",
            "Follow-cam orbit — reserved; enabled in the Phase B render pass."),
    )

    private fun bind(id: String, defaultKey: Int, label: String, description: String): Bind =
        Bind(id, defaultKey, null, label, description)

    /** All registered binds (read by [dev.iustitia.ui.KeybindHubScreen]). Order = display order. */
    val all: List<Bind> get() = binds

    /**
     * Create + register every bind with Fabric. Call once from `onInitializeClient`, before the tick
     * loop. Per-bind fail-open: one bad bind (e.g. a duplicate id) doesn't abort the rest, and a
     * failure leaves that bind's [Bind.keyBinding] null (hub shows "unregistered", poll skips it).
     */
    fun register() {
        // Vanilla "Miscellaneous" category — guaranteed present in KeyBinding.Category.CATEGORIES so
        // the binds appear in Controls → Miscellaneous and are rebindable.
        val category = KeyBinding.Category.MISC
        for (b in binds) {
            try {
                val kb = KeyBinding("key.iustitia.${b.id}", InputUtil.Type.KEYSYM, b.defaultKey, category)
                KeyBindingHelper.registerKeyBinding(kb)
                b.keyBinding = kb
            } catch (_: Throwable) {
                // fail-open: leave keyBinding null; hub/poll handle it
            }
        }
    }

    /**
     * Poll every bind for a rising edge and dispatch to [dev.iustitia.Iustitia.onKeybind]. Call from
     * `END_CLIENT_TICK`. Fail-open: a handler throw is swallowed so one bad bind never stalls the
     * tick. Unregistered binds (null [Bind.keyBinding]) are skipped.
     */
    fun poll() {
        try {
            for (b in binds) {
                try {
                    val kb = b.keyBinding ?: continue
                    if (kb.wasPressed()) dev.iustitia.Iustitia.onKeybind(b.id)
                } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {}
    }

    /** Look up a bind by id (for the hub / dispatch). */
    fun byId(id: String): Bind? = binds.firstOrNull { it.id == id }
}