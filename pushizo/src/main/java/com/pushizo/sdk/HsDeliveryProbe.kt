package com.pushizo.sdk

import android.app.Activity
import android.app.ActivityManager
import android.app.NotificationManager
import android.app.usage.UsageStatsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager

/**
 * Teslimat teşhisinin sistem çağrıları (ADR-0092). Her okuma ayrı ayrı korunur: üretici ROM'larında beklenmeyen istisna
 * (ör. `SecurityException`) uygulamayı çökertmez, ilgili bayrak "kısıt yok" varsayılır.
 */
internal object HsDeliveryProbe {
    /** `android.os.SystemProperties.get` (yansıma; erişilemezse null). */
    private fun systemProperty(key: String): String? = runCatching {
        Class.forName("android.os.SystemProperties").getMethod("get", String::class.java).invoke(null, key) as? String
    }.getOrNull()

    fun status(context: Context, notificationsEnabled: Boolean, defaultChannelId: String): HsDeliveryStatus {
        val ctx = context.applicationContext
        val pkg = ctx.packageName
        val manufacturer = HsDeliveryRules.normalize(Build.MANUFACTURER)
        val brand = HsDeliveryRules.normalize(Build.BRAND)
        val rom = HsRom.detect(manufacturer, brand, ::systemProperty)
        val power = runCatching { ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager }.getOrNull()
        return HsDeliveryStatus(
            manufacturer = manufacturer,
            brand = brand,
            vendor = HsDeviceVendor.from(manufacturer),
            rom = rom.rom,
            romVersion = rom.version,
            notificationsEnabled = notificationsEnabled,
            channelBlocked = channelBlocked(ctx, defaultChannelId),
            batteryOptimizationExempt = runCatching { power?.isIgnoringBatteryOptimizations(pkg) }.getOrNull() ?: false,
            backgroundRestricted = backgroundRestricted(ctx),
            standbyBucket = standbyBucket(ctx),
            powerSaveMode = runCatching { power?.isPowerSaveMode }.getOrNull() ?: false,
            dataSaverEnabled = dataSaver(ctx),
        )
    }

    private fun channelBlocked(ctx: Context, channelId: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        return runCatching {
            val channel = ctx.getSystemService(NotificationManager::class.java)?.getNotificationChannel(channelId)
            channel != null && channel.importance == NotificationManager.IMPORTANCE_NONE
        }.getOrDefault(false)
    }

    private fun backgroundRestricted(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
        return runCatching { (ctx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)?.isBackgroundRestricted }.getOrNull() ?: false
    }

    private fun standbyBucket(ctx: Context): HsStandbyBucket? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
        return runCatching {
            (ctx.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager)?.appStandbyBucket?.let(HsStandbyBucket::fromSystem)
        }.getOrNull()
    }

    private fun dataSaver(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        return runCatching {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            cm?.restrictBackgroundStatus == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED
        }.getOrDefault(false)
    }

    private fun intent(ctx: Context, target: HsSettingsTarget): Intent = Intent().apply {
        target.action?.let { action = it }
        if (target.pkg != null && target.cls != null) component = ComponentName(target.pkg, target.cls)
        if (target.packageUri) data = Uri.fromParts("package", ctx.packageName, null)
        target.extras.forEach { (key, value) -> putExtra(key, value) }
        // Activity dışı bağlamdan (ör. uygulama bağlamı) başlatma için yeni görev gerekir.
        if (ctx !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /**
     * Aday çözülebiliyor ve başlatılabilir mi. Üretici bileşeni dışa açık değilse ya da imza izni istiyorsa `startActivity`
     * `SecurityException` atar; böyle adaylar baştan elenir.
     */
    private fun canResolve(ctx: Context, target: HsSettingsTarget): Boolean {
        val info = ctx.packageManager.resolveActivity(intent(ctx, target), PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo ?: return false
        if (!info.exported) return false
        return info.permission.isNullOrEmpty() || ctx.checkSelfPermission(info.permission) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * [setting] ekranını açar: üreticiye özgü ekran çözülemezse standart Android ekranına düşer. Hiçbir ekran açılamazsa
     * false döner; istisna dışarı sızmaz.
     */
    fun open(context: Context, status: HsDeliveryStatus, setting: HsDeliverySetting): Boolean {
        val resolved = HsDeliveryTargets.resolve(setting, status)
        val targets = HsDeliveryTargets.candidates(status.vendor, resolved, context.packageName, Build.VERSION.SDK_INT)
        val opened = HsDeliveryTargets.launchFirst(
            targets,
            canResolve = { canResolve(context, it) },
            start = {
                context.startActivity(intent(context, it))
                true
            },
        )
        if (opened == null) HsLog.w("teslimat ayar ekranı açılamadı ($resolved)")
        else HsLog.d("teslimat ayar ekranı açıldı ($resolved, ${if (opened.vendorSpecific) "üretici" else "standart"})")
        return opened != null
    }
}
