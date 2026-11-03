package com.pushizo.sdk

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/** Faz 41 (ADR-0068): yerleşim yanıtı açılır mesajla aynı modele ayrıştırılır. */
class HsPlacementsTest {
    @Test fun `yerleşim yanıtı modele ayrıştırılır`() {
        val json = JSONObject(
            """{"language":"tr","data":[{"id":"iam_1","priority":2,"triggers":[],"trigger_groups":[[]],"variant_id":"var_1","remaining_impressions":1000000,
            "min_interval_seconds":0,"stop_after_interaction":false,"last_impression_at":null,"end_at":null,
            "display":{"layout":"modal","dismissible":true,"content_type":"blocks","blocks":[{"type":"title","text":"Başlık"}]}}]}""",
        )
        val messages = HsInAppParser.parseList(json)
        assertEquals(1, messages.size)
        assertEquals("iam_1", messages[0].id)
        assertEquals("var_1", messages[0].variantId)
    }
}
