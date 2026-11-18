package com.pushizo.sdk

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** İçerik kartı ayrıştırma ve yolları (ADR-0082). */
class HsContentCardsTest {
    @Test
    fun `kart akisi ayristirilir, bilinmeyen tur klasik sayilir`() {
        val json = JSONObject()
            .put("unviewed_count", 1)
            .put(
                "data",
                JSONArray()
                    .put(
                        JSONObject().put("id", "inb_1").put("type", "captioned_image").put("title", "Kampanya").put("image_url", "https://cdn/x.png")
                            .put("button_text", "İncele").put("pinned", true).put("priority", 70).put("viewed", false).put("clicked", false)
                            .put("data", JSONObject().put("k", "v")).put("created_at", "2026-11-10T10:00:00.000Z"),
                    )
                    .put(JSONObject().put("id", "inb_2").put("type", "gelecekte").put("viewed", true).put("expires_at", JSONObject.NULL)),
            )
        val feed = HsContentCardParser.parseFeed(json)
        assertEquals(1, feed.unviewedCount)
        val first = feed.cards[0]
        assertEquals(HsContentCardType.CAPTIONED_IMAGE, first.type)
        assertEquals("İncele", first.buttonText)
        assertTrue(first.pinned)
        assertEquals(70, first.priority)
        assertEquals(mapOf("k" to "v"), first.data)
        assertEquals(HsIsoTime.parse("2026-11-10T10:00:00.000Z"), first.createdAt)
        val second = feed.cards[1]
        assertEquals(HsContentCardType.CLASSIC, second.type)
        assertTrue(second.viewed)
        assertFalse(second.clicked)
        assertNull(second.expiresAt)
    }

    @Test
    fun `kart yolu kutu kanitini kullanir`() {
        val auth = HsInboxAuth("usr 1", "sub_1", "hash", null)
        assertEquals("/users/usr+1/cards", auth.cardsPath())
        assertEquals("subscription_id=sub_1&token_hash=hash", auth.query())
    }
}
