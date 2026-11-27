package tech.codesky.dockdeploy.data.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit

class ApiException(
    val code: String,
    override val message: String,
    val status: Int,
    val fields: Map<String, String> = emptyMap(),
) : IOException(message) {
    val isAuthFailure: Boolean get() = code == "unauthorized" || status == 401
    val requiresSudoPassword: Boolean get() = fields.containsKey("sudo_password")
}

@Serializable
private data class ErrorEnvelope(
    val error: ErrorDetail? = null,
)

@Serializable
private data class ErrorDetail(
    val code: String? = null,
    val message: String? = null,
    val fields: Map<String, String> = emptyMap(),
)

data class SseEvent(
    val type: String,
    val data: String,
)

/**
 * Dynamic host-aware HTTP, SSE, multipart, and WebSocket client for DockDeploy.
 *
 * Supports both cookie-based sessions (setting Origin/Referer for CSRF verification on
 * mutating routes) and optional Bearer API tokens.
 */
class ApiClient(
    val cookieJar: PersistentCookieJar,
) {
    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
        isLenient = true
    }

    @Volatile
    var baseUrl: String = ""
        private set

    @Volatile
    var origin: String = ""
        private set

    @Volatile
    var apiToken: String? = null

    fun setTargetHost(url: String) {
        val normalized = HostUrlNormalizer.normalize(url).getOrNull() ?: return
        baseUrl = normalized.baseUrl
        origin = normalized.origin
    }

    fun clearTargetHost() {
        baseUrl = ""
        origin = ""
    }

    private val client: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(cookieJar)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val original = chain.request()
            val builder = original.newBuilder()
            val token = apiToken
            if (!token.isNullOrBlank()) {
                builder.header("Authorization", "Bearer $token")
            } else if (origin.isNotBlank() && original.method !in setOf("GET", "HEAD", "OPTIONS")) {
                builder.header("Origin", origin)
                builder.header("Referer", "$origin/")
            }
            builder.header("Accept", "application/json")
            chain.proceed(builder.build())
        }
        .build()

    private val streamClient: OkHttpClient = client.newBuilder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(25, TimeUnit.SECONDS)
        .build()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    fun buildApiUrl(path: String, overrideBaseUrl: String? = null): String {
        val root = (overrideBaseUrl ?: baseUrl).trimEnd('/')
        if (root.isBlank()) throw ApiException("network_error", "No DockDeploy server URL configured.", 0)
        val cleanPath = if (path.startsWith("/")) path else "/$path"
        return "$root/api$cleanPath"
    }

    suspend inline fun <reified T> get(
        path: String,
        headers: Map<String, String> = emptyMap(),
        overrideBaseUrl: String? = null,
    ): T = executeJson("GET", path, null, headers, overrideBaseUrl)

    suspend inline fun <reified T> post(
        path: String,
        rawJsonBody: String? = null,
        headers: Map<String, String> = emptyMap(),
        overrideBaseUrl: String? = null,
    ): T = executeJsonTyped("POST", path, rawJsonBody, headers, overrideBaseUrl)

    suspend inline fun <reified B, reified T> postTyped(
        path: String,
        body: B,
        headers: Map<String, String> = emptyMap(),
        overrideBaseUrl: String? = null,
    ): T = executeJsonTyped("POST", path, json.encodeToString(body), headers, overrideBaseUrl)

    suspend inline fun <reified B, reified T> putTyped(
        path: String,
        body: B,
        headers: Map<String, String> = emptyMap(),
    ): T = executeJsonTyped("PUT", path, json.encodeToString(body), headers)

    suspend inline fun <reified B, reified T> patchTyped(
        path: String,
        body: B,
        headers: Map<String, String> = emptyMap(),
    ): T = executeJsonTyped("PATCH", path, json.encodeToString(body), headers)

    suspend inline fun <reified T> delete(
        path: String,
        headers: Map<String, String> = emptyMap(),
    ): T = executeJson("DELETE", path, null, headers)

    suspend inline fun <reified B, reified T> deleteTyped(
        path: String,
        body: B,
        headers: Map<String, String> = emptyMap(),
    ): T = executeJsonTyped("DELETE", path, json.encodeToString(body), headers)

    suspend inline fun <reified T> executeJson(
        method: String,
        path: String,
        rawJsonBody: String?,
        headers: Map<String, String> = emptyMap(),
        overrideBaseUrl: String? = null,
    ): T = executeJsonTyped(method, path, rawJsonBody, headers, overrideBaseUrl)

    suspend fun executeRaw(
        method: String,
        path: String,
        jsonBody: String?,
        headers: Map<String, String> = emptyMap(),
        overrideBaseUrl: String? = null,
    ): Pair<Int, String> = withContext(Dispatchers.IO) {
        val url = buildApiUrl(path, overrideBaseUrl)
        val reqBody = when {
            jsonBody != null -> jsonBody.toRequestBody("application/json; charset=utf-8".toMediaType())
            method in setOf("POST", "PUT", "PATCH") -> "{}".toRequestBody("application/json; charset=utf-8".toMediaType())
            else -> null
        }

        val reqBuilder = Request.Builder().url(url).method(method, reqBody)
        for ((k, v) in headers) {
            reqBuilder.header(k, v)
        }

        val response = try {
            client.newCall(reqBuilder.build()).execute()
        } catch (e: IOException) {
            throw ApiException("network_error", e.message ?: "Could not reach DockDeploy server.", 0)
        }

        response.use { res ->
            val raw = res.body?.string().orEmpty()
            if (!res.isSuccessful) {
                throw parseError(res.code, raw)
            }
            res.code to raw
        }
    }

    suspend inline fun <reified T> executeJsonTyped(
        method: String,
        path: String,
        jsonBody: String?,
        headers: Map<String, String> = emptyMap(),
        overrideBaseUrl: String? = null,
    ): T {
        val (code, raw) = executeRaw(method, path, jsonBody, headers, overrideBaseUrl)
        if (T::class == Unit::class || code == 204 || raw.isBlank()) {
            @Suppress("UNCHECKED_CAST")
            return Unit as T
        }
        return json.decodeFromString<T>(raw)
    }

    suspend fun postMultipartRaw(
        path: String,
        fileName: String,
        bytes: ByteArray,
        fields: Map<String, String> = emptyMap(),
    ): String = withContext(Dispatchers.IO) {
        val url = buildApiUrl(path)
        val multipartBuilder = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart(
                "file",
                fileName,
                bytes.toRequestBody("application/octet-stream".toMediaType()),
            )
        for ((k, v) in fields) {
            multipartBuilder.addFormDataPart(k, v)
        }
        val req = Request.Builder().url(url).post(multipartBuilder.build()).build()
        val response = try {
            streamClient.newCall(req).execute()
        } catch (e: IOException) {
            throw ApiException("network_error", e.message ?: "Upload failed.", 0)
        }
        response.use { res ->
            val raw = res.body?.string().orEmpty()
            if (!res.isSuccessful) throw parseError(res.code, raw)
            raw
        }
    }

    suspend inline fun <reified T> postMultipart(
        path: String,
        fileName: String,
        bytes: ByteArray,
        fields: Map<String, String> = emptyMap(),
    ): T {
        val raw = postMultipartRaw(path, fileName, bytes, fields)
        return json.decodeFromString<T>(raw)
    }

    suspend fun downloadToStream(
        path: String,
        output: OutputStream,
        headers: Map<String, String> = emptyMap(),
    ): String? = withContext(Dispatchers.IO) {
        val url = buildApiUrl(path)
        val reqBuilder = Request.Builder().url(url).get()
        headers.forEach { (k, v) -> reqBuilder.header(k, v) }
        val response = try {
            streamClient.newCall(reqBuilder.build()).execute()
        } catch (e: IOException) {
            throw ApiException("network_error", e.message ?: "Download failed.", 0)
        }
        response.use { res ->
            if (!res.isSuccessful) {
                throw parseError(res.code, res.body?.string().orEmpty())
            }
            val bodyStream: InputStream = res.body?.byteStream()
                ?: throw ApiException("internal_error", "Empty download stream.", res.code)
            bodyStream.use { input ->
                input.copyTo(output)
            }
            res.header("Content-Disposition")
        }
    }

    fun streamSse(
        path: String,
        method: String = "GET",
        jsonBody: String? = null,
    ): Flow<SseEvent> = callbackFlow {
        val url = buildApiUrl(path)
        val reqBuilder = Request.Builder()
            .url(url)
            .header("Accept", "text/event-stream")
            .header("Cache-Control", "no-cache")

        if (method == "POST") {
            reqBuilder.post((jsonBody ?: "{}").toRequestBody(jsonMediaType))
        } else {
            reqBuilder.get()
        }

        val factory = EventSources.createFactory(streamClient)
        val eventSource = factory.newEventSource(
            reqBuilder.build(),
            object : EventSourceListener() {
                override fun onEvent(
                    eventSource: EventSource,
                    id: String?,
                    type: String?,
                    data: String,
                ) {
                    trySend(SseEvent(type = type ?: "message", data = data))
                }

                override fun onClosed(eventSource: EventSource) {
                    close()
                }

                override fun onFailure(
                    eventSource: EventSource,
                    t: Throwable?,
                    response: Response?,
                ) {
                    if (response != null && !response.isSuccessful) {
                        val raw = runCatching { response.body?.string().orEmpty() }.getOrDefault("")
                        close(parseError(response.code, raw))
                    } else if (t != null) {
                        close(ApiException("network_error", t.message ?: "Stream disconnected.", 0))
                    } else {
                        close()
                    }
                }
            },
        )
        awaitClose { eventSource.cancel() }
    }

    fun openTerminalWebSocket(
        serverId: String,
        cols: Int,
        rows: Int,
        listener: WebSocketListener,
    ): WebSocket {
        val wsUrl = HostUrlNormalizer.toWebSocketUrl(baseUrl, "/servers/$serverId/terminal?cols=$cols&rows=$rows")
        val req = Request.Builder().url(wsUrl).build()
        return streamClient.newWebSocket(req, listener)
    }

    fun parseError(status: Int, rawBody: String): ApiException {
        val envelope = runCatching { json.decodeFromString<ErrorEnvelope>(rawBody) }.getOrNull()
        val detail = envelope?.error
        return ApiException(
            code = detail?.code ?: when (status) {
                401 -> "unauthorized"
                403 -> "forbidden"
                404 -> "not_found"
                422 -> "validation_failed"
                429 -> "rate_limited"
                else -> "internal_error"
            },
            message = detail?.message ?: "Request failed with HTTP $status.",
            status = status,
            fields = detail?.fields ?: emptyMap(),
        )
    }
}
