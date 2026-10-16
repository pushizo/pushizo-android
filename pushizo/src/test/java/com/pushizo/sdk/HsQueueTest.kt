package com.pushizo.sdk

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class HsQueueTest {
    private class MemoryStore : HsQueueStore {
        override var queue: List<JSONObject> = emptyList()
    }

    private val store = MemoryStore()
    private val handled = mutableListOf<String>()

    private fun op(type: String, extra: Map<String, Any> = emptyMap()) =
        JSONObject().put("type", type).put("at", 1L).apply { extra.forEach { (k, v) -> put(k, v) } }

    private fun types() = store.queue.map { it.getString("type") }

    @Before
    fun quietLog() {
        // android.util.Log JVM testlerinde yoktur.
        HsLog.level = HsLog.Level.NONE
    }

    @Test
    fun `onay yokken hicbir islem islenmez ve kuyruk korunur, onay gelince gonderilir`() {
        var consent = false
        val queue = HsQueue(store, { handled.add(it.getString("type")) }, canFlush = { consent })
        queue.enqueue(op("tags", mapOf("tags" to JSONObject().put("a", "1"))))
        queue.enqueue(op("contact_add"))
        queue.enqueue(op("received"))

        assertFalse(queue.flush())
        assertTrue("onay yokken ağ işlemi yapılmamalı", handled.isEmpty())
        assertEquals(listOf("tags", "contact_add", "received"), types())

        consent = true
        assertTrue(queue.flush())
        assertEquals(listOf("tags", "contact_add", "received"), handled)
        assertTrue(store.queue.isEmpty())
    }

    @Test
    fun `onay flush sirasinda geri alinirsa kalan islemler gonderilmez`() {
        var consent = true
        val queue = HsQueue(store, { handled.add(it.getString("type")); consent = false }, canFlush = { consent })
        queue.enqueue(op("received"))
        queue.enqueue(op("dismissed"))
        assertFalse(queue.flush())
        assertEquals(listOf("received"), handled)
        assertEquals(listOf("dismissed"), types())
    }

    @Test
    fun `onkosulu eksik islem kuyrukta kalir, sonrakiler engellenmez`() {
        var subscribed = false
        val queue = HsQueue(store, {
            val type = it.getString("type")
            if ((type == "event" || type == "event_batch" || type == "session_start") && !subscribed) throw HsDeferredException("abonelik yok")
            handled.add(type)
        })
        queue.enqueue(op("session_start"))
        queue.enqueue(op("event", mapOf("name" to "a")))
        queue.enqueue(op("event", mapOf("name" to "b")))
        queue.enqueue(op("contact_add"))

        assertTrue("ertelenen işlem yeniden deneme planlatmaz", queue.flush())
        assertEquals(listOf("contact_add"), handled)
        assertEquals("olaylar atılmamalı", listOf("session_start", "event", "event"), types())

        subscribed = true
        assertTrue(queue.flush())
        assertEquals(listOf("contact_add", "session_start", "event_batch"), handled)
        assertTrue(store.queue.isEmpty())
    }

    @Test
    fun `basarili islem sonrasi ertelenenler ayni flushta yeniden denenir`() {
        var hasUser = false
        val queue = HsQueue(store, {
            when (it.getString("type")) {
                "tags" -> if (!hasUser) throw HsDeferredException("kullanıcı yok")
                "contact_add" -> hasUser = true
            }
            handled.add(it.getString("type"))
        })
        queue.enqueue(op("tags", mapOf("tags" to JSONObject().put("a", "1"))))
        queue.enqueue(op("contact_add"))
        assertTrue(queue.flush())
        assertEquals(listOf("contact_add", "tags"), handled)
        assertTrue(store.queue.isEmpty())
    }

    @Test
    fun `gecici hata flushu durdurur, kalici hata islemi atar`() {
        var rejected: String? = null
        var fail = 503
        val queue = HsQueue(store, {
            if (it.getString("type") == "received") throw HsApiException(fail, "X", "x")
            handled.add(it.getString("type"))
        }, { o, _ -> rejected = o.getString("type") })
        queue.enqueue(op("received"))
        queue.enqueue(op("dismissed"))

        assertFalse(queue.flush())
        assertEquals(listOf("received", "dismissed"), types())

        fail = 400
        assertTrue(queue.flush())
        assertEquals("received", rejected)
        assertEquals(listOf("dismissed"), handled)
        assertTrue(store.queue.isEmpty())
    }
}
