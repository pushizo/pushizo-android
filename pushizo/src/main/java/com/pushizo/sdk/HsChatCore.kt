package com.pushizo.sdk

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Random

/** Sohbet sınırları (sunucudaki `CHAT_LIMITS` ile aynı). */
internal object HsChatLimits {
    const val MAX_TEXT = 4000
    const val MAX_ATTACHMENT_BYTES = 10L * 1024 * 1024
    val VISITOR_ID = Regex("^[A-Za-z0-9_-]{16,64}$")
    val VISITOR_TOKEN = Regex("^[A-Za-z0-9_-]{32,128}$")
    val CLIENT_MESSAGE_ID = Regex("^[A-Za-z0-9_.:-]{8,64}$")
    /** Sunucunun kabul ettiği ek türleri (sihirli bayt ile ayrıca doğrulanır). */
    val ATTACHMENT_TYPES = setOf("image/png", "image/jpeg", "image/webp", "image/gif", "application/pdf", "text/plain")
}

/** Sohbet ziyaretçi durumunun kalıcı deposu ([HsStorage]; testlerde bellek içi). */
internal interface HsChatState {
    var chatVisitorId: String?
    var chatVisitorToken: String?
    var chatEnabled: Boolean
    var chatIdentifyKey: String?
    var chatLastSeq: Long
    var chatDeliveredSeq: Long
    var chatUnreadCount: Int
    fun clearChatVisitor()
}

/**
 * Ziyaretçi kimliği (ADR-0040 §2): SDK ilk kullanımda rastgele 32 karakterlik `visitor_id` ve 64 karakterlik gizli
 * `visitor_token` üretir (alfabe `[A-Za-z0-9_-]`, karakter başına 6 bit; sırasıyla 192 ve 384 bit). Sunucu belirtecin
 * yalnızca SHA-256 özetini saklar. GET uçlarında `visitor_id` + `token` sorgusu, gövdeli uçlarda `visitor_token`.
 */
internal class HsChatVisitor(val id: String, val token: String) {
    fun auth(body: JSONObject = JSONObject()): JSONObject = body.put("visitor_id", id).put("visitor_token", token)

    /** GET sorgusu (başında `?` yok). */
    fun query(params: Map<String, Any?> = emptyMap()): String =
        (listOf("visitor_id" to id, "token" to token) + params.entries.mapNotNull { (k, v) -> v?.let { k to it.toString() } })
            .joinToString("&") { (k, v) -> "$k=${enc(v)}" }

    companion object {
        private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
        const val ID_LENGTH = 32
        const val TOKEN_LENGTH = 64
        private val secureRandom by lazy { SecureRandom() }

        fun randomString(length: Int, random: Random = secureRandom): String =
            buildString(length) { repeat(length) { append(ALPHABET[random.nextInt(ALPHABET.length)]) } }

        /** Kayıtlı ve biçimi geçerli kimlik varsa onu, yoksa yenisini üretip saklar. */
        @Synchronized
        fun ensure(state: HsChatState, random: Random = secureRandom): HsChatVisitor {
            val id = state.chatVisitorId
            val token = state.chatVisitorToken
            if (id != null && token != null && HsChatLimits.VISITOR_ID.matches(id) && HsChatLimits.VISITOR_TOKEN.matches(token)) {
                return HsChatVisitor(id, token)
            }
            state.clearChatVisitor()
            val visitor = HsChatVisitor(randomString(ID_LENGTH, random), randomString(TOKEN_LENGTH, random))
            state.chatVisitorId = visitor.id
            state.chatVisitorToken = visitor.token
            return visitor
        }

        /** İstemci mesaj kimliği (8–64 karakter); yeniden denemelerde aynı kimlik kullanılır (sunucu tekilleştirir). */
        fun newClientMessageId(random: Random = secureRandom): String = "m_" + randomString(24, random)

        private fun enc(value: String) = URLEncoder.encode(value, "UTF-8")
    }
}

