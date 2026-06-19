package dev.iustitia.config

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.iustitia.Iustitia
import net.fabricmc.loader.api.FabricLoader
import java.nio.file.Files
import java.nio.file.Path

/**
 * Loads/saves [IustitiaConfig] to `config/iustitia.json`. Hand-rolled through Gson's
 * JsonObject so we stay independent of the kotlinx.serialization compiler plugin and
 * keep Kotlin default-arg constructors out of the deserialization path.
 *
 * Everything is fail-open: a corrupt or partial file falls back to defaults and is
 * overwritten on the next save.
 */
object ConfigManager {

    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
    private val path: Path by lazy {
        FabricLoader.getInstance().configDir.resolve("iustitia.json")
    }

    @Volatile
    var config: IustitiaConfig = IustitiaConfig()
        private set

    fun load() {
        config = try {
            if (Files.exists(path)) {
                val obj = JsonParser.parseString(Files.readString(path)).asJsonObject
                fromJson(obj)
            } else {
                IustitiaConfig()
            }
        } catch (_: Throwable) {
            IustitiaConfig()
        }
    }

    fun save() {
        try {
            Files.createDirectories(path.parent)
            Files.writeString(path, gson.toJson(toJson(config)))
        } catch (_: Throwable) {
            // best-effort; never crash the client over a config write
        }
    }

    fun reload() {
        load()
        Iustitia.onConfigReloaded()
    }

    private fun toJson(c: IustitiaConfig): JsonObject = JsonObject().apply {
        addProperty("enabled", c.enabled)
        addProperty("verbose", c.verbose)
        addProperty("alertThrottleTicks", c.alertThrottleTicks)
        addProperty("joinGraceTicks", c.joinGraceTicks)
        addProperty("legitScaffoldStrictGates", c.legitScaffoldStrictGates)
        addProperty("alertsEnabled", c.alertsEnabled)
        addProperty("nametagPrefixes", c.nametagPrefixes)
        addProperty("nametagGreenEnabled", c.nametagGreenEnabled)
        add("mutedChecks", JsonArray().apply { c.mutedChecks.forEach { add(it) } })
        add("mutedPlayers", JsonArray().apply { c.mutedPlayers.forEach { add(it) } })
        for ((key, cc) in c.checks()) add(key, checkToJson(cc))
    }

    private fun checkToJson(cc: IustitiaConfig.CheckConfig): JsonObject = JsonObject().apply {
        addProperty("enabled", cc.enabled)
        addProperty("setbackVL", cc.setbackVL)
        addProperty("decay", cc.decay)
        addProperty("threshold", cc.threshold)
    }

    private fun fromJson(o: JsonObject): IustitiaConfig {
        val c = IustitiaConfig()
        try {
            if (o.has("enabled")) c.enabled = o.get("enabled").asBoolean
            if (o.has("verbose")) c.verbose = o.get("verbose").asBoolean
            if (o.has("alertThrottleTicks")) c.alertThrottleTicks = o.get("alertThrottleTicks").asInt
            if (o.has("joinGraceTicks")) c.joinGraceTicks = o.get("joinGraceTicks").asInt
            if (o.has("legitScaffoldStrictGates")) c.legitScaffoldStrictGates = o.get("legitScaffoldStrictGates").asBoolean
            if (o.has("alertsEnabled")) c.alertsEnabled = o.get("alertsEnabled").asBoolean
            if (o.has("nametagPrefixes")) c.nametagPrefixes = o.get("nametagPrefixes").asBoolean
            if (o.has("nametagGreenEnabled")) c.nametagGreenEnabled = o.get("nametagGreenEnabled").asBoolean
            readStringList(o, "mutedChecks", c.mutedChecks)
            readStringList(o, "mutedPlayers", c.mutedPlayers)
            for ((key, cc) in c.checks()) {
                if (o.has(key)) readCheck(o.getAsJsonObject(key), cc)
            }
        } catch (_: Throwable) {
            // partial parse → keep defaults for anything unread
        }
        return c
    }

    private fun readCheck(o: JsonObject, cc: IustitiaConfig.CheckConfig) {
        try {
            if (o.has("enabled")) cc.enabled = o.get("enabled").asBoolean
            if (o.has("setbackVL")) cc.setbackVL = o.get("setbackVL").asDouble
            if (o.has("decay")) cc.decay = o.get("decay").asDouble
            if (o.has("threshold")) cc.threshold = o.get("threshold").asDouble
        } catch (_: Throwable) {
            // ignore per-field errors
        }
    }

    private fun readStringList(o: JsonObject, key: String, out: MutableList<String>) {
        try {
            if (!o.has(key)) return
            val arr = o.getAsJsonArray(key) ?: return
            out.clear()
            arr.forEach { e -> if (e != null && !e.isJsonNull) out.add(e.asString) }
        } catch (_: Throwable) {
            // partial parse → keep what we have
        }
    }
}