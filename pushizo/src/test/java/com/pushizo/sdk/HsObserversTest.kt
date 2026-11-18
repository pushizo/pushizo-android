package com.pushizo.sdk

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** İstemci SDK eşitliği: gözlemciler, kullanıcı özelliği, rozet ve olay kimliği kuralları. */
class HsObserversTest {
    private fun item(id: String, read: Boolean) = HsInboxItem(id, null, "t", null, null, null, emptyMap(), read, null, null, null, null)
    private fun page(unread: Int, vararg items: HsInboxItem) = HsInboxPage(items.toList(), unread, null, false)

    @Test fun `gözlemci kümesi ekler, kaldırır ve hatayı yalıtır`() {
        val errors = ArrayList<Throwable>()
        val set = HsObserverSet<HsFeatureFlagObserver> { errors += it }
        assertTrue(set.isEmpty)
        var calls = 0
        val failing = HsFeatureFlagObserver { throw IllegalStateException("x") }
        val counting = HsFeatureFlagObserver { calls++ }
        set.add(failing)
        set.add(counting)
        set.add(counting)
        set.dispatch { it.onChange() }
        assertEquals(1, calls)
        assertEquals(1, errors.size)
        set.remove(counting)
        set.dispatch { it.onChange() }
        assertEquals(1, calls)
        assertFalse(set.isEmpty)
    }

    @Test fun `kutu listeleme yalnızca sayı değişince bildirilir`() {
        val events = ArrayList<HsInboxChangeEvent>()
        val tracker = HsInboxTracker { events += it }
        tracker.listed(page(2, item("a", false), item("b", false)), firstPage = true)
        tracker.listed(page(2, item("a", false), item("b", false)), firstPage = true)
        tracker.counted(2)
        assertEquals(listOf(HsInboxChangeEvent("listed", null, 2)), events)
        tracker.counted(3)
        assertEquals(HsInboxChangeEvent("count", null, 3), events.last())
    }

    @Test fun `okundu bilinen okunmamış öğede sayıyı yerelde düşürür`() {
        val events = ArrayList<HsInboxChangeEvent>()
        val tracker = HsInboxTracker { events += it }
        tracker.listed(page(2, item("a", false), item("b", true)), firstPage = true)
        tracker.read("a") { error("sunucuya gidilmemeli") }
        assertEquals(HsInboxChangeEvent("read", "a", 1), events.last())
        // Zaten okunmuş ya da bilinmeyen öğede sayı sunucudan alınır; olay yine bildirilir.
        tracker.read("b") { 1 }
        tracker.read("zz") { 1 }
        assertEquals(listOf("listed", "read", "read", "read"), events.map { it.type })
        assertEquals(1, events.last().unreadCount)
    }

    @Test fun `arşivleme ve tümünü okundu işaretleme`() {
        val events = ArrayList<HsInboxChangeEvent>()
        val tracker = HsInboxTracker { events += it }
        tracker.listed(page(2, item("a", false), item("b", false), item("c", true)), firstPage = true)
        tracker.archived("c") { error("sunucuya gidilmemeli") }
        assertEquals(HsInboxChangeEvent("archived", "c", 2), events.last())
        tracker.archived("a") { error("sunucuya gidilmemeli") }
        assertEquals(HsInboxChangeEvent("archived", "a", 1), events.last())
        tracker.archived("bilinmeyen") { 7 }
        assertEquals(HsInboxChangeEvent("archived", "bilinmeyen", 7), events.last())
        tracker.readAll(0)
        assertEquals(HsInboxChangeEvent("read_all", null, 0), events.last())
        // Tümü okunduktan sonra tek öğe okundu: yerelde düşülecek okunmamış yok, sunucuya sorulur.
        tracker.read("b") { 0 }
        assertEquals(HsInboxChangeEvent("read", "b", 0), events.last())
    }