/** Sohbet istek gövdeleri (sunucu şeması `apps/api/src/live-chat/chat.dto.ts`). */
internal object HsChatRequests {
    /**
     * `POST chat/identify`: kullanıcı bağlama (`external_id` + login'deki `identity_hash`; JWT başlıkta gider) ve push
     * aboneliği bağlama. Abonelik yalnızca sahiplik kanıtıyla (`token_hash` = kayıtlı token'ın SHA-256'sı, ADR-0016)
     * gönderilir; token yoksa abonelik alanları hiç eklenmez.
     */
    fun identifyBody(
        visitor: HsChatVisitor,
        externalId: String?,
        identityHash: String?,
        subscriptionId: String?,
        registeredToken: String?,
        name: String?,
        email: String?,
        language: String?,
    ): JSONObject = visitor.auth().apply {
        externalId?.takeIf { it.isNotBlank() }?.let { ext ->
            put("external_id", ext)
            identityHash?.takeIf { it.isNotBlank() }?.let { put("identity_hash", it) }
        }
        if (subscriptionId != null && registeredToken != null) {
            put("subscription_id", subscriptionId)
            put("token_hash", HsOwnership.tokenHash(registeredToken))
        }
        name?.trim()?.takeIf { it.isNotEmpty() }?.let { put("name", it) }
        email?.trim()?.takeIf { it.isNotEmpty() }?.let { put("email", it) }
        language?.trim()?.takeIf { it.isNotEmpty() }?.let { put("language", it) }
    }

    /** Identify girdisinin özeti: değişmediyse istek tekrarlanmaz (belirteç ve token düz metin saklanmaz). */
    fun identifyKey(body: JSONObject): String {
        val keys = body.keys().asSequence().filter { it != "visitor_token" }.sorted().toList()
        val canonical = keys.joinToString("\u0001") { "$it=${body.opt(it)}" }
        return MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    fun sendBody(visitor: HsChatVisitor, clientMessageId: String, text: String, optionId: String?, name: String?, email: String?, language: String?): JSONObject =
        visitor.auth().put("client_message_id", clientMessageId).put("text", text).apply {
            optionId?.takeIf { it.isNotBlank() }?.let { put("option_id", it) }
            name?.trim()?.takeIf { it.isNotEmpty() }?.let { put("name", it) }
            email?.trim()?.takeIf { it.isNotEmpty() }?.let { put("email", it) }
            language?.trim()?.takeIf { it.isNotEmpty() }?.let { put("language", it) }
        }

    fun offlineBody(visitor: HsChatVisitor, clientMessageId: String, email: String, text: String, name: String?, language: String?): JSONObject =
        visitor.auth().put("client_message_id", clientMessageId).put("email", email.trim()).put("text", text).apply {
            name?.trim()?.takeIf { it.isNotEmpty() }?.let { put("name", it) }
            language?.trim()?.takeIf { it.isNotEmpty() }?.let { put("language", it) }
        }

    fun typingBody(visitor: HsChatVisitor, typing: Boolean): JSONObject = visitor.auth().put("typing", typing)

    /** `POST chat/ack`: en az biri verilmelidir; işaretçiler sunucuda yalnızca ileri gider. */
    fun ackBody(visitor: HsChatVisitor, readSeq: Long?, deliveredSeq: Long?): JSONObject {
        require(readSeq != null || deliveredSeq != null) { "readSeq veya deliveredSeq gerekli." }
        return visitor.auth().apply {
            readSeq?.let { put("read_seq", it) }
            deliveredSeq?.let { put("delivered_seq", it) }
        }
    }

    /** `GET chat/messages` sorgusu: `after_seq` eksik tamamlama (eskiden yeniye), `before_seq` geçmiş. */
    fun messagesQuery(visitor: HsChatVisitor, afterSeq: Long?, beforeSeq: Long?, limit: Int): String {
        require(limit in 1..100) { "limit 1-100 olmalı." }
        require(afterSeq == null || afterSeq >= 0) { "afterSeq negatif olamaz." }
        require(beforeSeq == null || beforeSeq >= 1) { "beforeSeq en az 1 olmalı." }
        return visitor.query(linkedMapOf("after_seq" to afterSeq, "before_seq" to beforeSeq, "limit" to limit))
    }

    /** Metin doğrulaması (kırpılmış, 1–4000 karakter). */
    fun requireText(text: String): String {
        val trimmed = text.trim()
        require(trimmed.isNotEmpty()) { "Mesaj metni boş olamaz." }
        require(trimmed.length <= HsChatLimits.MAX_TEXT) { "Mesaj en fazla ${HsChatLimits.MAX_TEXT} karakter olabilir." }
        return trimmed
    }
}

/** `multipart/form-data` gövdesi (sohbet eki; ek bağımlılık yok). */
internal class HsMultipart(val boundary: String = "----pushizo" + HsChatVisitor.randomString(24)) {
    private val out = ByteArrayOutputStream()

