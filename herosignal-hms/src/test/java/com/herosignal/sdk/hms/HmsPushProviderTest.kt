package com.herosignal.sdk.hms

import com.herosignal.sdk.HsPushBridge
import com.huawei.hms.api.ConnectionResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HmsPushProviderTest {

    @Test
    fun `saglayici hms_push kanalini kullanir ve cekirdegin yansima ile bulacagi adda`() {
        assertEquals(HsPushBridge.CHANNEL_HMS, HmsPushProvider().channel)
        assertEquals("hms_push", HsPushBridge.CHANNEL_HMS)
        // Çekirdek HsPushProviders.HMS_PROVIDER_CLASS ile aynı ad (yeniden adlandırılırsa otomatik seçim bozulur).
        assertEquals("com.herosignal.sdk.hms.HmsPushProvider", HmsPushProvider::class.java.name)
    }

    @Test
    fun `App ID meta-data degerleri normalize edilir`() {
        assertEquals("105123456", HmsAppId.normalize("appid=105123456"))
        assertEquals("105123456", HmsAppId.normalize(" 105123456 "))
        assertEquals("105123456", HmsAppId.normalize(105123456)) // android:value sayı olarak okunur
        assertNull(HmsAppId.normalize(null))
        assertNull(HmsAppId.normalize(""))
        assertNull(HmsAppId.normalize("appid="))
    }

    @Test
    fun `HMS Core kurulu ve guncel veya guncelleme gerektiriyorsa kullanilabilir`() {
        assertTrue(HmsAvailability.isUsable(ConnectionResult.SUCCESS))
        assertTrue(HmsAvailability.isUsable(ConnectionResult.SERVICE_VERSION_UPDATE_REQUIRED))
        assertFalse(HmsAvailability.isUsable(ConnectionResult.SERVICE_MISSING))
        assertFalse(HmsAvailability.isUsable(ConnectionResult.SERVICE_DISABLED))
        assertFalse(HmsAvailability.isUsable(ConnectionResult.SERVICE_INVALID))
    }
}
