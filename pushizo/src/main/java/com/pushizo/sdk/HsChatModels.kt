package com.pushizo.sdk

import org.json.JSONArray
import org.json.JSONObject

/** Sohbet mesajı eki (`GET chat/attachments/{id}` ile indirilir; bkz. [PushizoChat.attachmentUrl]). */
data class HsChatAttachment(val id: String, val name: String, val type: String, val size: Long) {
    fun toMap(): Map<String, Any?> = mapOf("id" to id, "name" to name, "type" to type, "size" to size)
}

/** Bot sorusunun hızlı yanıt seçeneği; seçildiğinde [PushizoChat.sendMessage] `optionId` ile gönderilir. */
data class HsChatQuickReply(val id: String, val label: String) {
    fun toMap(): Map<String, Any?> = mapOf("id" to id, "label" to label)
}

/**
 * Sohbet mesajı (ADR-0040 §3). [seq] konuşma içi sunucu sıra numarasıdır (sıralama ve eksik tamamlama); henüz
 * gönderilemeyip çevrimdışı kuyrukta bekleyen yerel mesajda 0'dır ve [status] `pending` olur.
 */
data class HsChatMessage(
    /** Sunucu kimliği; bekleyen yerel mesajda null. */
    val id: String?,
    val seq: Long,
    val clientMessageId: String?,
    /** [SENDER_VISITOR], [SENDER_AGENT], [SENDER_BOT] veya [SENDER_SYSTEM]. */
    val sender: String,
    val authorName: String?,
    val text: String?,
    val attachments: List<HsChatAttachment>,
    /** Bot sorusunun seçenekleri (yoksa null). */
    val quickReplies: List<HsChatQuickReply>?,
    /** [STATUS_PENDING] (yerel), [STATUS_SENT], [STATUS_DELIVERED] veya [STATUS_READ]. */
    val status: String,
    /** Oluşturulma anı (epoch ms). */
    val createdAt: Long?,
) {
    /** Ziyaretçinin kendi mesajı mı. */
    val isFromVisitor: Boolean get() = sender == SENDER_VISITOR

    fun toMap(): Map<String, Any?> = mapOf(
        "id" to id,
        "seq" to seq,
        "clientMessageId" to clientMessageId,
        "sender" to sender,
        "authorName" to authorName,
        "text" to text,
        "attachments" to attachments.map { it.toMap() },
        "quickReplies" to quickReplies?.map { it.toMap() },
        "status" to status,
        "createdAt" to createdAt,
    )

    fun toJson(): JSONObject = JSONObject(toMap())

    companion object {
        const val SENDER_VISITOR = "visitor"
        const val SENDER_AGENT = "agent"
        const val SENDER_BOT = "bot"
        const val SENDER_SYSTEM = "system"
        const val STATUS_PENDING = "pending"
        const val STATUS_SENT = "sent"
        const val STATUS_DELIVERED = "delivered"
        const val STATUS_READ = "read"
    }
}

/** Ziyaretçinin konuşması. Durum `open`, `pending` veya `resolved` (`closed` eski kayıtlar). */
data class HsChatConversation(
    val id: String,
    val status: String,
    val lastSeq: Long,
    /** Temsilcinin okuduğu son sıra (ziyaretçi mesajlarında "okundu"). */
    val agentLastReadSeq: Long,
    val visitorLastReadSeq: Long,
    val unreadCount: Int,
    val botActive: Boolean,
    val assigneeName: String?,
    val createdAt: Long?,
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "id" to id,
        "status" to status,
        "lastSeq" to lastSeq,
        "agentLastReadSeq" to agentLastReadSeq,
        "visitorLastReadSeq" to visitorLastReadSeq,
        "unreadCount" to unreadCount,
        "botActive" to botActive,
        "assigneeName" to assigneeName,
        "createdAt" to createdAt,
    )
}

/** `GET chat/conversation` sonucu: konuşma (henüz yoksa null) ve okunmamış sayısı. */
data class HsChatConversationState(val conversation: HsChatConversation?, val unreadCount: Int) {
    fun toMap(): Map<String, Any?> = mapOf("conversation" to conversation?.toMap(), "unreadCount" to unreadCount)
}

/** Mesaj sayfası (kronolojik sırada; [hasMore] daha eski/yeni mesaj olduğunu gösterir). */
data class HsChatMessagePage(val messages: List<HsChatMessage>, val hasMore: Boolean) {
    fun toMap(): Map<String, Any?> = mapOf("messages" to messages.map { it.toMap() }, "hasMore" to hasMore)
}