    val contentType: String get() = "multipart/form-data; boundary=$boundary"

    fun field(name: String, value: String): HsMultipart {
        write("--$boundary\r\nContent-Disposition: form-data; name=\"${escape(name)}\"\r\n\r\n")
        write(value)
        write("\r\n")
        return this
    }

    fun file(name: String, fileName: String, mimeType: String, bytes: ByteArray): HsMultipart {
        write("--$boundary\r\nContent-Disposition: form-data; name=\"${escape(name)}\"; filename=\"${escape(fileName)}\"\r\n")
        write("Content-Type: $mimeType\r\n\r\n")
        out.write(bytes)
        write("\r\n")
        return this
    }

    fun build(): ByteArray {
        write("--$boundary--\r\n")
        return out.toByteArray()
    }

    private fun write(text: String) = out.write(text.toByteArray(Charsets.UTF_8))

    /** Başlık değerinde tırnak ve satır sonu kaçırılır (dosya adı kullanıcıdan gelebilir). */
    private fun escape(value: String) = value.replace("\r", " ").replace("\n", " ").replace("\"", "%22")
}

/** Ayrıştırılmış SSE olayı. */
internal data class HsSseEvent(val event: String, val data: String, val id: String?)

/**
 * Server-Sent Events satır ayrıştırıcısı (WHATWG kuralları): `event:`, `data:` (çok satırlı veri `\n` ile birleşir),
 * `id:` (son olay kimliği; NUL içeren kimlik yok sayılır), `retry:`; `:` ile başlayan satır yorumdur (kalp atışı).
 * Boş satır olayı tamamlar; verisi olmayan olay atılır.
 */
internal class HsSseParser {
    private var eventType: String? = null
    private val data = StringBuilder()
    private var hasData = false
    /** Son alınan olay kimliği (yeniden bağlanmada `Last-Event-ID`). */
    var lastEventId: String? = null
        private set
    /** Sunucunun önerdiği yeniden bağlanma süresi (ms). */
    var retryMs: Long? = null
        private set

    fun feed(rawLine: String): HsSseEvent? {
        val line = rawLine.removeSuffix("\r")
        if (line.isEmpty()) return dispatch()
        if (line.startsWith(":")) return null
        val colon = line.indexOf(':')
        val field = if (colon < 0) line else line.substring(0, colon)
        var value = if (colon < 0) "" else line.substring(colon + 1)
        if (value.startsWith(" ")) value = value.substring(1)
        when (field) {
            "event" -> eventType = value
            "data" -> {
                if (hasData) data.append('\n')
                data.append(value)
                hasData = true
            }
            "id" -> if (!value.contains('\u0000')) lastEventId = value
            "retry" -> value.toLongOrNull()?.takeIf { it >= 0 }?.let { retryMs = it }
        }
        return null
    }

