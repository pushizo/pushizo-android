package com.pushizo.sdk

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HsAdmRegistrationTest {

    private val id1 = "amzn1.adm-registration.v3.AAAA1111"
    private val id2 = "amzn1.adm-registration.v3.BBBB2222"

    private fun JSONObject.summary() = "${getString("type")}:${getString("channel")}:${getString("address")}"

    @Test
    fun `yeni kayit kimligi adm_push aboneligi olarak eklenir`() {
        val ops = HsAdmRegistration.registrationOps(emptyList(), "  $id1 ", 42L)!!
        assertEquals(listOf("contact_add:adm_push:$id1"), ops.map { it.summary() })
        assertEquals(42L, ops.single().getLong("at"))
    }

    @Test
    fun `kimlik yenilenince eski kimligin aboneligi once silinir`() {
        val ops = HsAdmRegistration.registrationOps(listOf(id1, id2, id1), id2, 1L)!!
        assertEquals(listOf("contact_remove:adm_push:$id1", "contact_add:adm_push:$id2"), ops.map { it.summary() })
    }

    @Test
    fun `gecersiz kimlik kaydedilmez`() {
        assertNull(HsAdmRegistration.registrationOps(emptyList(), "kisa", 1L))
        assertNull(HsAdmRegistration.registrationOps(emptyList(), "   ", 1L))
        assertNull(HsAdmRegistration.registrationOps(emptyList(), null, 1L))
        assertNull(HsAdmRegistration.normalize("x".repeat(4097)))
        assertEquals("x".repeat(4096), HsAdmRegistration.normalize("x".repeat(4096)))
    }

    @Test
    fun `bilinen kimlikler kayitli abonelikler ve kuyrukta bekleyen eklemelerdir`() {
        val queue = listOf(
            JSONObject().put("type", "contact_add").put("channel", "adm_push").put("address", id2),
            JSONObject().put("type", "contact_add").put("channel", "email").put("address", "a@b.co"),
            JSONObject().put("type", "contact_remove").put("channel", "adm_push").put("address", "eski-kimlik-123"),
            JSONObject().put("type", "contact_add").put("channel", "adm_push").put("address", id1),
        )
        assertEquals(listOf(id1, id2), HsAdmRegistration.known(listOf(id1), queue))
    }

    @Test
    fun `kayit silinince verilen veya bilinen tum kimlikler silinir`() {
        assertEquals(
            listOf("contact_remove:adm_push:$id1"),
            HsAdmRegistration.unregistrationOps(listOf(id1, id2), " $id1 ", 1L).map { it.summary() },
        )
        assertEquals(
            listOf("contact_remove:adm_push:$id1", "contact_remove:adm_push:$id2"),
            HsAdmRegistration.unregistrationOps(listOf(id1, id2), null, 1L).map { it.summary() },
        )
        assertTrue(HsAdmRegistration.unregistrationOps(emptyList(), "", 1L).isEmpty())
    }

    @Test
    fun `ADM kanali, hata kodu ve modul sinifi adi sabittir`() {
        assertEquals("adm_push", HsPushBridge.CHANNEL_ADM)
        assertEquals("adm_token_failed", Pushizo.tokenErrorCode(HsPushProviders.CHANNEL_ADM))
        assertEquals("hms_token_failed", Pushizo.tokenErrorCode(HsPushProviders.CHANNEL_HMS))
        assertEquals("fcm_token_failed", Pushizo.tokenErrorCode(HsPushProviders.CHANNEL_FCM))
        assertEquals("com.pushizo.sdk.adm.AdmRegistrar", HsPushProviders.ADM_REGISTRAR_CLASS)
        // Çekirdek test sınıf yolunda `pushizo-adm` yoktur: ek kayıt modülü bulunmaz.
        assertTrue(HsPushProviders.registrars().isEmpty())
    }
}
