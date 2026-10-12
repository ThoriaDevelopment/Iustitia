package dev.iustitia.config

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.ByteArrayOutputStream
import java.io.StringReader
import java.util.Base64
import java.util.zip.DataFormatException
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * The shareable-string form of a preset (`/ius presets export` / `/ius presets import`).
 *
 * ## Format
 *
 * `ius1.` + base64url (no padding) of a raw-DEFLATE of the minified JSON
 * `{"name":"<preset name>","preset":{<delta>}}`. The delta is the preset content
 * ([ConfigManager.presetContentObj]) with every field that equals the shipped default removed, and
 * per-check slices trimmed to the sub-fields that differ. `configVersion` is always kept: it is the
 * calibration-migration stamp, so a string exported before a recalibration round still has its
 * stale calibration reset on import, exactly as a stale preset file does.
 *
 * The delta is what keeps the string short. A full preset's content is about 4 KB of JSON, while a
 * typical tuned preset differs from the defaults in a handful of fields. Omitted fields mean "the
 * default", so an import expands the delta onto a fresh [IustitiaConfig] and writes the full preset
 * content to disk. The saved file then behaves exactly like one made with `/ius createpreset`.
 *
 * ## Untrusted input
 *
 * [decode] is the only entry point for a pasted string and never throws. It returns
 * [Decoded.Failure] with a reason for the user. In order, it:
 *
 *  - rejects an encoded string longer than [MAX_ENCODED_CHARS] before decoding anything;
 *  - caps the inflated payload at [MAX_DECODED_BYTES], which stops a deflate bomb from allocating
 *    without bound;
 *  - parses strictly (Gson's default parser is lenient and accepts `NaN`, comments and unquoted
 *    strings);
 *  - validates the preset against the schema, derived from [ConfigManager.presetContentObj] of a
 *    default config, so a new config field becomes importable with no second list to maintain.
 *    Every key must be a known preset-content key, every value must have the template's JSON
 *    type, integer fields must be whole numbers, and numbers must be finite;
 *  - checks the name with [validName]. A name that would need sanitizing, such as `../evil`, is
 *    rejected, not rewritten.
 *
 * Built-in and existing-name refusals, and the path resolution, live in [PresetManager.saveImported].
 * Nothing here touches disk or Minecraft, so the whole codec is covered by pure-JVM tests.
 */
object PresetShare {

    const val PREFIX = "ius1."

    /** Bounds the pasted string before any decoding work. Comfortably above the largest real
     *  export (the `debug` built-in, every field changed, is under 1 KB encoded). */
    const val MAX_ENCODED_CHARS = 8192

    /** Bounds the inflated JSON. A full, non-delta preset is about 4 KB, so 32 KB is generous and
     *  still blocks a deflate bomb. */
    const val MAX_DECODED_BYTES = 32 * 1024

    /** Longest preset name an import accepts. */
    const val MAX_NAME_LENGTH = 48

    private val NAME_RE = Regex("[A-Za-z0-9_-][A-Za-z0-9_.-]*")

    private val gson = Gson()

    sealed class Decoded {
        data class Success(val name: String, val config: IustitiaConfig) : Decoded()
        data class Failure(val reason: String) : Decoded()
    }

    /** True for a name an import may write as `<name>.json`: `[A-Za-z0-9_.-]`, not starting with a
     *  dot (no hidden files, no `..`), at most [MAX_NAME_LENGTH] characters. Stricter than
     *  PresetManager's `safeName`, which rewrites bad characters. An imported name comes from
     *  someone else, so it is checked rather than silently changed. */
    fun validName(name: String): Boolean = name.length <= MAX_NAME_LENGTH && NAME_RE.matches(name)

    /** Encode [config]'s preset content under [name]. Fail-open: a serializer failure yields null. */
    fun encode(name: String, config: IustitiaConfig): String? = try {
        val delta = diff(ConfigManager.presetContentObj(config), ConfigManager.presetContentObj(IustitiaConfig()))
        val payload = JsonObject().apply {
            addProperty("name", name)
            add("preset", delta)
        }
        PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(deflate(gson.toJson(payload).toByteArray(Charsets.UTF_8)))
    } catch (_: Throwable) {
        null
    }

    /** Decode and validate a pasted string. Never throws. */
    fun decode(input: String): Decoded {
        val text = input.trim()
        if (text.length > MAX_ENCODED_CHARS) return Decoded.Failure("string is too long (${text.length} chars, limit $MAX_ENCODED_CHARS)")
        if (!text.startsWith(PREFIX)) return Decoded.Failure("not an Iustitia preset string (it should start with $PREFIX)")
        return try {
            val compressed = try {
                Base64.getUrlDecoder().decode(text.substring(PREFIX.length))
            } catch (_: IllegalArgumentException) {
                return Decoded.Failure("string is damaged (bad characters; was it cut off when copied?)")
            }
            val json = inflate(compressed) ?: return Decoded.Failure("string is damaged or decodes to more than ${MAX_DECODED_BYTES / 1024} KB")
            val root = parseStrict(json) ?: return Decoded.Failure("string is damaged (the decoded preset isn't valid JSON)")
            if (!root.isJsonObject) return Decoded.Failure("string doesn't contain a preset")
            val obj = root.asJsonObject

            val nameEl = obj.get("name")
            if (nameEl == null || !nameEl.isJsonPrimitive || !nameEl.asJsonPrimitive.isString) return Decoded.Failure("string has no preset name")
            val name = nameEl.asString
            if (!validName(name)) return Decoded.Failure("preset name '${name.take(MAX_NAME_LENGTH)}' isn't allowed (letters, digits, _ . - only, not starting with a dot, at most $MAX_NAME_LENGTH chars)")

            val presetEl = obj.get("preset")
            if (presetEl == null || !presetEl.isJsonObject) return Decoded.Failure("string has no preset content")
            val preset = presetEl.asJsonObject
            schemaError(preset, ConfigManager.presetContentObj(IustitiaConfig()), "")?.let { return Decoded.Failure(it) }

            // The schema check above makes this read safe; the try is a backstop.
            val scratch = IustitiaConfig()
            ConfigManager.configFromJsonInto(preset, scratch)
            Decoded.Success(name, scratch)
        } catch (_: Throwable) {
            Decoded.Failure("string couldn't be read")
        }
    }

