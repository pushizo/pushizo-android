package com.herosignal.sdk

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HsPayloadTest {
    @Test
    fun `fcm data ve notification alanlari ayristirilir`() {
        val data = mapOf(
            "hs_delivery_id" to "dlv_1",
            "hs_message_id" to "msg_1",
            "hs_url" to "myapp://orders/7",
            "hs_actions" to """[{"id":"track","title":"Takip et","url":"https://x/t"},{"id":"later","title":"Sonra"}]""",
            "order_id" to "7",
        )
        val n = HsNotification.fromFcm(data, "Başlık", "Gövde", "https://img", "c1", "orders")
        assertEquals("dlv_1", n.deliveryId)
        assertEquals("msg_1", n.messageId)
        assertEquals("myapp://orders/7", n.url)
        assertEquals(2, n.actions.size)
        assertEquals("https://x/t", n.actions[0].url)
        assertNull(n.actions[1].url)
        assertEquals(mapOf("order_id" to "7"), n.data)
        assertEquals("orders", n.channelId)
    }

    @Test
    fun `veri-only yeni hs bicimi ayristirilir`() {
        val data = mapOf(
            "hs" to """{"dlv":"dlv_9","delivery_id":"dlv_9","msg":"msg_9","url":"https://x/n","buttons":[{"id":"ok","title":"Tamam","icon":"https://i"}]}""",
            "hs_delivery_id" to "dlv_9",
            "hs_title" to "Başlık",
            "hs_body" to "Gövde",
            "hs_subtitle" to "Alt",
            "hs_image" to "https://img",
            "hs_channel" to "promo",
            "hs_sound" to "ding",
            "hs_collapse" to "c9",
            "hs_group" to "g1",
            "kampanya" to "yaz",
        )
        val n = HsNotification.fromFcm(data, null, null, null, null, null)
        assertEquals("dlv_9", n.deliveryId)
        assertEquals("msg_9", n.messageId)
        assertEquals("Başlık", n.title)
        assertEquals("Gövde", n.body)
        assertEquals("Alt", n.subtitle)
        assertEquals("https://img", n.imageUrl)
        assertEquals("promo", n.channelId)
        assertEquals("ding", n.sound)
        assertEquals("c9", n.collapseKey)
        assertEquals("g1", n.groupKey)
        assertEquals("https://x/n", n.url)
        assertEquals(listOf(HsAction("ok", "Tamam", null, "https://i")), n.actions)
        assertEquals(mapOf("kampanya" to "yaz"), n.data)
        assertEquals(false, n.silent)
        assertEquals(true, HsNotification.isHeroSignal(data))
        assertEquals(false, HsNotification.isHeroSignal(mapOf("other" to "1")))
    }

    @Test
    fun `hs yoksa eski anahtarlara duser, sessiz mesaj isaretlenir`() {
        val n = HsNotification.fromFcm(mapOf("hs" to "{bozuk", "hs_delivery_id" to "dlv_old", "hs_message_id" to "msg_old", "hs_silent" to "1"), null, null, null, null, null)
        assertEquals("dlv_old", n.deliveryId)
        assertEquals("msg_old", n.messageId)
        assertEquals(true, n.silent)
        assertNull(n.title)
        // notification bloğu değerleri önceliklidir (eski payload uyumluluğu)
        val legacy = HsNotification.fromFcm(mapOf("hs_title" to "veri"), "blok", null, null, "tag1", "ch")
        assertEquals("blok", legacy.title)
        assertEquals("tag1", legacy.collapseKey)
        assertEquals("ch", legacy.channelId)
    }

    @Test
    fun `ikon, vurgu rengi, gorunurluk ve grup anahtarlari ayristirilir`() {
        val data = mapOf(
            "hs_delivery_id" to "dlv_5",
            "hs_title" to "T",
            "hs_small_icon" to "ic_stat_bell",
            "hs_large_icon" to "https://cdn.example.com/avatar.png",
            "hs_accent" to "FF0A84FF",
            "hs_visibility" to "-1",
            "hs_group" to "orders",
        )
        val n = HsNotification.fromFcm(data, null, null, null, null, null)
        assertEquals("ic_stat_bell", n.smallIcon)
        assertEquals("https://cdn.example.com/avatar.png", n.largeIcon)
        assertEquals(0xFF0A84FF.toInt(), n.accentColor)
        assertEquals(-1, n.visibility)
        assertEquals("orders", n.groupKey)
        assertEquals("dlv_5".hashCode(), n.androidNotificationId)
        assertEquals(true, HsNotification.isRemoteIcon(n.largeIcon!!))
        assertEquals(false, HsNotification.isRemoteIcon("ic_launcher"))
        // hs_* anahtarları müşteri verisine karışmaz
        assertEquals(emptyMap<String, String>(), n.data)
        // collapse kimliği varsa sistem kimliği ondan türetilir
        assertEquals("c1".hashCode(), HsNotification.fromFcm(data + ("hs_collapse" to "c1"), null, null, null, null, null).androidNotificationId)
        assertNull(HsNotification.fromFcm(mapOf("hs_title" to "T"), null, null, null, null, null).androidNotificationId)
    }

    @Test
    fun `vurgu rengi ve gorunurluk dogrulanir`() {
        assertEquals(0xFF112233.toInt(), HsNotification.parseAccentColor("FF112233"))
        assertEquals(0x80112233.toInt(), HsNotification.parseAccentColor("#80112233"))
        assertEquals(0xFFAABBCC.toInt(), HsNotification.parseAccentColor("aabbcc"))
        assertNull(HsNotification.parseAccentColor("GG112233"))
        assertNull(HsNotification.parseAccentColor("12345"))
        assertNull(HsNotification.parseAccentColor(""))
        assertNull(HsNotification.parseAccentColor(null))
        assertEquals(1, HsNotification.parseVisibility("1"))
        assertEquals(0, HsNotification.parseVisibility("0"))
        assertEquals(-1, HsNotification.parseVisibility("-1"))
        assertNull(HsNotification.parseVisibility("2"))
        assertNull(HsNotification.parseVisibility("public"))
        assertNull(HsNotification.parseVisibility(null))
    }

    @Test
    fun `bozuk aksiyon json'u bos listeye duser`() {
        val n = HsNotification.fromFcm(mapOf("hs_actions" to "{bozuk"), "T", null, null, null, null)
        assertEquals(0, n.actions.size)
    }

    @Test
    fun `ardisik tag yamalari birlestirilir`() {
        val a = JSONObject().put("type", "tags").put("tags", JSONObject().put("a", "1")).put("at", 1)
        val b = JSONObject().put("type", "tags").put("tags", JSONObject().put("b", "2").put("a", JSONObject.NULL)).put("at", 2)
        val merged = HsQueue.mergeTail(mutableListOf(a, b))
        assertEquals(1, merged.size)
        val tags = merged[0].getJSONObject("tags")
        assertEquals("2", tags.getString("b"))
        assertEquals(true, tags.isNull("a"))
        val event = JSONObject().put("type", "event").put("name", "x")
        assertEquals(2, HsQueue.mergeTail(mutableListOf(a, event)).size)
    }

    @Test
    fun `ardisik olaylar en fazla 100 luk toplu islem olur`() {
        val events = (0 until 150).map { JSONObject().put("type", "event").put("name", "e$it").put("at", it) }
        val tag = JSONObject().put("type", "tags").put("tags", JSONObject()).put("at", 999)
        val run = HsQueue.leadingEvents(events + tag)
        assertEquals(HsQueue.EVENT_BATCH_MAX, run.size)
        assertEquals(0, HsQueue.leadingEvents(listOf(tag) + events).size)
        val batch = HsQueue.batchOf(run)
        assertEquals("event_batch", batch.getString("type"))
        assertEquals(100, batch.getJSONArray("events").length())
        assertEquals("e0", batch.getJSONArray("events").getJSONObject(0).getString("name"))
    }
}
