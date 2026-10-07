package com.herosignal.sdk

/** Alias (kullanıcı kimliği etiketi) doğrulaması; kurallar sunucu ile aynıdır. */
internal object HsAliases {
    /** Tek istekte en fazla alias (SDK API sınırı). */
    const val MAX_PER_REQUEST = 20
    private val LABEL = Regex("^[a-z0-9_]{1,50}$")
    private val RESERVED = setOf("external_id", "hs_id")

    /** Etiket geçersizse veya ayrılmışsa (external_id → login/logout, hs_id → sistem) hata fırlatır. */
    fun requireLabel(label: String) {
        require(LABEL.matches(label)) { "Geçersiz alias etiketi \"$label\": küçük harf, rakam ve alt çizgi (en fazla 50)." }
        require(label !in RESERVED) { "\"$label\" alias olarak ayarlanamaz (external_id için login/logout kullanın)." }
    }

    /** Alias haritasını doğrular ve değerleri kırpar. */
    fun normalize(aliases: Map<String, String>): Map<String, String> {
        require(aliases.isNotEmpty()) { "En az bir alias gerekli." }
        require(aliases.size <= MAX_PER_REQUEST) { "Tek seferde en fazla $MAX_PER_REQUEST alias eklenebilir." }
        return aliases.mapValues { (label, raw) ->
            requireLabel(label)
            val value = raw.trim()
            require(value.length in 1..255) { "\"$label\" alias değeri 1-255 karakter olmalı." }
            value
        }
    }
}
