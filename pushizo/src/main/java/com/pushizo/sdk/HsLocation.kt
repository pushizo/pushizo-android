package com.pushizo.sdk

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.util.Consumer
import org.json.JSONObject

/** Konum örneği (Android `Location`'dan bağımsız; JVM birim testlerinde kullanılır). */
internal data class HsLocationFix(val lat: Double, val lng: Double, val accuracyM: Double?, val timeMs: Long)

/**
 * Konum paylaşımı kuralları (saf mantık; Android bağımlılığı yok). Konum yalnızca kullanıcı paylaşımı açtıysa, gizlilik
 * onayı varsa (veya gerekmiyorsa) ve konum izni verilmişse, en sık [MIN_INTERVAL_MS] aralıkla toplanır. Gönderim
 * `PATCH /subscriptions/{id}` gövdesindeki `location` alanıyla, kalıcı kuyruk üzerinden yapılır; kapatmada `location: null`
 * gider ve sunucu konumu ile rıza kaydını siler.
 */
internal object HsLocationRules {
    /** Kalıcı paylaşım durumu ([HsStorage]; testlerde bellek içi). */
    interface State {
        var locationShared: Boolean
        /** Son konum işleminin kuyruğa yazıldığı an (epoch ms; 0 = yok). */
        var locationLastSharedAt: Long
    }

    /** İki konum gönderimi arasındaki en kısa süre (5 dakika). */
    const val MIN_INTERVAL_MS = 5 * 60_000L

    /** Son bilinen konumun kullanılabileceği en büyük yaş; daha eskiyse anlık konum istenir. */
    const val MAX_LAST_KNOWN_AGE_MS = 10 * 60_000L

    /** Sunucu sınırı (`accuracy_m` 0..100000). */
    const val MAX_ACCURACY_M = 100_000.0

    const val OP_TYPE = "location"

    /** Konum şimdi toplanıp gönderilmeli mi: paylaşım açık, ağ (onay) serbest, izin var ve son gönderimden 5 dk geçti. */
    fun isDue(state: State, networkAllowed: Boolean, permissionGranted: Boolean, now: Long): Boolean {
        if (!state.locationShared || !networkAllowed || !permissionGranted) return false
        val last = state.locationLastSharedAt
        // Cihaz saati geri alındıysa (now < last) sınır kilitlenmesin diye gönderime izin verilir.
        return last <= 0L || now < last || now - last >= MIN_INTERVAL_MS
    }

    /** `{ lat, lng, accuracy_m? }`; koordinat geçersiz/aralık dışıysa null (gönderilmez). Doğruluk 100 km'ye kırpılır. */
    fun locationJson(fix: HsLocationFix): JSONObject? {
        if (!fix.lat.isFinite() || !fix.lng.isFinite()) return null
        if (fix.lat !in -90.0..90.0 || fix.lng !in -180.0..180.0) return null
        val json = JSONObject().put("lat", fix.lat).put("lng", fix.lng)
        fix.accuracyM?.takeIf { it.isFinite() && it >= 0.0 }?.let { json.put("accuracy_m", it.coerceAtMost(MAX_ACCURACY_M)) }
        return json
    }

    /** Kuyruk işlemi; [fix] null ise konum silme (`location: null`). Geçersiz koordinatta null. */
    fun op(fix: HsLocationFix?, now: Long): JSONObject? {
        val location: Any = if (fix == null) JSONObject.NULL else locationJson(fix) ?: return null
        return JSONObject().put("type", OP_TYPE).put("location", location).put("at", now)
    }

    /** Kuyruk işleminden `PATCH` gövdesi (sahiplik kanıtı çağıran tarafından eklenir). */
    fun body(op: JSONObject): JSONObject =
        JSONObject().put("location", if (op.isNull("location")) JSONObject.NULL else op.getJSONObject("location"))

    /**
     * Kuyrukta daha yeni bir konum işlemi varsa bu işlem gönderilmez: son durum kazanır, böylece paylaşım kapatılınca
     * henüz gönderilmemiş konum da hiç gönderilmez.
     */
    fun isSuperseded(op: JSONObject, queue: List<JSONObject>): Boolean {
        val latest = queue.lastOrNull { it.optString("type") == OP_TYPE } ?: return false
        return latest.toString() != op.toString()
    }

    /** Son bilinen konumlardan [MAX_LAST_KNOWN_AGE_MS] içindeki en doğru olanı (eşitlikte en yenisi); yoksa null. */
    fun best(fixes: List<HsLocationFix>, now: Long): HsLocationFix? =
        fixes.filter { now - it.timeMs <= MAX_LAST_KNOWN_AGE_MS }
            .minWithOrNull(compareBy<HsLocationFix> { it.accuracyM ?: Double.MAX_VALUE }.thenByDescending { it.timeMs })
}

/**
 * Cihaz konumu: Google Play Services gerektirmez (`android.location.LocationManager`). Önce yeterince yeni son bilinen
 * konum kullanılır, yoksa tek seferlik anlık konum istenir. Arka plan konumu kullanılmaz.
 */
internal object HsLocationProvider {
    private val PERMISSIONS = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

    /** Yaklaşık veya hassas konum izni verilmiş mi. */
    fun permissionGranted(context: Context): Boolean = PERMISSIONS.any { granted(context, it) }

    /** Uygulama manifest'inde tanımlı konum izinleri (yalnızca bunlar istenebilir). */
    fun declaredPermissions(context: Context): List<String> = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
        val requested = info.requestedPermissions?.toSet().orEmpty()
        PERMISSIONS.filter { it in requested }
    }.getOrDefault(emptyList())

    private fun granted(context: Context, permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /** Güncel konumu verir (bulunamazsa veya izin yoksa null). [callback] herhangi bir iş parçacığında çağrılabilir. */
    @SuppressLint("MissingPermission")
    fun current(context: Context, callback: (HsLocationFix?) -> Unit) {
        if (!permissionGranted(context)) return callback(null)
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return callback(null)
        val fine = granted(context, Manifest.permission.ACCESS_FINE_LOCATION)
        try {
            val enabled = candidateProviders(fine).filter { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }
            // PASSIVE yalnızca diğer uygulamaların aldığı son konumu okumak içindir (hassas izin gerekir).
            val lastKnownSources = if (fine) enabled + LocationManager.PASSIVE_PROVIDER else enabled
            val lastKnown = lastKnownSources.distinct().mapNotNull { provider ->
                runCatching { manager.getLastKnownLocation(provider) }.getOrNull()?.toFix()
            }
            HsLocationRules.best(lastKnown, System.currentTimeMillis())?.let { return callback(it) }
            val provider = enabled.firstOrNull() ?: return callback(null)
            LocationManagerCompat.getCurrentLocation(
                manager,
                provider,
                null as CancellationSignal?,
                ContextCompat.getMainExecutor(context),
                Consumer<Location?> { location -> callback(location?.toFix()) },
            )
        } catch (e: SecurityException) {
            // İzin bu arada geri alınmış olabilir.
            HsLog.d("konum alınamadı (izin yok)")
            callback(null)
        }
    }

    /** Anlık konum için sağlayıcı sırası: füzyon (Android 12+), ağ, GPS (yalnızca hassas izinle). */
    private fun candidateProviders(fine: Boolean): List<String> = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(LocationManager.FUSED_PROVIDER)
        add(LocationManager.NETWORK_PROVIDER)
        if (fine) add(LocationManager.GPS_PROVIDER)
    }

    private fun Location.toFix() = HsLocationFix(latitude, longitude, if (hasAccuracy()) accuracy.toDouble() else null, time)
}
