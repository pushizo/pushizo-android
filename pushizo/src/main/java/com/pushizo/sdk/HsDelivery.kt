package com.pushizo.sdk

import org.json.JSONObject

/**
 * Android teslimat güçlendirme (ADR-0092): üretici/ROM algılama, arka plan kısıtı teşhisi ve ayar ekranı adayları.
 * Bu dosyadaki kurallar Android bağımlılığı taşımaz (JVM birim testinde sınanır); sistem çağrıları [HsDeliveryProbe]
 * içindedir.
 */

/**
 * Üretici ailesi. [aggressive]: üreticinin kendi pil/otomatik başlatma yöneticisi arka plandaki uygulamaları
 * durdurabildiği için bildirimlerin gecikebildiği ya da hiç gelmeyebildiği aileler. [id] sunucudaki aile kimliğiyle aynıdır.
 */
enum class HsDeviceVendor(val id: String, val aggressive: Boolean) {
    XIAOMI("xiaomi", true),
    HUAWEI("huawei", true),
    HONOR("honor", true),
    OPPO("oppo", true),
    REALME("realme", true),
    ONEPLUS("oneplus", true),
    VIVO("vivo", true),
    SAMSUNG("samsung", true),
    MEIZU("meizu", true),
    ASUS("asus", true),
    TRANSSION("transsion", true),
    OTHER("other", false);

    companion object {
        private val ALIASES = mapOf(
            "xiaomi" to XIAOMI, "redmi" to XIAOMI, "poco" to XIAOMI, "blackshark" to XIAOMI,
            "huawei" to HUAWEI,
            "honor" to HONOR,
            "oppo" to OPPO,
            "realme" to REALME,
            "oneplus" to ONEPLUS,
            "vivo" to VIVO, "iqoo" to VIVO,
            "samsung" to SAMSUNG,
            "meizu" to MEIZU,
            "asus" to ASUS,
            "tecno" to TRANSSION, "infinix" to TRANSSION, "itel" to TRANSSION,
            "tecno mobile limited" to TRANSSION, "infinix mobility limited" to TRANSSION, "itel mobile limited" to TRANSSION,
        )

        /** `Build.MANUFACTURER` değerinden aile (büyük/küçük harf ve boşluk duyarsız); bilinmiyorsa [OTHER]. */
        @JvmStatic
        fun from(manufacturer: String?): HsDeviceVendor = ALIASES[HsDeliveryRules.normalize(manufacturer)] ?: OTHER
    }
}

/** Uygulamanın bekleme kovası (`UsageStatsManager.getAppStandbyBucket`, Android 9+). [id] sunucuya giden değerdir. */
enum class HsStandbyBucket(val id: String) {
    EXEMPTED("exempted"),
    ACTIVE("active"),
    WORKING_SET("working_set"),
    FREQUENT("frequent"),
    RARE("rare"),
    RESTRICTED("restricted");

    companion object {
        /** Sistem değeri (5, 10, 20, 30, 40, 45) → kova; tanınmayan değer null. */
        @JvmStatic
        fun fromSystem(value: Int): HsStandbyBucket? = when (value) {
            5 -> EXEMPTED
            10 -> ACTIVE
            20 -> WORKING_SET
            30 -> FREQUENT
            40 -> RARE
            45 -> RESTRICTED
            else -> null
        }
    }
}

/** Bildirim teslim riski. */
enum class HsDeliveryRisk {
    /** Bilinen bir engel yok. */
    NONE,

    /** Bildirimler gecikebilir (saldırgan üretici ve pil optimizasyonu açık, seyrek kullanım kovası, veri tasarrufu). */
    ELEVATED,

    /** Bildirimler büyük olasılıkla gelmiyor (izin kapalı, kanal engelli, arka plan kısıtlı). */
    HIGH,
}

/** [Pushizo.Delivery.openSettings] ile açılabilen ayar ekranı. */
enum class HsDeliverySetting {
    /** Duruma en uygun ekran ([HsDeliveryStatus.recommendedSettings] ilk öğesi; öneri yoksa pil optimizasyonu). */
    AUTO,

