package com.pushizo.sdk

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * SDK iç hatalarını `POST /sdk/v1/apps/{appId}/errors` uç noktasına toplu ve en iyi çabayla bildirir.
 * - Oturum başına en fazla [maxPerSession] rapor; aynı kod+mesaj bir kez gönderilir.
 * - Ağ/çevrimdışı hataları raporlanmaz (raporun kendisi de gönderilemez).
 * - Rapor gönderimi hatası yeniden raporlanmaz (özyineleme yok).
 * - Mesaj ve bağlamda token/sır/kişisel veri bulunmaz: yalnızca SDK'nın kendi metni, hata sınıfı ve HTTP kodu
 *   gönderilir; uzun onaltılık/base64 dizileri ayrıca maskelenir.
 */
internal class HsErrorReporter(
    private val platform: String,
    private val sdkVersion: String,
    private val maxPerSession: Int = 20,
) {
    private val lock = Any()
    private val pending = ArrayList<JSONObject>()
    private val seen = HashSet<String>()
    private var accepted = 0

    /** @return Rapor kuyruğa alındıysa true (yinelenen, limit aşan veya ağ hatasıysa false). */
    fun record(code: String, message: String, error: Throwable? = null, subscriptionId: String? = null, context: Map<String, Any?> = emptyMap(), level: String = "error"): Boolean {
        if (error != null && isNetworkError(error)) return false
        val text = sanitize(listOfNotNull(message, error?.let { describe(it) }).joinToString(": ")).take(2000)
        if (text.isBlank()) return false
        synchronized(lock) {
            if (accepted >= maxPerSession || !seen.add("$code|$text")) return false
            accepted++
            val item = JSONObject()
                .put("level", if (level == "warning") "warning" else "error")
                .put("platform", platform)
                .put("sdk_version", sdkVersion)
                .put("code", code.take(100))
                .put("message", text)
                .put("occurred_at", isoNow())
            subscriptionId?.let { item.put("subscription_id", it) }
            if (context.isNotEmpty()) {
                val ctx = JSONObject()
                context.forEach { (k, v) -> ctx.put(k.take(64), if (v is String) sanitize(v).take(256) else v ?: JSONObject.NULL) }
                if (ctx.toString().length <= 4096) item.put("context", ctx)
            }
            pending.add(item)
            return true
        }
    }

    /** Gönderilecek bir sonraki toplu gövde (en fazla 20 kayıt); bekleyen yoksa null. */
    fun drain(): JSONObject? = synchronized(lock) {
        if (pending.isEmpty()) return null
        val batch = pending.take(MAX_BATCH)
        repeat(batch.size) { pending.removeAt(0) }
        JSONObject().put("errors", JSONArray(batch))
    }

    val pendingCount: Int get() = synchronized(lock) { pending.size }

    companion object {
        const val MAX_BATCH = 20
        private val SECRET_LIKE = Regex("[A-Za-z0-9_\\-+/=:]{32,}")

        fun isNetworkError(error: Throwable): Boolean =
            (error is HsApiException && error.status == 0) || (error !is HsApiException && error is IOException)

        /** ISO-8601 UTC zaman damgası (minSdk 23: java.time kullanılmaz). */
        private fun isoNow(): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date())

        /** Token/anahtar benzeri uzun dizileri maskeler. */
        fun sanitize(text: String): String = SECRET_LIKE.replace(text, "[redacted]")

        /** Hata özeti: yalnızca sınıf adı ve (API hatasıysa) HTTP durumu ile kodu; ham mesaj gönderilmez. */
        private fun describe(error: Throwable): String =
            if (error is HsApiException) "HTTP ${error.status} ${error.code}" else error.javaClass.simpleName
    }
}
