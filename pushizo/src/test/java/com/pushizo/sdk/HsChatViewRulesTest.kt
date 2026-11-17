package com.pushizo.sdk

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Hazır sohbet arayüzünün ([HsChatView]) ekran ve çizim kuralları. */
class HsChatViewRulesTest {
    private fun config(
        enabled: Boolean = true,
        online: Boolean = true,
        collectEmailOffline: Boolean = false,
        requireEmail: Boolean = false,
        maxBytes: Long = 5L * 1024 * 1024,
        types: List<String> = emptyList(),
    ) = HsChatConfig(enabled, online, true, null, null, "right", "auto", null, null, null, collectEmailOffline, requireEmail, true, maxBytes, types, "tr")

    private fun message(
        id: String?,
        seq: Long,
        sender: String = HsChatMessage.SENDER_VISITOR,
        clientId: String? = null,
        status: String = HsChatMessage.STATUS_SENT,
        text: String? = "merhaba",
        author: String? = null,
        attachments: List<HsChatAttachment> = emptyList(),
        replies: List<HsChatQuickReply>? = null,
        createdAt: Long? = null,
    ) = HsChatMessage(id, seq, clientId, sender, author, text, attachments, replies, status, createdAt)

    @Test fun `ekran seçimi`() {
        val s = HsChatViewRules.Screen.CHAT
        assertEquals(s, HsChatViewRules.screen(config(), hasConversation = false, emailGiven = false, offlineSent = false))
        assertEquals(HsChatViewRules.Screen.UNAVAILABLE, HsChatViewRules.screen(config(enabled = false, online = false, collectEmailOffline = true), false, false, false))
        // Çevrimdışı form yalnızca konuşma yokken ve app e-posta topluyorsa.
        assertEquals(HsChatViewRules.Screen.OFFLINE_FORM, HsChatViewRules.screen(config(online = false, collectEmailOffline = true), false, false, false))
        assertEquals(s, HsChatViewRules.screen(config(online = false, collectEmailOffline = true), true, false, false))
        assertEquals(s, HsChatViewRules.screen(config(online = false), false, false, false))
        assertEquals(HsChatViewRules.Screen.OFFLINE_THANKS, HsChatViewRules.screen(config(online = false, collectEmailOffline = true), false, false, true))
        // E-posta ön formu: zorunluysa ve konuşma/e-posta yoksa.
        assertEquals(HsChatViewRules.Screen.PRECHAT, HsChatViewRules.screen(config(requireEmail = true), false, false, false))
        assertEquals(s, HsChatViewRules.screen(config(requireEmail = true), false, true, false))
        assertEquals(s, HsChatViewRules.screen(config(requireEmail = true), true, false, false))
        // Çevrimdışı form ön formdan önce gelir.
        assertEquals(HsChatViewRules.Screen.OFFLINE_FORM, HsChatViewRules.screen(config(online = false, collectEmailOffline = true, requireEmail = true), false, false, false))
    }

    @Test fun `mesajlar tekilleşir, sıralanır ve bekleyen yerel mesaj sunucu kopyasıyla değişir`() {
        val pending = message(null, 0, clientId = "cm_1", status = HsChatMessage.STATUS_PENDING, createdAt = 50)
        var list = HsChatViewRules.merge(emptyList(), listOf(message("m2", 2, HsChatMessage.SENDER_AGENT), message("m1", 1), pending))
        assertEquals(listOf("m1", "m2", null), list.map { it.id })
        // Aynı kimlik yeniden gelirse güncel kopya kullanılır (durum değişimi).
        list = HsChatViewRules.merge(list, listOf(message("m1", 1, status = HsChatMessage.STATUS_READ)))
        assertEquals(3, list.size)
        assertEquals(HsChatMessage.STATUS_READ, list[0].status)
        // Sunucu kopyası gelince bekleyen yerel mesaj kalkar.
        list = HsChatViewRules.merge(list, listOf(message("m3", 3, clientId = "cm_1")))
        assertEquals(listOf("m1", "m2", "m3"), list.map { it.id })
        // Sunucu kopyası varken gecikmiş bekleyen kopya eklenmez.
        assertEquals(3, HsChatViewRules.merge(list, listOf(pending)).size)
        // İki bekleyen mesaj oluşturulma anına göre sonda sıralanır.
        val later = message(null, 0, clientId = "cm_3", status = HsChatMessage.STATUS_PENDING, createdAt = 90)
        val earlier = message(null, 0, clientId = "cm_2", status = HsChatMessage.STATUS_PENDING, createdAt = 60)
        assertEquals(listOf("cm_2", "cm_3"), HsChatViewRules.merge(list, listOf(later, earlier)).takeLast(2).map { it.clientMessageId })
    }