    private fun dispatch(): HsSseEvent? {
        val event = if (hasData) HsSseEvent(eventType?.takeIf { it.isNotEmpty() } ?: "message", data.toString(), lastEventId) else null
        eventType = null
        data.setLength(0)
        hasData = false
        return event
    }
}

/**
 * Mesaj listesi birleştirme: sunucu mesajları sıra numarasına göre artan, aynı kimlik/sıra tekil; bekleyen yerel mesaj
 * (seq 0) aynı `clientMessageId` ile sunucu kopyası gelince yerini ona bırakır ve listenin sonunda durur. Uygulamalar
 * kendi sohbet ekranı listesini bu yardımcıyla tutabilir.
 */
object HsChatTimeline {
    @JvmStatic
    fun merge(current: List<HsChatMessage>, incoming: List<HsChatMessage>): List<HsChatMessage> {
        val bySeq = LinkedHashMap<Long, HsChatMessage>()
        val pending = LinkedHashMap<String, HsChatMessage>()
        fun add(m: HsChatMessage) {
            if (m.seq <= 0) {
                val key = m.clientMessageId ?: return
                pending[key] = m
                return
            }
            m.clientMessageId?.let { pending.remove(it) }
            // Aynı sıra numarasında yeni gelen (ör. durum güncellemesi) eskisinin yerine geçer.
            bySeq[m.seq] = m
        }
        current.forEach(::add)
        incoming.forEach(::add)
        val confirmed = bySeq.values.mapNotNull { it.clientMessageId }.toSet()
        return bySeq.values.sortedBy { it.seq } + pending.values.filter { it.clientMessageId !in confirmed }
    }
}

/** Aynı sunucu mesajının dinleyicilere bir kez bildirilmesi için sınırlı "görüldü" kümesi. */
internal class HsChatSeen(private val capacity: Int = 500) {
    private val ids = LinkedHashSet<String>()

    /** İlk kez görülüyorsa true (ve kaydeder). */
    @Synchronized
    fun mark(id: String?): Boolean {
        if (id == null) return true
        if (!ids.add(id)) return false
        while (ids.size > capacity) ids.remove(ids.first())
        return true
    }

    @Synchronized
    fun clear() = ids.clear()
}

/** Yeniden bağlanma bekleme süresi: 1 sn'den başlayıp ikiye katlanır, 30 sn tavan; ±%20 sapma eşzamanlı yığılmayı önler. */
internal object HsChatBackoff {
    const val BASE_MS = 1_000L
    const val MAX_MS = 30_000L

    /** [failures] art arda başarısız deneme sayısı (≥1); [jitter] 0..1 arası. */
    fun delayMs(failures: Int, jitter: Double = Math.random()): Long {
        val exp = BASE_MS shl (failures.coerceIn(1, 16) - 1)
        val capped = minOf(exp, MAX_MS)
        val factor = 0.8 + 0.4 * jitter.coerceIn(0.0, 1.0)
        return (capped * factor).toLong().coerceAtLeast(250L)
    }
}

/**
 * Push payload'undaki sohbet işareti. Sunucu `pushizo_chat` anahtarını (JSON metni `{"conversation_id","seq"}`) veri
 * alanına ekler; Android'de FCM/HMS `data`, iOS'ta APNs kök anahtarıdır. `custom_data` içinde iç içe gelen biçim de kabul edilir.
 */
internal object HsChatPush {
    const val KEY = "pushizo_chat"

    fun parse(data: Map<String, String>): HsChatPushInfo? {
        data[KEY]?.let { raw -> fromJson(runCatching { JSONObject(raw) }.getOrNull())?.let { return it } }
        val custom = data["custom_data"]?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return null
        return fromJson(custom.optJSONObject(KEY) ?: custom.optStringOrNull(KEY)?.let { runCatching { JSONObject(it) }.getOrNull() })
    }

    fun fromJson(json: JSONObject?): HsChatPushInfo? {
        val conversationId = json?.optStringOrNull("conversation_id") ?: return null
        val seq = json.optLong("seq", 0L)
        return HsChatPushInfo(conversationId, seq)
    }
}
