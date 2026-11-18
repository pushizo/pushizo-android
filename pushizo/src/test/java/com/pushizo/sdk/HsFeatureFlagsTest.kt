package com.pushizo.sdk

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Faz 39 (ADR-0066): özellik bayrağı kuralları. */
class HsFeatureFlagsTest {
    private val response = JSONObject(
        """{"flags":{"yeni_sepet":{"enabled":true,"value":"mavi"},"kapali":{"enabled":false,"value":false},"limit":{"enabled":true,"value":5},
        "ayar":{"enabled":true,"value":{"a":1}},"bos":{"enabled":true,"value":null},"bozuk":{"enabled":"x"},"metin":"x"}}""",
    )

    @Test fun `yanıt okunur, bozuk satır atlanır`() {
        val cache = HsFeatureFlagRules.parse(response)!!
        assertEquals(setOf("yeni_sepet", "kapali", "limit", "ayar", "bos"), cache.keys().asSequence().toSet())
    }

    @Test fun `bozuk yanıt son değerleri korur`() {
        assertNull(HsFeatureFlagRules.parse(JSONObject()))
        assertNull(HsFeatureFlagRules.parse(JSONObject().put("flags", "x")))
    }

    @Test fun `varsayılan yalnızca bilinmeyen anahtarda döner`() {
        val cache = HsFeatureFlagRules.parse(response)
        assertTrue(HsFeatureFlagRules.isEnabled(cache, "yeni_sepet", false))
        assertFalse(HsFeatureFlagRules.isEnabled(cache, "kapali", true))
        assertTrue(HsFeatureFlagRules.isEnabled(cache, "bilinmeyen", true))
        assertFalse(HsFeatureFlagRules.isEnabled(null, "yeni_sepet", false))
    }

    @Test fun `değerler türüyle okunur`() {
        val cache = HsFeatureFlagRules.parse(response)
        assertEquals("mavi", HsFeatureFlagRules.value(cache, "yeni_sepet"))
        assertEquals(5, HsFeatureFlagRules.value(cache, "limit"))
        assertEquals(1, (HsFeatureFlagRules.value(cache, "ayar") as JSONObject).getInt("a"))
        assertNull(HsFeatureFlagRules.value(cache, "bos"))
        assertNull(HsFeatureFlagRules.value(cache, "bilinmeyen"))
    }

    @Test fun `abonelik varsa kimliği, yoksa rastgele özneyi gönderir`() {
        assertEquals("""{"subscription_id":"sub_1"}""", HsFeatureFlagRules.body("sub_1", "anon_x").toString())
        assertEquals("""{"subject":"anon_x"}""", HsFeatureFlagRules.body(null, "anon_x").toString())
    }

    @Test fun `rastgele özne önekli ve tekildir`() {
        val a = HsFeatureFlagRules.newSubject()
        assertTrue(a.matches(Regex("^anon_[0-9a-f]{32}$")))
        assertNotEquals(a, HsFeatureFlagRules.newSubject())
        assertEquals(0, JSONArray().length())
    }
}
