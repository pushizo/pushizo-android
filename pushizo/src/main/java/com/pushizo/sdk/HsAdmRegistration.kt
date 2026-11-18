package com.pushizo.sdk

import org.json.JSONObject

/**
 * Fire OS ADM kayıt kimliğinin `adm_push` aboneliğine dönüşümü (saf mantık; birim testinde sınanır). Kimlik
 * `contact_add`/`contact_remove` kuyruk işlemleriyle kaydedilir: cihazda tek ADM aboneliği kalır, kimlik yenilenince
 * eskisinin aboneliği silinir.
 */
internal object HsAdmRegistration {
    private const val MIN_LENGTH = 8
    private const val MAX_LENGTH = 4096

    /** Kırpılmış kimlik; uzunluk 8–4096 değilse null (sunucu ve ADM gönderim yolu aynı sınırı uygular). */
    fun normalize(registrationId: String?): String? =
        registrationId?.trim()?.takeIf { it.length in MIN_LENGTH..MAX_LENGTH }

    /** Bilinen kimlikler: cihazda kayıtlı abonelikler ve kuyrukta eklenmeyi bekleyenler (sıra korunur, tekrarsız). */
    fun known(registered: Collection<String>, queue: List<JSONObject>): List<String> =
        (registered + queue.filter { it.optString("type") == "contact_add" && it.optString("channel") == HsPushProviders.CHANNEL_ADM }
            .mapNotNull { it.optString("address").ifBlank { null } }).distinct()

    /** Yeni kimlik için işlemler: eski kimliklerin silinmesi, ardından yeni kimliğin eklenmesi. Geçersiz kimlikte null. */
    fun registrationOps(known: Collection<String>, registrationId: String?, at: Long): List<JSONObject>? {
        val value = normalize(registrationId) ?: return null
        return known.filter { it != value }.distinct().map { op("contact_remove", it, at) } + op("contact_add", value, at)
    }

    /** Kayıt silindi: kimlik verildiyse yalnızca o, verilmediyse bilinen bütün kimlikler silinir. */
    fun unregistrationOps(known: Collection<String>, registrationId: String?, at: Long): List<JSONObject> {
        val value = registrationId?.trim().orEmpty()
        val targets = if (value.isNotEmpty()) listOf(value) else known.distinct()
        return targets.map { op("contact_remove", it, at) }
    }

    private fun op(type: String, address: String, at: Long): JSONObject =
        JSONObject().put("type", type).put("channel", HsPushProviders.CHANNEL_ADM).put("address", address).put("at", at)
}
