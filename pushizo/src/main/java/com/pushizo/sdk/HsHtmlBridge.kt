package com.pushizo.sdk

import org.json.JSONObject
import java.security.SecureRandom
import kotlin.math.ceil

/** HTML in-app köprüsünden (`PushizoBridge`) gelen, doğrulanmış eylem. */
internal sealed class HsHtmlBridgeAction {
    object Ready : HsHtmlBridgeAction()
    object Close : HsHtmlBridgeAction()
    data class Click(val actionId: String, val url: String? = null) : HsHtmlBridgeAction()
    data class AddTag(val key: String, val value: String) : HsHtmlBridgeAction()
    /** [props] değerleri yalnızca String, Number, Boolean veya null olur. */
    data class TrackEvent(val name: String, val props: Map<String, Any?>) : HsHtmlBridgeAction()
    data class Resize(val height: Int) : HsHtmlBridgeAction()
}

/**
 * HTML in-app mesajı belgesi ve `PushizoBridge` köprü protokolü (v2 Faz 18, ADR-0027). Saf Kotlin: WebView'a
 * bağlı değildir, JVM birim testleriyle sınanır (ortak vektörler `sdk/testdata/html-bridge-vectors.json`).
 *
 * Mantık `packages/shared/src/html-in-app.ts` ile birebir aynıdır; tek fark köprü betiğinin mesajı `window.parent`
 * yerine yerel kanala (`window.PushizoNative.postMessage(JSON)`) göndermesidir. Güvenlik modeli temizleme değil
 * yalıtımdır: CSP ağ isteklerini ve dış betikleri engeller, WebView ayarları ([HsHtmlInAppView]) dosya/depolama/gezinme
 * erişimini kapatır, uygulamaya yalnızca bu sınıfın doğruladığı dört eylem ulaşır.
 */
internal object HsHtmlBridge {
    /** Belgeye eklenen içerik güvenliği politikası (Web SDK ile aynı). */
    const val CSP = "default-src 'none'; img-src https: data:; style-src 'unsafe-inline'; script-src 'unsafe-inline'"
    const val MARK = "pushizo-bridge"
    const val VERSION = 1
    /** HTML içeriği üst sınırı (karakter). */
    const val MAX_HTML_LENGTH = 100_000
    /** Köprüden gelen tek mesajın üst sınırı (karakter); daha büyüğü ayrıştırılmadan reddedilir. */
    const val MAX_MESSAGE_LENGTH = 32_000
    /** WebView'a eklenen yerel arayüzün adı (`window.PushizoNative`). */
    const val NATIVE_INTERFACE = "PushizoNative"

    private const val MAX_PROPS = 20
    private const val NONCE_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789_-"

    /** JavaScript `\s` ve `String.prototype.trim` ile aynı boşluk kümesi. */
    private const val JS_WHITESPACE =
        "\u0009\u000A\u000B\u000C\u000D\u0020\u00A0\u1680\u2000\u2001\u2002\u2003\u2004\u2005\u2006\u2007\u2008\u2009\u200A\u2028\u2029\u202F\u205F\u3000\uFEFF"
    private val ACTION_ID = Regex("^[A-Za-z0-9_.:-]{1,64}$")
    private val SCHEME = Regex("^[a-z][a-z0-9+.-]*:", RegexOption.IGNORE_CASE)
    private val WEB_URL = Regex("^https?://[^\\t\\n\\u000B\\f\\r \\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF/]+", RegexOption.IGNORE_CASE)
    private val APP_URL = Regex("^[a-z][a-z0-9+.-]*://", RegexOption.IGNORE_CASE)
    private val BLOCKED_SCHEMES = setOf("javascript", "data", "vbscript", "file", "blob", "about")
    private val random = SecureRandom()

    private fun isJsWhitespace(c: Char) = JS_WHITESPACE.indexOf(c) >= 0

    private fun jsTrim(value: String): String = value.trim(::isJsWhitespace)

    /** Her gösterim için yeni nonce (24 güvenli karakter). */
    fun newNonce(): String = buildString(24) { repeat(24) { append(NONCE_ALPHABET[random.nextInt(NONCE_ALPHABET.length)]) } }

