package com.pushizo.sdk.adm

import com.amazon.device.messaging.ADMMessageReceiver

/**
 * ADM yayın alıcısı (manifest merge ile eklenir; yalnızca `com.amazon.device.messaging.permission.SEND` sahibi ADM
 * hizmeti gönderebilir). ADM 1.1.0+ cihazlarda olaylar [PushizoAdmMessageHandler] işine, eski cihazlarda
 * [PushizoAdmLegacyMessageHandler] servisine aktarılır.
 */
class PushizoAdmMessageReceiver : ADMMessageReceiver(PushizoAdmLegacyMessageHandler::class.java) {
    init {
        if (AdmClasses.isPresent(AdmClasses.JOB_BASE)) registerJobServiceClass(PushizoAdmMessageHandler::class.java, JOB_ID)
    }

    companion object {
        /** JobScheduler iş kimliği; uygulamanın kendi işleriyle çakışmaması için sabit ve seyrek bir değer ("PZAD"). */
        const val JOB_ID: Int = 0x505A4144
    }
}
