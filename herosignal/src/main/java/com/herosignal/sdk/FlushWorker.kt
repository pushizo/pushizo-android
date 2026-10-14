package com.herosignal.sdk

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/** Ağ bağlantısı gelince bekleyen kuyruğu gönderir (WorkManager). */
class FlushWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        // Süreç uygulama kodu çalışmadan başladıysa SDK kayıtlı yapılandırmayla başlatılır; hiç başlatılmadıysa iş yoktur.
        if (!HeroSignal.ensureInitialized(applicationContext)) return Result.success()
        return if (HeroSignal.flushQueue()) Result.success() else Result.retry()
    }
}
