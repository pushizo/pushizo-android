package com.herosignal.sdk

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/** Bildirim kullanıcı tarafından kaydırılıp/temizlenip kapatıldığında (deleteIntent) `dismissed` bildirir. */
class NotificationDismissedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val deliveryId = intent.getStringExtra(NotificationOpenedReceiver.EXTRA_DELIVERY_ID) ?: return
        if (HeroSignal.isInitialized) HeroSignal.reportDismissed(deliveryId)
    }

    internal companion object {
        fun pendingIntent(context: Context, deliveryId: String, requestCode: Int): PendingIntent {
            val intent = Intent(context, NotificationDismissedReceiver::class.java)
                .putExtra(NotificationOpenedReceiver.EXTRA_DELIVERY_ID, deliveryId)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
            return PendingIntent.getBroadcast(context, requestCode, intent, flags)
        }
    }
}
