package tech.codesky.dockdeploy.data.network

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Normalizes user-entered DockDeploy server addresses into canonical base URLs.
 *
 * Public domains like "dc.codesky.tech" default to HTTPS, while local/LAN hosts
 * (localhost, 127.0.0.1, 10.x, 192.168.x, 172.16-31.x, or *.local) default to HTTP
 * when no scheme is supplied.
 */
object HostUrlNormalizer {

    data class Result(
        val baseUrl: String,
        val origin: String,
        val httpUrl: HttpUrl,
    )

    fun normalize(rawInput: String): kotlin.Result<Result> {
        val trimmed = rawInput.trim().trimEnd('/')
        if (trimmed.isEmpty()) {
            return kotlin.Result.failure(IllegalArgumentException("Enter a DockDeploy host URL, e.g. dc.codesky.tech"))
        }

        val withScheme = if (trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true)
        ) {
            trimmed
        } else {
            val hostPart = trimmed.substringBefore('/').substringBefore(':')
            val scheme = if (isLocalOrPrivateHost(hostPart)) "http" else "https"
            "$scheme://$trimmed"
        }

        val parsed = withScheme.toHttpUrlOrNull()
            ?: return kotlin.Result.failure(IllegalArgumentException("Invalid host URL. Example: dc.codesky.tech"))

        if (parsed.host.isBlank()) {
            return kotlin.Result.failure(IllegalArgumentException("Host name cannot be empty."))
        }

        val canonical = parsed.newBuilder()
            .query(null)
            .fragment(null)
            .build()
            .toString()
            .trimEnd('/')

        val defaultPort = if (parsed.isHttps) 443 else 80
        val portSuffix = if (parsed.port != defaultPort) ":${parsed.port}" else ""
        val origin = "${parsed.scheme}://${parsed.host}$portSuffix"

        return kotlin.Result.success(
            Result(
                baseUrl = canonical,
                origin = origin,
                httpUrl = parsed,
            ),
        )
    }

    fun toWebSocketUrl(baseUrl: String, apiPath: String): String {
        val cleanBase = baseUrl.trimEnd('/')
        val wsBase = when {
            cleanBase.startsWith("https://", ignoreCase = true) ->
                "wss://" + cleanBase.removePrefix("https://").removePrefix("HTTPS://")
            cleanBase.startsWith("http://", ignoreCase = true) ->
                "ws://" + cleanBase.removePrefix("http://").removePrefix("HTTP://")
            else -> "wss://$cleanBase"
        }
        val path = if (apiPath.startsWith("/")) apiPath else "/$apiPath"
        return "$wsBase/api$path"
    }

    private fun isLocalOrPrivateHost(host: String): Boolean {
        val lower = host.lowercase()
        if (lower == "localhost" || lower == "127.0.0.1" || lower == "10.0.2.2" || lower.endsWith(".local")) {
            return true
        }
        if (lower.startsWith("10.") || lower.startsWith("192.168.")) return true
        if (lower.startsWith("172.")) {
            val second = lower.removePrefix("172.").substringBefore('.').toIntOrNull()
            if (second != null && second in 16..31) return true
        }
        return false
    }
}
