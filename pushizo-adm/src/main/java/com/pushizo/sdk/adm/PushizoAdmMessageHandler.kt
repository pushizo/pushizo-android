package com.pushizo.sdk.adm

import android.content.Context
import android.content.Intent
import com.amazon.device.messaging.ADMMessageHandlerJobBase

/**
 * ADM 1.1.0+ (Android 8+) JobScheduler işleyicisi; manifest merge ile eklenir ve [PushizoAdmMessageReceiver] tarafından
 * kaydedilir. Kayıt kimliği `adm_push` aboneliğine, mesajlar çekirdeğin bildirim hattına iletilir.
 */
class PushizoAdmMessageHandler : ADMMessageHandlerJobBase() {
    override fun onMessage(context: Context?, intent: Intent?) =
        AdmDispatcher.onMessage(context ?: applicationContext, AdmDispatcher.extrasOf(intent))

    override fun onRegistered(context: Context?, registrationId: String?) =
        AdmDispatcher.onRegistered(context ?: applicationContext, registrationId)

    override fun onUnregistered(context: Context?, registrationId: String?) =
        AdmDispatcher.onUnregistered(context ?: applicationContext, registrationId)

    override fun onRegistrationError(context: Context?, errorId: String?) =
        AdmDispatcher.onRegistrationError(context ?: applicationContext, errorId)
}
