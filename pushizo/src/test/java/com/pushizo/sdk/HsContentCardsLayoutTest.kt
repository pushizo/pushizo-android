package com.pushizo.sdk

import com.pushizo.sdk.HsContentCardsLayout.Part
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Hazır kart görünümünün yerleşim ve gösterim kuralları (ADR-0082). */
class HsContentCardsLayoutTest {
    private fun card(
        type: HsContentCardType,
        title: String? = "Başlık",
        body: String? = null,
        imageUrl: String? = null,
        buttonText: String? = null,
    ) = HsContentCard(
        id = "cc_1", messageId = null, type = type, title = title, body = body, url = null, imageUrl = imageUrl,
        buttonText = buttonText, data = emptyMap(), pinned = false, priority = 0, viewed = false, clicked = false,
        expiresAt = null, createdAt = null,
    )

    @Test
    fun `klasik kart metin ve dugme gosterir, gorsel gostermez`() {
        assertEquals(setOf(Part.TEXT, Part.BUTTON), HsContentCardsLayout.parts(card(HsContentCardType.CLASSIC, imageUrl = "https://x/a.png", buttonText = "Aç")))
        assertEquals(setOf(Part.TEXT), HsContentCardsLayout.parts(card(HsContentCardType.CLASSIC, buttonText = " ")))
    }

    @Test
    fun `gorselli kart gorsel ustte, metin altta`() {
        assertEquals(listOf(Part.IMAGE, Part.TEXT), HsContentCardsLayout.parts(card(HsContentCardType.CAPTIONED_IMAGE, imageUrl = "https://x/a.png")).toList())
        // Metinsiz görselli kart yalnız görsel; görselsiz görselli kart metne düşer.
        assertEquals(setOf(Part.IMAGE), HsContentCardsLayout.parts(card(HsContentCardType.CAPTIONED_IMAGE, title = null, imageUrl = "https://x/a.png")))
        assertEquals(setOf(Part.TEXT), HsContentCardsLayout.parts(card(HsContentCardType.CAPTIONED_IMAGE)))
    }

    @Test
    fun `yalniz gorsel kart metin ve dugme gostermez`() {
        assertEquals(setOf(Part.IMAGE), HsContentCardsLayout.parts(card(HsContentCardType.IMAGE_ONLY, imageUrl = "https://x/a.png", buttonText = "Aç")))
        assertEquals(setOf(Part.TEXT, Part.BUTTON), HsContentCardsLayout.parts(card(HsContentCardType.IMAGE_ONLY, buttonText = "Aç")))
    }

    @Test
    fun `gosterim icin kartin yarisi gorunmeli`() {
        assertTrue(HsContentCardsLayout.countsAsImpression(50, 100))
        assertTrue(HsContentCardsLayout.countsAsImpression(100, 100))
        assertFalse(HsContentCardsLayout.countsAsImpression(49, 100))
        assertFalse(HsContentCardsLayout.countsAsImpression(0, 0))
    }
}
