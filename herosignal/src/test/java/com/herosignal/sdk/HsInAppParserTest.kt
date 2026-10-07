package com.herosignal.sdk

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HsInAppParserTest {
    private val payload = """
        {
          "language": "tr",
          "data": [
            {
              "id": "iam_1",
              "priority": 10,
              "triggers": [
                { "kind": "event", "name": "cart_viewed" },
                { "kind": "trigger", "key": "cart_total", "op": "gt", "value": 100 }
              ],
              "display": {
                "layout": "banner_bottom",
                "background_color": "#ffffff",
                "text_color": null,
                "dismissible": false,
                "auto_dismiss_seconds": 8,
                "blocks": [
                  { "type": "image", "url": "https://cdn.example.com/sale.png", "alt": "İndirim" },
                  { "type": "title", "text": "Merhaba Ayşe" },
                  { "type": "body", "text": "Sepetin seni bekliyor" },
                  { "type": "button", "id": "go", "text": "Sepete git", "action": { "type": "deeplink", "url": "myapp://cart" }, "background_color": "#18181b", "text_color": null },
                  { "type": "button", "id": "web", "text": "Web", "action": { "type": "url", "url": "https://example.com/sale" }, "background_color": null, "text_color": null },
                  { "type": "button", "id": "vip", "text": "VIP", "action": { "type": "tags", "tags": { "segment": "vip" } }, "background_color": null, "text_color": null },
                  { "type": "button", "id": "bad", "text": "Kötü", "action": { "type": "url", "url": "javascript:alert(1)" }, "background_color": null, "text_color": null },
                  { "type": "video", "url": "https://cdn.example.com/x.mp4" }
                ]
              },
              "remaining_impressions": 2,
              "min_interval_seconds": 86400,
              "stop_after_interaction": true,
              "last_impression_at": "2026-10-06T12:00:00.000Z",
              "end_at": null
            },
            { "id": "iam_broken", "priority": 1 },
            {
              "id": "iam_2", "priority": 0, "triggers": [{ "kind": "session_start" }],
              "display": { "layout": "carousel", "background_color": null, "text_color": null, "dismissible": true, "auto_dismiss_seconds": null, "blocks": [] },
              "remaining_impressions": 1, "min_interval_seconds": 0, "last_impression_at": null, "end_at": "2026-12-31T23:59:59+03:00"
            }
          ]
        }
    """.trimIndent()

    @Test
    fun `liste ayristirilir, bozuk mesaj atlanir`() {
        HsLog.level = HsLog.Level.NONE
        val list = HsInAppParser.parseList(JSONObject(payload))
        assertEquals(listOf("iam_1", "iam_2"), list.map { it.id })

        val m = list[0]
        assertEquals(10, m.priority)
        assertEquals(HsInAppTrigger.Event("cart_viewed"), m.triggers[0])
        assertEquals(HsInAppTrigger.Condition("cart_total", "gt", 100), m.triggers[1])
        assertEquals(HsInAppLayout.BANNER_BOTTOM, m.display.layout)
        assertEquals("#ffffff", m.display.backgroundColor)
        assertNull(m.display.textColor)
        assertFalse(m.display.dismissible)
        assertEquals(8, m.display.autoDismissSeconds)
        assertEquals(2, m.remainingImpressions)
        assertEquals(86_400L, m.minIntervalSeconds)
        assertTrue(m.stopAfterInteraction)
        assertEquals(HsIsoTime.parse("2026-10-06T12:00:00Z"), m.lastImpressionAt)
        assertNull(m.endAt)

        // Görsel, başlık, gövde ve geçerli 3 düğme; geçersiz URL'li düğme ve tanınmayan blok atlanır.
        assertEquals(6, m.display.blocks.size)
        assertEquals(HsInAppBlock.Image("https://cdn.example.com/sale.png", "İndirim"), m.display.blocks[0])
        assertEquals(HsInAppBlock.Title("Merhaba Ayşe"), m.display.blocks[1])
        val buttons = m.display.buttons
        assertEquals(listOf("go", "web", "vip"), buttons.map { it.id })
        assertEquals(HsInAppAction.Deeplink("myapp://cart"), buttons[0].action)
        assertEquals("#18181b", buttons[0].backgroundColor)
        assertEquals(HsInAppAction.Url("https://example.com/sale"), buttons[1].action)
        assertEquals(HsInAppAction.Tags(mapOf("segment" to "vip")), buttons[2].action)
        assertTrue(m.toJson().contains("\"iam_1\""))

        val m2 = list[1]
        assertEquals(HsInAppLayout.MODAL, m2.display.layout) // bilinmeyen düzen → modal
        assertNull(m2.display.autoDismissSeconds)
        assertFalse(m2.stopAfterInteraction) // alan yoksa false
        assertEquals(HsIsoTime.parse("2026-12-31T20:59:59Z"), m2.endAt)
    }

    @Test
    fun `ISO zaman ayristirma`() {
        assertEquals(0L, HsIsoTime.parse("1970-01-01T00:00:00Z"))
        assertEquals(1_500L, HsIsoTime.parse("1970-01-01T00:00:01.5Z"))
        assertEquals(1_759_838_400_000L, HsIsoTime.parse("2025-10-07T12:00:00Z"))
        assertEquals(HsIsoTime.parse("2025-10-07T12:00:00Z"), HsIsoTime.parse("2025-10-07T15:00:00+03:00"))
        assertEquals(HsIsoTime.parse("2025-10-07T12:00:00Z"), HsIsoTime.parse("2025-10-07T07:00:00-0500"))
        assertNull(HsIsoTime.parse("2025-10-07"))
        assertNull(HsIsoTime.parse("dün"))
    }

    @Test
    fun `renkler sistem temasina gore varsayilir`() {
        val light = HsInAppStyle.resolve(null, null, dark = false)
        assertEquals(HsInAppStyle.LIGHT_BACKGROUND, light.background)
        assertEquals(HsInAppStyle.LIGHT_TEXT, light.text)
        val dark = HsInAppStyle.resolve(null, null, dark = true)
        assertEquals(HsInAppStyle.DARK_BACKGROUND, dark.background)
        assertEquals(HsInAppStyle.DARK_TEXT, dark.text)
        // Yalnızca arka plan verilirse metin kontrasta göre seçilir (tema yok sayılır).
        val custom = HsInAppStyle.resolve("#18181b", null, dark = false)
        assertEquals(HsInAppStyle.DARK_TEXT, custom.text)
        assertEquals(HsInAppStyle.parseColor("#ff0000"), HsInAppStyle.resolve("#fff", "#ff0000", dark = true).text)
        val button = HsInAppBlock.Button("b", "B", HsInAppAction.Dismiss, "#facc15", null)
        assertEquals(HsInAppStyle.parseColor("#facc15")!! to HsInAppStyle.LIGHT_TEXT, HsInAppStyle.buttonColors(button, light))
    }

    @Test
    fun `inbox sayfasi ayristirilir`() {
        val page = HsInboxParser.parsePage(JSONObject("""
            {
              "data": [
                { "id": "ibx_2", "message_id": "msg_2", "title": "Kargo", "body": "Siparişin yolda", "url": "myapp://orders/2", "image_url": null,
                  "data": { "order": "2" }, "read": false, "read_at": null, "archived_at": null, "expires_at": "2026-11-01T00:00:00Z", "created_at": "2026-10-07T10:00:00Z" },
                { "id": "ibx_1", "message_id": null, "title": null, "body": "Hoş geldin", "url": null, "image_url": "https://cdn.example.com/a.png",
                  "data": {}, "read": true, "read_at": "2026-10-06T09:00:00Z", "archived_at": null, "expires_at": null, "created_at": "2026-10-06T08:00:00Z" }
              ],
              "unread_count": 1,
              "next_cursor": "c2",
              "has_more": true
            }
        """.trimIndent()))
        assertEquals(listOf("ibx_2", "ibx_1"), page.items.map { it.id })
        assertEquals(1, page.unreadCount)
        assertEquals("c2", page.nextCursor)
        assertTrue(page.hasMore)
        val first = page.items[0]
        assertEquals("Siparişin yolda", first.body)
        assertEquals(mapOf("order" to "2"), first.data)
        assertFalse(first.read)
        assertNull(first.imageUrl)
        assertEquals(HsIsoTime.parse("2026-10-07T10:00:00Z"), first.createdAt)
        assertNull(page.items[1].title)
        assertNull(page.items[1].messageId)
        assertTrue(page.items[1].read)

        val empty = HsInboxParser.parsePage(JSONObject("""{ "data": [], "unread_count": 0, "next_cursor": null, "has_more": false }"""))
        assertTrue(empty.items.isEmpty())
        assertNull(empty.nextCursor)
    }

    @Test
    fun `yerel gosterim sayaci ve oncelik`() {
        val m = HsInAppParser.parseMessage(JSONObject("""
            { "id": "iam_x", "priority": 1, "triggers": [{ "kind": "event", "name": "e" }],
              "display": { "layout": "modal", "dismissible": true, "blocks": [] },
              "remaining_impressions": 2, "min_interval_seconds": 0, "last_impression_at": null, "end_at": null }
        """.trimIndent()))
        val cause = HsInAppCause.Event("e")
        assertEquals("iam_x", HsInAppRules.select(listOf(m), cause, HsInAppState(), mapOf("iam_x" to HsLocalImpressions(1, 0)), 1_000)?.id)
        assertNull(HsInAppRules.select(listOf(m), cause, HsInAppState(), mapOf("iam_x" to HsLocalImpressions(2, 0)), 1_000))
    }
}