    /** Uygulamanın bildirim ayarları. */
    NOTIFICATIONS,

    /** Üreticinin otomatik başlatma / arka planda çalışma yöneticisi (yoksa uygulama ayrıntıları). */
    AUTO_START,

    /** Pil optimizasyonu: üreticinin pil yöneticisi, yoksa sistemin pil optimizasyonu listesi. */
    BATTERY_OPTIMIZATION,

    /** Uygulama ayrıntıları (arka plan kısıtı buradan kaldırılır). */
    APP_DETAILS,
}

/**
 * Cihazın bildirim teslim durumu (`Pushizo.Delivery.status()`). Kişisel veri içermez: üretici, ROM ve kısıt bayrakları.
 * Otomatik başlatma izni Android'de okunamaz; bu yüzden saldırgan üreticilerde ([HsDeviceVendor.aggressive]) kullanıcıya
 * ayar ekranı önerilir.
 */
data class HsDeliveryStatus(
    /** `Build.MANUFACTURER` (küçük harf). */
    val manufacturer: String,
    /** `Build.BRAND` (küçük harf). */
    val brand: String,
    val vendor: HsDeviceVendor,
    /** ROM kimliği: `miui`, `hyperos`, `emui`, `harmonyos`, `magicos`, `coloros`, `oxygenos`, `realmeui`, `funtouchos`, `originos`, `oneui`, `flyme`, `android`. */
    val rom: String,
    val romVersion: String?,
    /** Bildirim izni verilmiş ve bildirimler açık mı. */
    val notificationsEnabled: Boolean,
    /** Varsayılan bildirim kanalı kullanıcı tarafından kapatılmış mı (Android 8+). */
    val channelBlocked: Boolean,
    /** Uygulama pil optimizasyonundan muaf mı (`PowerManager.isIgnoringBatteryOptimizations`). */
    val batteryOptimizationExempt: Boolean,
    /** Kullanıcı arka plan etkinliğini kısıtlamış mı (`ActivityManager.isBackgroundRestricted`, Android 9+). */
    val backgroundRestricted: Boolean,
    /** Bekleme kovası (Android 9+; okunamadıysa null). */
    val standbyBucket: HsStandbyBucket?,
    /** Pil tasarrufu kipi açık mı. */
    val powerSaveMode: Boolean,
    /** Veri tasarrufu uygulamanın arka plan verisini engelliyor mu (Android 7+). */
    val dataSaverEnabled: Boolean,
) {
    /** Sistem arka plan kısıtı: kullanıcı kısıtlamış ya da uygulama `rare`/`restricted` kovasında (sunucu tanımıyla aynı). */
    val restricted: Boolean
        get() = backgroundRestricted || standbyBucket == HsStandbyBucket.RARE || standbyBucket == HsStandbyBucket.RESTRICTED

    val risk: HsDeliveryRisk
        get() = when {
            !notificationsEnabled || channelBlocked || backgroundRestricted || standbyBucket == HsStandbyBucket.RESTRICTED -> HsDeliveryRisk.HIGH
            (vendor.aggressive && !batteryOptimizationExempt) || standbyBucket == HsStandbyBucket.RARE || dataSaverEnabled -> HsDeliveryRisk.ELEVATED
            else -> HsDeliveryRisk.NONE
        }

    /** Kullanıcının açması önerilen ayar ekranları (önem sırasıyla; öneri yoksa boş). */
    val recommendedSettings: List<HsDeliverySetting>
        get() = buildList {
            if (!notificationsEnabled || channelBlocked) add(HsDeliverySetting.NOTIFICATIONS)
            if (backgroundRestricted) add(HsDeliverySetting.APP_DETAILS)
            if (vendor.aggressive && !batteryOptimizationExempt) {
                if (HsDeliveryTargets.hasVendorAutoStart(vendor)) add(HsDeliverySetting.AUTO_START)
                add(HsDeliverySetting.BATTERY_OPTIMIZATION)
            } else if (!batteryOptimizationExempt && (standbyBucket == HsStandbyBucket.RARE || standbyBucket == HsStandbyBucket.RESTRICTED)) {
                add(HsDeliverySetting.BATTERY_OPTIMIZATION)
            }
        }

    /** Abonelik `device` alanına eklenen teşhis alanları (`manufacturer`, `brand`, `rom`, `rom_version`, `delivery`). */
    internal fun applyTo(device: JSONObject): JSONObject = device.apply {
        if (manufacturer.isNotEmpty()) put("manufacturer", manufacturer.take(50))
        if (brand.isNotEmpty()) put("brand", brand.take(50))
        put("rom", rom)
        romVersion?.takeIf { it.isNotBlank() }?.let { put("rom_version", it.take(30)) }
        put(
            "delivery",
            JSONObject()
                .put("battery_optimization_exempt", batteryOptimizationExempt)
                .put("background_restricted", backgroundRestricted)
                .put("channel_blocked", channelBlocked)
                .put("power_save_mode", powerSaveMode)
                .put("data_saver", dataSaverEnabled)
                .apply { standbyBucket?.let { put("standby_bucket", it.id) } },
        )
    }

    /** Kalıcı bayraklar (üretici, ROM, pil muafiyeti, arka plan kısıtı, kanal): değişince sunucu hemen güncellenir. */
    internal val stableFingerprint: String
        get() = listOf(manufacturer, brand, rom, romVersion.orEmpty(), batteryOptimizationExempt, backgroundRestricted, channelBlocked).joinToString("|")

    /** Sık değişen bayraklar (kova, pil/veri tasarrufu): değişiklik en sık [HsDeliveryRules.VOLATILE_SYNC_INTERVAL_MS] aralığıyla yazılır. */
    internal val volatileFingerprint: String
        get() = listOf(standbyBucket?.id.orEmpty(), powerSaveMode, dataSaverEnabled).joinToString("|")
}

