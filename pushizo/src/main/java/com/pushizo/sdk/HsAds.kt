package com.pushizo.sdk

import org.json.JSONObject

/**
 * Rıza kaydı (ADR-0037): `ads_personalization` = kullanıcının e-posta/telefon SHA-256 özetinin, app'in bağladığı reklam
 * platformlarının kitlelerine gönderilmesine açık onay. Varsayılan rıza yoktur; `source` kaydı yazan taraftır.
 */
data class HsConsent(
    val purpose: String,
    val granted: Boolean,
    val source: String?,
    /** Epoch milisaniye; kayıt yoksa null. */
    val updatedAt: Long?,
)

/** Rıza amaçları (tercih API'sinde ayrılmış kategori anahtarları). */
object HsConsents {
    const val ADS_PERSONALIZATION = "ads_personalization"
}

/**
 * Mobil reklam kimliği (GAID) kuralları (saf mantık): kimlik yalnızca kullanıcı izin verdiyse uygulama tarafından
 * verilir (Google Play reklam kimliği ayarı; SDK reklam kitaplığı içermez). Sıfır kimlik ("reklam kimliğini sil") ve
 * geçersiz biçim `null` sayılır ve sunucudaki kayıt silinir. Gönderim `PATCH /subscriptions/{id}`
 * (`advertising_id`), kalıcı kuyruk üzerinden.
 */
internal object HsAdsRules {
    const val OP_TYPE = "advertising_id"
    private val UUID = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
    private const val ZERO = "00000000-0000-0000-0000-000000000000"

    /** Geçerli, sıfır olmayan UUID küçük harfle; aksi halde null. */
    fun normalize(id: String?): String? {
        val v = id?.trim().orEmpty()
        if (!UUID.matches(v) || v == ZERO) return null
        return v.lowercase()
    }

    /** Kuyruk işlemi (`null` = kimliği sil). */
    fun op(id: String?, now: Long): JSONObject =
        JSONObject().put("type", OP_TYPE).put("advertising_id", normalize(id) ?: JSONObject.NULL).put("at", now)

    /** Kuyrukta daha yeni bir reklam kimliği işlemi varsa bu işlem gönderilmeden atılır. */
    fun isSuperseded(op: JSONObject, queue: List<JSONObject>): Boolean {
        val latest = queue.lastOrNull { it.optString("type") == OP_TYPE } ?: return false
        return latest.toString() != op.toString()
    }

    /** Kuyruk işleminden `PATCH` gövdesi (sahiplik kanıtı çağıran tarafından eklenir). */
    fun body(op: JSONObject): JSONObject =
        JSONObject().put("advertising_id", if (op.isNull("advertising_id")) JSONObject.NULL else op.getString("advertising_id"))
}
