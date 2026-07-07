package dev.iustitia.mixin

import dev.iustitia.config.ConfigManager
import dev.iustitia.history.FlagHistory
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.hud.PlayerListHud
import net.minecraft.client.network.PlayerListEntry
import net.minecraft.text.Text
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable

/**
 * Tab-list badge (Phase B): prepends the cheat-tier prefix (green [+] / yellow [!] / red [X]) to
 * each OTHER player's name in the Tab (player list) HUD, so the tier cue is visible without looking
 * at the player in-world. Just the tier glyph — no numeric confidence score on the tab (the score
 * is still available in `/ius hist`, `/ius session`, the snapshot, and the crosshair confidence HUD).
 *
 * Read-only on the tab render path: it rewrites the **return value** of `PlayerListHud.getPlayerName`
 * (`@At("RETURN")`, cancellable) — the single per-entry name source that both the row text and the
 * width measurement read (confirmed by decompiling `PlayerListHud.render` on yarn 1.21.11: it calls
 * `getPlayerName(entry)` then measures/draws that `Text`). No field mutation, no visibility/position
 * change. Whole body fail-open: any error leaves the original name untouched.
 *
 * - **Local-player exclusion:** `ClientPlayerEntity` is also a `PlayerListEntry` in the list; we
 *   skip the local player by uuid so your own row isn't badged.
 * - **GREEN gating:** clean players are only badged when `nametagGreenEnabled` is on (same rule as
 *   the nametag / target-highlight / ghost-trail — no `[+]` spam on every clean row by default).
 * - **Idempotent:** `getPlayerName` is called once per entry per render (row + width share the same
 *   returned `Text`); replacing the return value is idempotent — no `[X][X] Name` accumulation.
 * - **No burst-pulse:** the tab surface is secondary; the nametag carries the pulse. The badge here
 *   is the steady tier glyph (consistent with the in-world nametag).
 *
 * Runtime-caveat (same class as the nametag mixin was originally developed under): the inject POINT
 * in `getPlayerName` is build-confirmed (`@At("RETURN")` on a public method), so a build GREEN + a
 * successful mixin APPLY at launch is expected; if a future mapping rename breaks it, the mixin
 * apply failure shows at launch (not a crash — the tab just shows un-badged names, fail-open).
 */
@Mixin(PlayerListHud::class)
class PlayerListHudMixin {

    @Inject(method = ["getPlayerName"], at = [At("RETURN")], cancellable = true)
    private fun iustitia_prefixTabName(entry: PlayerListEntry, cir: CallbackInfoReturnable<Text>) {
        try {
            val cfg = ConfigManager.config
            if (!cfg.tabListBadge) return
            if (!cfg.nametagPrefixes) return

            val original = cir.returnValue ?: return
            val uuid = entry.profile?.id ?: return

            // Never badge the local player's own tab row.
            val self = MinecraftClient.getInstance().player
            if (self == null || uuid == self.uuid) return

            val tier = FlagHistory.tierFor(uuid)
            if (tier == FlagHistory.Tier.GREEN && !cfg.nametagGreenEnabled) return

            val (color, glyph) = when (tier) {
                FlagHistory.Tier.GREEN -> "a" to "+"
                FlagHistory.Tier.YELLOW -> "e" to "!"
                FlagHistory.Tier.RED -> "c" to "X"
            }
            val prefix = Text.literal("§${color}[$glyph]§r ")
            cir.setReturnValue(Text.empty().append(prefix).append(original))
        } catch (_: Throwable) {
            // fail-open: leave the original name
        }
    }
}