/** Teşhis kuralları (Android bağımlılığı yok). */
internal object HsDeliveryRules {
    /** Sık değişen bayrakların sunucuya yazılma aralığı: her ön plana gelişte PATCH atılmaz. */
    const val VOLATILE_SYNC_INTERVAL_MS = 24L * 60 * 60 * 1000

    /** Hazır açıklama diyaloğunun yeniden önerilme aralığı (varsayılan). */
    const val DEFAULT_PROMPT_INTERVAL_DAYS = 14

    fun normalize(value: String?): String = value.orEmpty().trim().lowercase().replace(Regex("\\s+"), " ")

    /**
     * Sunucudaki teşhis güncellenmeli mi: kalıcı bayrak değiştiyse hemen, yalnızca sık değişen bayrak değiştiyse son
     * yazımdan [VOLATILE_SYNC_INTERVAL_MS] sonra.
     */
    fun shouldSync(serverStable: String?, serverVolatile: String?, lastSyncAt: Long, status: HsDeliveryStatus, now: Long): Boolean {
        if (serverStable != status.stableFingerprint) return true
        if (serverVolatile == status.volatileFingerprint) return false
        return now - lastSyncAt >= VOLATILE_SYNC_INTERVAL_MS || now < lastSyncAt
    }

    /** Açıklama diyaloğu şimdi önerilmeli mi: risk var ve son gösterimden [intervalDays] gün geçmiş. */
    fun shouldPrompt(status: HsDeliveryStatus, lastPromptAt: Long, now: Long, intervalDays: Int): Boolean {
        if (status.risk == HsDeliveryRisk.NONE) return false
        if (lastPromptAt <= 0L || now < lastPromptAt) return true
        return now - lastPromptAt >= intervalDays.coerceAtLeast(0) * 24L * 60 * 60 * 1000
    }

    /**
     * FCM yüksek öncelikle gönderilen mesajı normal önceliğe düşürdü mü (uygulama kısıtlı kovada ya da yüksek öncelik
     * kotası aşıldı). Değerler `RemoteMessage.PRIORITY_HIGH` = 1, `PRIORITY_NORMAL` = 2.
     */
    fun deprioritized(originalPriority: Int, priority: Int): Boolean = originalPriority == 1 && priority == 2

