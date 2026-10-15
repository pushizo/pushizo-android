package com.herosignal.sdk

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * Cihazda kalıcı SDK durumu (SharedPreferences, app başına). İkincil kurucu birim testlerinde bellek içi
 * [SharedPreferences] ile kullanılır.
 */
internal class HsStorage(private val prefs: SharedPreferences) : HsQueueStore, HsSessionTracker.State {
    constructor(context: Context, appId: String) :
        this(context.applicationContext.getSharedPreferences("herosignal_$appId", Context.MODE_PRIVATE))

    var subscriptionId: String?
        get() = prefs.getString(KEY_SUBSCRIPTION_ID, null)
        set(value) = prefs.edit().putString(KEY_SUBSCRIPTION_ID, value).apply()

    var userId: String?
        get() = prefs.getString(KEY_USER_ID, null)
        set(value) = prefs.edit().putString(KEY_USER_ID, value).apply()

    var externalId: String?
        get() = prefs.getString(KEY_EXTERNAL_ID, null)
        set(value) = prefs.edit().putString(KEY_EXTERNAL_ID, value).apply()

    var pendingIdentityHash: String?
        get() = prefs.getString(KEY_IDENTITY_HASH, null)
        set(value) = prefs.edit().putString(KEY_IDENTITY_HASH, value).apply()

    /** Login'de verilen kimlik doğrulama hash'i; kullanıcı çağrılarına (tag, alias) eklenir, logout ile silinir. */
    var userIdentityHash: String?
        get() = prefs.getString(KEY_USER_IDENTITY_HASH, null)
        set(value) = prefs.edit().putString(KEY_USER_IDENTITY_HASH, value).apply()

    /**
     * Login'de veya `updateUserJwt` ile verilen kimlik JWT'si (ADR-0023 §9). Uygulamaya özel depoda (MODE_PRIVATE)
     * identity hash ile aynı biçimde saklanır; yalnızca `Authorization` başlığına eklenir, logout ile silinir.
     */
    var userJwt: String?
        get() = prefs.getString(KEY_USER_JWT, null)
        set(value) = prefs.edit().putString(KEY_USER_JWT, value).apply()

    var fcmToken: String?
        get() = prefs.getString(KEY_FCM_TOKEN, null)
        set(value) = prefs.edit().putString(KEY_FCM_TOKEN, value).apply()

    /** Sunucuya en son başarıyla kaydedilen token (değişiklik tespiti). */
    var registeredToken: String?
        get() = prefs.getString(KEY_REGISTERED_TOKEN, null)
        set(value) = prefs.edit().putString(KEY_REGISTERED_TOKEN, value).apply()

    var optedIn: Boolean
        get() = prefs.getBoolean(KEY_OPTED_IN, true)
        set(value) = prefs.edit().putBoolean(KEY_OPTED_IN, value).apply()

    var consentGiven: Boolean
        get() = prefs.getBoolean(KEY_CONSENT, false)
        set(value) = prefs.edit().putBoolean(KEY_CONSENT, value).apply()

    var tags: Map<String, String>
        get() {
            val raw = prefs.getString(KEY_TAGS, null) ?: return emptyMap()
            return runCatching {
                val json = JSONObject(raw)
                json.keys().asSequence().associateWith { json.getString(it) }
            }.getOrDefault(emptyMap())
        }
        set(value) = prefs.edit().putString(KEY_TAGS, JSONObject(value).toString()).apply()

    /** Herhangi bir in-app mesajın bu cihazdaki son gösterimi (epoch ms); app geneli aralık için (ADR-0019 §7). */
    var lastInAppImpressionAt: Long?
        get() = prefs.getLong(KEY_IN_APP_LAST_IMPRESSION, 0L).takeIf { it > 0 }
        set(value) = prefs.edit().putLong(KEY_IN_APP_LAST_IMPRESSION, value ?: 0L).apply()

    /** Config'den gelen `in_app.min_interval_seconds`; config alınamazsa son bilinen değer kullanılır. */
    var inAppMinIntervalSeconds: Long
        get() = prefs.getLong(KEY_IN_APP_MIN_INTERVAL, 0L)
        set(value) = prefs.edit().putLong(KEY_IN_APP_MIN_INTERVAL, value).apply()

    /** Sunucuya en son başarıyla yazılan `device.push_capable` (null: bilinmiyor); fark varsa PATCH edilir. */
    var serverPushCapable: Boolean?
        get() = if (prefs.contains(KEY_SERVER_PUSH_CAPABLE)) prefs.getBoolean(KEY_SERVER_PUSH_CAPABLE, false) else null
        set(value) = prefs.edit().apply { if (value == null) remove(KEY_SERVER_PUSH_CAPABLE) else putBoolean(KEY_SERVER_PUSH_CAPABLE, value) }.apply()

