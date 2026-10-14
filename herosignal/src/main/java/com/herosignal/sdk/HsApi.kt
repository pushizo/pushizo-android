package com.herosignal.sdk

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** API hata zarfı; 429/5xx ve ağ hataları yeniden denenebilir. */
class HsApiException(val status: Int, val code: String, message: String) : IOException(message) {
    val retryable: Boolean get() = status == 0 || status == 429 || status >= 500
}

/**
 * `/sdk/v1/apps/{appId}` uçlarına JSON istek (HttpURLConnection; ek bağımlılık yok).
 * [networkAllowed] false iken (KVKK/GDPR onayı bekleniyor/geri alındı) hiçbir istek bağlantı açmadan
 * `CONSENT_REQUIRED` (yeniden denenebilir) ile reddedilir; kuyruktaki işlemler bu sayede kaybolmaz.
 */
internal class HsApi(
    private val apiUrl: String,
    private val appId: String,
    private val timeoutMs: Int = 15_000,
    private val networkAllowed: () -> Boolean = { true },
) {
    private val base: String get() = "${apiUrl.trimEnd('/')}/sdk/v1/apps/${URLEncoder.encode(appId, "UTF-8")}"

    @Throws(HsApiException::class)
    fun request(method: String, path: String, body: JSONObject? = null, attempt: Int = 0): JSONObject =
        send(method, path, body, attempt, gated = true)

    /** Onay kapısını atlayan istek; yalnızca onay geri alınırken aboneliği kapatan son çağrı için kullanılır. */
    @Throws(HsApiException::class)
    fun requestIgnoringConsent(method: String, path: String, body: JSONObject? = null): JSONObject =
        send(method, path, body, 0, gated = false)

    private fun send(method: String, path: String, body: JSONObject?, attempt: Int, gated: Boolean): JSONObject {
        if (gated && !networkAllowed()) throw HsApiException(0, CONSENT_REQUIRED, "gizlilik onayı verilmedi; istek gönderilmedi")
        val (wire, override) = wireMethod(method)
        val connection = (URL(base + path).openConnection() as HttpURLConnection).apply {
            requestMethod = wire
            override?.let { setRequestProperty(METHOD_OVERRIDE_HEADER, it) }
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "herosignal-android/${BuildConfig.SDK_VERSION}")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
        }
        try {
            if (body != null) connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val text = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (status in 200..299) return if (text.isBlank()) JSONObject() else JSONObject(text)
            val error = runCatching { JSONObject(text).optJSONObject("error") }.getOrNull()
            val ex = HsApiException(status, error?.optString("code") ?: "HTTP_$status", error?.optString("message") ?: "HTTP $status")
            if (!ex.retryable || attempt >= MAX_RETRIES) throw ex
            val retryAfter = connection.getHeaderField("Retry-After")?.toLongOrNull()
            Thread.sleep(if (retryAfter != null && retryAfter > 0) retryAfter * 1000 else BACKOFF_MS[minOf(attempt, BACKOFF_MS.size - 1)])
            return send(method, path, body, attempt + 1, gated)
        } catch (e: HsApiException) {
            throw e
        } catch (e: IOException) {
            if (attempt >= MAX_RETRIES) throw HsApiException(0, "NETWORK", e.message ?: "network error")
            Thread.sleep(BACKOFF_MS[minOf(attempt, BACKOFF_MS.size - 1)])
            return send(method, path, body, attempt + 1, gated)
        } finally {
            connection.disconnect()
        }
    }

    internal companion object {
        private const val MAX_RETRIES = 3
        private val BACKOFF_MS = longArrayOf(500, 1500, 4000)
        const val METHOD_OVERRIDE_HEADER = "X-HTTP-Method-Override"
        /** Onay kapısı kapalıyken dönen hata kodu (ağ bağlantısı açılmaz). */
        const val CONSENT_REQUIRED = "CONSENT_REQUIRED"

        /** HttpURLConnection'ın göndermediği yöntemler (PATCH → ProtocolException). */
        private val OVERRIDDEN_METHODS = setOf("PATCH")

        /**
         * Kablodaki yöntem ve override başlığı: PATCH, `POST` + `X-HTTP-Method-Override: PATCH` olarak gider
         * (sunucu `/sdk/...` ve `/v1/...` için bunu PATCH olarak yönlendirir). Diğer yöntemler olduğu gibi gider.
         */
        fun wireMethod(method: String): Pair<String, String?> =
            if (method in OVERRIDDEN_METHODS) "POST" to method else method to null
    }
}
