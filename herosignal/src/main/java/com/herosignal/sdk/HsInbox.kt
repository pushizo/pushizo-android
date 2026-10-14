package com.herosignal.sdk

import org.json.JSONObject
import java.net.URLEncoder

/** Bildirim kutusu öğesi (`docs/messaging.md` §8). Zamanlar epoch milisaniyedir. */
data class HsInboxItem(
    val id: String,
    val messageId: String?,
    val title: String?,
    val body: String?,
    val url: String?,
    val imageUrl: String?,
    val data: Map<String, String>,
    val read: Boolean,
    val readAt: Long?,
    val archivedAt: Long?,
    val expiresAt: Long?,
    val createdAt: Long?,
)

/** Bildirim kutusu sayfası (yeniden eskiye). */
data class HsInboxPage(
    val items: List<HsInboxItem>,
    val unreadCount: Int,
    val nextCursor: String?,
    val hasMore: Boolean,
)

/** Java/köprü kullanımı için sonuç geri çağrısı (ana iş parçacığında çağrılmaz; arka plan iş parçacığındadır). */
fun interface HsCallback<T> {
    fun onResult(result: Result<T>)
}

/**
 * Bildirim kutusu isteklerinin yetkilendirmesi: abonelik kullanıcıya ait olmalı (sunucu doğrular). `tokenHash`
 * aboneliğin sahiplik kanıtıdır (ADR-0016; push: kayıtlı token, e-posta/SMS: kanonik adres özeti), `identityHash`
 * login'de verilen HMAC'tir; ikisi de yalnızca mevcutsa gönderilir.
 */
internal class HsInboxAuth(
    val userId: String,
    val subscriptionId: String,
    val tokenHash: String?,
    val identityHash: String?,
) {
    private fun enc(value: String) = URLEncoder.encode(value, "UTF-8")

    val basePath: String get() = "/users/${enc(userId)}/inbox"

    /** `GET` ve `DELETE` için sorgu dizesi (başında `?` yok). */
    fun query(): String = listOfNotNull(
        "subscription_id=${enc(subscriptionId)}",
        tokenHash?.let { "token_hash=${enc(it)}" },
        identityHash?.let { "identity_hash=${enc(it)}" },
    ).joinToString("&")

    /** `POST …/read` ve `POST …/read-all` gövdesi. */
    fun body(): JSONObject = JSONObject().put("subscription_id", subscriptionId).apply {
        tokenHash?.let { put("token_hash", it) }
        identityHash?.let { put("identity_hash", it) }
    }
}

internal object HsInboxParser {
    fun parsePage(json: JSONObject): HsInboxPage {
        val arr = json.optJSONArray("data")
        val items = if (arr == null) emptyList() else (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let(::parseItem) }
        return HsInboxPage(
            items = items,
            unreadCount = json.optInt("unread_count", items.count { !it.read }),
            nextCursor = json.optStringOrNull("next_cursor"),
            hasMore = json.optBoolean("has_more", false),
        )
    }

    fun parseItem(json: JSONObject): HsInboxItem {
        val data = json.optJSONObject("data") ?: JSONObject()
        return HsInboxItem(
            id = json.getString("id"),
            messageId = json.optStringOrNull("message_id"),
            title = json.optStringOrNull("title"),
            body = json.optStringOrNull("body"),
            url = json.optStringOrNull("url"),
            imageUrl = json.optStringOrNull("image_url"),
            data = data.keys().asSequence().associateWith { data.optString(it) },
            read = json.optBoolean("read", false),
            readAt = json.optStringOrNull("read_at")?.let(HsIsoTime::parse),
            archivedAt = json.optStringOrNull("archived_at")?.let(HsIsoTime::parse),
            expiresAt = json.optStringOrNull("expires_at")?.let(HsIsoTime::parse),
            createdAt = json.optStringOrNull("created_at")?.let(HsIsoTime::parse),
        )
    }
}
