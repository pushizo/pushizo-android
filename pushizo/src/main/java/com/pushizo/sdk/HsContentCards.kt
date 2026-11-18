package com.pushizo.sdk

import org.json.JSONObject

/** İçerik kartı türü (ADR-0082). */
enum class HsContentCardType(val wire: String) {
    CLASSIC("classic"),
    CAPTIONED_IMAGE("captioned_image"),
    IMAGE_ONLY("image_only");

    companion object {
        fun from(value: String?): HsContentCardType = values().firstOrNull { it.wire == value } ?: CLASSIC
    }
}

/** İçerik kartı (ADR-0082). Zamanlar epoch milisaniyedir; akış sunucuda sabitlenmiş → öncelik → yeni sırasıyla gelir. */
data class HsContentCard(
    val id: String,
    val messageId: String?,
    val type: HsContentCardType,
    val title: String?,
    val body: String?,
    val url: String?,
    val imageUrl: String?,
    val buttonText: String?,
    val data: Map<String, String>,
    val pinned: Boolean,
    val priority: Int,
    val viewed: Boolean,
    val clicked: Boolean,
    val expiresAt: Long?,
    val createdAt: Long?,
)

/** Kart akışı ve görülmemiş kart sayısı. */
data class HsContentCardFeed(val cards: List<HsContentCard>, val unviewedCount: Int)

internal object HsContentCardParser {
    fun parseFeed(json: JSONObject): HsContentCardFeed {
        val arr = json.optJSONArray("data")
        val cards = if (arr == null) emptyList() else (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let(::parseCard) }
        return HsContentCardFeed(cards, json.optInt("unviewed_count", cards.count { !it.viewed }))
    }

    fun parseCard(json: JSONObject): HsContentCard {
        val data = json.optJSONObject("data") ?: JSONObject()
        return HsContentCard(
            id = json.getString("id"),
            messageId = json.optStringOrNull("message_id"),
            type = HsContentCardType.from(json.optStringOrNull("type")),
            title = json.optStringOrNull("title"),
            body = json.optStringOrNull("body"),
            url = json.optStringOrNull("url"),
            imageUrl = json.optStringOrNull("image_url"),
            buttonText = json.optStringOrNull("button_text"),
            data = data.keys().asSequence().associateWith { data.optString(it) },
            pinned = json.optBoolean("pinned", false),
            priority = json.optInt("priority", 0),
            viewed = json.optBoolean("viewed", false),
            clicked = json.optBoolean("clicked", false),
            expiresAt = json.optStringOrNull("expires_at")?.let(HsIsoTime::parse),
            createdAt = json.optStringOrNull("created_at")?.let(HsIsoTime::parse),
        )
    }
}

/** Kart uçlarının yolu (kutu ile aynı kanıt kuralı, ADR-0016). */
internal fun HsInboxAuth.cardsPath(): String = basePath.removeSuffix("/inbox") + "/cards"
