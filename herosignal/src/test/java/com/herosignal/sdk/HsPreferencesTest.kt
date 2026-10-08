package com.herosignal.sdk

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class HsPreferencesTest {
    @Test
    fun `tercih sonucu ayristirilir`() {
        val result = HsPreferencesParser.parseResult(JSONObject("""
            {
              "data": [
                { "category": "promos", "channel": null, "opted_in": false, "explicit": true, "source": "sdk", "updated_at": "2026-10-07T10:00:00Z" },
                { "category": "orders", "channel": "android_push", "opted_in": true, "explicit": false, "source": null, "updated_at": null }
              ],
              "categories": [
                { "key": "promos", "name": { "tr": "Kampanyalar", "en": "Promotions" }, "description": { "tr": "İndirimler" }, "channels": ["android_push", "email"], "default_opted_in": false },
                { "key": "orders", "name": { "en": "Orders" }, "description": {}, "channels": null, "default_opted_in": true },
                { "name": { "en": "Anahtarsız" } }
              ]
            }
        """.trimIndent()))
        assertEquals(2, result.preferences.size)
        val promos = result.preferences[0]
        assertNull(promos.channel)
        assertFalse(promos.optedIn)
        assertTrue(promos.explicit)
        assertEquals("sdk", promos.source)
        assertEquals(HsIsoTime.parse("2026-10-07T10:00:00Z"), promos.updatedAt)
        assertEquals("android_push", result.preferences[1].channel)
        assertNull(result.preferences[1].updatedAt)

        assertEquals(listOf("promos", "orders"), result.categories.map { it.key }) // anahtarsız kategori atlanır
        val cat = result.categories[0]
        assertEquals("Kampanyalar", cat.localizedName("tr"))
        assertEquals("Promotions", cat.localizedName("de")) // İngilizce yedek
        assertEquals(listOf("android_push", "email"), cat.channels)
        assertFalse(cat.defaultOptedIn)
        assertNull(result.categories[1].channels)
        assertEquals("Orders", result.categories[1].localizedName("tr"))
        assertTrue(result.categories[1].defaultOptedIn)
    }

    @Test
    fun `tercih girdisi dogrulanir ve JSON'a cevrilir`() {
        val inputs = listOf(HsPreferenceInput("promos", null, false), HsPreferenceInput("orders", "email", true))
        HsPreferencesParser.validate(inputs)
        val json = HsPreferencesParser.toJson(inputs)
        assertEquals(2, json.length())
        assertTrue(json.getJSONObject(0).isNull("channel"))
        assertFalse(json.getJSONObject(0).getBoolean("opted_in"))
        assertEquals("email", json.getJSONObject(1).getString("channel"))

        assertThrows(IllegalArgumentException::class.java) { HsPreferencesParser.validate(emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { HsPreferencesParser.validate(listOf(HsPreferenceInput("", null, true))) }
        assertThrows(IllegalArgumentException::class.java) { HsPreferencesParser.validate(listOf(HsPreferenceInput("promos", "fax", true))) }
    }

    @Test
    fun `bildirim kanali onem esleme`() {
        assertEquals(android.app.NotificationManager.IMPORTANCE_LOW, HsNotificationChannels.importance("low"))
        assertEquals(android.app.NotificationManager.IMPORTANCE_DEFAULT, HsNotificationChannels.importance("default"))
        assertEquals(android.app.NotificationManager.IMPORTANCE_HIGH, HsNotificationChannels.importance("high"))
        assertEquals(android.app.NotificationManager.IMPORTANCE_MAX, HsNotificationChannels.importance("urgent"))
        assertEquals(android.app.NotificationManager.IMPORTANCE_DEFAULT, HsNotificationChannels.importance(null))
        assertEquals(android.app.NotificationManager.IMPORTANCE_DEFAULT, HsNotificationChannels.importance("loud"))
    }
}