/** `chat/identify` sonucu: bağlanan kullanıcı kimliği ve (varsa) konuşma. */
data class HsChatIdentifyResult(val userId: String?, val conversation: HsChatConversation?) {
    fun toMap(): Map<String, Any?> = mapOf("userId" to userId, "conversation" to conversation?.toMap())
}

/** Sohbet widget ayarları ve çevrimiçi durumu (`GET chat/config`). */
data class HsChatConfig(
    val enabled: Boolean,
    /** Çalışma saatleri içinde ve en az bir temsilci (veya bot) çevrimiçi. */
    val online: Boolean,
    val withinBusinessHours: Boolean,
    val color: String?,
    val textColor: String?,
    val position: String,
    val theme: String,
    val title: String?,
    val greeting: String?,
    /** Çevrimdışıyken gösterilecek metin; çevrimdışı mesaj için [PushizoChat.leaveOfflineMessage]. */
    val offlineMessage: String?,
    val collectEmailOffline: Boolean,
    val requireEmail: Boolean,
    val attachmentsEnabled: Boolean,
    val attachmentMaxBytes: Long,
    val attachmentTypes: List<String>,
    val language: String,
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "enabled" to enabled,
        "online" to online,
        "withinBusinessHours" to withinBusinessHours,
        "color" to color,
        "textColor" to textColor,
        "position" to position,
        "theme" to theme,
        "title" to title,
        "greeting" to greeting,
        "offlineMessage" to offlineMessage,
        "collectEmailOffline" to collectEmailOffline,
        "requireEmail" to requireEmail,
        "attachmentsEnabled" to attachmentsEnabled,
        "attachmentMaxBytes" to attachmentMaxBytes,
        "attachmentTypes" to attachmentTypes,
        "language" to language,
    )
}

/** Push bildirimindeki sohbet işareti (`pushizo_chat`): yeni temsilci mesajının konuşması ve sıra numarası. */
data class HsChatPushInfo(val conversationId: String, val seq: Long) {
    fun toMap(): Map<String, Any?> = mapOf("conversationId" to conversationId, "seq" to seq)
}

/**
 * Sohbet olay dinleyicisi; tüm yöntemler isteğe bağlıdır ve **ana iş parçacığında** çağrılır.
 * Mesajlar her sunucu kimliği için bir kez bildirilir (akış, yoklama ve gönderim sonucu tekilleştirilir).
 */
interface HsChatListener {
    /** Yeni mesaj (temsilci, bot, sistem veya bu cihazdan gönderilen); bekleyen yerel mesaj da `pending` olarak gelir. */
    fun onMessage(message: HsChatMessage) {}
    /** Temsilci yazıyor göstergesi. */
    fun onTyping(typing: Boolean, name: String?) {}
    /** Temsilci [seq] numarasına kadar ziyaretçi mesajlarını okudu. */
    fun onAgentRead(seq: Long) {}
    /** Okunmamış (temsilci/bot) mesaj sayısı değişti. */
    fun onUnreadCountChanged(count: Int) {}
    /** Konuşma durumu değişti (`open`/`pending`/`resolved`, bot etkin mi). */
    fun onConversationUpdated(conversationId: String, status: String, botActive: Boolean) {}
    /** Canlı akış bağlandı (`true`) veya koptu (`false`; SDK geri çekilmeyle yeniden bağlanır). */
    fun onConnectionChanged(connected: Boolean) {}
    /** Kalıcı hata (ör. plan kapısı `HS_PLAN_FEATURE_REQUIRED`, geçersiz ziyaretçi belirteci); dinleme durdu. */
    fun onError(code: String, message: String?) {}
}

/** Sohbet push bildirimine tıklandı: uygulama sohbet ekranını açmalı. Ana iş parçacığında çağrılır. */
fun interface HsChatOpenListener {
    fun onOpen(info: HsChatPushInfo)
}

/**
 * Köprüler (Flutter, React Native, Capacitor, Cordova, .NET MAUI, Unity) için olay uyarlayıcısı: her olayı
 * `(tür, yük)` olarak iletir. Türler: `message`, `typing`, `read`, `unread`, `conversation`, `connection`, `error`.
 */