    /** Sunucuya en son başarıyla yazılan `opted_in` (null: bilinmiyor). */
    var serverOptedIn: Boolean?
        get() = if (prefs.contains(KEY_SERVER_OPTED_IN)) prefs.getBoolean(KEY_SERVER_OPTED_IN, true) else null
        set(value) = prefs.edit().apply { if (value == null) remove(KEY_SERVER_OPTED_IN) else putBoolean(KEY_SERVER_OPTED_IN, value) }.apply()

    /** Onay yokken yapılan login/logout sunucuya henüz yansıtılmadı; onay verilince eşitlenir. */
    var identitySyncPending: Boolean
        get() = prefs.getBoolean(KEY_IDENTITY_SYNC_PENDING, false)
        set(value) = prefs.edit().putBoolean(KEY_IDENTITY_SYNC_PENDING, value).apply()

    /** Açık oturumun başlangıcı (epoch ms; 0 = oturum yok). Süreç ölse de oturum süresiyle kapatılabilsin diye kalıcı. */
    override var startedAt: Long
        get() = prefs.getLong(KEY_SESSION_STARTED_AT, 0L)
        set(value) = prefs.edit().putLong(KEY_SESSION_STARTED_AT, value).apply()

    /** Uygulamanın arka plana geçtiği an (epoch ms; 0 = ön planda veya oturum yok). */
    override var backgroundAt: Long
        get() = prefs.getLong(KEY_SESSION_BACKGROUND_AT, 0L)
        set(value) = prefs.edit().putLong(KEY_SESSION_BACKGROUND_AT, value).apply()

    override var queue: List<JSONObject>
        get() {
            val raw = prefs.getString(KEY_QUEUE, null) ?: return emptyList()
            return runCatching {
                val arr = JSONArray(raw)
                List(arr.length()) { arr.getJSONObject(it) }
            }.getOrDefault(emptyList())
        }
        set(value) = prefs.edit().putString(KEY_QUEUE, JSONArray(value).toString()).apply()

    /** E-posta/SMS abonelikleri: kanal (`email`/`sms`) → normalize adres → abonelik kimliği. */
    fun contacts(channel: String): Map<String, String> {
        val raw = prefs.getString(contactKey(channel), null) ?: return emptyMap()
        return runCatching {
            val json = JSONObject(raw)
            json.keys().asSequence().associateWith { json.getString(it) }
        }.getOrDefault(emptyMap())
    }

    @Synchronized
    fun putContact(channel: String, address: String, subscriptionId: String) {
        prefs.edit().putString(contactKey(channel), JSONObject(contacts(channel) + (address to subscriptionId)).toString()).apply()
    }

    @Synchronized
    fun removeContact(channel: String, address: String) {
        prefs.edit().putString(contactKey(channel), JSONObject(contacts(channel) - address).toString()).apply()
    }

    private fun contactKey(channel: String) = "${KEY_CONTACTS_PREFIX}$channel"

    /** Kimlik temizliği; e-posta/SMS abonelikleri önceki kullanıcıda kalır, cihazdaki eşlemeleri silinir. */
    fun clearIdentity() {
        prefs.edit().remove(KEY_USER_ID).remove(KEY_EXTERNAL_ID).remove(KEY_TAGS).remove(KEY_IDENTITY_HASH).remove(KEY_USER_IDENTITY_HASH)
            .remove(KEY_USER_JWT)
            .remove(contactKey("email")).remove(contactKey("sms")).apply()
    }

    private companion object {
        const val KEY_SUBSCRIPTION_ID = "subscription_id"
        const val KEY_USER_ID = "user_id"
        const val KEY_EXTERNAL_ID = "external_id"
        const val KEY_IDENTITY_HASH = "identity_hash"
        const val KEY_USER_IDENTITY_HASH = "user_identity_hash"
        const val KEY_USER_JWT = "user_jwt"
        const val KEY_FCM_TOKEN = "fcm_token"
        const val KEY_REGISTERED_TOKEN = "registered_token"
        const val KEY_OPTED_IN = "opted_in"
        const val KEY_CONSENT = "consent"
        const val KEY_TAGS = "tags"
        const val KEY_QUEUE = "queue"
        const val KEY_IN_APP_LAST_IMPRESSION = "in_app_last_impression_at"
        const val KEY_IN_APP_MIN_INTERVAL = "in_app_min_interval_seconds"
        const val KEY_CONTACTS_PREFIX = "contacts_"
        const val KEY_SERVER_PUSH_CAPABLE = "server_push_capable"
        const val KEY_SERVER_OPTED_IN = "server_opted_in"
        const val KEY_IDENTITY_SYNC_PENDING = "identity_sync_pending"
        const val KEY_SESSION_STARTED_AT = "session_started_at"
        const val KEY_SESSION_BACKGROUND_AT = "session_background_at"
    }
}
