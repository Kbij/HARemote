package com.koen.haremote.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl

private val Context.dataStore by preferencesDataStore(name = "haremote_settings")

/**
 * Persists [AppSettings] as a single JSON blob in DataStore. Using JSON as the storage
 * format directly means export/import is just writing/reading that same string to a file.
 */
class SettingsRepository(private val context: Context) {

    private val settingsKey = stringPreferencesKey("app_settings_json")

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    val settingsFlow: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        val raw = prefs[settingsKey]
        if (raw.isNullOrBlank()) {
            AppSettings()
        } else {
            runCatching { decode(raw) }.getOrElse { AppSettings() }
        }
    }

    suspend fun save(settings: AppSettings) {
        context.dataStore.edit { prefs ->
            prefs[settingsKey] = json.encodeToString(settings)
        }
    }

    fun toJson(settings: AppSettings): String = json.encodeToString(settings)

    fun fromJson(text: String): Result<AppSettings> = runCatching { decode(text) }

    /**
     * Decodes a settings JSON blob, transparently upgrading the pre-[HostConfig] format
     * (every button carrying its own full `url`/`bearerToken`) to the current one. The
     * presence of a top-level "hosts" key is what tells the two formats apart - it never
     * existed before this change, so its absence is a reliable "this is an old export, or
     * whatever is still sitting in DataStore from before the app was updated" signal.
     */
    private fun decode(raw: String): AppSettings {
        val root = json.parseToJsonElement(raw).jsonObject
        return if (root.containsKey("hosts")) {
            json.decodeFromString<AppSettings>(raw)
        } else {
            migrateLegacy(root)
        }
    }

    /**
     * One-time upgrade path for settings exported/saved before [HostConfig] existed. Buttons
     * that shared the same origin (scheme+host+port) and bearer token are folded into a
     * single [HostConfig]; the longest URL-path prefix they have in common becomes that
     * host's [HostConfig.basePath], and each button keeps only what's left over as its
     * [ButtonConfig.path]. A button with a blank/unparsable `url` (e.g. the old "Volume"
     * placeholder button) is kept as-is with no host assigned - exactly matching its
     * previous, non-functional state, nothing lost.
     */
    private fun migrateLegacy(root: JsonObject): AppSettings {
        data class LegacyButton(
            val id: Int,
            val label: String,
            val icon: String,
            val url: String,
            val method: String,
            val body: String,
            val bearerToken: String
        )

        val legacyButtons = root["buttons"]?.jsonArray.orEmpty().map { element ->
            val obj = element.jsonObject
            LegacyButton(
                id = obj["id"]?.jsonPrimitive?.intOrNull ?: 0,
                label = obj["label"]?.jsonPrimitive?.contentOrNull ?: "",
                icon = obj["icon"]?.jsonPrimitive?.contentOrNull ?: ButtonIcon.POWER.name,
                url = obj["url"]?.jsonPrimitive?.contentOrNull ?: "",
                method = obj["method"]?.jsonPrimitive?.contentOrNull ?: HttpMethod.POST.name,
                body = obj["body"]?.jsonPrimitive?.contentOrNull ?: "",
                bearerToken = obj["bearerToken"]?.jsonPrimitive?.contentOrNull ?: ""
            )
        }

        fun originOf(url: String) = runCatching {
            val httpUrl = url.toHttpUrl()
            "${httpUrl.scheme}://${httpUrl.host}:${httpUrl.port}"
        }.getOrNull()

        fun pathSegmentsOf(url: String) = runCatching {
            url.toHttpUrl().encodedPathSegments.filter { it.isNotEmpty() }
        }.getOrDefault(emptyList())

        data class OriginKey(val origin: String, val token: String)

        val groups = LinkedHashMap<OriginKey, MutableList<LegacyButton>>()
        legacyButtons.forEach { button ->
            val origin = originOf(button.url) ?: return@forEach
            groups.getOrPut(OriginKey(origin, button.bearerToken)) { mutableListOf() }.add(button)
        }

        val hosts = mutableListOf<HostConfig>()
        val hostIdByKey = HashMap<OriginKey, Int>()
        groups.forEach { (key, buttons) ->
            val segmentLists = buttons.map { pathSegmentsOf(it.url) }
            val shortest = segmentLists.minOf { it.size }
            // Leave at least one segment per button as its own "path" - never claim the
            // *whole* thing as a shared basePath even if every button happens to match fully.
            var commonLength = 0
            while (commonLength < shortest - 1 &&
                segmentLists.all { it[commonLength] == segmentLists[0][commonLength] }
            ) {
                commonLength++
            }
            val basePath = segmentLists[0].take(commonLength).joinToString("/")
            val id = hosts.size + 1
            hosts += HostConfig(
                id = id,
                name = key.origin.substringAfter("://").substringBefore(":"),
                baseUrl = key.origin,
                basePath = basePath,
                bearerToken = key.token
            )
            hostIdByKey[key] = id
        }

        val newButtons = legacyButtons.map { legacy ->
            val icon = runCatching { ButtonIcon.valueOf(legacy.icon) }.getOrDefault(ButtonIcon.POWER)
            val method = runCatching { HttpMethod.valueOf(legacy.method) }.getOrDefault(HttpMethod.POST)
            val origin = originOf(legacy.url)
            val hostId = origin?.let { hostIdByKey[OriginKey(it, legacy.bearerToken)] }
            val host = hosts.firstOrNull { it.id == hostId }
            val baseSegments = host?.basePath?.split("/")?.filter { it.isNotBlank() } ?: emptyList()
            val segments = pathSegmentsOf(legacy.url)
            val path = if (segments.take(baseSegments.size) == baseSegments) {
                segments.drop(baseSegments.size).joinToString("/")
            } else {
                segments.joinToString("/")
            }
            ButtonConfig(
                id = legacy.id,
                label = legacy.label,
                icon = icon,
                hostId = hostId,
                path = path,
                method = method,
                body = legacy.body
            )
        }

        return AppSettings(
            hosts = hosts.ifEmpty { defaultHosts() },
            buttons = newButtons.ifEmpty { defaultButtons() },
            tcpServerHost = root["tcpServerHost"]?.jsonPrimitive?.contentOrNull ?: "",
            tcpServerPort = root["tcpServerPort"]?.jsonPrimitive?.intOrNull ?: AppSettings.DEFAULT_TCP_PORT,
            gpsLoggingEnabled = root["gpsLoggingEnabled"]?.jsonPrimitive?.booleanOrNull ?: false
        )
    }
}
