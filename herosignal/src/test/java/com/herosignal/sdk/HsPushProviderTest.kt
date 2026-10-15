package com.herosignal.sdk

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HsPushProviderTest {

    @Test
    fun `HMS data JSON metni FCM data mapine cevrilir`() {
        val hs = JSONObject().put("dlv", "dlv_1").put("msg", "msg_1")
        val actions = JSONArray().put(JSONObject().put("id", "buy").put("title", "Satın al").put("url", "https://example.com/buy"))
        val json = JSONObject()
            .put("hs", hs.toString()) // sunucu `hs` alanını metin olarak gönderir
            .put("hs_delivery_id", "dlv_1")
            .put("hs_title", "Kampanya")
            .put("hs_body", "Sepetinizde ürün var")
            .put("hs_actions", actions) // iç içe dizi de kabul edilir
            .put("hs_silent", 0)
            .put("hs_accent_color", "FF9900FF")
            .put("order_id", 42)
            .put("vip", true)
            .put("empty", JSONObject.NULL)
            .toString()

        val data = HsPushBridge.dataFromJson(json)

        assertEquals("Kampanya", data["hs_title"])
        assertEquals("0", data["hs_silent"])
        assertEquals("42", data["order_id"])
        assertEquals("true", data["vip"])
        assertFalse("null değerler atlanır", data.containsKey("empty"))
        assertEquals("dlv_1", JSONObject(data.getValue("hs")).getString("dlv"))

        // FCM ile aynı ayrıştırma yolu: teslimat/mesaj kimliği, düğmeler, renk ve müşteri verisi.
        val n = HsNotification.fromFcm(data, null, null, null, null, null)
        assertTrue(HsNotification.isHeroSignal(data))
        assertEquals("dlv_1", n.deliveryId)
        assertEquals("msg_1", n.messageId)
        assertEquals("Kampanya", n.title)
        assertEquals("Sepetinizde ürün var", n.body)
        assertEquals(listOf(HsAction("buy", "Satın al", "https://example.com/buy")), n.actions)
        assertEquals(0xFF9900FF.toInt(), n.accentColor)
        assertFalse(n.silent)
        assertEquals(mapOf("order_id" to "42", "vip" to "true"), n.data)
    }

    @Test
    fun `gecersiz veya bos HMS verisi bos map verir`() {
        assertTrue(HsPushBridge.dataFromJson(null).isEmpty())
        assertTrue(HsPushBridge.dataFromJson("").isEmpty())
        assertTrue(HsPushBridge.dataFromJson("   ").isEmpty())
        assertTrue(HsPushBridge.dataFromJson("{bozuk").isEmpty())
        assertTrue(HsPushBridge.dataFromJson("[1,2]").isEmpty())
    }

    @Test
    fun `kanal secimi yalnizca HMS modulu varken, GMS yokken ve HMS Core varken hms_push`() {
        val hms = HsPushProviders.CHANNEL_HMS
        val fcm = HsPushProviders.CHANNEL_FCM
        assertEquals(hms, HsPushProviders.selectChannel(hmsModulePresent = true, gmsAvailable = false, hmsAvailable = true))
        // GMS'li Huawei cihazı: FCM.
        assertEquals(fcm, HsPushProviders.selectChannel(hmsModulePresent = true, gmsAvailable = true, hmsAvailable = true))
        // HMS Core yok: FCM (önceki davranış).
        assertEquals(fcm, HsPushProviders.selectChannel(hmsModulePresent = true, gmsAvailable = false, hmsAvailable = false))
        // Modül eklenmemiş: her durumda FCM.
        assertEquals(fcm, HsPushProviders.selectChannel(hmsModulePresent = false, gmsAvailable = false, hmsAvailable = true))
        assertEquals(fcm, HsPushProviders.selectChannel(hmsModulePresent = false, gmsAvailable = true, hmsAvailable = false))
    }

    @Test
    fun `HMS modulu yoksa saglayici bulunmaz`() {
        // Çekirdek test sınıf yolunda `herosignal-hms` yoktur.
        assertNull(HsPushProviders.hmsProvider())
    }

    private data class Call(val method: String, val path: String, val body: JSONObject?)

    @Test
    fun `kanal degisince yeni kanalda abonelik acilir ve eski abonelik bildirilir`() {
        val calls = mutableListOf<Call>()
        val sync = HsTokenSync { method, path, body ->
            calls.add(Call(method, path, body))
            JSONObject().put("subscription_id", "sub_hms").put("user_id", "usr_1").put("external_id", JSONObject.NULL)
        }
        val result = sync.sync("sub_fcm", "fcm_tok", "hms_tok", JSONObject(), JSONObject(), HsPushProviders.CHANNEL_HMS, null)

        assertEquals(HsTokenSyncResult.Created("sub_hms", "usr_1", null, replacedSubscriptionId = "sub_fcm"), result)
        assertEquals("kanal PATCH ile değiştirilemez; yalnızca POST", listOf("POST"), calls.map { it.method })
        val body = calls.single().body!!
        assertEquals("hms_push", body.getString("channel"))
        assertEquals("hms_tok", body.getString("token"))
        assertTrue(body.getBoolean("ownership_proof"))
        // Anonim cihaz: yeni abonelik eski aboneliğin kullanıcısına bağlanır (kanıt: eski token'ın özeti).
        assertEquals("sub_fcm", body.getJSONObject("user").getString("subscription_id"))
        assertEquals(HsOwnership.tokenHash("fcm_tok"), body.getJSONObject("user").getString("token_hash"))
    }

    @Test
    fun `kimlikli kullanicida kanal degisiminde external_id korunur, eski aboneliğe baglama yapilmaz`() {
        val calls = mutableListOf<Call>()
        val sync = HsTokenSync { method, path, body ->
            calls.add(Call(method, path, body))
            JSONObject().put("subscription_id", "sub_hms").put("user_id", "usr_1").put("external_id", "u-42")
        }
        val user = JSONObject().put("external_id", "u-42")
        sync.sync("sub_fcm", "fcm_tok", "hms_tok", JSONObject(), user, HsPushProviders.CHANNEL_HMS, HsPushProviders.CHANNEL_FCM)
        val sentUser = calls.single().body!!.getJSONObject("user")
        assertEquals("u-42", sentUser.getString("external_id"))
        assertFalse(sentUser.has("subscription_id"))
    }

    @Test
    fun `ayni kanalda ayni token gonderilmez, onceki surum kaydi android_push sayilir`() {
        val calls = mutableListOf<Call>()
        val sync = HsTokenSync { method, path, body -> calls.add(Call(method, path, body)); JSONObject() }
        assertEquals(HsTokenSyncResult.Unchanged, sync.sync("sub_1", "tok", "tok", JSONObject(), JSONObject(), HsPushProviders.CHANNEL_FCM, null))
        assertEquals(HsTokenSyncResult.Unchanged, sync.sync("sub_2", "hms", "hms", JSONObject(), JSONObject(), HsPushProviders.CHANNEL_HMS, HsPushProviders.CHANNEL_HMS))
        assertTrue(calls.isEmpty())
        // HMS'te token yenilenince aynı abonelik PATCH ile güncellenir.
        assertEquals(HsTokenSyncResult.Updated, sync.sync("sub_2", "hms", "hms_new", JSONObject(), JSONObject(), HsPushProviders.CHANNEL_HMS, HsPushProviders.CHANNEL_HMS))
        assertEquals(Call("PATCH", "/subscriptions/sub_2", calls.single().body), calls.single())
        assertEquals("hms_new", calls.single().body!!.getString("token"))
    }
}
