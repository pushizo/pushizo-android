package com.pushizo.sdk

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Sunucunun döndürdüğü bölge (ADR-0065 §3). */
internal data class HsGeofenceRegion(val id: String, val lat: Double, val lng: Double, val radiusM: Int)

/**
 * Arka plan bölge izleme kuralları (saf mantık; Android bağımlılığı yok). İşletim sistemi bölgeyi izler, sunucuya yalnızca giriş ve
 * çıkış bildirilir, konum gönderilmez.
 */
internal object HsGeofenceRules {
    /** Kalıcı izleme durumu ([HsStorage]; testlerde bellek içi). */
    interface State {
        var geofencingEnabled: Boolean
        var geofenceLastSyncAt: Long
        var geofenceLastSyncLat: Double
        var geofenceLastSyncLng: Double
        /** Son eşitlemenin açılıştan beri geçen süresi; küçülmüşse cihaz yeniden başlamış, kayıtlar silinmiştir. */
        var geofenceLastSyncUptime: Long
    }

    const val OP_TYPE = "geofence_event"
    const val MAX_REGIONS = 90
    const val SYNC_INTERVAL_MS = 6 * 60 * 60_000L
    const val SYNC_DISTANCE_M = 5_000.0
    private const val EARTH_RADIUS_M = 6_371_008.8

    fun distanceM(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2).pow(2)
        return EARTH_RADIUS_M * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    /** Bölge listesi yenilenmeli mi: hiç alınmadı, süre doldu, cihaz yeniden başladı veya kullanıcı uzaklaştı. */
    fun isSyncDue(state: State, lat: Double, lng: Double, now: Long, uptime: Long): Boolean {
        if (!state.geofencingEnabled) return false
        val last = state.geofenceLastSyncAt
        if (last <= 0L || now < last || now - last >= SYNC_INTERVAL_MS) return true
        if (uptime < state.geofenceLastSyncUptime) return true
        return distanceM(state.geofenceLastSyncLat, state.geofenceLastSyncLng, lat, lng) >= SYNC_DISTANCE_M
    }

    fun parseRegions(response: JSONObject): List<HsGeofenceRegion> {
        val data = response.optJSONArray("data") ?: return emptyList()
        return (0 until data.length()).mapNotNull { i ->
            val o = data.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("id")
            val lat = o.optDouble("lat")
            val lng = o.optDouble("lng")
            val radius = o.optInt("radius_m")
            if (id.isEmpty() || lat.isNaN() || lng.isNaN() || radius <= 0) null else HsGeofenceRegion(id, lat, lng, radius)
        }.take(MAX_REGIONS)
    }

    fun op(geofenceId: String, transition: String, now: Long): JSONObject =
        JSONObject().put("type", OP_TYPE).put("geofence_id", geofenceId).put("transition", transition).put("at", now)

    /** Kuyruk işleminden istek gövdesi (sahiplik kanıtı çağıran tarafından eklenir). */
    fun body(op: JSONObject): JSONObject = JSONObject().put(
        "events",
        JSONArray().put(
            JSONObject()
                .put("geofence_id", op.getString("geofence_id"))
                .put("transition", op.getString("transition"))
                .put("occurred_at", java.time.Instant.ofEpochMilli(op.getLong("at")).toString()),
        ),
    )
}

/** İşletim sistemi bölge izleme. Play Services sınıflarına yalnızca [isAvailable] doğrulandıktan sonra dokunulur (ADR-0065 §5). */
internal object HsGeofenceMonitor {
    private const val REQUEST_CODE = 0x4847

    fun isAvailable(): Boolean = runCatching { Class.forName("com.google.android.gms.location.LocationServices") }.isSuccess

    /** Arka plan izni: Android 10 öncesinde hassas konum yeterlidir. */
    fun backgroundPermissionGranted(context: Context): Boolean {
        if (!granted(context, Manifest.permission.ACCESS_FINE_LOCATION)) return false
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || granted(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
    }

    fun backgroundPermissionDeclared(context: Context): Boolean = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
        Manifest.permission.ACCESS_BACKGROUND_LOCATION in info.requestedPermissions.orEmpty()
    }.getOrDefault(false)

    private fun granted(context: Context, permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private fun pendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, HsGeofenceReceiver::class.java)
        // Sistem doldurduğu için değiştirilebilir olmalıdır (GeofencingClient belgesi).
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
        return PendingIntent.getBroadcast(context, REQUEST_CODE, intent, flags)
    }

    /** SDK'nın kayıtları değiştirilir; aynı `PendingIntent` kullanıldığı için uygulamanın kendi bölgelerine dokunulmaz. */
    @SuppressLint("MissingPermission")
    fun register(context: Context, regions: List<HsGeofenceRegion>) {
        if (!isAvailable() || !backgroundPermissionGranted(context)) return
        val client = LocationServices.getGeofencingClient(context)
        val intent = pendingIntent(context)
        client.removeGeofences(intent).addOnCompleteListener {
            if (regions.isEmpty()) return@addOnCompleteListener
            val request = GeofencingRequest.Builder()
                // Kullanıcı zaten bölgedeyse giriş bildirilir; sunucu durumu çifti eler.
                .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER)
                .addGeofences(
                    regions.map {
                        Geofence.Builder()
                            .setRequestId(it.id)
                            .setCircularRegion(it.lat, it.lng, it.radiusM.toFloat())
                            .setExpirationDuration(Geofence.NEVER_EXPIRE)
                            .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_EXIT)
                            .build()
                    },
                )
                .build()
            try {
                client.addGeofences(request, intent).addOnFailureListener { e -> HsLog.w("bölgeler kaydedilemedi: ${e.message}") }
            } catch (e: SecurityException) {
                HsLog.d("bölgeler kaydedilemedi (izin yok)")
            }
        }
    }

    fun unregister(context: Context) {
        if (!isAvailable()) return
        LocationServices.getGeofencingClient(context).removeGeofences(pendingIntent(context))
    }
}

/** İşletim sisteminin bölge geçişi bildirimi: uygulama kapalıyken de çalışır, kalıcı kuyruğa yazar. */
class HsGeofenceReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!HsGeofenceMonitor.isAvailable()) return
        val event = GeofencingEvent.fromIntent(intent) ?: return
        if (event.hasError()) {
            HsLog.d("bölge olayı hatası: ${event.errorCode}")
            return
        }
        val transition = when (event.geofenceTransition) {
            Geofence.GEOFENCE_TRANSITION_ENTER -> "enter"
            Geofence.GEOFENCE_TRANSITION_EXIT -> "exit"
            else -> return
        }
        val ids = event.triggeringGeofences.orEmpty().map { it.requestId }
        if (ids.isNotEmpty() && Pushizo.ensureInitialized(context)) Pushizo.reportGeofenceTransition(ids, transition)
    }
}