    @Test fun `iletim durumu`() {
        assertEquals(HsChatViewRules.Delivery.SENDING, HsChatViewRules.delivery(message(null, 0, status = HsChatMessage.STATUS_PENDING), 0))
        assertEquals(HsChatViewRules.Delivery.SENT, HsChatViewRules.delivery(message("m1", 4), 3))
        assertEquals(HsChatViewRules.Delivery.DELIVERED, HsChatViewRules.delivery(message("m1", 4, status = HsChatMessage.STATUS_DELIVERED), 3))
        assertEquals(HsChatViewRules.Delivery.READ, HsChatViewRules.delivery(message("m1", 4, status = HsChatMessage.STATUS_READ), 0))
        // Temsilcinin okuma işaretçisi mesajın sırasını geçtiyse okundu.
        assertEquals(HsChatViewRules.Delivery.READ, HsChatViewRules.delivery(message("m1", 4), 4))
    }

    @Test fun `son ziyaretçi mesajı ve hızlı yanıtlar`() {
        val replies = listOf(HsChatQuickReply("o1", "Evet"))
        val list = listOf(message("m1", 1), message("m2", 2, HsChatMessage.SENDER_BOT, replies = replies), message("m3", 3), message("m4", 4, HsChatMessage.SENDER_BOT, replies = replies))
        assertEquals(2, HsChatViewRules.lastVisitorIndex(list))
        assertEquals(-1, HsChatViewRules.lastVisitorIndex(emptyList()))
        assertFalse(HsChatViewRules.showsQuickReplies(1, list))
        assertTrue(HsChatViewRules.showsQuickReplies(3, list))
        assertFalse(HsChatViewRules.showsQuickReplies(3, list + message("m5", 5)))
        assertFalse(HsChatViewRules.showsQuickReplies(9, list))
        assertFalse(HsChatViewRules.showsQuickReplies(0, listOf(message("m1", 1, HsChatMessage.SENDER_AGENT))))
    }

    @Test fun `gönderen etiketi ve balon metni`() {
        assertNull(HsChatViewRules.senderLabel(message("m", 1), "Asistan", "Temsilci"))
        assertNull(HsChatViewRules.senderLabel(message("m", 1, HsChatMessage.SENDER_SYSTEM), "Asistan", "Temsilci"))
        assertEquals("Asistan", HsChatViewRules.senderLabel(message("m", 1, HsChatMessage.SENDER_BOT), "Asistan", "Temsilci"))
        assertEquals("Temsilci", HsChatViewRules.senderLabel(message("m", 1, HsChatMessage.SENDER_AGENT, author = " "), "Asistan", "Temsilci"))
        assertEquals("Ayşe", HsChatViewRules.senderLabel(message("m", 1, HsChatMessage.SENDER_AGENT, author = "Ayşe"), "Asistan", "Temsilci"))
        val file = HsChatAttachment("a1", "fatura.pdf", "application/pdf", 10)
        assertNull(HsChatViewRules.displayText(message("m", 1, text = "fatura.pdf", attachments = listOf(file))))
        assertEquals("ekte", HsChatViewRules.displayText(message("m", 1, text = "ekte", attachments = listOf(file))))
        assertNull(HsChatViewRules.displayText(message("m", 1, text = "  ")))
        assertNull(HsChatViewRules.displayText(message("m", 1, text = null)))
    }

