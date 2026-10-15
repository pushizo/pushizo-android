package com.herosignal.sdk

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** In-app ve inbox isteklerinde abonelik sahiplik kanıtı (ADR-0016 genişlemesi: `token_hash`). */
class HsInAppInboxProofTest {
    private val pushHash = HsOwnership.tokenHash("fcm_token")
    private val mailHash = HsOwnership.tokenHash("ayse@example.com")

    @Test
    fun `in-app listesi kanit varsa token_hash ile istenir`() {
        assertEquals(
            "/in-app?subscription_id=sub_push&token_hash=$pushHash&supports=html,carousel",
            HsInAppController.listPath("sub_push", pushHash),
        )
        // Kanıt yoksa (ör. eski kayıt) parametre eklenmez; içerik yetenekleri (ADR-0027) her zaman bildirilir.
        assertEquals("/in-app?subscription_id=sub+1&supports=html,carousel", HsInAppController.listPath("sub 1", null))
    }

    @Test
    fun `in-app olay govdesi token_hash buton ve varyanti tasir`() {
        val op = JSONObject().put("type", "in_app_event").put("message_id", "iam_1").put("event", "click")
            .put("button_id", "go").put("variant_id", "var_b")
        val body = HsInAppController.eventBody(op, "sub_mail", mailHash)
        assertEquals("sub_mail", body.getString("subscription_id"))
        assertEquals(mailHash, body.getString("token_hash"))
        assertEquals("click", body.getString("type"))
        assertEquals("go", body.getString("button_id"))
        assertEquals("var_b", body.getString("variant_id"))

        val bare = HsInAppController.eventBody(JSONObject().put("event", "impression"), "sub_x", null)
        assertFalse(bare.has("token_hash"))
        assertFalse(bare.has("button_id"))
        assertEquals("impression", bare.getString("type"))
    }

    @Test
    fun `inbox sorgu ve govdesi token_hash ve identity_hash tasir`() {
        val identity = "a".repeat(64)
        val auth = HsInboxAuth("usr 1", "sub_push", pushHash, identity)
        assertEquals("/users/usr+1/inbox", auth.basePath)
        assertEquals("subscription_id=sub_push&token_hash=$pushHash&identity_hash=$identity", auth.query())
        val body = auth.body()
        assertEquals("sub_push", body.getString("subscription_id"))
        assertEquals(pushHash, body.getString("token_hash"))
        assertEquals(identity, body.getString("identity_hash"))
    }

    @Test
    fun `inbox kanit yoksa yalnizca subscription_id gonderir`() {
        val auth = HsInboxAuth("usr_1", "sub_mail", null, null)
        assertEquals("subscription_id=sub_mail", auth.query())
        val body = auth.body()
        assertEquals(1, body.length())
        assertEquals("sub_mail", body.getString("subscription_id"))
    }
}
