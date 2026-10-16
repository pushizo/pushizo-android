package com.pushizo.sdk

import org.json.JSONObject

/** App'in kabul ettiği kimlik kanıtı (ADR-0023 §9): HMAC `identity_hash`, ES256 JWT veya ikisinden biri. */
internal enum class HsIdentityVerificationMode(val wire: String) {
    HMAC("hmac"),
    JWT("jwt"),
    BOTH("both");

    companion object {
        /** `/config` yanıtındaki `identity_verification_mode`; alan yoksa veya tanınmıyorsa `hmac` (eski sunucular). */
        fun fromConfig(config: JSONObject): HsIdentityVerificationMode {
            val value = config.optString("identity_verification_mode")
            return values().firstOrNull { it.wire == value } ?: HMAC
        }
    }
}

/** Login öncesi kimlik kanıtı kuralları ve JWT yenileme (web SDK `login`/`updateUserJwt` ile aynı). */
internal object HsIdentityVerification {
    /** Doğrulama zorunluysa verilen kanıtın app moduna uyup uymadığını denetler; uymuyorsa hata mesajı döner. */
    fun loginError(required: Boolean, mode: HsIdentityVerificationMode, identityHash: String?, jwt: String?): String? {
        if (!required) return null
        val hasHash = !identityHash.isNullOrEmpty()
        val hasJwt = !jwt.isNullOrEmpty()
        return when {
            mode == HsIdentityVerificationMode.JWT && !hasJwt -> "Bu app için kimlik JWT'si (login(externalId, jwt = …)) zorunlu"
            mode == HsIdentityVerificationMode.HMAC && !hasHash -> "Bu app için identityHash zorunlu"
            mode == HsIdentityVerificationMode.BOTH && !hasHash && !hasJwt -> "Bu app için identityHash veya JWT zorunlu"
            else -> null
        }
    }

    /** [loginError] varsa `IllegalStateException` fırlatır (login'in önceki `error(...)` davranışıyla aynı tür). */
    fun requireLoginProof(required: Boolean, mode: HsIdentityVerificationMode, identityHash: String?, jwt: String?) {
        loginError(required, mode, identityHash, jwt)?.let { throw IllegalStateException(it) }
    }

    /**
     * Giriş yapmış kullanıcının JWT'sini yeniler. Belirteç boşsa `IllegalArgumentException`, kimlikli kullanıcı yoksa
     * `IllegalStateException`; sonraki tüm SDK istekleri yeni belirteci taşır.
     */
    fun updateJwt(store: HsStorage, jwt: String) {
        require(jwt.isNotBlank()) { "jwt gerekli" }
        check(store.externalId != null) { "Önce login ile kullanıcı kimliklendirilmeli" }
        store.userJwt = jwt
    }
}
