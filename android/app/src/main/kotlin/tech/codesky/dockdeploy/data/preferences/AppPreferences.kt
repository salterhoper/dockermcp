package tech.codesky.dockdeploy.data.preferences

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.dataStore by preferencesDataStore(name = "dockdeploy_prefs")

enum class ThemeMode {
    DARK,
    LIGHT,
    SYSTEM,
}

@Serializable
data class SavedHost(
    val url: String,
    val label: String = "",
    val version: String = "",
    val lastConnectedAt: Long = 0L,
)

class AppPreferences(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true }

    private object Keys {
        val ACTIVE_HOST_URL = stringPreferencesKey("active_host_url")
        val SAVED_HOSTS = stringPreferencesKey("saved_hosts")
        val API_TOKEN = stringPreferencesKey("api_token")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
    }

    val activeHostUrl: Flow<String?> = context.dataStore.data.map { it[Keys.ACTIVE_HOST_URL] }

    val savedHosts: Flow<List<SavedHost>> = context.dataStore.data.map { prefs ->
        val raw = prefs[Keys.SAVED_HOSTS] ?: return@map emptyList()
        runCatching { json.decodeFromString<List<SavedHost>>(raw) }.getOrDefault(emptyList())
    }

    val apiToken: Flow<String?> = context.dataStore.data.map { it[Keys.API_TOKEN] }

    val themeMode: Flow<ThemeMode> = context.dataStore.data.map { prefs ->
        val raw = prefs[Keys.THEME_MODE]
        ThemeMode.entries.firstOrNull { it.name == raw } ?: ThemeMode.DARK
    }

    val useDynamicColor: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.DYNAMIC_COLOR] ?: false
    }

    suspend fun setActiveHost(url: String, version: String = "") {
        context.dataStore.edit { prefs ->
            prefs[Keys.ACTIVE_HOST_URL] = url
            val existing = prefs[Keys.SAVED_HOSTS]?.let {
                runCatching { json.decodeFromString<List<SavedHost>>(it) }.getOrNull()
            } ?: emptyList()
            val updated = listOf(
                SavedHost(
                    url = url,
                    label = url.removePrefix("https://").removePrefix("http://"),
                    version = version.ifBlank { existing.firstOrNull { it.url == url }?.version.orEmpty() },
                    lastConnectedAt = System.currentTimeMillis(),
                ),
            ) + existing.filterNot { it.url == url }
            prefs[Keys.SAVED_HOSTS] = json.encodeToString(updated.take(8))
        }
    }

    suspend fun removeSavedHost(url: String) {
        context.dataStore.edit { prefs ->
            val existing = prefs[Keys.SAVED_HOSTS]?.let {
                runCatching { json.decodeFromString<List<SavedHost>>(it) }.getOrNull()
            } ?: emptyList()
            val updated = existing.filterNot { it.url == url }
            prefs[Keys.SAVED_HOSTS] = json.encodeToString(updated)
            if (prefs[Keys.ACTIVE_HOST_URL] == url) {
                prefs.remove(Keys.ACTIVE_HOST_URL)
            }
        }
    }

    suspend fun clearActiveHost() {
        context.dataStore.edit { it.remove(Keys.ACTIVE_HOST_URL) }
    }

    suspend fun setApiToken(token: String?) {
        context.dataStore.edit { prefs ->
            if (token.isNullOrBlank()) {
                prefs.remove(Keys.API_TOKEN)
            } else {
                prefs[Keys.API_TOKEN] = token.trim()
            }
        }
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.dataStore.edit { it[Keys.THEME_MODE] = mode.name }
    }

    suspend fun setDynamicColor(enabled: Boolean) {
        context.dataStore.edit { it[Keys.DYNAMIC_COLOR] = enabled }
    }
}
