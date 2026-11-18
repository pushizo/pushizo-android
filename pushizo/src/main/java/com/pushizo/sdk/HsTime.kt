package com.pushizo.sdk

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * ISO-8601 UTC zaman damgası üretimi. `java.time` API 26 ister; SDK minSdk 23 olduğundan (çekirdek kitaplık
 * desugaring'i açmayan uygulamalarda Android 6–7'de `NoClassDefFoundError`) `SimpleDateFormat` kullanılır.
 * `SimpleDateFormat` iş parçacığı güvenli olmadığından her çağrıda yeni örnek oluşturulur.
 */
internal object HsTime {
    fun iso(epochMillis: Long): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }
        .format(Date(epochMillis))

    fun isoNow(): String = iso(System.currentTimeMillis())
}
