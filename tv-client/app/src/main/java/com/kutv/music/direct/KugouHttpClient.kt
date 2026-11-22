package com.kutv.music.direct

import java.io.IOException
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

enum class KugouNetworkErrorKind {
    TIMEOUT,
    NETWORK,
    HTTP,
    DATA,
    SESSION_EXPIRED,
}

class KugouNetworkException(
    val kind: KugouNetworkErrorKind,
    val statusCode: Int? = null,
    cause: Throwable? = null,
) : Exception(
    when (kind) {
        KugouNetworkErrorKind.TIMEOUT -> "酷狗请求超时"
        KugouNetworkErrorKind.SESSION_EXPIRED -> "酷狗登录已失效"
        KugouNetworkErrorKind.HTTP -> "酷狗服务暂时不可用"
        KugouNetworkErrorKind.DATA -> "酷狗返回数据无效"
        KugouNetworkErrorKind.NETWORK -> "无法连接酷狗"
    },
    cause,
)

data class KugouRawRequest(
    val url: String,
    val method: String,
    val headers: Map<String, String>,
    val body: String?,
    val connectTimeoutMs: Int,
    val readTimeoutMs: Int,
    val bodyBytes: ByteArray? = null,
)

data class KugouRawResponse(
    val statusCode: Int,
    val body: String,
    val headers: Map<String, List<String>> = emptyMap(),
    val bodyBytes: ByteArray? = null,
)

fun interface KugouTransport {
    suspend fun execute(request: KugouRawRequest): KugouRawResponse
}

fun interface KugouRawRequestExecutor {
    suspend fun executeRaw(request: KugouRequest): KugouRawResponse
}

class OkHttpKugouTransport : KugouTransport {
    override suspend fun execute(request: KugouRawRequest): KugouRawResponse = suspendCancellableCoroutine { continuation ->
        val bytes = request.bodyBytes ?: request.body?.toByteArray(Charsets.UTF_8)
            ?: if (request.method in setOf("POST", "PUT", "PATCH", "PROPPATCH", "REPORT")) ByteArray(0) else null
        // Keep the signed body bytes and explicit Content-Type unchanged.
        val builder = Request.Builder().url(request.url).method(request.method, bytes?.toRequestBody())
        request.headers.forEach { (key, value) -> builder.header(key, value) }
        val call = client.newBuilder()
            .connectTimeout(request.connectTimeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .readTimeout(request.readTimeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .writeTimeout(request.readTimeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .build().newCall(builder.build())
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, error: IOException) {
                continuation.resumeWith(Result.failure(error))
            }

            override fun onResponse(call: Call, response: Response) {
                // Read and close on OkHttp's worker, not the UI thread. cancel()
                // also closes a response whose body is currently stalled.
                val result = runCatching {
                    response.use {
                        val body = it.body?.bytes() ?: ByteArray(0)
                        KugouRawResponse(it.code, body.toString(Charsets.UTF_8), it.headers.toMultimap(), body)
                    }
                }
                continuation.resumeWith(result)
            }
        })
    }

    private companion object {
        val client = OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .build()
    }
}