    @Test fun `sonraki sayfa önceki öğeleri korur, sıfırlama önbelleği temizler`() {
        val events = ArrayList<HsInboxChangeEvent>()
        val tracker = HsInboxTracker { events += it }
        tracker.listed(page(2, item("a", false)), firstPage = true)
        tracker.listed(page(2, item("b", false)), firstPage = false)
        tracker.read("a") { error("sunucuya gidilmemeli") }
        assertEquals(1, events.last().unreadCount)
        tracker.reset()
        tracker.read("b") { 5 }
        assertEquals(HsInboxChangeEvent("read", "b", 5), events.last())
    }

    @Test fun `kutu olayı köprü haritasına çevrilir`() {
        assertEquals(mapOf("type" to "read", "itemId" to "a", "unreadCount" to 3), HsInboxChangeEvent(HsInboxChangeEvent.READ, "a", 3).toMap())
        assertEquals(setOf("listed", "read", "read_all", "archived", "count"), setOf(HsInboxChangeEvent.LISTED, HsInboxChangeEvent.READ, HsInboxChangeEvent.READ_ALL, HsInboxChangeEvent.ARCHIVED, HsInboxChangeEvent.COUNT))
    }

    @Test fun `kullanıcı özelliği kuyruk işlemine çevrilir`() {
        val op = HsUserProperties.op("plan", "pro", 42L)
        assertEquals("properties", op.getString("type"))
        assertEquals(42L, op.getLong("at"))
        assertEquals("pro", op.getJSONObject("properties").getString("plan"))
        assertTrue(HsUserProperties.op("plan", null, 1L).getJSONObject("properties").isNull("plan"))
        assertEquals(3, HsUserProperties.op("n", 3, 1L).getJSONObject("properties").getInt("n"))
        assertEquals(true, HsUserProperties.op("b", true, 1L).getJSONObject("properties").getBoolean("b"))
        val nested = HsUserProperties.op("o", mapOf("a" to listOf(1, "x", null), "m" to mapOf("k" to 1.5)), 1L).getJSONObject("properties").getJSONObject("o")
        assertEquals(3, nested.getJSONArray("a").length())
        assertTrue(nested.getJSONArray("a").isNull(2))
        assertEquals(1.5, nested.getJSONObject("m").getDouble("k"), 0.0)
        assertEquals(2, (HsUserProperties.wrap(arrayOf("a", "b")) as JSONArray).length())
        val json = JSONObject()
        assertSame(json, HsUserProperties.wrap(json))
        assertThrows(IllegalArgumentException::class.java) { HsUserProperties.op(" ", 1, 1L) }
    }

    @Test fun `rozet kuralları`() {
        assertEquals(0, HsBadgeRules.normalize(-4))
        assertEquals(7, HsBadgeRules.normalize(7))
        assertNull(HsBadgeRules.numberFor(0))
        assertNull(HsBadgeRules.numberFor(-1))
        assertEquals(3, HsBadgeRules.numberFor(3))
        assertTrue(HsBadgeRules.supported(26, notificationsEnabled = true, channelShowsBadge = true))
        assertFalse(HsBadgeRules.supported(25, notificationsEnabled = true, channelShowsBadge = true))
        assertFalse(HsBadgeRules.supported(34, notificationsEnabled = false, channelShowsBadge = true))
        assertFalse(HsBadgeRules.supported(34, notificationsEnabled = true, channelShowsBadge = false))
    }

    @Test fun `olay kimliği yalnızca geçerli biçimde kabul edilir`() {
        assertEquals("evt_12345678", HsEvents.eventId("evt_12345678"))
        assertEquals("a.b:c-d_e1", HsEvents.eventId("a.b:c-d_e1"))
        assertNull(HsEvents.eventId("kısa"))
        assertNull(HsEvents.eventId("boşluk var 12"))
        assertNull(HsEvents.eventId("x".repeat(65)))
        assertNull(HsEvents.eventId(null))
    }
}