    /** Kalıcı kuyruktaki `received` işlemi: cihazın mesajı aldığı an ve önceliğin düşürülüp düşürülmediği saklanır. */
    fun receivedOp(deliveryId: String, at: Long, deprioritized: Boolean): JSONObject =
        JSONObject().put("type", "received").put("delivery_id", deliveryId).put("at", at).apply { if (deprioritized) put("deprioritized", true) }

    /**
     * `POST deliveries/{id}/received` gövdesi. Alındı kuyrukta beklediyse (çevrimdışı, süreç durduruldu) sunucu teslim anını
     * gönderim anı değil cihazın aldığı an (`received_at`) olarak yazar; gecikme ölçümü böylece doğru kalır.
     */
    fun receivedBody(op: JSONObject): JSONObject = JSONObject().apply {
        val at = op.optLong("at", 0L)
        if (at > 0L) put("received_at", java.time.Instant.ofEpochMilli(at).toString())
        if (op.optBoolean("deprioritized", false)) put("deprioritized", true)
    }
}

/** ROM algılama: sistem özelliklerinden (`ro.*`) ROM kimliği ve sürümü. [prop] okunamayan anahtar için null/boş döner. */
internal object HsRom {
    data class Info(val rom: String, val version: String?)

    private fun clean(value: String?): String? = value?.trim()?.takeIf { it.isNotEmpty() && !it.equals("unknown", ignoreCase = true) }

    /** `EmotionUI_12.0.0` → `12.0.0`, `V14` → `14`, `OS1.0.2.0` → `1.0.2.0`. */
    private fun version(raw: String, vararg prefixes: String): String? {
        var v = raw.substringAfter('_', raw).trim()
        for (p in prefixes) if (v.startsWith(p, ignoreCase = true)) v = v.substring(p.length)
        return v.trim().takeIf { it.isNotEmpty() }?.take(30)
    }

    /** One UI sürüm kodu `60101` → `6.1.1` (ana × 10000 + alt × 100 + yama). */
    private fun oneUi(raw: String): String? {
        val code = raw.toIntOrNull() ?: return null
        if (code <= 0) return null
        val major = code / 10_000
        val minor = code / 100 % 100
        val patch = code % 100
        return if (patch == 0) "$major.$minor" else "$major.$minor.$patch"
    }

    fun detect(manufacturer: String?, brand: String?, prop: (String) -> String?): Info {
        val get = { key: String -> clean(runCatching { prop(key) }.getOrNull()) }
        val maker = HsDeliveryRules.normalize(manufacturer)
        val brandName = HsDeliveryRules.normalize(brand)
        get("ro.mi.os.version.name")?.let { return Info("hyperos", version(it, "OS")) }
        get("ro.miui.ui.version.name")?.let { return Info("miui", version(it, "V")) }
        get("hw_sc.build.platform.version")?.let { return Info("harmonyos", version(it)) }
        get("ro.build.version.magic")?.let { return Info("magicos", version(it)) }
        get("ro.build.version.emui")?.let { return Info("emui", version(it)) }
        get("ro.build.version.realmeui")?.let { return Info("realmeui", version(it, "V")) }
        get("ro.oxygen.version")?.let { return Info("oxygenos", version(it)) }
        (get("ro.build.version.oplusrom") ?: get("ro.build.version.opporom"))?.let {
            return Info(if (maker == "oneplus" || brandName == "oneplus") "oxygenos" else "coloros", version(it, "V"))
        }
        val vivoName = get("ro.vivo.os.name")
        val vivoVersion = get("ro.vivo.os.version")
        if (vivoName != null || vivoVersion != null) {
            val origin = vivoName?.lowercase()?.let { it.contains("origin") || it == "vos" } ?: false
            return Info(if (origin) "originos" else "funtouchos", vivoVersion?.let { version(it) })
        }
        get("ro.build.version.oneui")?.let { return Info("oneui", oneUi(it)) }
        if (maker == "samsung") return Info("oneui", null)
        get("ro.build.display.id")?.takeIf { it.contains("flyme", ignoreCase = true) }?.let {
            return Info("flyme", Regex("(\\d+(?:\\.\\d+)*)").find(it.substringAfter("lyme"))?.value)
        }
        return Info("android", null)
    }
}

