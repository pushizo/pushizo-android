package com.herosignal.sdk

import com.herosignal.sdk.HsNotificationTray.Entry
import org.junit.Assert.assertEquals
import org.junit.Test

class HsNotificationTrayTest {
    private val summaryTag = HeroSignalMessagingService.GROUP_SUMMARY_TAG
    private val a1 = Entry(null, 1, "a", isSummary = false, fromSdk = true)
    private val a2 = Entry("c2", 2, "a", isSummary = false, fromSdk = true)
    private val aSummary = Entry(summaryTag, "a".hashCode(), "a", isSummary = true, fromSdk = true)
    private val b3 = Entry(null, 3, "b", isSummary = false, fromSdk = true)
    private val bSummary = Entry(summaryTag, "b".hashCode(), "b", isSummary = true, fromSdk = true)
    private val loose = Entry(null, 4, null, isSummary = false, fromSdk = true)
    private val appOwn = Entry(null, 1, "a", isSummary = false, fromSdk = false)
    private val all = listOf(a1, a2, aSummary, b3, bSummary, loose, appOwn)

    @Test
    fun `clearAll yalnizca SDK bildirimlerini secer`() {
        assertEquals(listOf(a1, a2, aSummary, b3, bSummary, loose), HsNotificationTray.selectAll(all))
    }

    @Test
    fun `kimlikle kaldirma uygulamanin kendi bildirimine dokunmaz`() {
        assertEquals(listOf(a1), HsNotificationTray.selectById(all, 1))
        assertEquals(listOf(loose), HsNotificationTray.selectById(all, 4))
        assertEquals(emptyList<Entry>(), HsNotificationTray.selectById(all, 99))
    }

    @Test
    fun `grubun son bildirimi kaldirilinca ozet de kaldirilir`() {
        assertEquals(listOf(b3, bSummary), HsNotificationTray.selectById(all, 3))
        assertEquals(listOf(a2, aSummary), HsNotificationTray.selectById(all - a1, 2))
    }

    @Test
    fun `grup kaldirma ozet dahil grubu secer`() {
        assertEquals(listOf(a1, a2, aSummary), HsNotificationTray.selectGroup(all, "a"))
        assertEquals(emptyList<Entry>(), HsNotificationTray.selectGroup(all, "yok"))
    }
}