    // ---- internals ----

    /** [actual] minus everything equal to [defaults]. Nested objects (the per-check slices) diff
     *  field by field, and drop out entirely when nothing in them changed. `configVersion` always
     *  stays (see the class doc). */
    private fun diff(actual: JsonObject, defaults: JsonObject): JsonObject {
        val out = JsonObject()
        for ((key, value) in actual.entrySet()) {
            val def = defaults.get(key)
            when {
                key == "configVersion" -> out.add(key, value)
                value.isJsonObject && def != null && def.isJsonObject -> {
                    val sub = diff(value.asJsonObject, def.asJsonObject)
                    if (sub.size() > 0) out.add(key, sub)
                }
                !sameValue(value, def) -> out.add(key, value)
            }
        }
        return out
    }

    /** Numeric equality by value, so `1` and `1.0` compare equal; structural otherwise. */
    private fun sameValue(a: JsonElement, b: JsonElement?): Boolean {
        if (b == null) return false
        if (a.isJsonPrimitive && b.isJsonPrimitive && a.asJsonPrimitive.isNumber && b.asJsonPrimitive.isNumber) {
            return a.asDouble == b.asDouble
        }
        return a == b
    }

    /** The first schema violation in [obj] against [template], or null when it conforms. */
    private fun schemaError(obj: JsonObject, template: JsonObject, path: String): String? {
        for ((key, value) in obj.entrySet()) {
            val where = if (path.isEmpty()) key else "$path.$key"
            val expected = template.get(key) ?: return "unknown setting '$where'"
            when {
                expected.isJsonObject -> {
                    if (!value.isJsonObject) return "'$where' should be a group of settings"
                    schemaError(value.asJsonObject, expected.asJsonObject, where)?.let { return it }
                }
                expected.isJsonPrimitive -> {
                    if (!value.isJsonPrimitive) return "'$where' has the wrong type"
                    primitiveError(value.asJsonPrimitive, expected.asJsonPrimitive, where)?.let { return it }
                }
                // Arrays only appear under excluded per-user keys, which are not in the template.
                else -> return "'$where' has an unsupported type"
            }
        }
        return null
    }

    private fun primitiveError(v: JsonPrimitive, t: JsonPrimitive, where: String): String? = when {
        t.isBoolean -> if (v.isBoolean) null else "'$where' should be true or false"
        t.isString -> if (v.isString) null else "'$where' should be text"
        t.isNumber -> {
            if (!v.isNumber) {
                "'$where' should be a number"
            } else {
                val d = v.asDouble
                val integral = t.asNumber.let { it is Int || it is Long || it is Short || it is Byte }
                when {
                    !d.isFinite() -> "'$where' isn't a finite number"
                    integral && (d != Math.rint(d) || d > Int.MAX_VALUE || d < Int.MIN_VALUE) -> "'$where' should be a whole number"
                    else -> null
                }
            }
        }
        else -> "'$where' has an unsupported type"
    }

    /** Strict single-value JSON parse: rejects lenient syntax (NaN, comments, unquoted strings) and
     *  trailing content. Null on any error. */
    private fun parseStrict(json: String): JsonElement? = try {
        JsonReader(StringReader(json)).use { reader ->
            reader.isLenient = false
            val el = gson.getAdapter(JsonElement::class.java).read(reader)
            if (reader.peek() != JsonToken.END_DOCUMENT) null else el
        }
    } catch (_: Throwable) {
        null
    }

    private fun deflate(bytes: ByteArray): ByteArray {
        val d = Deflater(Deflater.BEST_COMPRESSION, true)
        try {
            d.setInput(bytes)
            d.finish()
            val out = ByteArrayOutputStream()
            val buf = ByteArray(1024)
            while (!d.finished()) out.write(buf, 0, d.deflate(buf))
            return out.toByteArray()
        } finally {
            d.end()
        }
    }

    /** Inflate raw DEFLATE, refusing output past [MAX_DECODED_BYTES] and truncated streams.
     *  Null on any failure. */
    private fun inflate(bytes: ByteArray): String? {
        val inf = Inflater(true)
        try {
            // A nowrap Inflater may need one byte past the end of the stream (Inflater's own docs).
            inf.setInput(bytes + 0)
            val out = ByteArrayOutputStream()
            val buf = ByteArray(1024)
            while (!inf.finished()) {
                val n = inf.inflate(buf)
                if (n == 0 && (inf.needsInput() || inf.needsDictionary())) return null // truncated / corrupt
                out.write(buf, 0, n)
                if (out.size() > MAX_DECODED_BYTES) return null
            }
            return out.toString(Charsets.UTF_8)
        } catch (_: DataFormatException) {
            return null
        } finally {
            inf.end()
        }
    }
}
