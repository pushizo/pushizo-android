package com.herosignal.sdk

import org.json.JSONObject

/**
 * Çevrimdışı işlem kuyruğu: JSON nesneleri (`type` alanı ile) kalıcı saklanır; sırayla işlenir.
 * Ardışık tag yamaları birleştirilir; 4xx hatasında işlem atılır, geçici hatada flush durur.
 */
internal class HsQueue(
    private val storage: HsStorage,
    private val handler: (JSONObject) -> Unit,
    /** Kalıcı olarak reddedilip atılan işlem (hata raporu için). */
    private val onRejected: (JSONObject, HsApiException) -> Unit = { _, _ -> },
) {
    private val lock = Any()
    @Volatile private var flushing = false

    val size: Int get() = storage.queue.size

    fun enqueue(op: JSONObject) {
        synchronized(lock) {
            val queue = storage.queue.toMutableList()
            queue.add(op)
            storage.queue = mergeTail(queue).takeLast(MAX_QUEUE)
        }
    }

    /** @return true: kuyruk boşaldı; false: geçici hata, sonra tekrar denenecek. */
    fun flush(): Boolean {
        if (flushing) return false
        flushing = true
        try {
            while (true) {
                val queue = synchronized(lock) { storage.queue }
                val head = queue.firstOrNull() ?: return true
                // Ardışık olaylar tek istekte gönderilir (en fazla EVENT_BATCH_MAX).
                val events = leadingEvents(queue)
                val unit = if (events.size > 1) batchOf(events) else head
                val consumed = if (events.size > 1) events.size else 1
                try {
                    handler(unit)
                } catch (e: HsApiException) {
                    if (e.retryable) {
                        HsLog.d("flush durdu (geçici hata): ${unit.optString("type")}")
                        return false
                    }
                    HsLog.w("kuyruktaki işlem reddedildi, atılıyor: ${unit.optString("type")} ${e.code}")
                    runCatching { onRejected(unit, e) }
                }
                synchronized(lock) { storage.queue = storage.queue.drop(consumed) }
            }
        } finally {
            flushing = false
        }
    }

    fun clear() { synchronized(lock) { storage.queue = emptyList() } }

    companion object {
        const val MAX_QUEUE = 500
        /** Tek istekte en fazla olay (SDK API sınırı). */
        const val EVENT_BATCH_MAX = 100

        /** Kuyruğun başındaki ardışık `event` işlemleri. */
        fun leadingEvents(queue: List<JSONObject>): List<JSONObject> {
            val run = mutableListOf<JSONObject>()
            for (op in queue) {
                if (op.optString("type") != "event" || run.size >= EVENT_BATCH_MAX) break
                run.add(op)
            }
            return run
        }

        fun batchOf(events: List<JSONObject>): JSONObject {
            val arr = org.json.JSONArray()
            events.forEach { arr.put(it) }
            return JSONObject().put("type", "event_batch").put("events", arr).put("at", events.first().optLong("at"))
        }

        fun mergeTail(queue: MutableList<JSONObject>): List<JSONObject> {
            if (queue.size < 2) return queue
            val last = queue[queue.size - 1]
            val prev = queue[queue.size - 2]
            if (last.optString("type") == "tags" && prev.optString("type") == "tags") {
                val merged = JSONObject(prev.getJSONObject("tags").toString())
                val lastTags = last.getJSONObject("tags")
                for (key in lastTags.keys()) merged.put(key, lastTags.get(key))
                val op = JSONObject().put("type", "tags").put("tags", merged).put("at", last.optLong("at"))
                return queue.dropLast(2) + op
            }
            return queue
        }
    }
}