    /** Köprüden gelen URL: http(s) veya uygulama şeması (`myapp://`); javascript:/data:/file: reddedilir. */
    fun safeUrl(value: Any?): String? {
        if (value !is String) return null
        val v = jsTrim(value)
        if (v.isEmpty() || v.length > 2000) return null
        if (!SCHEME.containsMatchIn(v)) return null
        val scheme = v.substring(0, v.indexOf(':')).lowercase()
        if (scheme in BLOCKED_SCHEMES) return null
        if (scheme == "http" || scheme == "https") return if (WEB_URL.containsMatchIn(v)) v else null
        return if (APP_URL.containsMatchIn(v)) v else null
    }

    /** http(s) adresi tarayıcıda, diğer (uygulama şeması) adresler deeplink olarak açılır. */
    fun isWebUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.startsWith("http://") || lower.startsWith("https://")
    }

    /** Yerel kanaldan gelen JSON metnini doğrular; geçersiz, başka nonce'lu veya bilinmeyen mesaj → null. */
    fun parse(json: String, nonce: String): HsHtmlBridgeAction? {
        if (json.length > MAX_MESSAGE_LENGTH) return null
        val data = runCatching { JSONObject(json) }.getOrNull() ?: return null
        return parse(data, nonce)
    }

    fun parse(data: JSONObject, nonce: String): HsHtmlBridgeAction? {
        if (data.value("hs") != MARK) return null
        val v = data.value("v")
        if (v !is Number || v.toDouble() != VERSION.toDouble()) return null
        if (data.value("nonce") != nonce) return null
        return when (data.value("type")) {
            "ready" -> HsHtmlBridgeAction.Ready
            "close" -> HsHtmlBridgeAction.Close
            "click" -> {
                val actionId = data.value("action_id") as? String ?: return null
                if (!ACTION_ID.matches(actionId)) return null
                val raw = data.value("url")
                if (raw == null || raw == "") return HsHtmlBridgeAction.Click(actionId)
                HsHtmlBridgeAction.Click(actionId, safeUrl(raw))
            }
            "add_tag" -> {
                val key = data.value("key") as? String ?: return null
                if (key.isEmpty() || key.length > 128 || key.any(::isJsWhitespace)) return null
                val value = data.value("value") as? String ?: return null
                if (value.length > 255) return null
                HsHtmlBridgeAction.AddTag(key, value)
            }
            "track_event" -> {
                val rawName = data.value("name") as? String ?: return null
                val name = jsTrim(rawName)
                if (name.isEmpty() || name.length > 100) return null
                val props = LinkedHashMap<String, Any?>()
                when (val raw = data.value("props")) {
                    null -> Unit
                    is JSONObject -> {
                        if (raw.length() > MAX_PROPS) return null
                        for (k in raw.keys()) {
                            if (k.isEmpty() || k.length > 64) return null
                            props[k] = when (val p = raw.value(k)) {
                                null -> null
                                is String -> p.take(255)
                                is Boolean -> p
                                is Number -> p.takeIf { it.toDouble().isFinite() } ?: return null
                                else -> return null
                            }
                        }
                    }
                    else -> return null
                }
                HsHtmlBridgeAction.TrackEvent(name, props)
            }
            "resize" -> {
                val h = data.value("height") as? Number ?: return null
                val d = h.toDouble()
                if (!d.isFinite() || d < 1) return null
                HsHtmlBridgeAction.Resize(minOf(ceil(d), 5000.0).toInt())
            }
            else -> null
        }
    }

    /** Eksik alan ve JSON `null` → Kotlin null; dizi değerleri (`JSONArray`) olduğu gibi döner (çağıran reddeder). */
    private fun JSONObject.value(name: String): Any? = if (!has(name) || isNull(name)) null else opt(name)

    /** Nonce'u yalnızca güvenli karakterlerle sınırlar (betik içine gömülür). */
    private fun cleanNonce(nonce: String): String {
        val n = nonce.filter { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '_' || it == '-' }
        require(n.length >= 8) { "Köprü nonce değeri en az 8 güvenli karakter olmalı." }
        return n.take(64)
    }

    /**
     * WebView içinde çalışan köprü betiği (Web SDK `htmlBridgeScript` ile aynı davranış). `PushizoBridge` değiştirilemez
     * nesnedir; bağlantı tıklamaları gezinme yerine `click('link', href)` olur, `data-hs-close` kapatır,
     * `data-hs-action="kimlik"` tıklama bildirir, form gönderimi engellenir, yükseklik `resize` ile bildirilir.
     */
    fun script(nonce: String): String {
        val n = cleanNonce(nonce)
        return "(function(){\"use strict\";var N=\"" + n + "\",P=window." + NATIVE_INTERFACE + ";" +
            "function s(m){m.hs=\"" + MARK + "\";m.v=" + VERSION + ";m.nonce=N;try{P.postMessage(JSON.stringify(m))}catch(e){}}" +
            "function t(v,x){return typeof v===\"string\"?v.slice(0,x):\"\"}" +
            "var B=Object.freeze({" +
            "close:function(){s({type:\"close\"})}," +
            "click:function(a,u){s({type:\"click\",action_id:t(a,64)||\"html\",url:typeof u===\"string\"?u.slice(0,2000):undefined})}," +
            "addTag:function(k,v){s({type:\"add_tag\",key:t(k,128),value:String(v==null?\"\":v).slice(0,255)})}," +
            "trackEvent:function(n,p){var o={};try{o=JSON.parse(JSON.stringify(p||{}))}catch(e){}s({type:\"track_event\",name:t(n,100),props:o})}" +
            "});" +
            "try{Object.defineProperty(window,\"PushizoBridge\",{value:B,writable:false,configurable:false})}catch(e){}" +
            "document.addEventListener(\"click\",function(e){var el=e.target;if(!el||!el.closest)return;" +
            "var c=el.closest(\"[data-hs-close]\");if(c){e.preventDefault();B.close();return}" +
            "var a=el.closest(\"[data-hs-action]\");var l=el.closest(\"a[href]\");" +
            "if(a){e.preventDefault();B.click(a.getAttribute(\"data-hs-action\"),(l||a).getAttribute(\"href\")||a.getAttribute(\"data-hs-url\")||undefined);return}" +
            "if(l){e.preventDefault();B.click(\"link\",l.getAttribute(\"href\"))}},true);" +
            "document.addEventListener(\"submit\",function(e){e.preventDefault()},true);" +
            "var H=0;function r(){var h=Math.ceil(document.documentElement.scrollHeight||0);if(h&&h!==H){H=h;s({type:\"resize\",height:h})}}" +
            "window.addEventListener(\"load\",function(){s({type:\"ready\"});r()});" +
            "if(typeof ResizeObserver===\"function\"){try{new ResizeObserver(r).observe(document.documentElement)}catch(e){}}" +
            "})();"
    }

    /**
     * WebView belgesi: CSP meta etiketi en başta (kullanıcı HTML'inden önce), ardından köprü betiği ve kullanıcı HTML'i.
     * Kullanıcı HTML'i tam belge ise olduğu gibi `<body>` içine konur; ikinci `<html>/<head>` yok sayılır, CSP ilk meta ile
     * zaten yürürlüktedir.
     */
    fun document(html: String, nonce: String, language: String?): String {
        val lang = (language ?: "en").filter { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '-' }.take(10).ifEmpty { "en" }
        return "<!doctype html><html lang=\"" + lang + "\"><head><meta charset=\"utf-8\">" +
            "<meta http-equiv=\"Content-Security-Policy\" content=\"" + CSP + "\">" +
            "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">" +
            "<meta name=\"color-scheme\" content=\"light dark\">" +
            "<meta name=\"referrer\" content=\"no-referrer\">" +
            "<style>html,body{margin:0;padding:0}body{font:15px/1.5 system-ui,-apple-system,\"Segoe UI\",Roboto,sans-serif}</style>" +
            "<script>" + script(nonce) + "</script></head><body>" +
            html.take(MAX_HTML_LENGTH) +
            "</body></html>"
    }
}
