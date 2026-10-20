package com.pushizo.sdk

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** ADR-0037: reklam kimliği (GAID) kuyruk işlemi ve `ads_personalization` rıza kaydının ayrıştırılması. */
class HsAdsTest {
    @Test
    fun `reklam kimligi normalize edilir, sifir ve gecersiz kimlik silinir`() {
        assertEquals("ab12cd34-ef56-7890-ab12-cd34ef567890", HsAdsRules.normalize(" AB12CD34-EF56-7890-AB12-CD34EF567890 "))
        assertNull(HsAdsRules.normalize("00000000-0000-0000-0000-000000000000"))
        assertNull(HsAdsRules.normalize("not-a-uuid"))
        assertNull(HsAdsRules.normalize(null))
        val op = HsAdsRules.op("AB12CD34-EF56-7890-AB12-CD34EF567890", 1_000L)
        assertEquals(HsAdsRules.OP_TYPE, op.getString("type"))
        assertEquals("ab12cd34-ef56-7890-ab12-cd34ef567890", HsAdsRules.body(op).getString("advertising_id"))
        assertTrue(HsAdsRules.body(HsAdsRules.op("00000000-0000-0000-0000-000000000000", 2_000L)).isNull("advertising_id"))
    }

    @Test
    fun `kuyrukta yalnizca en son reklam kimligi islemi gonderilir`() {
        val first = HsAdsRules.op("ab12cd34-ef56-7890-ab12-cd34ef567890", 1_000L)
        val second = HsAdsRules.op(null, 2_000L)
        val queue = listOf(JSONObject(first.toString()), JSONObject().put("type", "event"), JSONObject(second.toString()))
        assertTrue(HsAdsRules.isSuperseded(JSONObject(first.toString()), queue))
        assertFalse(HsAdsRules.isSuperseded(JSONObject(second.toString()), queue))
    }

    @Test
    fun `riza kayitlari tercih yanitindan ayristirilir`() {
        val result = HsPreferencesParser.parseResult(JSONObject("""
            {
              "data": [],
              "categories": [],
              "consents": [
                { "purpose": "ads_personalization", "granted": true, "source": "sdk", "updated_at": "2026-10-20T10:00:00Z" },
                { "granted": true }
              ]
            }
        """.trimIndent()))
        assertEquals(1, result.consents.size)
        val c = result.consents[0]
        assertEquals(HsConsents.ADS_PERSONALIZATION, c.purpose)
        assertTrue(c.granted)
        assertEquals("sdk", c.source)
        assertEquals(HsIsoTime.parse("2026-10-20T10:00:00Z"), c.updatedAt)
        // Eski API yanıtı (consents yok) boş liste verir; rıza girdisi tercih doğrulamasından geçer.
        assertTrue(HsPreferencesParser.parseResult(JSONObject("""{"data":[],"categories":[]}""")).consents.isEmpty())
        HsPreferencesParser.validate(listOf(HsPreferenceInput(HsConsents.ADS_PERSONALIZATION, null, false)))
    }
}