    @Test fun `renk yalnızca rrggbb biçiminde kabul edilir, tema seçimi`() {
        assertEquals(0xFF2563EB.toInt(), HsChatViewRules.color("#2563eb"))
        assertEquals(0xFFFFFFFF.toInt(), HsChatViewRules.color(" #FFFFFF "))
        assertNull(HsChatViewRules.color("#fff"))
        assertNull(HsChatViewRules.color("red"))
        assertNull(HsChatViewRules.color("#12345g"))
        assertNull(HsChatViewRules.color(null))
        assertTrue(HsChatViewRules.isDark("dark", systemDark = false))
        assertFalse(HsChatViewRules.isDark("light", systemDark = true))
        assertTrue(HsChatViewRules.isDark("auto", systemDark = true))
        assertFalse(HsChatViewRules.isDark(null, systemDark = false))
    }

    @Test fun `konuşma durumu ve e-posta doğrulama`() {
        assertTrue(HsChatViewRules.isResolved("resolved"))
        assertTrue(HsChatViewRules.isResolved("closed"))
        assertFalse(HsChatViewRules.isResolved("open"))
        assertFalse(HsChatViewRules.isResolved(null))
        assertTrue(HsChatViewRules.isValidEmail("ali@pushizo.com"))
        assertFalse(HsChatViewRules.isValidEmail("ali@"))
        assertFalse(HsChatViewRules.isValidEmail(""))
    }

    @Test fun `ek sınırları`() {
        val five = 5L * 1024 * 1024
        assertEquals(five, HsChatViewRules.maxAttachmentBytes(config()))
        // App ayarı SDK üst sınırını aşamaz; ayar yokken SDK sınırı.
        assertEquals(HsChatLimits.MAX_ATTACHMENT_BYTES, HsChatViewRules.maxAttachmentBytes(config(maxBytes = 99L * 1024 * 1024)))
        assertEquals(HsChatLimits.MAX_ATTACHMENT_BYTES, HsChatViewRules.maxAttachmentBytes(null))
        assertEquals(HsChatLimits.MAX_ATTACHMENT_BYTES, HsChatViewRules.maxAttachmentBytes(config(maxBytes = 0)))
        assertFalse(HsChatViewRules.attachmentTooLarge(five, config()))
        assertTrue(HsChatViewRules.attachmentTooLarge(five + 1, config()))
        assertFalse(HsChatViewRules.attachmentTooLarge(null, config()))
        assertEquals("5", HsChatViewRules.megabytes(five))
        assertEquals("1", HsChatViewRules.megabytes(10))
        assertArrayEquals(arrayOf("image/png", "application/pdf"), HsChatViewRules.attachmentTypes(config(types = listOf("image/png", "video/mp4", "application/pdf"))))
        assertEquals(HsChatLimits.ATTACHMENT_TYPES, HsChatViewRules.attachmentTypes(config()).toSet())
        assertEquals(HsChatLimits.ATTACHMENT_TYPES, HsChatViewRules.attachmentTypes(null).toSet())
    }

    @Test fun `sohbet ekranı manifest'te kayıtlı ve dışa kapalı, kullanılan metinler katalogda`() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val activity = Regex("<activity[^>]*android:name=\"\\.HsChatActivity\"[^>]*>").find(manifest)?.value
        assertTrue(activity != null && activity.contains("android:exported=\"false\""))
        val strings = File("src/main/res/values/strings.xml").readText()
        val used = Regex("R\\.string\\.(hs_chat_\\w+)").findAll(
            File("src/main/java/com/pushizo/sdk/HsChatView.kt").readText() + File("src/main/java/com/pushizo/sdk/HsChatActivity.kt").readText(),
        ).map { it.groupValues[1] }.toSet()
        assertTrue(used.size >= 20)
        for (name in used) assertTrue(name, strings.contains("<string name=\"$name\">"))
    }
}