/**
 * Açılacak ayar ekranı adayı: açık bileşen ([pkg]/[cls]) ya da standart işlem ([action]). [packageUri]: `package:<uygulama>`
 * verisi eklenir; [extras]: metin ek alanları.
 */
internal data class HsSettingsTarget(
    val pkg: String? = null,
    val cls: String? = null,
    val action: String? = null,
    val packageUri: Boolean = false,
    val extras: Map<String, String> = emptyMap(),
) {
    /** Üreticiye özgü (standart olmayan) ekran mı. */
    val vendorSpecific: Boolean get() = pkg != null
}

/**
 * Üreticiye özgü ayar ekranı bileşenleri ve standart geri düşüşler. Üretici bileşenleri ROM sürümüne göre değişebildiği için
 * her biri çalışma zamanında çözülür ([HsDeliveryProbe]); çözülemeyen ya da dışa açık olmayan aday atlanır ve liste sonundaki
 * standart Android ekranına düşülür.
 */
internal object HsDeliveryTargets {
    const val ACTION_APP_NOTIFICATIONS = "android.settings.APP_NOTIFICATION_SETTINGS"
    const val ACTION_BATTERY_OPTIMIZATION = "android.settings.IGNORE_BATTERY_OPTIMIZATION_SETTINGS"
    const val ACTION_APP_DETAILS = "android.settings.APPLICATION_DETAILS_SETTINGS"
    const val ACTION_SETTINGS = "android.settings.SETTINGS"
    const val EXTRA_APP_PACKAGE = "android.provider.extra.APP_PACKAGE"

    private fun c(pkg: String, cls: String, extras: Map<String, String> = emptyMap()) = HsSettingsTarget(pkg = pkg, cls = cls, extras = extras)

