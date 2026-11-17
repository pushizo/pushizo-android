package com.pushizo.sdk

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Faz 38 (ADR-0065): arka plan bölge izleme kuralları. */
class HsGeofenceTest {
    private class MemoryState(
        override var geofencingEnabled: Boolean = true,
        override var geofenceLastSyncAt: Long = 0L,
        override var geofenceLastSyncLat: Double = 41.0,
        override var geofenceLastSyncLng: Double = 29.0,
        override var geofenceLastSyncUptime: Long = 1_000L,
    ) : HsGeofenceRules.State

    private val now = 1_800_000_000_000L

    @Test fun `kapalıyken eşitleme yapılmaz`() {
        assertFalse(HsGeofenceRules.isSyncDue(MemoryState(geofencingEnabled = false), 41.0, 29.0, now, 2_000L))
    }

    @Test fun `ilk kez ve süre dolunca eşitlenir`() {
        assertTrue(HsGeofenceRules.isSyncDue(MemoryState(), 41.0, 29.0, now, 2_000L))
        val state = MemoryState(geofenceLastSyncAt = now - HsGeofenceRules.SYNC_INTERVAL_MS + 1)
        assertFalse(HsGeofenceRules.isSyncDue(state, 41.0, 29.0, now, 2_000L))
        state.geofenceLastSyncAt = now - HsGeofenceRules.SYNC_INTERVAL_MS
        assertTrue(HsGeofenceRules.isSyncDue(state, 41.0, 29.0, now, 2_000L))
    }

    @Test fun `saat geri alınınca ve cihaz yeniden başlayınca eşitlenir`() {
        assertTrue(HsGeofenceRules.isSyncDue(MemoryState(geofenceLastSyncAt = now + 1), 41.0, 29.0, now, 2_000L))
        assertTrue(HsGeofenceRules.isSyncDue(MemoryState(geofenceLastSyncAt = now - 1), 41.0, 29.0, now, 500L))
    }

    @Test fun `5 km uzaklaşınca eşitlenir`() {
        val state = MemoryState(geofenceLastSyncAt = now - 1)
        assertFalse(HsGeofenceRules.isSyncDue(state, 41.04, 29.0, now, 2_000L))
        assertTrue(HsGeofenceRules.isSyncDue(state, 41.05, 29.0, now, 2_000L))
    }

    @Test fun `mesafe doğru hesaplanır`() {
        assertEquals(111_195.0, HsGeofenceRules.distanceM(41.0, 29.0, 42.0, 29.0), 5.0)
        assertEquals(0.0, HsGeofenceRules.distanceM(41.0, 29.0, 41.0, 29.0), 0.001)
    }

    @Test fun `bölge listesi okunur, bozuk satır atlanır, sınır uygulanır`() {
        val data = JSONArray()
            .put(JSONObject().put("id", "gfn_1").put("lat", 41.0).put("lng", 29.0).put("radius_m", 300))
            .put(JSONObject().put("id", "").put("lat", 41.0).put("lng", 29.0).put("radius_m", 300))
            .put(JSONObject().put("id", "gfn_2").put("lng", 29.0).put("radius_m", 300))
            .put(JSONObject().put("id", "gfn_3").put("lat", 41.0).put("lng", 29.0).put("radius_m", 0))
            .put("bozuk")
        assertEquals(listOf(HsGeofenceRegion("gfn_1", 41.0, 29.0, 300)), HsGeofenceRules.parseRegions(JSONObject().put("data", data)))
        assertEquals(emptyList<HsGeofenceRegion>(), HsGeofenceRules.parseRegions(JSONObject()))

        val many = JSONArray()
        repeat(HsGeofenceRules.MAX_REGIONS + 5) { many.put(JSONObject().put("id", "gfn_$it").put("lat", 41.0).put("lng", 29.0).put("radius_m", 100)) }
        assertEquals(HsGeofenceRules.MAX_REGIONS, HsGeofenceRules.parseRegions(JSONObject().put("data", many)).size)
    }

    @Test fun `istek gövdesi koordinat içermez`() {
        val op = HsGeofenceRules.op("gfn_1", "enter", now)
        assertEquals(HsGeofenceRules.OP_TYPE, op.getString("type"))
        val body = HsGeofenceRules.body(op)
        val event = body.getJSONArray("events").getJSONObject(0)
        assertEquals("gfn_1", event.getString("geofence_id"))
        assertEquals("enter", event.getString("transition"))
        assertEquals("2027-01-15T08:00:00.000Z", event.getString("occurred_at"))
        assertFalse(body.toString().contains("lat"))
        assertFalse(body.toString().contains("lng"))
    }
}
