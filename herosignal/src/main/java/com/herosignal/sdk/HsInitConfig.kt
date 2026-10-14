package com.herosignal.sdk

import android.content.Context
import org.json.JSONObject

/**
 * Son `HeroSignal.initialize` yapılandırması (appId ve [HeroSignal.Options]; gizli bilgi içermez). Uygulama kodu
 * `initialize` çağırmadan başlayan süreçlerde (ör. Flutter/React Native uygulaması kapalıyken gelen FCM mesajı,
 * bildirim tıklaması/kapatılması, WorkManager flush'ı) SDK'nın yerelde başlatılabilmesi için kalıcı saklanır.
 * Gizlilik onayı durumu app'e ait [HsStorage] içinde ayrıca kalıcıdır.
 */
internal object HsInitConfig {
    private const val PREFS = "herosignal_init"
    private const val KEY_CONFIG = "config"

    fun encode(appId: String, options: HeroSignal.Options): JSONObject = JSONObject()
        .put("app_id", appId)
        .put("api_url", options.apiUrl)
        .put("requires_privacy_consent", options.requiresPrivacyConsent)
        .put("log_level", options.logLevel.name)
        .put("default_channel_name", options.defaultChannelName)
        .put("default_channel_id", options.defaultChannelId)

    /** Bozuk veya eksik kayıtta null (SDK başlatılmaz). */
    fun decode(json: JSONObject): Pair<String, HeroSignal.Options>? {
        val appId = json.optString("app_id").takeIf { it.isNotBlank() } ?: return null
        val defaults = HeroSignal.Options()
        val options = HeroSignal.Options(
            apiUrl = json.optString("api_url").takeIf { it.isNotBlank() } ?: defaults.apiUrl,
            requiresPrivacyConsent = json.optBoolean("requires_privacy_consent", defaults.requiresPrivacyConsent),
            logLevel = HsLog.Level.values().firstOrNull { it.name == json.optString("log_level") } ?: defaults.logLevel,
            defaultChannelName = json.optString("default_channel_name").takeIf { it.isNotBlank() } ?: defaults.defaultChannelName,
            defaultChannelId = json.optString("default_channel_id").takeIf { it.isNotBlank() } ?: defaults.defaultChannelId,
        )
        return appId to options
    }

    fun save(context: Context, appId: String, options: HeroSignal.Options) {
        val raw = encode(appId, options).toString()
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getString(KEY_CONFIG, null) != raw) prefs.edit().putString(KEY_CONFIG, raw).apply()
    }

    fun load(context: Context): Pair<String, HeroSignal.Options>? {
        val raw = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_CONFIG, null) ?: return null
        return runCatching { decode(JSONObject(raw)) }.getOrNull()
    }
}
