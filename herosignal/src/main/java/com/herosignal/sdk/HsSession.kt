package com.herosignal.sdk

/**
 * Oturum penceresi (docs/plan/06 "Oturum"): uygulama ön plana gelince oturum başlar; arka planda en az [windowMs]
 * (30 sn) kalırsa oturum, arka plana geçiş anına kadarki süresiyle biter. Daha kısa aralıkta ön plana dönülürse aynı
 * oturum sürer (yeni `session_start` gönderilmez). Başlangıç ve arka plana geçiş zamanı kalıcı saklandığından süreç
 * pencere içinde öldürülse de önceki oturum bir sonraki başlatmada süresiyle kapatılır.
 *
 * Saf mantıktır (Android bağımlılığı yok); kuyruğa ekleme [HeroSignal] tarafından yapılır.
 */
internal class HsSessionTracker(private val state: State, private val windowMs: Long = WINDOW_MS) {

    /** Kalıcı oturum durumu ([HsStorage]; testlerde bellek içi). 0 = değer yok. */
    interface State {
        var startedAt: Long
        var backgroundAt: Long
    }

    /** Ön plana geçişin sonucu: kapanan önceki oturumun süresi (sn; yoksa null) ve yeni oturum başladı mı. */
    data class Transition(val endedDurationSeconds: Int?, val started: Boolean)

    /** Uygulama ön plana geldi. */
    @Synchronized
    fun onForeground(now: Long): Transition {
        val started = state.startedAt
        val background = state.backgroundAt
        if (started > 0 && background > 0 && now - background < windowMs) {
            // Pencere içinde dönüldü: oturum devam eder.
            state.backgroundAt = 0
            return Transition(endedDurationSeconds = null, started = false)
        }
        // Arka plana geçiş kaydı olmayan açık oturum (süreç ön plandayken öldü) için güvenilir bitiş anı yoktur;
        // süre uydurulmaz, yalnızca yeni oturum açılır.
        val ended = if (started > 0 && background > 0) duration(started, background) else null
        state.startedAt = now
        state.backgroundAt = 0
        return Transition(endedDurationSeconds = ended, started = true)
    }

    /** Uygulama arka plana geçti; açık oturum varsa true (pencere dolunca [expire] çağrılmalı). */
    @Synchronized
    fun onBackground(now: Long): Boolean {
        if (state.startedAt == 0L) return false
        state.backgroundAt = now
        return true
    }

    /** Arka planda pencere dolduysa oturumu kapatır ve süresini (sn) döndürür; aksi halde null. */
    @Synchronized
    fun expire(now: Long): Int? {
        val started = state.startedAt
        val background = state.backgroundAt
        if (started == 0L || background == 0L || now - background < windowMs) return null
        state.startedAt = 0
        state.backgroundAt = 0
        return duration(started, background)
    }

    private fun duration(start: Long, end: Long): Int =
        ((end - start).coerceAtLeast(0) / 1000).coerceAtMost(MAX_DURATION_SECONDS).toInt()

    companion object {
        /** Oturumun bitmesi için arka planda kalma süresi. */
        const val WINDOW_MS = 30_000L
        /** Sunucu sınırı (`POST /sessions` `duration_seconds` ≤ 7 gün). */
        const val MAX_DURATION_SECONDS = 86_400L * 7
    }
}
