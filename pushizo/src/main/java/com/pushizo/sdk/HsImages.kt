package com.pushizo.sdk

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.net.HttpURLConnection
import java.net.URL

/** Görsel indirme (bildirim büyük resmi ve in-app görselleri ortak kullanır); ek bağımlılık yok. */
internal object HsImages {
    private const val TIMEOUT_MS = 10_000
    /** Bellek koruması: daha büyük yanıtlar okunmaz. */
    private const val MAX_BYTES = 8 * 1024 * 1024
    /** Uzun kenar bu değeri aşarsa örnekleme ile küçültülür. */
    private const val MAX_DIMENSION = 1440

    /** Arka plan iş parçacığında çağrılmalıdır; başarısızsa null. */
    fun download(url: String): Bitmap? = runCatching {
        if (!url.startsWith("https://") && !url.startsWith("http://")) return null
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            instanceFollowRedirects = true
        }
        try {
            if (connection.responseCode !in 200..299) return null
            val length = connection.contentLengthLong
            if (length > MAX_BYTES) return null
            val bytes = connection.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    if (out.size() > MAX_BYTES) return null
                }
                out.toByteArray()
            }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_DIMENSION) sample *= 2
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
        } finally {
            connection.disconnect()
        }
    }.onFailure { HsLog.d("görsel indirilemedi") }.getOrNull()
}
