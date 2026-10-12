package dev.iustitia.config

import com.google.gson.Gson
import com.google.gson.JsonObject
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.Deflater
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Pure-JVM tests for the preset share string ([PresetShare]): the encode/decode round trip, and
 * every way a pasted string must be refused. No Minecraft/Fabric objects and no disk. The
 * built-in, existing-name and path checks on the save side live in [PresetManager.saveImported],
 * which needs the Fabric game dir.
 */
class PresetShareTest {

    /** Preset content as JSON, the thing a round trip has to preserve exactly. */
    private fun content(c: IustitiaConfig): JsonObject = ConfigManager.presetContentObj(c)

    private fun roundTrip(name: String, c: IustitiaConfig): PresetShare.Decoded.Success {
        val s = assertNotNull(PresetShare.encode(name, c))
        assertTrue(s.startsWith(PresetShare.PREFIX))
        return assertIs<PresetShare.Decoded.Success>(PresetShare.decode(s), "round trip of '$name' failed: ${PresetShare.decode(s)}")
    }

    /** Encode an arbitrary payload the way [PresetShare.encode] does, to forge hostile strings. */
    private fun forge(payload: String): String {
        val d = Deflater(Deflater.BEST_COMPRESSION, true)
        d.setInput(payload.toByteArray(Charsets.UTF_8)); d.finish()
        val out = ByteArrayOutputStream(); val buf = ByteArray(4096)
        while (!d.finished()) out.write(buf, 0, d.deflate(buf))
        d.end()
        return PresetShare.PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(out.toByteArray())
    }

    private fun failure(s: String): String =
        assertIs<PresetShare.Decoded.Failure>(PresetShare.decode(s), "expected '$s' to be refused").reason

    @Test
    fun `a tuned preset round-trips every preset-content value`() {
        val tuned = IustitiaConfig().apply {
            alertLevel = 2
            audioCues = true
            audioVolume = 0.35
            joinGraceTicks = 40
            clipChunkRadius = 5
            playclipMode = IustitiaConfig.PlayclipMode.LEGACY // excluded key: must not travel
            slice("reach").setbackVL = 3.25
            slice("reach").threshold = 3.4
            slice("killAura").enabled = false
            slice("timerRate").decay = 0.125
        }
        val decoded = roundTrip("myTune", tuned)
        assertEquals("myTune", decoded.name)
        assertEquals(content(tuned), content(decoded.config))
        assertEquals(IustitiaConfig().playclipMode, decoded.config.playclipMode)
    }

    @Test
    fun `every built-in round-trips`() {
        for (name in PresetManager.builtInNames) {
            val template = PresetManager.builtIn(name)!!
            assertEquals(content(template), content(roundTrip(name, template).config), "built-in '$name'")
        }
    }

    @Test
    fun `string sizes`() {
        // The issue asks how big a share string comes out. Printed so the PR can quote it, and
        // bounded so a schema change that blows the size up is noticed.
        val full = Gson().toJson(content(IustitiaConfig())).length
        val sizes = PresetManager.builtInNames.associateWith { PresetShare.encode(it, PresetManager.builtIn(it)!!)!!.length }
        val small = PresetShare.encode("t", IustitiaConfig().apply { alertLevel = 2; slice("reach").setbackVL = 3.0 })!!.length
        println("[PresetShareTest] full preset JSON: $full chars; defaults+2 changes: $small; built-ins: $sizes")
        assertTrue(small <= 236, "a two-field preset ($small chars) no longer fits in the chat box with the command")
        for ((name, n) in sizes) assertTrue(n < PresetShare.MAX_ENCODED_CHARS / 2, "'$name' encodes to $n chars")
    }

    @Test
    fun `surrounding whitespace from a paste is ignored`() {
        val s = PresetShare.encode("ws", IustitiaConfig())!!
        assertIs<PresetShare.Decoded.Success>(PresetShare.decode("  $s\n"))
    }

    @Test
    fun `malformed strings are refused`() {
        failure("")
        failure("hello")
        failure("${PresetShare.PREFIX}!!!not base64!!!")
        failure("${PresetShare.PREFIX}AAAA")                              // decodes, isn't deflate
        val good = PresetShare.encode("x", PresetManager.builtIn("strict")!!)!!
        failure(good.substring(0, good.length / 2))                     // cut off mid-paste
        failure(forge("not json"))
        failure(forge("[1,2,3]"))
        failure(forge("""{"name":"x","preset":{"alertLevel":NaN}}"""))  // lenient-only syntax
        failure(forge("""{"name":"x","preset":{}} trailing"""))
        failure(forge("""{"preset":{}}"""))
        failure(forge("""{"name":"x"}"""))
    }

    @Test
    fun `oversized input is refused before decoding`() {
        val reason = failure(PresetShare.PREFIX + "A".repeat(PresetShare.MAX_ENCODED_CHARS))
        assertTrue("too long" in reason, reason)
    }

    @Test
    fun `a deflate bomb is refused`() {
        // ~1 MB of whitespace compresses to a string well under the encoded limit.
        val bomb = forge("""{"name":"x","preset":{}""" + " ".repeat(1_000_000) + "}")
        assertTrue(bomb.length <= PresetShare.MAX_ENCODED_CHARS, "test bomb is ${bomb.length} chars")
        failure(bomb)
    }

    @Test
    fun `payloads that break the schema are refused`() {
        fun preset(body: String) = forge("""{"name":"x","preset":{$body}}""")
        failure(preset(""""notASetting":true"""))
        failure(preset(""""alertLevel":"two""""))
        failure(preset(""""alertLevel":1.5"""))                           // int field
        failure(preset(""""alertLevel":1e300"""))
        failure(preset(""""audioCues":1"""))
        failure(preset(""""playclipMode":"LEGACY""""))                    // excluded per-user key
        failure(preset(""""mutedPlayers":["a"]"""))                       // excluded per-user key
        failure(preset(""""reach":true"""))
        failure(preset(""""reach":{"setbackVL":"high"}"""))
        failure(preset(""""reach":{"bogus":1}"""))
        // and the same shape with correct types passes
        assertIs<PresetShare.Decoded.Success>(PresetShare.decode(preset(""""alertLevel":2,"reach":{"setbackVL":3}""")))
    }

    @Test
    fun `traversal and bad names are refused, not rewritten`() {
        for (bad in listOf("../evil", "..", ".hidden", "a/b", "a\\b", "", "with space", "x".repeat(PresetShare.MAX_NAME_LENGTH + 1), "§cred")) {
            assertFalse(PresetShare.validName(bad), "validName accepted '$bad'")
            val reason = failure(forge(Gson().toJson(JsonObject().apply { addProperty("name", bad); add("preset", JsonObject()) })))
            assertTrue("name" in reason, "'$bad' was refused for the wrong reason: $reason")
        }
        for (good in listOf("myTune", "pvp-1.2", "_x", "a".repeat(PresetShare.MAX_NAME_LENGTH))) {
            assertTrue(PresetShare.validName(good), "validName refused '$good'")
        }
    }

    @Test
    fun `omitted fields import as the defaults, not as the importer's live values`() {
        // A delta string only carries what differs from the defaults. The import must expand it
        // onto a fresh config, or the saved preset would silently depend on the recipient's tuning.
        val decoded = assertIs<PresetShare.Decoded.Success>(PresetShare.decode(forge("""{"name":"x","preset":{"alertLevel":2}}""")))
        val expected = IustitiaConfig().apply { alertLevel = 2 }
        assertEquals(content(expected), content(decoded.config))
    }
}
