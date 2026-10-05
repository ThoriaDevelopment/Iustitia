package dev.iustitia.keybind

import dev.iustitia.i18n.L10n
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

    /** A registered bind: metadata always present; [keyBinding] is null until [register] succeeds.
     *  [label] and [description] are live lookups (not frozen constructor strings) so a runtime
     *  language switch applies to the hub immediately. The texts live in the language files
     *  (`key.iustitia.<id>` / `iustitia.misc.keybind.<id>`); the static verifier fails if en_us is
     *  missing either key, and [net.minecraft.util.Language] falls back to en_us for any translation
     *  that lacks them. */
    data class Bind(
        val id: String,
        val defaultKey: Int,
        var keyBinding: KeyBinding?,
    ) {
        /** Display label — the vanilla `key.iustitia.<id>` key (same text the Controls screen shows). */
        val label: String
            get() = L10n.s("key.iustitia.$id")

        /** One-line description shown by the keybind hub. */
        val description: String
            get() = L10n.s("iustitia.misc.keybind.$id")
    }

    private val binds: List<Bind> = listOf(
        Bind("snapshot", GLFW.GLFW_KEY_K, null),
        Bind("transcript", GLFW.GLFW_KEY_J, null),
        Bind("session", GLFW.GLFW_KEY_HOME, null),
        Bind("keybinds", GLFW.GLFW_KEY_END, null),
        Bind("config", GLFW.GLFW_KEY_F8, null),
        Bind("note", GLFW.GLFW_KEY_N, null),
        Bind("compact", GLFW.GLFW_KEY_F7, null),
        Bind("watch", GLFW.GLFW_KEY_F9, null),
        Bind("replayPause", GLFW.GLFW_KEY_KP_5, null),
        Bind("replaySeekFwd", GLFW.GLFW_KEY_KP_ADD, null),
        Bind("replaySeekBack", GLFW.GLFW_KEY_KP_SUBTRACT, null),
        Bind("replayExit", GLFW.GLFW_KEY_KP_0, null),
        Bind("replayToggle", GLFW.GLFW_KEY_KP_MULTIPLY, null),
    )

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