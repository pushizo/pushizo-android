package com.herosignal.sdk

import org.json.JSONArray
import org.json.JSONObject

/**
 * Kullanıcı tercihi (ADR-0019 §4): kategori × kanal (`null` = tüm kanallar) → opt-in. `explicit` false ise değer
 * kategori varsayılanından türetilmiştir; `source` kaydı yazan taraf (sdk, api, preference_center…).
 */
data class HsPreference(
    val category: String,
    /** `null` | `ios_push` | `android_push` | `web_push` | `email` | `sms`. */
    val channel: String?,
    val optedIn: Boolean,
    val explicit: Boolean,
    val source: String?,
    /** Epoch milisaniye; türetilmiş değerde null. */
    val updatedAt: Long?,
)

/** Mesaj kategorisi: çok dilli ad/açıklama (`dil kodu → metin`), kanal kısıtı (`null` = tümü) ve varsayılan opt-in. */
data class HsMessageCategory(
    val key: String,
    val name: Map<String, String>,
    val description: Map<String, String>,
    val channels: List<String>?,
    val defaultOptedIn: Boolean,
) {
    /** Verilen dilde ad; yoksa İngilizce, o da yoksa ilk tanımlı dil. */
    fun localizedName(language: String): String = name[language] ?: name["en"] ?: name.values.firstOrNull() ?: key
}

/** `GET/PUT /preferences` sonucu: kullanıcının tercihleri ve app'in kategorileri. */
data class HsPreferencesResult(val preferences: List<HsPreference>, val categories: List<HsMessageCategory>)

/** `User.setPreferences` girdisi; `channel` null ise tüm kanallar. */
data class HsPreferenceInput(val category: String, val channel: String? = null, val optedIn: Boolean) {
    fun toJson(): JSONObject = JSONObject().put("category", category).put("channel", channel ?: JSONObject.NULL).put("opted_in", optedIn)
}

internal object HsPreferencesParser {
    private val CHANNELS = setOf("ios_push", "android_push", "web_push", "email", "sms")

    fun parseResult(json: JSONObject): HsPreferencesResult = HsPreferencesResult(
        preferences = json.optJSONArray("data").objects().map(::parsePreference),
        categories = json.optJSONArray("categories").objects().mapNotNull(::parseCategory),
    )

    fun parsePreference(json: JSONObject): HsPreference = HsPreference(
        category = json.getString("category"),
        channel = json.optStringOrNull("channel"),
        optedIn = json.optBoolean("opted_in", false),
        explicit = json.optBoolean("explicit", false),
        source = json.optStringOrNull("source"),
        updatedAt = json.optStringOrNull("updated_at")?.let(HsIsoTime::parse),
    )

    /** Anahtarsız kategori atlanır; `channels` null ise kısıt yok. */
    fun parseCategory(json: JSONObject): HsMessageCategory? {
        val key = json.optStringOrNull("key") ?: return null
        val channels = json.optJSONArray("channels")?.let { arr -> (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { c -> c.isNotEmpty() } } }
        return HsMessageCategory(
            key = key,
            name = json.optJSONObject("name").strings(),
            description = json.optJSONObject("description").strings(),
            channels = channels,
            defaultOptedIn = json.optBoolean("default_opted_in", true),
        )
    }

    /** Girdi doğrulaması: kategori 1-64 karakter, kanal bilinen bir anahtar, en fazla 100 kayıt. */
    fun validate(inputs: List<HsPreferenceInput>) {
        require(inputs.isNotEmpty() && inputs.size <= 100) { "1-100 tercih verilmeli." }
        for (p in inputs) {
            require(p.category.isNotBlank() && p.category.length <= 64) { "Kategori anahtarı 1-64 karakter olmalı." }
            require(p.channel == null || p.channel in CHANNELS) { "Geçersiz kanal: ${p.channel}" }
        }
    }

    fun toJson(inputs: List<HsPreferenceInput>): JSONArray = JSONArray().also { arr -> inputs.forEach { arr.put(it.toJson()) } }

    private fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }
    private fun JSONObject?.strings(): Map<String, String> =
        this?.keys()?.asSequence()?.mapNotNull { k -> optStringOrNull(k)?.let { k to it } }?.toMap() ?: emptyMap()
}
