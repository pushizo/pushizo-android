package com.pushizo.sdk.adm

import android.content.Context
import android.content.Intent
import com.pushizo.sdk.HsLog
import com.pushizo.sdk.HsPushBridge

/** ADM olaylarının çekirdeğe iletildiği hedef (birim testinde sahte uygulanır). */
internal interface AdmSink {
    fun registered(context: Context, registrationId: String)
    fun unregistered(context: Context, registrationId: String?)
    fun registrationError(context: Context, errorId: String)
    fun message(context: Context, data: Map<String, String>)
}

/** Çekirdek SDK giriş noktaları: `adm_push` aboneliği ve FCM ile aynı bildirim gösterim/olay hattı. */
internal object CoreAdmSink : AdmSink {
    override fun registered(context: Context, registrationId: String) =
        HsPushBridge.onAdmRegistered(context, registrationId)

    override fun unregistered(context: Context, registrationId: String?) =
        HsPushBridge.onAdmUnregistered(context, registrationId)

    override fun registrationError(context: Context, errorId: String) =
        HsPushBridge.onTokenError(context, HsPushBridge.CHANNEL_ADM, AdmRegistrationException(errorId))

    override fun message(context: Context, data: Map<String, String>) =
        HsPushBridge.onMessageReceived(context, data)
}

/** ADM kayıt hatası; mesaj yalnızca ADM hata kodunu taşır (ör. `SERVICE_NOT_AVAILABLE`, `INVALID_SENDER`). */
class AdmRegistrationException(val errorId: String) : Exception("ADM kayıt hatası: $errorId")

/**
 * ADM işleyicilerinin (JobScheduler ve eski IntentService) ortak mantığı: girdileri doğrular, mesaj ekstralarını FCM
 * data map'ine çevirir ve [sink]'e iletir.
 */
internal object AdmDispatcher {
    @Volatile var sink: AdmSink = CoreAdmSink

    fun onRegistered(context: Context, registrationId: String?) {
        val value = registrationId?.trim()
        if (value.isNullOrEmpty()) return
        HsLog.d("ADM kayıt kimliği alındı")
        sink.registered(context, value)
    }

    fun onUnregistered(context: Context, registrationId: String?) {
        HsLog.d("ADM kaydı silindi")
        sink.unregistered(context, registrationId?.trim()?.ifEmpty { null })
    }

    fun onRegistrationError(context: Context, errorId: String?) {
        sink.registrationError(context, errorId?.trim()?.ifEmpty { null } ?: UNKNOWN_ERROR)
    }

    fun onMessage(context: Context, extras: Map<String, Any?>) {
        val data = dataFromExtras(extras)
        if (data.isEmpty()) return
        sink.message(context, data)
    }

    /**
     * ADM mesajının `data` alanı niyet ekstralarına metin olarak gelir (sunucu FCM data map'iyle aynı anahtarları
     * gönderir: `hs`, `hs_title`, `hs_body`…). Yalnızca metin değerler alınır; boş anahtarlar atlanır.
     */
    fun dataFromExtras(extras: Map<String, Any?>): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        for ((key, value) in extras) {
            if (key.isBlank() || value !is String) continue
            result[key] = value
        }
        return result
    }

    /** Niyet ekstraları → anahtar/değer map'i (Bundle'a bağlı tek nokta). */
    fun extrasOf(intent: Intent?): Map<String, Any?> {
        val extras = intent?.extras ?: return emptyMap()
        @Suppress("DEPRECATION")
        return extras.keySet().associateWith { extras.get(it) }
    }

    private const val UNKNOWN_ERROR = "UNKNOWN"
}
