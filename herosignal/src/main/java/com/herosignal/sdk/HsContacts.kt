package com.herosignal.sdk

import org.json.JSONObject

/** E-posta/SMS adresi normalizasyonu; kurallar sunucu (`packages/shared` messaging) ile aynıdır. */
object HsContacts {
    private val EMAIL = Regex(
        "^[^\\s@\"(),:;<>\\[\\\\\\]]+@[A-Za-z0-9](?:[A-Za-z0-9-]{0,62}[A-Za-z0-9])?(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]{0,62}[A-Za-z0-9])?)+$",
    )
    private val E164 = Regex("^\\+[1-9]\\d{6,14}$")
    private val PHONE_SEPARATORS = Regex("[\\s().-]")

    /** Boşluk kırpma + küçük harf (yerel ayardan bağımsız); geçersizse null. */
    @JvmStatic
    fun normalizeEmail(value: String): String? {
        val v = value.trim().lowercase()
        if (v.length > 320 || !EMAIL.matches(v)) return null
        return v
    }

    /** E.164 biçimi: boşluk, tire, parantez ve nokta atılır, `00` öneki `+` olur; geçersizse null. */
    @JvmStatic
    fun normalizePhone(value: String): String? {
        var v = value.trim().replace(PHONE_SEPARATORS, "")
        if (v.startsWith("00")) v = "+" + v.substring(2)
        return if (E164.matches(v)) v else null
    }

    /**
     * E-posta/SMS kaydındaki `user` nesnesi: kimlikli kullanıcıda `external_id` (+ `identity_hash`); anonimde cihazın
     * mevcut aboneliği (`subscription_id`: push, yoksa önceki e-posta/SMS) ile aynı kullanıcıya bağlanır; hiçbiri yoksa null.
     */
    internal fun registrationUser(externalId: String?, identityHash: String?, subscriptionId: String?): JSONObject? = when {
        externalId != null -> JSONObject().put("external_id", externalId).apply { identityHash?.let { put("identity_hash", it) } }
        subscriptionId != null -> JSONObject().put("subscription_id", subscriptionId)
        else -> null
    }
}
