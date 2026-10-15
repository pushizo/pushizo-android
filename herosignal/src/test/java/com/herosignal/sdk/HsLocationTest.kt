package com.herosignal.sdk

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class HsLocationTest {
    private class MemoryState(
        override var locationShared: Boolean = true,
        override var locationLastSharedAt: Long = 0L,
    ) : HsLocationRules.State

    private class MemoryQueueStore : HsQueueStore {
        override var queue: List<JSONObject> = emptyList()
    }

    private val now = 1_800_000_000_000L

    @Before
    fun quietLog() {
        // android.util.Log JVM testlerinde yoktur.
        HsLog.level = HsLog.Level.NONE
    }

    @Test
    fun `konum govdesi lat lng ve dogruluk tasir`() {
        val op = HsLocationRules.op(HsLocationFix(41.0082, 28.9784, 25.5, now), now)!!
        assertEquals(HsLocationRules.OP_TYPE, op.getString("type"))
        val body = HsLocationRules.body(JSONObject(op.toString())) // kalıcı kuyruktan geri okunmuş gibi
        val location = body.getJSONObject("location")
        assertEquals(41.0082, location.getDouble("lat"), 0.0)
        assertEquals(28.9784, location.getDouble("lng"), 0.0)
        assertEquals(25.5, location.getDouble("accuracy_m"), 0.0)
        assertEquals(setOf("location"), body.keys().asSequence().toSet())
    }

    @Test
    fun `paylasim kapatilinca govde location null olur`() {
        val op = HsLocationRules.op(null, now)!!
        val restored = JSONObject(op.toString())
        val body = HsLocationRules.body(restored)
        assertTrue(body.has("location"))
        assertTrue(body.isNull("location"))
        assertEquals("""{"location":null}""", body.toString())
    }

    @Test
    fun `dogruluk yoksa alan gonderilmez, sinir disi dogruluk kirpilir, gecersiz koordinat gonderilmez`() {
        val noAccuracy = HsLocationRules.locationJson(HsLocationFix(-90.0, 180.0, null, now))!!
        assertFalse(noAccuracy.has("accuracy_m"))
        assertEquals(100_000.0, HsLocationRules.locationJson(HsLocationFix(0.0, 0.0, 250_000.0, now))!!.getDouble("accuracy_m"), 0.0)
        assertFalse(HsLocationRules.locationJson(HsLocationFix(0.0, 0.0, -1.0, now))!!.has("accuracy_m"))
        assertNull(HsLocationRules.locationJson(HsLocationFix(90.1, 0.0, 10.0, now)))
        assertNull(HsLocationRules.locationJson(HsLocationFix(0.0, -180.5, 10.0, now)))
        assertNull(HsLocationRules.locationJson(HsLocationFix(Double.NaN, 0.0, 10.0, now)))
        assertNull(HsLocationRules.op(HsLocationFix(91.0, 0.0, null, now), now))
    }

    @Test
    fun `en sik 5 dakikada bir gonderilir`() {
        val state = MemoryState(locationLastSharedAt = 0L)
        assertTrue("ilk gönderim", HsLocationRules.isDue(state, networkAllowed = true, permissionGranted = true, now = now))
        state.locationLastSharedAt = now
        assertFalse(HsLocationRules.isDue(state, true, true, now + 60_000))
        assertFalse(HsLocationRules.isDue(state, true, true, now + HsLocationRules.MIN_INTERVAL_MS - 1))
        assertTrue(HsLocationRules.isDue(state, true, true, now + HsLocationRules.MIN_INTERVAL_MS))
        // Saat geri alındıysa sınır kilitlenmez.
        assertTrue(HsLocationRules.isDue(state, true, true, now - 1_000))
    }

    @Test
    fun `paylasim kapali, riza veya izin yokken gonderilmez`() {
        assertFalse("paylaşım kapalı", HsLocationRules.isDue(MemoryState(locationShared = false), true, true, now))
        assertFalse("gizlilik onayı yok", HsLocationRules.isDue(MemoryState(), networkAllowed = false, permissionGranted = true, now = now))
        assertFalse("konum izni yok", HsLocationRules.isDue(MemoryState(), networkAllowed = true, permissionGranted = false, now = now))
    }

    @Test
    fun `riza yokken konum islemi kuyrukta bekler, kapatilinca bekleyen konum gonderilmez yalnizca null gider`() {
        val store = MemoryQueueStore()
        val sent = mutableListOf<JSONObject>()
        var consent = false
        // HeroSignal.perform'daki konum dalının aynısı: daha yeni konum işlemi varsa atlanır.
        val queue = HsQueue(store, { op ->
            if (op.optString("type") == HsLocationRules.OP_TYPE && !HsLocationRules.isSuperseded(op, store.queue)) {
                sent.add(HsLocationRules.body(op))
            }
        }, canFlush = { consent })

        queue.enqueue(HsLocationRules.op(HsLocationFix(41.0, 29.0, 10.0, now), now)!!)
        assertFalse(queue.flush())
        assertTrue("onay yokken hiçbir şey gönderilmez", sent.isEmpty())

        queue.enqueue(HsLocationRules.op(null, now + 1)!!) // setShared(false)
        consent = true
        assertTrue(queue.flush())
        assertEquals(listOf("""{"location":null}"""), sent.map { it.toString() })
        assertTrue(store.queue.isEmpty())
    }

    @Test
    fun `tek konum islemi yerine gecmis sayilmaz`() {
        val op = HsLocationRules.op(HsLocationFix(1.0, 2.0, null, now), now)!!
        val restored = JSONObject(op.toString())
        assertFalse(HsLocationRules.isSuperseded(restored, listOf(JSONObject().put("type", "tags"), restored)))
        assertFalse(HsLocationRules.isSuperseded(restored, emptyList()))
    }

    @Test
    fun `son bilinen konumlardan 10 dakikadan yeni ve en dogru olan secilir`() {
        val old = HsLocationFix(1.0, 1.0, 5.0, now - HsLocationRules.MAX_LAST_KNOWN_AGE_MS - 1)
        val coarse = HsLocationFix(2.0, 2.0, 1_500.0, now - 1_000)
        val precise = HsLocationFix(3.0, 3.0, 20.0, now - 60_000)
        val noAccuracy = HsLocationFix(4.0, 4.0, null, now)
        assertEquals(precise, HsLocationRules.best(listOf(old, coarse, precise, noAccuracy), now))
        assertEquals(noAccuracy, HsLocationRules.best(listOf(old, noAccuracy), now))
        assertNull("yalnızca eski konum varsa anlık konum istenir", HsLocationRules.best(listOf(old), now))
        // Eşit doğrulukta daha yeni olan.
        val newer = precise.copy(lat = 5.0, timeMs = now - 1)
        assertEquals(newer, HsLocationRules.best(listOf(precise, newer), now))
    }
}
