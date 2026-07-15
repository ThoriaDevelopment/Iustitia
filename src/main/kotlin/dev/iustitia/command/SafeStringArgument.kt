package dev.iustitia.command

import com.mojang.brigadier.Message
import com.mojang.brigadier.StringReader
import com.mojang.brigadier.arguments.ArgumentType
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType

/**
 * A [StringArgumentType][com.mojang.brigadier.arguments.StringArgumentType]-shaped argument that
 * **rejects a configured set of reserved words** in [parse]. Its sole purpose is to silence
 * Brigadier's
 * `Ambiguity between arguments [...] and [...] with inputs: [...]` warnings — the benign
 * `[Render thread/WARN]` lines some launchers (Modrinth) surface as "fatal errors".
 *
 * The warning fires whenever a string argument is a **sibling** of literal subcommands, e.g.
 * `/ius replay <target>` sitting next to `/ius replay off|pause|resume|seek|...`. Brigadier's
 * `CommandNode.findAmbiguities` takes each sibling literal's example word (the literal's own name)
 * and tests it against the argument's `ArgumentCommandNode.isValidInput`, which runs [parse] and
 * returns **false** on a [com.mojang.brigadier.exceptions.CommandSyntaxException]. The stock
 * `word()`/`string()` parsers accept every token, so the literal words pass → overlap → warning.
 * By throwing for exactly those sibling-literal words, the overlap disappears and the warning is
 * suppressed.
 *
 * **This changes no runtime behavior.** Brigadier resolves a child by name first
 * (`CommandNode.getChild` checks the literal map before the argument nodes), so `replay off`
 * already hits the `off` literal and the argument's [parse] is never even reached for those words
 * — a player literally named "off" was already unreachable via `replay off` and stays so. Real
 * usernames / clip names (not in the banned set) parse exactly as `word()`/`string()` would, and
 * `StringArgumentType.getString(ctx, name)` still retrieves the result (it only casts the stored
 * `String`). The `.suggests(...)` provider chained on the builder at each call site is used at
 * runtime for tab-completion regardless of this type's own examples.
 */
class SafeStringArgument private constructor(
    private val quoted: Boolean,
    private val banned: Set<String>,
) : ArgumentType<String> {

    override fun parse(reader: StringReader): String {
        val start = reader.cursor
        val value = if (quoted) reader.readString() else reader.readUnquotedString()
        if (value.isNotEmpty() && banned.any { it.equals(value, ignoreCase = true) }) {
            reader.cursor = start
            throw REJECT.createWithContext(reader)
        }
        return value
    }

    // Non-banned example so getExamples() isn't empty (the default returns Collections.emptyList()).
    // Chosen to never collide with a banned literal word.
    override fun getExamples(): Collection<String> = EXAMPLES

    companion object {
        private val REJECT = SimpleCommandExceptionType(Message { "Reserved subcommand keyword" })
        private val EXAMPLES = listOf("player")

        /** Like [com.mojang.brigadier.arguments.StringArgumentType.word], but rejects [banned] words. */
        fun wordExcluding(vararg banned: String): SafeStringArgument =
            SafeStringArgument(quoted = false, banned = banned.toSet())

        /** Like [com.mojang.brigadier.arguments.StringArgumentType.string], but rejects [banned] words. */
        fun stringExcluding(vararg banned: String): SafeStringArgument =
            SafeStringArgument(quoted = true, banned = banned.toSet())
    }
}