    private val AUTO_START: Map<HsDeviceVendor, List<HsSettingsTarget>> = mapOf(
        HsDeviceVendor.XIAOMI to listOf(
            c("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
        ),
        HsDeviceVendor.HUAWEI to listOf(
            c("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
            c("com.huawei.systemmanager", "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity"),
            c("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"),
        ),
        HsDeviceVendor.HONOR to listOf(
            c("com.hihonor.systemmanager", "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
            c("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
            c("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"),
        ),
        HsDeviceVendor.OPPO to listOf(
            c("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
            c("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
            c("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"),
        ),
        HsDeviceVendor.REALME to listOf(
            c("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
            c("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
        ),
        HsDeviceVendor.ONEPLUS to listOf(
            c("com.oneplus.security", "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"),
            c("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
        ),
        HsDeviceVendor.VIVO to listOf(
            c("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
            c("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"),
            c("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"),
        ),
        HsDeviceVendor.ASUS to listOf(
            c("com.asus.mobilemanager", "com.asus.mobilemanager.autostart.AutoStartActivity"),
            c("com.asus.mobilemanager", "com.asus.mobilemanager.entry.FunctionActivity"),
        ),
        HsDeviceVendor.TRANSSION to listOf(
            c("com.transsion.phonemanager", "com.itel.autobootmanager.activity.AutoBootMgrActivity"),
        ),
    )

    private val BATTERY: Map<HsDeviceVendor, (String) -> List<HsSettingsTarget>> = mapOf(
        HsDeviceVendor.XIAOMI to { app ->
            listOf(c("com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity", mapOf("package_name" to app)))
        },
        HsDeviceVendor.SAMSUNG to { _ ->
            listOf(
                c("com.samsung.android.lool", "com.samsung.android.sm.battery.ui.BatteryActivity"),
                c("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity"),
                c("com.samsung.android.sm", "com.samsung.android.sm.ui.battery.BatteryActivity"),
            )
        },
        HsDeviceVendor.OPPO to { _ ->
            listOf(c("com.coloros.oppoguardelf", "com.coloros.powermanager.fuelgaue.PowerUsageModelActivity"))
        },
        HsDeviceVendor.REALME to { _ ->
            listOf(c("com.coloros.oppoguardelf", "com.coloros.powermanager.fuelgaue.PowerUsageModelActivity"))
        },
        HsDeviceVendor.MEIZU to { _ ->
            listOf(c("com.meizu.safe", "com.meizu.safe.powerui.PowerAppPermissionActivity"))
        },
        HsDeviceVendor.ASUS to { _ ->
            listOf(c("com.asus.mobilemanager", "com.asus.mobilemanager.powersaver.PowerSaverSettings"))
        },
    )

    /** Üreticinin otomatik başlatma yöneticisi biliniyor mu. */
    fun hasVendorAutoStart(vendor: HsDeviceVendor): Boolean = AUTO_START.containsKey(vendor)

    /** Android 11+ paket görünürlüğü için manifest `<queries>` içinde bulunması gereken paketler (test eşitliği doğrular). */
    val vendorPackages: Set<String> by lazy {
        (AUTO_START.values.flatten() + BATTERY.values.flatMap { it("") }).mapNotNull { it.pkg }.toSortedSet()
    }

    private fun appDetails() = HsSettingsTarget(action = ACTION_APP_DETAILS, packageUri = true)
    private fun systemSettings() = HsSettingsTarget(action = ACTION_SETTINGS)

    /**
     * [setting] için sırayla denenecek adaylar: önce üreticiye özgü bileşenler, sonra standart Android ekranları. Liste her
     * zaman standart bir ekranla biter. [setting] `AUTO` olmamalıdır (çağıran çözer).
     */
    fun candidates(vendor: HsDeviceVendor, setting: HsDeliverySetting, appPackage: String, sdkInt: Int): List<HsSettingsTarget> = when (setting) {
        HsDeliverySetting.NOTIFICATIONS -> buildList {
            if (sdkInt >= 26) add(HsSettingsTarget(action = ACTION_APP_NOTIFICATIONS, extras = mapOf(EXTRA_APP_PACKAGE to appPackage)))
            add(appDetails())
            add(systemSettings())
        }
        HsDeliverySetting.AUTO_START -> AUTO_START[vendor].orEmpty() + listOf(appDetails(), systemSettings())
        HsDeliverySetting.BATTERY_OPTIMIZATION ->
            BATTERY[vendor]?.invoke(appPackage).orEmpty() + listOf(HsSettingsTarget(action = ACTION_BATTERY_OPTIMIZATION), appDetails(), systemSettings())
        HsDeliverySetting.APP_DETAILS, HsDeliverySetting.AUTO -> listOf(appDetails(), systemSettings())
    }

    /** `AUTO` → durumun ilk önerisi (öneri yoksa pil optimizasyonu). */
    fun resolve(setting: HsDeliverySetting, status: HsDeliveryStatus): HsDeliverySetting =
        if (setting != HsDeliverySetting.AUTO) setting else status.recommendedSettings.firstOrNull() ?: HsDeliverySetting.BATTERY_OPTIMIZATION

    /**
     * Adayları sırayla dener: [canResolve] false ise (bileşen yok ya da dışa açık değil) atlanır; [start] false dönerse
     * (ör. `SecurityException`, `ActivityNotFoundException`) sonraki adaya geçilir. Açılan adayı döndürür; hiçbiri açılamazsa null.
     */
    fun launchFirst(targets: List<HsSettingsTarget>, canResolve: (HsSettingsTarget) -> Boolean, start: (HsSettingsTarget) -> Boolean): HsSettingsTarget? {
        for (target in targets) {
            if (!runCatching { canResolve(target) }.getOrDefault(false)) continue
            if (runCatching { start(target) }.getOrDefault(false)) return target
        }
        return null
    }
}