class KugouHttpClient(
    private val deviceProvider: () -> KugouDeviceIdentity,
    private val credentialProvider: () -> KugouCredentials?,
    private val transport: KugouTransport = OkHttpKugouTransport(),
    private val clockSeconds: () -> Long = { System.currentTimeMillis() / 1000L },
    private val timeoutMs: Long = 10_000L,
) : KugouRequestExecutor, KugouRawRequestExecutor {
    init {
        require(timeoutMs in 1..120_000) { "timeoutMs must be positive" }
    }

    override suspend fun execute(request: KugouRequest): KugouResponse {
        val raw = executeRaw(request)
        val json = parseJson(raw.body)
        val businessCode = json?.optInt("error_code", 0) ?: 0
        val searchAuthenticationRequired = businessCode == 152 &&
            request.headers["x-router"] == "complexsearch.kugou.com" &&
            request.path.matches(Regex("^/v[13]/search/(song|special|album|author|lyric|mv)$"))
        if (businessCode == 20018 || searchAuthenticationRequired) {
            throw KugouNetworkException(KugouNetworkErrorKind.SESSION_EXPIRED, raw.statusCode)
        }
        if (raw.statusCode !in 200..299) {
            throw KugouNetworkException(KugouNetworkErrorKind.HTTP, raw.statusCode)
        }
        if (json == null) throw KugouNetworkException(KugouNetworkErrorKind.DATA, raw.statusCode)
        if (businessCode != 0) {
            throw KugouNetworkException(KugouNetworkErrorKind.DATA, raw.statusCode)
        }
        return KugouResponse(raw.statusCode, json, raw.headers)
    }

    override suspend fun executeRaw(request: KugouRequest): KugouRawResponse {
        try {
            // Only our own deadline becomes TIMEOUT; a parent deadline remains
            // cancellation so page/activity teardown cannot return stale data.
            return withTimeoutOrNull(timeoutMs) { performRaw(request) }
                ?: throw KugouNetworkException(KugouNetworkErrorKind.TIMEOUT)
        } catch (error: KugouNetworkException) {
            throw error
        } catch (error: CancellationException) {
            throw error
        } catch (_: SocketTimeoutException) {
            throw KugouNetworkException(KugouNetworkErrorKind.TIMEOUT)
        } catch (error: IOException) {
            throw KugouNetworkException(KugouNetworkErrorKind.NETWORK, cause = error)
        } catch (error: Exception) {
            throw KugouNetworkException(KugouNetworkErrorKind.NETWORK, cause = error)
        }
    }

    private suspend fun performRaw(request: KugouRequest): KugouRawResponse {
        val base = KugouDomainPolicy.requireApiBase(request.baseUrl)
        val path = canonicalPath(request.path)
        val method = request.method.uppercase().also {
            if (!it.matches(Regex("^[A-Z]+$"))) throw KugouNetworkException(KugouNetworkErrorKind.NETWORK)
        }
        val device = deviceProvider()
        val clientTime = clockSeconds()
        val query = if (request.clearDefaultParams) linkedMapOf() else linkedMapOf<String, Any?>(
            "dfid" to (device.dfid ?: "-"),
            "mid" to KugouCrypto.deriveMid(device.mid),
            "uuid" to "-",
            "appid" to KugouSigner.APP_ID,
            "clientver" to KugouSigner.CLIENT_VERSION,
            "clienttime" to clientTime,
        )
        if (request.includeCredentials) {
            credentialProvider()?.let { auth ->
                query["userid"] = auth.userId
                query["token"] = auth.token
            }
        }
        query.putAll(request.query)

        val headers = linkedMapOf(
            "User-Agent" to "Android15-1070-11083-46-0-DiscoveryDRADProtocol-wifi",
            "kg-rc" to "1",
            "kg-thash" to "5d816a0",
            "kg-rec" to "1",
            "kg-rf" to "B9EDA08A64250DEFFBCADDEE00F8F25F",
            "dfid" to (device.dfid ?: "-"),
            "mid" to KugouCrypto.deriveMid(device.mid),
            "clienttime" to clientTime.toString(),
        )
        request.headers.forEach { (key, value) ->
            if (!validHeader(key, value)) throw KugouNetworkException(KugouNetworkErrorKind.NETWORK)
            headers[key] = value.trim(' ', '\t')
        }
        val bodyBytes = request.body as? ByteArray
        val body = if (bodyBytes == null) encodeBody(request.body) else null
        if (bodyBytes != null && headers.keys.none { it.equals("content-type", ignoreCase = true) }) {
            headers["content-type"] = "application/octet-stream"
        } else if (body != null && headers.keys.none { it.equals("content-type", ignoreCase = true) }) {
            headers["content-type"] = "application/json"
        }
        val signed = KugouSigner.sign(
            request.copy(method = method, path = path, query = query, headers = headers, body = body ?: bodyBytes),
        )
        val url = buildUrl(base, path, signed.query)
        val raw = transport.execute(
            KugouRawRequest(
                url = url,
                method = method,
                headers = headers,
                body = body,
                connectTimeoutMs = timeoutMs.toInt(),
                readTimeoutMs = timeoutMs.toInt(),
                bodyBytes = bodyBytes,
            ),
        )
        if (raw.statusCode in 300..399) {
            throw KugouNetworkException(KugouNetworkErrorKind.NETWORK)
        }
        return raw
    }

    private fun canonicalPath(raw: String): String {
        if (!raw.startsWith("/") || raw.startsWith("//") || '\\' in raw || '?' in raw || '#' in raw) {
            throw KugouNetworkException(KugouNetworkErrorKind.NETWORK)
        }
        var decoded = raw
        repeat(8) {
            if (decoded.startsWith("//") || '\\' in decoded || decoded.split('/').any { it == "." || it == ".." }) {
                throw KugouNetworkException(KugouNetworkErrorKind.NETWORK)
            }
            val next = runCatching { URLDecoder.decode(decoded, Charsets.UTF_8.name()) }.getOrElse {
                throw KugouNetworkException(KugouNetworkErrorKind.NETWORK)
            }
            if (next == decoded) return raw
            decoded = next
        }
        throw KugouNetworkException(KugouNetworkErrorKind.NETWORK)
    }

    private fun buildUrl(base: URI, path: String, query: Map<String, Any?>): String {
        val encoded = query.keys.sorted().flatMap { key ->
            val value = query[key]
            val values = when (value) {
                is Iterable<*> -> value.toList()
                is Array<*> -> value.toList()
                else -> listOf(value)
            }
            values.filterNotNull().map { item ->
                "${encode(key)}=${encode(KugouSigner.stringifyQueryValue(item))}"
            }
        }.joinToString("&")
        val origin = "${base.scheme}://${base.host}"
        return if (encoded.isEmpty()) "$origin$path" else "$origin$path?$encoded"
    }

    private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())
        .replace("+", "%20")

    private fun parseJson(raw: String): JSONObject? = try {
        if (raw.isBlank()) null else JSONObject(raw)
    } catch (_: JSONException) {
        null
    }

    private fun encodeBody(value: Any?): String? = when (value) {
        null -> null
        is String -> value
        is JSONObject, is JSONArray -> value.toString()
        is Map<*, *> -> JSONObject(value).toString()
        else -> value.toString()
    }

    private fun validHeader(name: String, value: String): Boolean =
        name.matches(Regex("^[!#$%&'*+.^_`|~0-9A-Za-z-]+$")) &&
            value.none { it.code in 0..8 || it.code in 10..31 || it.code == 127 }
}
