package dev.iustitia.chathist

import net.minecraft.client.MinecraftClient
import net.minecraft.text.Text
import java.util.UUID

/**
 * Decorated-chat capture for `/ius chathist`. The signed player-chat path
 * ([ChatMessageS2CPacket][net.minecraft.network.packet.s2c.play.ChatMessageS2CPacket], handled in
 * [dev.iustitia.mixin.ClientPlayNetworkHandlerMixin.iustitia_onChatMessage]) only fires on servers
 * that keep chat signing. The servers most users want chathist on — Hypixel, ArchMC, Minemen,
 * Cavern, EvoxMC, Stray, mcpvp, PvpHQ, Mineplex — broadcast decorated chat
 * (`[rank] [stars] username ❣: message`) as **system/game messages**
 * ([GameMessageS2CPacket][net.minecraft.network.packet.s2c.play.GameMessageS2CPacket]) or
 * **profileless chat** ([ProfilelessChatMessageS2CPacket][net.minecraft.network.packet.s2c.play.ProfilelessChatMessageS2CPacket]),
 * neither of which carries a sender UUID. This parser recovers `(username, message)` from those
 * paths.
 *
 * ## Structure-based — no per-server regex table
 *
 * Every non-Mineplex decorated format is `<decorations> USERNAME <optional suffix> <SEP> <message>`
 * where `SEP` ∈ {`:`, `»`, `→`, `➠`}. The parser:
 *  1. strips `§` color codes from the flattened [Text.getString];
 *  2. finds the **first** separator (first, so a `:` inside the message — `PvpHQ "hello, world!"`,
 *     mcpvp long lines — is ignored; the sender's separator is always first because rank/star tokens
 *     never contain `:`/arrows);
 *  3. takes the **last tab-list name** found in the region before the separator as the username — the
 *     suffix (`❣`, `•꙳⋆ Snow ⋆꙳•`) and all ranks/stars are non-name tokens before/after the username,
 *     so the last name in the region is the username (no explicit suffix parsing); and
 *  4. the message is the text after the separator, leading whitespace trimmed.
 *
 * **Mineplex** has no separator between username and message, and the message can contain `:`
 * (`:O`). Structural detection: the stripped line **starts with a digit** (the `§f<N>` star count)
 * → Mineplex-style; sender = the **first** tab-list name in the line (stars + rank are not player
 * names); message = the rest after it. System messages (death/join) don't start with a bare digit.
 *
 * ## Sender anchor: tab list (preferred), permissive fallback (toggle)
 *
 * The tab list — not the render-distance [dev.iustitia.tracking.EntityTrackerManager] — is the
 * "other players" set, because lobby chat includes players not in render distance. When a tab-list
 * name is found in the region, it's the username and resolves a real UUID via
 * `getPlayerListEntry(name).profile.id`. The local player is excluded by UUID with a name-match
 * fallback.
 *
 * When NO tab-list name is found in the region, the line is dropped by default. With
 * `chathistCaptureUnknown` ON (permissive / cross-server mode — **defaults OFF**, see
 * [dev.iustitia.config.IustitiaConfig.chathistCaptureUnknown]), the parser instead falls back to
 * "the last `[A-Za-z0-9_]` word-token before the separator" as the username and still captures the
 * row (with a synthetic `UUID.nameUUIDFromBytes` so it's queryable by name). This catches
 * Bungee/Velocity cross-sub-server chat where the sender isn't in this client's tab list, at the
 * cost of mis-attributing when a suffix contains word tokens (Minemen `•꙳⋆ Snow ⋆꙳•` → `Snow`) —
 * which is exactly why it defaults OFF.
 *
 * Everything is fail-open: any throw → null → nothing recorded (never a crash). Verified against all
 * 14 non-Mineplex latestlog examples + the Mineplex example by hand.
 */
object DecoratedChatParser {

    /** Resolved chat row: a clean username, its UUID (real if tab-list, synthetic if permissive fallback), and the message text. */
    data class Parsed(val name: String, val uuid: UUID, val text: String)

    /** Parse a system/game message ([GameMessageS2CPacket.content]) into a chat row, or null. Fail-open. */
    fun parseGameMessage(text: Text, permissive: Boolean): Parsed? = try {
        val s = stripColor(text.string).trim()
        if (s.isEmpty()) return null
        val localName = localName()
        val sep = firstSep(s)
        if (sep >= 0) {
            val region = s.substring(0, sep)
            val msg = s.substring(sep + 1).trimStart()
            if (msg.isEmpty()) return null
            var name = findLastNameIn(region, tabNames(), localName)
            if (name == null) {
                if (!permissive) return null
                name = lastWordToken(region) ?: return null
            }
            if (name.equals(localName, ignoreCase = true)) return null
            val uuid = resolveByName(name, permissive) ?: return null
            Parsed(name, uuid, msg)
        } else if (s.first().isDigit()) {
            // Mineplex-style: "<stars digit> <rank> USERNAME <message>" — no separator.
            val first = findFirstNameIn(s, tabNames(), localName)
            val (name, start) = if (first != null) {
                first
            } else {
                if (!permissive) return null
                val fw = firstWordAfter(s) ?: return null
                fw
            }
            if (name.equals(localName, ignoreCase = true)) return null
            val msg = s.substring(start + name.length).trimStart()
            if (msg.isEmpty()) return null
            val uuid = resolveByName(name, permissive) ?: return null
            Parsed(name, uuid, msg)
        } else null
    } catch (_: Throwable) { null }

