package com.pushizo.sdk.adm

import android.content.Intent
import com.amazon.device.messaging.ADMMessageHandlerBase

/**
 * Eski Fire OS / ADM sürümleri için IntentService işleyicisi (JobScheduler işleyicisi yoksa kullanılır); davranış
 * [PushizoAdmMessageHandler] ile aynıdır.
 */
class PushizoAdmLegacyMessageHandler : ADMMessageHandlerBase(NAME) {
    override fun onMessage(intent: Intent?) =
        AdmDispatcher.onMessage(applicationContext, AdmDispatcher.extrasOf(intent))

    override fun onRegistered(registrationId: String?) =
        AdmDispatcher.onRegistered(applicationContext, registrationId)

    override fun onUnregistered(registrationId: String?) =
        AdmDispatcher.onUnregistered(applicationContext, registrationId)

    override fun onRegistrationError(errorId: String?) =
        AdmDispatcher.onRegistrationError(applicationContext, errorId)

    private companion object {
        const val NAME = "PushizoAdmLegacyMessageHandler"
    }
}
