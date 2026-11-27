package tech.codesky.dockdeploy.data.network

import android.content.Context
import android.content.SharedPreferences
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import java.util.concurrent.ConcurrentHashMap

@Serializable
private data class StoredCookie(
    val name: String,
    val value: String,
    val expiresAt: Long,
    val domain: String,
    val path: String,
    val secure: Boolean,
    val httpOnly: Boolean,
    val hostOnly: Boolean,
) {
    fun toOkHttpCookie(): Cookie {
        val builder = Cookie.Builder()
            .name(name)
            .value(value)
            .expiresAt(expiresAt)
            .path(path)
        if (hostOnly) builder.hostOnlyDomain(domain) else builder.domain(domain)
        if (secure) builder.secure()
        if (httpOnly) builder.httpOnly()
        return builder.build()
    }

    companion object {
        fun from(c: Cookie) = StoredCookie(
            name = c.name,
            value = c.value,
            expiresAt = c.expiresAt,
            domain = c.domain,
            path = c.path,
            secure = c.secure,
            httpOnly = c.httpOnly,
            hostOnly = c.hostOnly,
        )
    }
}

/**
 * Persists DockDeploy session cookies across app restarts so normal email/password/2FA
 * sign-in works like the web browser without requiring manual API tokens.
 */
class PersistentCookieJar(context: Context) : CookieJar {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("dockdeploy_cookies", Context.MODE_PRIVATE)

    private val json = Json { ignoreUnknownKeys = true }
    private val store = ConcurrentHashMap<String, MutableList<Cookie>>()

    init {
        loadFromDisk()
    }

    @Synchronized
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val hostKey = url.host.lowercase()
        val current = store.getOrPut(hostKey) { mutableListOf() }
        val now = System.currentTimeMillis()
        for (incoming in cookies) {
            current.removeAll { existing ->
                existing.name == incoming.name &&
                    existing.domain == incoming.domain &&
                    existing.path == incoming.path
            }
            if (incoming.expiresAt > now) {
                current.add(incoming)
            }
        }
        persistHost(hostKey, current)
    }

    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val hostKey = url.host.lowercase()
        val now = System.currentTimeMillis()
        val cookies = store[hostKey] ?: return emptyList()
        val expiredRemoved = cookies.removeAll { it.expiresAt <= now }
        if (expiredRemoved) {
            persistHost(hostKey, cookies)
        }
        return cookies.filter { it.matches(url) }
    }

    @Synchronized
    fun clearHost(host: String) {
        val hostKey = host.lowercase()
        store.remove(hostKey)
        prefs.edit().remove(hostKey).apply()
    }

    @Synchronized
    fun clearAll() {
        store.clear()
        prefs.edit().clear().apply()
    }

    private fun loadFromDisk() {
        val now = System.currentTimeMillis()
        for ((key, raw) in prefs.all) {
            val serialized = raw as? String ?: continue
            val parsed = runCatching { json.decodeFromString<List<StoredCookie>>(serialized) }
                .getOrNull() ?: continue
            val valid = parsed.map { it.toOkHttpCookie() }.filter { it.expiresAt > now }.toMutableList()
            if (valid.isNotEmpty()) {
                store[key] = valid
            }
        }
    }

    private fun persistHost(hostKey: String, cookies: List<Cookie>) {
        val encoded = json.encodeToString(cookies.map { StoredCookie.from(it) })
        prefs.edit().putString(hostKey, encoded).apply()
    }
}