    /**
     * Parse a profileless chat message ([ProfilelessChatMessageS2CPacket]): the packet already splits
     * the sender display name ([nameText]) from the message body ([messageText]), so only the name →
     * clean-username resolution is needed. Fail-open.
     */
    fun parseProfileless(nameText: Text, messageText: Text, permissive: Boolean): Parsed? = try {
        val msg = stripColor(messageText.string).trim()
        if (msg.isEmpty()) return null
        val localName = localName()
        val displayName = stripColor(nameText.string).trim()
        val names = tabNames()
        var name: String? = null
        if (names.isNotEmpty()) {
            // longest tab name that appears in the display name (word-bounded); excludes the local player.
            name = names.asSequence()
                .filter { localName == null || !it.equals(localName, ignoreCase = true) }
                .filter { nameRx(it).containsMatchIn(displayName) }
                .maxByOrNull { it.length }
        }
        if (name == null) {
            if (!permissive) return null
            name = lastWordToken(displayName) ?: return null
        }
        if (name.equals(localName, ignoreCase = true)) return null
        val uuid = resolveByName(name, permissive) ?: return null
        Parsed(name, uuid, msg)
    } catch (_: Throwable) { null }

    /**
     * Shared clean-name resolver (also used by the signed `onChatMessage` path so the `name` field is
     * consistently the clean username across all three capture paths). Prefers the tab-list profile
     * name for [uuid]; falls back to [fallback]. Fail-open (returns [fallback]).
     */
    fun resolveCleanName(uuid: UUID, fallback: String): String = try {
        MinecraftClient.getInstance().networkHandler?.getPlayerListEntry(uuid)?.profile?.name
            ?.takeIf { it.isNotBlank() } ?: fallback
    } catch (_: Throwable) { fallback }

    // ---- internals ----

    private const val SECTION = "§"

    private fun stripColor(s: String): String =
        s.replace(Regex("$SECTION."), "")
            .replace(Regex("(?i)&[0-9A-FK-OR]"), "")

    private fun firstSep(s: String): Int {
        var best = -1
        for (c in charArrayOf(':', '»', '→', '➠')) {
            val i = s.indexOf(c)
            if (i >= 0 && (best < 0 || i < best)) best = i
        }
        return best
    }

    private fun nameRx(name: String): Regex =
        Regex("(?<![A-Za-z0-9_])" + Regex.escape(name) + "(?![A-Za-z0-9_])", RegexOption.IGNORE_CASE)

    /** The tab name whose (rightmost) occurrence in [region] ends closest to the separator. Longest-name tiebreak. */
    private fun findLastNameIn(region: String, names: List<String>, exclude: String?): String? {
        var bestName: String? = null
        var bestEnd = -1
        for (name in names) {
            if (exclude != null && name.equals(exclude, ignoreCase = true)) continue
            var rightmostEnd = -1
            for (m in nameRx(name).findAll(region)) {
                if (m.range.last > rightmostEnd) rightmostEnd = m.range.last
            }
            if (rightmostEnd < 0) continue
            if (rightmostEnd > bestEnd || (rightmostEnd == bestEnd && name.length > (bestName?.length ?: 0))) {
                bestEnd = rightmostEnd
                bestName = name
            }
        }
        return bestName
    }

    /** The leftmost tab-list name in [line] → (name, start index). Mineplex sender anchor. */
    private fun findFirstNameIn(line: String, names: List<String>, exclude: String?): Pair<String, Int>? {
        var best: Pair<String, Int>? = null
        var bestStart = Int.MAX_VALUE
        for (name in names) {
            if (exclude != null && name.equals(exclude, ignoreCase = true)) continue
            val m = nameRx(name).find(line) ?: continue
            if (m.range.first < bestStart) {
                bestStart = m.range.first
                best = name to m.range.first
            }
        }
        return best
    }

    /** Permissive fallback: the last `[A-Za-z0-9_]` run in [region] (skips trailing non-word suffixes like `❣`). */
    private fun lastWordToken(region: String): String? =
        Regex("[A-Za-z0-9_]+").findAll(region).maxByOrNull { it.range.last }?.value

    /** Permissive Mineplex fallback: the first word-token after the leading `<digits> <rank>`. Best-effort (single-token rank). */
    private fun firstWordAfter(s: String): Pair<String, Int>? {
        val m = Regex("\\d+\\s+\\S+\\s+([A-Za-z0-9_]+)").find(s) ?: return null
        val g = m.groups[1] ?: return null
        return g.value to g.range.first
    }

    private fun tabNames(): List<String> = try {
        val h = MinecraftClient.getInstance().networkHandler ?: return emptyList()
        h.playerList.mapNotNull { it.profile?.name }.filter { it.isNotBlank() }
    } catch (_: Throwable) { emptyList() }

    private fun localName(): String? = try {
        MinecraftClient.getInstance().player?.gameProfile?.name
    } catch (_: Throwable) { null }

    /**
     * Resolve a sender name → UUID. Real UUID from the tab list when present; in permissive mode, a
     * synthetic name-derived UUID so a non-tab-list sender is still queryable. Excludes the local
     * player (by UUID). Returns null when not in the tab list and not permissive (→ drop the row).
     */
    private fun resolveByName(name: String, permissive: Boolean): UUID? {
        val mc = MinecraftClient.getInstance()
        val local = try { mc.player?.uuid } catch (_: Throwable) { null }
        val entry = try { mc.networkHandler?.getCaseInsensitivePlayerInfo(name) } catch (_: Throwable) { null }
        val uuid = entry?.profile?.id
        if (uuid != null) {
            if (uuid == local) return null
            return uuid
        }
        if (!permissive) return null
        val synth = UUID.nameUUIDFromBytes(name.lowercase().toByteArray(Charsets.UTF_8))
        if (synth == local) return null
        return synth
    }
}