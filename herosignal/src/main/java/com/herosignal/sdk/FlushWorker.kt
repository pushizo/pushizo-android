package com.herosignal.sdk

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/** Ağ bağlantısı gelince bekleyen kuyruğu gönderir (WorkManager). */
class FlushWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (!HeroSignal.isInitialized) return Result.success()
        return if (HeroSignal.flushQueue()) Result.success() else Result.retry()
    }
}
