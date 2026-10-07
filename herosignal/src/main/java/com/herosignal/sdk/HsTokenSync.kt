package com.herosignal.sdk

import org.json.JSONObject
import java.net.URLEncoder
import java.security.MessageDigest

/**
 * Abonelik sahiplik kanıtı (ADR-0016): `PATCH`/`DELETE subscriptions/{id}`, `login`, `logout`, `POST sessions` ve
 * `user.subscription_id` ile bağlama isteklerine kayıtlı token'ın SHA-256 özeti (`token_hash`) ve varsa kullanıcının
 * `identity_hash`'i eklenir; `ownership_proof: true` ile kaydolan abonelik kanıtsız değiştirilemez/silinemez.
 */
internal object HsOwnership {
    /** UTF-8 metnin SHA-256 özeti (küçük harf hex); sunucudaki `token_hash` ile aynıdır. */
    fun tokenHash(token: String): String =
        MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    /** Gövdeye kanıt alanlarını ekler (değer yoksa eklenmez). */
    fun withProof(body: JSONObject, registeredToken: String?, identityHash: String? = null): JSONObject = body.apply {
        registeredToken?.let { put("token_hash", tokenHash(it)) }
        identityHash?.let { put("identity_hash", it) }
    }

    /**
     * `login` gövdesi: `identity_hash` hedef kullanıcıya aittir (yalnızca aynı kullanıcıya yeniden girişte kanıt sayılır);
     * aboneliğin kanıtı kayıtlı token'ın (e-posta/SMS'te kanonik adresin) özetidir.
     */
    fun loginBody(externalId: String, identityHash: String?, registeredToken: String?): JSONObject =
        withProof(JSONObject().put("external_id", externalId).apply { identityHash?.let { put("identity_hash", it) } }, registeredToken)

    /** `DELETE` için sorgu dizesi (`?token_hash=…&identity_hash=…`); kanıt yoksa boş. */
    fun query(registeredToken: String?, identityHash: String? = null): String {
        val params = listOfNotNull(
            registeredToken?.let { "token_hash=${tokenHash(it)}" },
            identityHash?.let { "identity_hash=${URLEncoder.encode(it, "UTF-8")}" },
        )
        return if (params.isEmpty()) "" else params.joinToString("&", prefix = "?")
    }
}

/** Push token eşitleme sonucu. */
internal sealed class HsTokenSyncResult {
    /** Token zaten kayıtlı; istek gönderilmedi. */
    object Unchanged : HsTokenSyncResult()

    /** Mevcut aboneliğin token'ı `PATCH` ile güncellendi. */
    object Updated : HsTokenSyncResult()

    /** Yeni abonelik oluşturuldu (`POST subscriptions`). */
    data class Created(val subscriptionId: String, val userId: String, val externalId: String?) : HsTokenSyncResult()
}

/**
 * Token yaşam döngüsü (docs/plan/06): kayıtlı abonelik varsa token değişikliğinde yeni abonelik açılmaz,
 * `PATCH subscriptions/{id}` ile token yerinde güncellenir. Abonelik sunucuda silinmişse (404) yeniden oluşturulur.
 * Ağ katmanından bağımsızdır (JVM birim testinde sahte istekçi ile sınanır).
 */
internal class HsTokenSync(private val request: (method: String, path: String, body: JSONObject?) -> JSONObject) {

    fun sync(
        subscriptionId: String?,
        registeredToken: String?,
        token: String,
        device: JSONObject,
        user: JSONObject,
        channel: String = "android_push",
    ): HsTokenSyncResult {
        require(token.isNotBlank()) { "boş token kaydedilemez" }
        if (subscriptionId != null && registeredToken == token) return HsTokenSyncResult.Unchanged
        if (subscriptionId != null) {
            try {
                // Kanıt: sunucudaki (eski) token'ın özeti; yeni token bu istekle kaydedilir.
                val body = HsOwnership.withProof(JSONObject().put("token", token).put("device", device), registeredToken)
                request("PATCH", "/subscriptions/$subscriptionId", body)
                return HsTokenSyncResult.Updated
            } catch (e: HsApiException) {
                if (e.status != 404) throw e
                // Abonelik sunucuda yok: aşağıda yeniden oluşturulur.
            }
        }
        val body = JSONObject()
            .put("channel", channel)
            .put("token", token)
            .put("device", device)
            .put("user", user)
            .put("ownership_proof", true)
        val result = request("POST", "/subscriptions", body)
        return HsTokenSyncResult.Created(
            subscriptionId = result.getString("subscription_id"),
            userId = result.getString("user_id"),
            externalId = result.optString("external_id").takeIf { it.isNotBlank() && it != "null" },
        )
    }
}
