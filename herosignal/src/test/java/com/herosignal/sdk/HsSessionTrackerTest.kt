package com.herosignal.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HsSessionTrackerTest {
    private class MemoryState : HsSessionTracker.State {
        override var startedAt: Long = 0
        override var backgroundAt: Long = 0
    }

    private val state = MemoryState()
    private val tracker = HsSessionTracker(state)
    private val t0 = 1_700_000_000_000L

    @Test
    fun `ilk on plan yeni oturum baslatir`() {
        assertEquals(HsSessionTracker.Transition(null, true), tracker.onForeground(t0))
        assertEquals(t0, state.startedAt)
    }

    @Test
    fun `30 saniyeden kisa arka plan oturumu surdurur`() {
        tracker.onForeground(t0)
        assertTrue(tracker.onBackground(t0 + 60_000))
        assertNull("pencere dolmadan oturum bitmez", tracker.expire(t0 + 60_000 + 29_999))
        assertEquals(HsSessionTracker.Transition(null, false), tracker.onForeground(t0 + 60_000 + 29_999))
        assertEquals(t0, state.startedAt)
        assertEquals(0L, state.backgroundAt)
    }

    @Test
    fun `30 saniye arka planda kalinca oturum arka plana gecis anina kadarki sureyle biter`() {
        tracker.onForeground(t0)
        tracker.onBackground(t0 + 90_000)
        assertEquals(90, tracker.expire(t0 + 90_000 + 30_000))
        assertEquals(0L, state.startedAt)
        assertNull("ikinci kez kapatılmaz", tracker.expire(t0 + 200_000))
        assertEquals(HsSessionTracker.Transition(null, true), tracker.onForeground(t0 + 300_000))
    }

    @Test
    fun `surec pencere icinde oldurulurse onceki oturum sonraki baslatmada kapatilir`() {
        tracker.onForeground(t0)
        tracker.onBackground(t0 + 45_000)
        // Süreç öldü; kalıcı durumla yeni izleyici.
        val restarted = HsSessionTracker(state)
        val transition = restarted.onForeground(t0 + 10 * 60_000)
        assertEquals(HsSessionTracker.Transition(45, true), transition)
        assertEquals(t0 + 10 * 60_000, state.startedAt)
    }

    @Test
    fun `surec pencere icinde yeniden baslarsa ayni oturum surer`() {
        tracker.onForeground(t0)
        tracker.onBackground(t0 + 45_000)
        val restarted = HsSessionTracker(state)
        assertNull(restarted.expire(t0 + 50_000))
        assertEquals(HsSessionTracker.Transition(null, false), restarted.onForeground(t0 + 50_000))
    }

    @Test
    fun `on planda olen surecin suresi uydurulmaz`() {
        tracker.onForeground(t0)
        val restarted = HsSessionTracker(state)
        assertNull(restarted.expire(t0 + 3_600_000))
        assertEquals(HsSessionTracker.Transition(null, true), restarted.onForeground(t0 + 3_600_000))
    }

    @Test
    fun `oturum yokken arka plan bir sey yapmaz`() {
        assertFalse(tracker.onBackground(t0))
        assertEquals(0L, state.backgroundAt)
    }
}