class HsChatEventAdapter(private val emit: (type: String, payload: Map<String, Any?>) -> Unit) : HsChatListener {
    override fun onMessage(message: HsChatMessage) = emit("message", mapOf("message" to message.toMap()))
    override fun onTyping(typing: Boolean, name: String?) = emit("typing", mapOf("typing" to typing, "name" to name))
    override fun onAgentRead(seq: Long) = emit("read", mapOf("seq" to seq))
    override fun onUnreadCountChanged(count: Int) = emit("unread", mapOf("count" to count))
    override fun onConversationUpdated(conversationId: String, status: String, botActive: Boolean) =
        emit("conversation", mapOf("conversationId" to conversationId, "status" to status, "botActive" to botActive))
    override fun onConnectionChanged(connected: Boolean) = emit("connection", mapOf("connected" to connected))
    override fun onError(code: String, message: String?) = emit("error", mapOf("code" to code, "message" to message))
}

/** Sunucu yanıtlarının ayrıştırıcısı (snake_case JSON → modeller). */
internal object HsChatParser {
    fun message(json: JSONObject): HsChatMessage = HsChatMessage(
        id = json.optStringOrNull("id"),
        seq = json.optLong("seq", 0L),
        clientMessageId = json.optStringOrNull("client_message_id"),
        sender = json.optStringOrNull("sender") ?: HsChatMessage.SENDER_SYSTEM,
        authorName = json.optStringOrNull("author_name"),
        text = json.optStringOrNull("text"),
        attachments = json.optJSONArray("attachments").objects().map {
            HsChatAttachment(it.optString("id"), it.optString("name"), it.optString("type"), it.optLong("size", 0L))
        },
        quickReplies = json.optJSONArray("quick_replies")?.objects()?.map { HsChatQuickReply(it.optString("id"), it.optString("label")) },
        status = json.optStringOrNull("status") ?: HsChatMessage.STATUS_SENT,
        createdAt = json.optStringOrNull("created_at")?.let(HsIsoTime::parse),
    )

    fun conversation(json: JSONObject?): HsChatConversation? {
        if (json == null) return null
        val id = json.optStringOrNull("id") ?: return null
        return HsChatConversation(
            id = id,
            status = json.optStringOrNull("status") ?: "open",
            lastSeq = json.optLong("last_seq", 0L),
            agentLastReadSeq = json.optLong("agent_last_read_seq", 0L),
            visitorLastReadSeq = json.optLong("visitor_last_read_seq", 0L),
            unreadCount = json.optInt("unread_count", 0),
            botActive = json.optBoolean("bot_active", false),
            assigneeName = json.optStringOrNull("assignee_name"),
            createdAt = json.optStringOrNull("created_at")?.let(HsIsoTime::parse),
        )
    }

    fun conversationState(json: JSONObject): HsChatConversationState {
        val conversation = conversation(json.optJSONObject("conversation"))
        return HsChatConversationState(conversation, json.optInt("unread_count", conversation?.unreadCount ?: 0))
    }

    fun page(json: JSONObject): HsChatMessagePage =
        HsChatMessagePage(json.optJSONArray("data").objects().map(::message).sortedBy { it.seq }, json.optBoolean("has_more", false))

    fun identify(json: JSONObject): HsChatIdentifyResult =
        HsChatIdentifyResult(json.optStringOrNull("user_id"), conversation(json.optJSONObject("conversation")))

    fun config(json: JSONObject): HsChatConfig {
        val attachments = json.optJSONObject("attachments")
        return HsChatConfig(
            enabled = json.optBoolean("enabled", false),
            online = json.optBoolean("online", false),
            withinBusinessHours = json.optBoolean("within_business_hours", false),
            color = json.optStringOrNull("color"),
            textColor = json.optStringOrNull("text_color"),
            position = json.optStringOrNull("position") ?: "right",
            theme = json.optStringOrNull("theme") ?: "auto",
            title = json.optStringOrNull("title"),
            greeting = json.optStringOrNull("greeting"),
            offlineMessage = json.optStringOrNull("offline_message"),
            collectEmailOffline = json.optBoolean("collect_email_offline", false),
            requireEmail = json.optBoolean("require_email", false),
            attachmentsEnabled = attachments?.optBoolean("enabled", false) ?: false,
            attachmentMaxBytes = attachments?.optLong("max_bytes", HsChatLimits.MAX_ATTACHMENT_BYTES) ?: HsChatLimits.MAX_ATTACHMENT_BYTES,
            attachmentTypes = attachments?.optJSONArray("types")?.let { arr -> (0 until arr.length()).map { arr.optString(it) } } ?: emptyList(),
            language = json.optStringOrNull("language") ?: "en",
        )
    }

    private fun JSONArray?.objects(): List<JSONObject> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }
}
