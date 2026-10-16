package com.pushizo.sdk

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HsEventsTest {
    private fun eventOp(name: String, value: Double? = null) = JSONObject()
        .put("type", "event").put("name", name).put("properties", JSONObject().put("sku", "A1"))
        .put("occurred_at", "2026-10-14T10:00:00Z").put("at", 1L)
        .apply { value?.let { put("value", it) } }

    @Test
    fun `olay ogeleri sahiplik kanitini icerir`() {
        val tokenHash = HsOwnership.tokenHash("fcm_token")
        val identityHash = "b".repeat(64)
        val items = HsEvents.items(listOf(eventOp("purchase", 49.9), eventOp("view")), "sub_1", tokenHash, identityHash)
        assertEquals(2, items.length())
        val first = items.getJSONObject(0)
        assertEquals("purchase", first.getString("name"))
        assertEquals("sub_1", first.getString("subscription_id"))
        assertEquals(49.9, first.getDouble("value"), 0.0)
        assertEquals("A1", first.getJSONObject("properties").getString("sku"))
        assertEquals(tokenHash, first.getString("token_hash"))
        assertEquals(identityHash, first.getString("identity_hash"))
        assertEquals(tokenHash, items.getJSONObject(1).getString("token_hash"))
        assertFalse(items.getJSONObject(1).has("value"))
    }

    @Test
    fun `istemci event_id varsa iletilir, eski kuyruk ogesinde gonderilmez`() {
        val withId = HsEvents.items(listOf(eventOp("view").put("event_id", "e-123456789")), "sub_1", null, null).getJSONObject(0)
        assertEquals("e-123456789", withId.getString("event_id"))
        val legacy = HsEvents.items(listOf(eventOp("view")), "sub_1", null, null).getJSONObject(0)
        assertFalse(legacy.has("event_id"))
    }

    @Test
    fun `kanit yoksa alanlar gonderilmez`() {
        val item = HsEvents.items(listOf(eventOp("view")), "sub_1", null, null).getJSONObject(0)
        assertFalse(item.has("token_hash"))
        assertFalse(item.has("identity_hash"))
    }

    private fun device(appVersion: String?) = HsEvents.deviceInfo(
        model = "Google Pixel 8", osVersion = "15", appVersion = appVersion, sdkVersion = "0.1.0",
        locale = "tr-TR", timezone = "Europe/Istanbul", pushCapable = false,
    )

    @Test
    fun `app_version bilinmiyorsa alan gonderilmez`() {
        val json = device(null)
        assertFalse("JSON null sunucuda 400 ile reddedilir", json.has("app_version"))
        assertNull(json.opt("app_version"))
        assertFalse(json.toString().contains("app_version"))
        assertFalse(device("").has("app_version"))
    }

    @Test
    fun `cihaz bilgisi push_capable ve surumleri icerir`() {
        val json = device("2.3.1")
        assertEquals("2.3.1", json.getString("app_version"))
        assertEquals("android", json.getString("sdk_platform"))
        assertEquals("Android", json.getString("os"))
        assertTrue(json.has("push_capable"))
        assertFalse(json.getBoolean("push_capable"))
    }
}
