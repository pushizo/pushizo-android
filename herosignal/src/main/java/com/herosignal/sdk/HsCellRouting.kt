package com.herosignal.sdk

import org.json.JSONObject
import java.net.URI

/**
 * Hücre mimarisi (ADR-0032 §4): SDK, app'in veri bölgesindeki API kökünü config `api_url`'den veya yanlış hücreye
 * giden isteğin 421 `HS_WRONG_CELL` yanıtındaki `details.api_url`'den öğrenir. Saf mantık; ortak vektörler
 * `sdk/testdata/cell-routing-vectors.json` (Web ve iOS aynı kuralları uygular).
 */
internal object HsCellRouting {
    const val WRONG_CELL_STATUS = 421
    const val WRONG_CELL_CODE = "HS_WRONG_CELL"

    /** Varsayılan (global) API kökü: [HeroSignal.Options.apiUrl] bu değerdeyse config `api_url` benimsenir. */
    const val DEFAULT_API_URL = "https://api.herosignal.dev"

    private val IPV4_LOOPBACK = Regex("^127(\\.\\d{1,3}){3}$")

    private fun isLoopback(host: String?): Boolean {
        val h = host?.lowercase() ?: return false
        return h == "localhost" || h.endsWith(".localhost") || h == "[::1]" || IPV4_LOOPBACK.matches(h)
    }

    private fun parse(value: String): URI? = runCatching { URI(value) }.getOrNull()

    /**
     * Sunucunun bildirdiği API kökünü doğrular; kabul edilirse sondaki `/` atılmış hâlini döner.
     * Kural: geçerli mutlak URL, `https` zorunlu (tek istisna: mevcut kök de `http` loopback iken loopback hedef),
     * kullanıcı bilgisi / sorgu / parça içeremez. Alan adı kısıtlaması yoktur (bölge ve bayi alan adları farklı üst
     * alan adlarında olabilir); adres yalnızca zaten güvenilen API'nin TLS yanıtından alınır.
     */
    fun acceptApiUrl(candidate: Any?, current: String): String? {
        if (candidate !is String || candidate.isEmpty() || candidate.length > 2048) return null
        val uri = parse(candidate) ?: return null
        if (uri.isOpaque || uri.host.isNullOrEmpty()) return null
        if (uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null) return null
        when (uri.scheme?.lowercase()) {
            "https" -> Unit
            "http" -> {
                val cur = parse(current)
                if (!isLoopback(uri.host) || cur?.scheme?.lowercase() != "http" || !isLoopback(cur.host)) return null
            }
            else -> return null
        }
        return candidate.trimEnd('/')
    }

    /** 421 `HS_WRONG_CELL` hata zarfından kabul edilebilir yeni API kökü (yoksa veya mevcut kökse null). */
    fun wrongCellTarget(status: Int, envelope: JSONObject?, current: String): String? {
        if (status != WRONG_CELL_STATUS || envelope == null) return null
        val error = envelope.optJSONObject("error") ?: return null
        if (error.optString("code") != WRONG_CELL_CODE) return null
        val candidate = error.optJSONObject("details")?.opt("api_url")
        val next = acceptApiUrl(candidate, current) ?: return null
        return next.takeIf { it != current.trimEnd('/') }
    }

    /**
     * Başlangıçta kullanılacak kök: daha önce aynı başlangıç kökü ([seed]) için öğrenilmiş geçerli bölgesel kök
     * varsa o, yoksa [seed].
     */
    fun initialBase(seed: String, savedSeed: String?, savedUrl: String?): String {
        val normalized = seed.trimEnd('/')
        if (savedSeed != normalized) return normalized
        return acceptApiUrl(savedUrl, normalized) ?: normalized
    }
}
