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
    fun `tetik gruplari, session_duration, varyant ve yeni aksiyonlar ayristirilir`() {
        val m = HsInAppParser.parseMessage(JSONObject("""
            { "id": "iam_g", "priority": 3, "variant_id": "var_b",
              "triggers": [{ "kind": "event", "name": "cart_viewed" }],
              "trigger_groups": [
                [{ "kind": "event", "name": "cart_viewed" }, { "kind": "trigger", "key": "cart_total", "op": "gt", "value": 100 }],
                [{ "kind": "session_duration", "seconds": 30 }]
              ],
              "display": { "layout": "modal", "dismissible": true, "blocks": [
                { "type": "button", "id": "allow", "text": "İzin ver", "action": { "type": "push_prompt" } },
                { "type": "button", "id": "buy", "text": "Satın al", "action": { "type": "outcome", "name": "purchase", "value": 49.9 } },
                { "type": "button", "id": "later", "text": "Sonra", "action": { "type": "outcome", "name": "remind_later" } },
                { "type": "button", "id": "noname", "text": "Bozuk", "action": { "type": "outcome" } }
              ] },
              "remaining_impressions": 1, "min_interval_seconds": 0, "last_impression_at": null, "end_at": null }
        """.trimIndent()))
        assertEquals("var_b", m.variantId)
        assertEquals(2, m.triggerGroups.size)
        assertEquals(m.triggerGroups[0], m.triggers) // geriye uyum: ilk grup
        assertEquals(listOf(HsInAppTrigger.SessionDuration(30)), m.triggerGroups[1])
        val buttons = m.display.buttons
        assertEquals(listOf("allow", "buy", "later"), buttons.map { it.id }) // adsız outcome düğmesi atlanır
        assertEquals(HsInAppAction.PushPrompt, buttons[0].action)
        assertEquals(HsInAppAction.Outcome("purchase", 49.9), buttons[1].action)
        assertEquals(HsInAppAction.Outcome("remind_later", null), buttons[2].action)

        // trigger_groups yok/boş → triggers tek grup; variant_id yok → null.
        val legacy = HsInAppParser.parseMessage(JSONObject("""
            { "id": "iam_l", "priority": 0, "triggers": [{ "kind": "screen", "name": "home" }], "trigger_groups": [], "variant_id": null,
              "display": { "layout": "modal", "dismissible": true, "blocks": [] }, "remaining_impressions": 1 }
        """.trimIndent()))
        assertEquals(listOf(listOf(HsInAppTrigger.Screen("home"))), legacy.triggerGroups)
        assertNull(legacy.variantId)
        assertEquals(listOf(emptyList<HsInAppTrigger>()), HsInAppParser.parseTriggerGroups(null, org.json.JSONArray()))
    }

    @Test
    fun `carousel blogu ayristirilir, kart dugmeleri dugme listesine katilir`() {
        val m = HsInAppParser.parseMessage(JSONObject("""
            { "id": "iam_c", "priority": 0, "triggers": [],
              "display": { "layout": "modal", "dismissible": true, "content_type": "blocks", "html": null, "html_height": null, "blocks": [
                { "type": "title", "text": "Yeni özellikler" },
                { "type": "carousel", "id": "steps", "cards": [
                  { "id": "c1", "title": "Adım 1", "body": "Profilini tamamla", "image_url": "https://cdn.example.com/1.png", "image_alt": "Profil",
                    "button": { "id": "b1", "text": "Başla", "action": { "type": "deeplink", "url": "myapp://profile" } } },
                  { "id": "c2", "title": "Adım 2", "body": null, "image_url": null, "image_alt": null, "button": null },
                  { "id": "c3", "title": "Adım 3", "body": null, "image_url": null, "image_alt": null,
                    "button": { "id": "bad", "text": "Kötü", "action": { "type": "url", "url": "javascript:alert(1)" } } },
                  { "title": "Kimliksiz kart" }
                ] },
                { "type": "carousel", "id": "empty", "cards": [] },
                { "type": "button", "id": "close", "text": "Kapat", "action": { "type": "dismiss" } },
                { "type": "countdown", "id": "future" }
              ] } }
        """.trimIndent()))
        assertEquals(HsInAppContentType.BLOCKS, m.display.contentType)
        assertFalse(m.display.isHtml)
        assertNull(m.display.html)
        // Başlık, carousel ve düğme; kartsız carousel ve tanınmayan blok atlanır.
        assertEquals(3, m.display.blocks.size)
        val carousel = m.display.blocks[1] as HsInAppBlock.Carousel
        assertEquals("steps", carousel.id)
        assertEquals(listOf("c1", "c2", "c3"), carousel.cards.map { it.id }) // kimliksiz kart atlanır
        val first = carousel.cards[0]
        assertEquals("Adım 1", first.title)
        assertEquals("Profilini tamamla", first.body)
        assertEquals("https://cdn.example.com/1.png", first.imageUrl)
        assertEquals("Profil", first.imageAlt)
        assertEquals(HsInAppAction.Deeplink("myapp://profile"), first.button?.action)
        assertNull(carousel.cards[1].button)
        assertNull(carousel.cards[1].body)
        assertNull(carousel.cards[2].button) // geçersiz aksiyonlu kart düğmesi düşer, kart kalır
        // Tıklama bildirimi düğme listesinden çözülür: kart düğmeleri de bulunur.
        assertEquals(listOf("b1", "close"), m.display.buttons.map { it.id })
    }

    @Test
    fun `HTML icerik ayristirilir, eski sunucu yanitinda bloklar varsayilir`() {
        HsLog.level = HsLog.Level.NONE
        val list = HsInAppParser.parseList(JSONObject("""
            { "language": "tr", "data": [
              { "id": "iam_h", "priority": 1, "triggers": [{ "kind": "session_start" }],
                "display": { "layout": "banner_bottom", "dismissible": true, "content_type": "html",
                  "html": "<h1>Merhaba Ayşe</h1><button data-hs-close>Kapat</button>", "html_height": 140,
                  "blocks": [{ "type": "title", "text": "yok sayılır" }] } },
              { "id": "iam_empty", "priority": 0, "triggers": [],
                "display": { "layout": "modal", "content_type": "html", "html": null, "html_height": null, "blocks": [] } },
              { "id": "iam_legacy", "priority": 0, "triggers": [],
                "display": { "layout": "modal", "blocks": [{ "type": "body", "text": "Eski" }] } }
            ] }
        """.trimIndent()))
        // İçeriksiz HTML mesajı atlanır.
        assertEquals(listOf("iam_h", "iam_legacy"), list.map { it.id })
        val html = list[0]
        assertTrue(html.display.isHtml)
        assertEquals("<h1>Merhaba Ayşe</h1><button data-hs-close>Kapat</button>", html.display.html)
        assertEquals(140, html.display.htmlHeight)
        assertTrue(html.display.blocks.isEmpty())
        assertTrue(html.display.buttons.isEmpty())
        assertEquals("tr", html.language)
        val legacy = list[1]
        assertEquals(HsInAppContentType.BLOCKS, legacy.display.contentType)
        assertNull(legacy.display.htmlHeight)
        assertEquals(listOf<HsInAppBlock>(HsInAppBlock.Body("Eski")), legacy.display.blocks)
    }

    @Test
    fun `tetik gruplari VEYA, grup ici VE olarak degerlendirilir`() {
        val groups = HsInAppParser.parseTriggerGroups(org.json.JSONArray("""
            [
              [{ "kind": "event", "name": "cart_viewed" }, { "kind": "trigger", "key": "cart_total", "op": "gt", "value": 100 }],
              [{ "kind": "screen", "name": "checkout" }]
            ]
        """.trimIndent()), org.json.JSONArray())
        val cheap = HsInAppState(triggers = mapOf("cart_total" to "50"))
        val rich = HsInAppState(triggers = mapOf("cart_total" to "150"))
        assertFalse(HsInAppRules.matchesAny(groups, HsInAppCause.Event("cart_viewed"), cheap)) // ilk grup VE: koşul sağlanmadı
        assertTrue(HsInAppRules.matchesAny(groups, HsInAppCause.Event("cart_viewed"), rich))
        assertTrue(HsInAppRules.matchesAny(groups, HsInAppCause.Screen("checkout"), HsInAppState(screen = "checkout"))) // ikinci grup
        assertFalse(HsInAppRules.matchesAny(groups, HsInAppCause.Screen("home"), HsInAppState(screen = "home")))
        // Boş grup listesi tetiksiz mesaj gibi: yalnızca oturum başı.
        assertTrue(HsInAppRules.matchesAny(emptyList(), HsInAppCause.SessionStart, HsInAppState()))
        assertFalse(HsInAppRules.matchesAny(emptyList(), HsInAppCause.Event("x"), HsInAppState()))
    }

    @Test
    fun `session_duration zamanlayiciyla ateslenir, sonraki degerlendirmelerde kosul olarak kalir`() {
        val duration = listOf(HsInAppTrigger.SessionDuration(30))
        assertFalse(HsInAppRules.matches(duration, HsInAppCause.SessionDuration, HsInAppState(sessionSeconds = 29)))
        assertTrue(HsInAppRules.matches(duration, HsInAppCause.SessionDuration, HsInAppState(sessionSeconds = 30)))
        assertFalse(HsInAppRules.matches(duration, HsInAppCause.SessionStart, HsInAppState(sessionSeconds = 0)))
        // Olayla birlikte: olay ateşler, süre koşulu sağlanmalı.
        val combined = duration + HsInAppTrigger.Event("cart_viewed")
        assertFalse(HsInAppRules.matches(combined, HsInAppCause.Event("cart_viewed"), HsInAppState(sessionSeconds = 10)))
        assertTrue(HsInAppRules.matches(combined, HsInAppCause.Event("cart_viewed"), HsInAppState(sessionSeconds = 45)))
        assertFalse(HsInAppRules.matches(combined, HsInAppCause.SessionDuration, HsInAppState(sessionSeconds = 45))) // olay anı değil

        val messages = listOf(
            HsInAppParser.parseMessage(JSONObject("""{ "id": "a", "trigger_groups": [[{ "kind": "session_duration", "seconds": 60 }], [{ "kind": "session_duration", "seconds": 20 }]], "display": { "blocks": [] } }""")),
            HsInAppParser.parseMessage(JSONObject("""{ "id": "b", "triggers": [{ "kind": "event", "name": "x" }], "display": { "blocks": [] } }""")),
        )
        assertEquals(20L, HsInAppRules.nextSessionDurationSeconds(messages, 0))
        assertEquals(60L, HsInAppRules.nextSessionDurationSeconds(messages, 20))
        assertNull(HsInAppRules.nextSessionDurationSeconds(messages, 60))
        assertNull(HsInAppRules.nextSessionDurationSeconds(listOf(messages[1]), 0))
    }

    @Test
    fun `app geneli gosterim araligi tum mesajlari kapsar`() {
        assertTrue(HsInAppRules.globalIntervalAllows(0, 5_000, 6_000))
        assertTrue(HsInAppRules.globalIntervalAllows(60, null, 6_000))
        assertFalse(HsInAppRules.globalIntervalAllows(60, 100_000, 159_999))
        assertTrue(HsInAppRules.globalIntervalAllows(60, 100_000, 160_000))
        val m = HsInAppParser.parseMessage(JSONObject("""
            { "id": "iam_x", "priority": 1, "triggers": [{ "kind": "event", "name": "e" }],
              "display": { "layout": "modal", "dismissible": true, "blocks": [] }, "remaining_impressions": 5, "min_interval_seconds": 0 }
        """.trimIndent()))
        val cause = HsInAppCause.Event("e")
        assertNull(HsInAppRules.select(listOf(m), cause, HsInAppState(), emptyMap(), 150_000, globalMinIntervalSeconds = 60, lastImpressionAt = 100_000))
        assertEquals("iam_x", HsInAppRules.select(listOf(m), cause, HsInAppState(), emptyMap(), 160_000, globalMinIntervalSeconds = 60, lastImpressionAt = 100_000)?.id)
        assertEquals("iam_x", HsInAppRules.select(listOf(m), cause, HsInAppState(), emptyMap(), 150_000, globalMinIntervalSeconds = 0, lastImpressionAt = 100_000)?.id)
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
