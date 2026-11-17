package com.pushizo.sdk

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** ADR-0092: üretici/ROM algılama, teslim riski, teşhis gövdesi, eşitleme sıklığı ve ayar ekranı geri düşüşü. */
class HsDeliveryTest {
    private fun status(
        manufacturer: String = "google",
        rom: String = "android",
        notificationsEnabled: Boolean = true,
        channelBlocked: Boolean = false,
        batteryOptimizationExempt: Boolean = false,
        backgroundRestricted: Boolean = false,
        standbyBucket: HsStandbyBucket? = HsStandbyBucket.ACTIVE,
        powerSaveMode: Boolean = false,
        dataSaverEnabled: Boolean = false,
    ) = HsDeliveryStatus(
        manufacturer = manufacturer,
        brand = manufacturer,
        vendor = HsDeviceVendor.from(manufacturer),
        rom = rom,
        romVersion = null,
        notificationsEnabled = notificationsEnabled,
        channelBlocked = channelBlocked,
        batteryOptimizationExempt = batteryOptimizationExempt,
        backgroundRestricted = backgroundRestricted,
        standbyBucket = standbyBucket,
        powerSaveMode = powerSaveMode,
        dataSaverEnabled = dataSaverEnabled,
    )

    private fun props(vararg pairs: Pair<String, String>): (String) -> String? = mapOf(*pairs)::get

    @Test
    fun `uretici ailesi marka adlarindan cozulur`() {
        assertEquals(HsDeviceVendor.XIAOMI, HsDeviceVendor.from("Xiaomi"))
        assertEquals(HsDeviceVendor.XIAOMI, HsDeviceVendor.from(" POCO "))
        assertEquals(HsDeviceVendor.XIAOMI, HsDeviceVendor.from("Redmi"))
        assertEquals(HsDeviceVendor.HUAWEI, HsDeviceVendor.from("HUAWEI"))
        assertEquals(HsDeviceVendor.HONOR, HsDeviceVendor.from("HONOR"))
        assertEquals(HsDeviceVendor.OPPO, HsDeviceVendor.from("OPPO"))
        assertEquals(HsDeviceVendor.REALME, HsDeviceVendor.from("realme"))
        assertEquals(HsDeviceVendor.ONEPLUS, HsDeviceVendor.from("OnePlus"))
        assertEquals(HsDeviceVendor.VIVO, HsDeviceVendor.from("vivo"))
        assertEquals(HsDeviceVendor.VIVO, HsDeviceVendor.from("iQOO"))
        assertEquals(HsDeviceVendor.SAMSUNG, HsDeviceVendor.from("samsung"))
        assertEquals(HsDeviceVendor.TRANSSION, HsDeviceVendor.from("TECNO MOBILE LIMITED"))
        assertEquals(HsDeviceVendor.OTHER, HsDeviceVendor.from("Google"))
        assertEquals(HsDeviceVendor.OTHER, HsDeviceVendor.from(null))
        assertTrue(HsDeviceVendor.XIAOMI.aggressive)
        assertFalse(HsDeviceVendor.OTHER.aggressive)
    }

    @Test
    fun `rom sistem ozelliklerinden algilanir`() {
        assertEquals(HsRom.Info("hyperos", "1.0.2.0"), HsRom.detect("Xiaomi", "Redmi", props("ro.mi.os.version.name" to "OS1.0.2.0", "ro.miui.ui.version.name" to "V816")))
        assertEquals(HsRom.Info("miui", "14"), HsRom.detect("Xiaomi", "POCO", props("ro.miui.ui.version.name" to "V14")))
        assertEquals(HsRom.Info("harmonyos", "4.0.0"), HsRom.detect("HUAWEI", "HUAWEI", props("hw_sc.build.platform.version" to "4.0.0", "ro.build.version.emui" to "EmotionUI_14.0.0")))
        assertEquals(HsRom.Info("emui", "12.0.0"), HsRom.detect("HUAWEI", "HUAWEI", props("ro.build.version.emui" to "EmotionUI_12.0.0")))
        assertEquals(HsRom.Info("magicos", "8.0.0"), HsRom.detect("HONOR", "HONOR", props("ro.build.version.magic" to "MagicOS_8.0.0", "ro.build.version.emui" to "MagicOS_8.0.0")))
        assertEquals(HsRom.Info("coloros", "14.0"), HsRom.detect("OPPO", "OPPO", props("ro.build.version.oplusrom" to "V14.0")))
        assertEquals(HsRom.Info("coloros", "7.1"), HsRom.detect("OPPO", "OPPO", props("ro.build.version.opporom" to "V7.1")))
        assertEquals(HsRom.Info("realmeui", "5.0"), HsRom.detect("realme", "realme", props("ro.build.version.realmeui" to "V5.0", "ro.build.version.oplusrom" to "V14.0")))
        assertEquals(HsRom.Info("oxygenos", "14.0.0"), HsRom.detect("OnePlus", "OnePlus", props("ro.oxygen.version" to "14.0.0", "ro.build.version.oplusrom" to "V14.0")))
        assertEquals(HsRom.Info("oxygenos", "14.0"), HsRom.detect("OnePlus", "OnePlus", props("ro.build.version.oplusrom" to "V14.0")))
        assertEquals(HsRom.Info("funtouchos", "14.0"), HsRom.detect("vivo", "vivo", props("ro.vivo.os.name" to "Funtouch", "ro.vivo.os.version" to "14.0")))
        assertEquals(HsRom.Info("originos", "4.0"), HsRom.detect("vivo", "iQOO", props("ro.vivo.os.name" to "vos", "ro.vivo.os.version" to "4.0")))
        assertEquals(HsRom.Info("oneui", "6.1.1"), HsRom.detect("samsung", "samsung", props("ro.build.version.oneui" to "60101")))
        assertEquals(HsRom.Info("oneui", "7.0"), HsRom.detect("samsung", "samsung", props("ro.build.version.oneui" to "70000")))
        assertEquals(HsRom.Info("oneui", null), HsRom.detect("samsung", "samsung", props()))
        assertEquals(HsRom.Info("flyme", "10.5.0.1"), HsRom.detect("Meizu", "Meizu", props("ro.build.display.id" to "Flyme 10.5.0.1A")))
        assertEquals(HsRom.Info("android", null), HsRom.detect("Google", "google", props("ro.build.display.id" to "AP2A.240805.005")))
    }

    @Test
    fun `sistem ozelligi okunamazsa ROM algilama cokmez`() {
        assertEquals(HsRom.Info("android", null), HsRom.detect("Xiaomi", "Xiaomi") { throw SecurityException("erişim yok") })
        assertEquals(HsRom.Info("android", null), HsRom.detect(null, null) { " " })
    }

    @Test
    fun `bekleme kovasi sistem degerinden cozulur`() {
        assertEquals(HsStandbyBucket.EXEMPTED, HsStandbyBucket.fromSystem(5))
        assertEquals(HsStandbyBucket.ACTIVE, HsStandbyBucket.fromSystem(10))
        assertEquals(HsStandbyBucket.WORKING_SET, HsStandbyBucket.fromSystem(20))
        assertEquals(HsStandbyBucket.FREQUENT, HsStandbyBucket.fromSystem(30))
        assertEquals(HsStandbyBucket.RARE, HsStandbyBucket.fromSystem(40))
        assertEquals(HsStandbyBucket.RESTRICTED, HsStandbyBucket.fromSystem(45))
        assertNull(HsStandbyBucket.fromSystem(50))
    }

    @Test
    fun `teslim riski ve oneriler durumdan hesaplanir`() {
        val stock = status()
        assertEquals(HsDeliveryRisk.NONE, stock.risk)
        assertFalse(stock.restricted)
        assertTrue(stock.recommendedSettings.isEmpty())

        val xiaomi = status(manufacturer = "xiaomi", rom = "hyperos")
        assertEquals(HsDeliveryRisk.ELEVATED, xiaomi.risk)
        assertEquals(listOf(HsDeliverySetting.AUTO_START, HsDeliverySetting.BATTERY_OPTIMIZATION), xiaomi.recommendedSettings)
        assertEquals(HsDeliveryRisk.NONE, status(manufacturer = "xiaomi", batteryOptimizationExempt = true).risk)

        // Samsung'da otomatik başlatma yöneticisi yok: yalnızca pil önerilir.
        assertEquals(listOf(HsDeliverySetting.BATTERY_OPTIMIZATION), status(manufacturer = "samsung").recommendedSettings)

        val restricted = status(backgroundRestricted = true)
        assertEquals(HsDeliveryRisk.HIGH, restricted.risk)
        assertTrue(restricted.restricted)
        assertEquals(listOf(HsDeliverySetting.APP_DETAILS), restricted.recommendedSettings)

        val blocked = status(notificationsEnabled = false)
        assertEquals(HsDeliveryRisk.HIGH, blocked.risk)
        assertEquals(HsDeliverySetting.NOTIFICATIONS, blocked.recommendedSettings.first())
        assertEquals(HsDeliveryRisk.HIGH, status(channelBlocked = true).risk)

        val rare = status(standbyBucket = HsStandbyBucket.RARE)
        assertEquals(HsDeliveryRisk.ELEVATED, rare.risk)
        assertTrue(rare.restricted)
        assertEquals(listOf(HsDeliverySetting.BATTERY_OPTIMIZATION), rare.recommendedSettings)
        assertEquals(HsDeliveryRisk.HIGH, status(standbyBucket = HsStandbyBucket.RESTRICTED).risk)
        assertEquals(HsDeliveryRisk.ELEVATED, status(dataSaverEnabled = true).risk)
        // Kova okunamadıysa (Android 8 ve öncesi) kısıt sayılmaz.
        assertEquals(HsDeliveryRisk.NONE, status(standbyBucket = null).risk)
    }

    @Test
    fun `teshis cihaz govdesine kisisel veri olmadan eklenir`() {
        val device = HsEvents.deviceInfo("Xiaomi 2201117TG", "14", "1.0", "0.2.1", "tr-TR", "Europe/Istanbul", true)
        status(manufacturer = "xiaomi", rom = "hyperos", standbyBucket = HsStandbyBucket.FREQUENT, powerSaveMode = true)
            .copy(brand = "redmi", romVersion = "1.0.2.0")
            .applyTo(device)
        assertEquals("xiaomi", device.getString("manufacturer"))
        assertEquals("redmi", device.getString("brand"))
        assertEquals("hyperos", device.getString("rom"))
        assertEquals("1.0.2.0", device.getString("rom_version"))
        val delivery = device.getJSONObject("delivery")
        assertEquals(
            setOf("battery_optimization_exempt", "background_restricted", "channel_blocked", "power_save_mode", "data_saver", "standby_bucket"),
            delivery.keySet(),
        )
        assertFalse(delivery.getBoolean("battery_optimization_exempt"))
        assertFalse(delivery.getBoolean("background_restricted"))
        assertTrue(delivery.getBoolean("power_save_mode"))
        assertEquals("frequent", delivery.getString("standby_bucket"))
        // Mevcut alanlar korunur.
        assertEquals("Xiaomi 2201117TG", device.getString("model"))
        assertTrue(device.getBoolean("push_capable"))

        // Sürüm ve kova bilinmiyorsa alan gönderilmez (JSON null sunucuda reddedilir).
        val bare = status(standbyBucket = null).applyTo(JSONObject())
        assertFalse(bare.has("rom_version"))
        assertFalse(bare.getJSONObject("delivery").has("standby_bucket"))
    }

    @Test
    fun `kalici bayrak degisince hemen, sik degisen bayrak gunde bir esitlenir`() {
        val now = 10L * HsDeliveryRules.VOLATILE_SYNC_INTERVAL_MS
        val current = status(manufacturer = "xiaomi")
        // Hiç yazılmadı.
        assertTrue(HsDeliveryRules.shouldSync(null, null, 0L, current, now))
        // Aynı durum: istek gönderilmez.
        assertFalse(HsDeliveryRules.shouldSync(current.stableFingerprint, current.volatileFingerprint, now - 1_000, current, now))
        // Pil muafiyeti verildi: hemen.
        val exempt = current.copy(batteryOptimizationExempt = true)
        assertTrue(HsDeliveryRules.shouldSync(current.stableFingerprint, current.volatileFingerprint, now - 1_000, exempt, now))
        // Yalnızca pil tasarrufu kipi değişti: 24 saat dolmadan yazılmaz, dolunca yazılır.
        val saver = current.copy(powerSaveMode = true)
        assertFalse(HsDeliveryRules.shouldSync(current.stableFingerprint, current.volatileFingerprint, now - 60_000, saver, now))
        assertTrue(HsDeliveryRules.shouldSync(current.stableFingerprint, current.volatileFingerprint, now - HsDeliveryRules.VOLATILE_SYNC_INTERVAL_MS, saver, now))
        // Saat geri alındıysa bekleme sonsuza uzamaz.
        assertTrue(HsDeliveryRules.shouldSync(current.stableFingerprint, current.volatileFingerprint, now + 60_000, saver, now))
    }

    @Test
    fun `aciklama diyalogu risk varken ve aralik dolunca onerilir`() {
        val day = 24L * 60 * 60 * 1000
        val now = 100 * day
        assertFalse(HsDeliveryRules.shouldPrompt(status(), 0L, now, 14))
        val risky = status(manufacturer = "oppo")
        assertTrue(HsDeliveryRules.shouldPrompt(risky, 0L, now, 14))
        assertFalse(HsDeliveryRules.shouldPrompt(risky, now - 13 * day, now, 14))
        assertTrue(HsDeliveryRules.shouldPrompt(risky, now - 14 * day, now, 14))
        assertTrue(HsDeliveryRules.shouldPrompt(risky, now - 1, now, 0))
    }

    @Test
    fun `alindi govdesi cihazin aldigi ani ve oncelik dususunu tasir`() {
        assertTrue(HsDeliveryRules.deprioritized(originalPriority = 1, priority = 2))
        assertFalse(HsDeliveryRules.deprioritized(originalPriority = 1, priority = 1))
        assertFalse(HsDeliveryRules.deprioritized(originalPriority = 2, priority = 2))
        assertFalse(HsDeliveryRules.deprioritized(originalPriority = 0, priority = 0))

        val op = HsDeliveryRules.receivedOp("dlv_1", 1_763_380_800_000L, deprioritized = true)
        assertEquals("received", op.getString("type"))
        assertEquals("dlv_1", op.getString("delivery_id"))
        val body = HsDeliveryRules.receivedBody(JSONObject(op.toString()))
        assertEquals("2025-11-17T12:00:00.000Z", body.getString("received_at"))
        assertTrue(body.getBoolean("deprioritized"))

        val plain = HsDeliveryRules.receivedBody(HsDeliveryRules.receivedOp("dlv_2", 1_763_380_800_000L, deprioritized = false))
        assertFalse(plain.has("deprioritized"))
        // Önceki SDK sürümünün kuyruğa yazdığı işlem (`at` yok): boş gövde.
        assertEquals(0, HsDeliveryRules.receivedBody(JSONObject().put("type", "received").put("delivery_id", "dlv_3")).length())
    }

    @Test
    fun `ayar adaylari ureticiye ozgu ekranla baslar ve standart ekranla biter`() {
        val app = "com.example.app"
        val xiaomiAuto = HsDeliveryTargets.candidates(HsDeviceVendor.XIAOMI, HsDeliverySetting.AUTO_START, app, 34)
        assertEquals("com.miui.securitycenter", xiaomiAuto.first().pkg)
        assertTrue(xiaomiAuto.first().vendorSpecific)
        assertEquals(HsDeliveryTargets.ACTION_APP_DETAILS, xiaomiAuto[xiaomiAuto.size - 2].action)
        assertEquals(HsDeliveryTargets.ACTION_SETTINGS, xiaomiAuto.last().action)

        val xiaomiBattery = HsDeliveryTargets.candidates(HsDeviceVendor.XIAOMI, HsDeliverySetting.BATTERY_OPTIMIZATION, app, 34)
        assertEquals(mapOf("package_name" to app), xiaomiBattery.first().extras)
        assertTrue(xiaomiBattery.any { it.action == HsDeliveryTargets.ACTION_BATTERY_OPTIMIZATION })

        // Bilinmeyen üretici: yalnızca standart ekranlar.
        val other = HsDeliveryTargets.candidates(HsDeviceVendor.OTHER, HsDeliverySetting.AUTO_START, app, 34)
        assertTrue(other.none { it.vendorSpecific })
        assertEquals(HsDeliveryTargets.ACTION_APP_DETAILS, other.first().action)
        assertTrue(other.first().packageUri)

        val notifications = HsDeliveryTargets.candidates(HsDeviceVendor.SAMSUNG, HsDeliverySetting.NOTIFICATIONS, app, 34)
        assertEquals(HsDeliveryTargets.ACTION_APP_NOTIFICATIONS, notifications.first().action)
        assertEquals(app, notifications.first().extras[HsDeliveryTargets.EXTRA_APP_PACKAGE])
        // Android 7: uygulama bildirim ayarı işlemi yok, uygulama ayrıntılarına düşülür.
        assertEquals(HsDeliveryTargets.ACTION_APP_DETAILS, HsDeliveryTargets.candidates(HsDeviceVendor.SAMSUNG, HsDeliverySetting.NOTIFICATIONS, app, 24).first().action)

        // Her üretici ve ayar için liste standart bir ekranla biter.
        for (vendor in HsDeviceVendor.values()) {
            for (setting in HsDeliverySetting.values()) {
                val list = HsDeliveryTargets.candidates(vendor, setting, app, 34)
                assertEquals("$vendor/$setting", HsDeliveryTargets.ACTION_SETTINGS, list.last().action)
                assertTrue("$vendor/$setting", list.all { (it.pkg != null && it.cls != null) || it.action != null })
            }
        }
    }

    @Test
    fun `AUTO ayari durumun ilk onerisine cozulur`() {
        assertEquals(HsDeliverySetting.AUTO_START, HsDeliveryTargets.resolve(HsDeliverySetting.AUTO, status(manufacturer = "huawei")))
        assertEquals(HsDeliverySetting.NOTIFICATIONS, HsDeliveryTargets.resolve(HsDeliverySetting.AUTO, status(manufacturer = "huawei", notificationsEnabled = false)))
        assertEquals(HsDeliverySetting.BATTERY_OPTIMIZATION, HsDeliveryTargets.resolve(HsDeliverySetting.AUTO, status()))
        assertEquals(HsDeliverySetting.APP_DETAILS, HsDeliveryTargets.resolve(HsDeliverySetting.APP_DETAILS, status(manufacturer = "huawei")))
    }

    @Test
    fun `cozulemeyen ya da acilamayan aday atlanir, istisna disari sizmaz`() {
        val targets = HsDeliveryTargets.candidates(HsDeviceVendor.HUAWEI, HsDeliverySetting.AUTO_START, "com.example.app", 34)
        val started = mutableListOf<HsSettingsTarget>()

        // Üretici bileşenleri cihazda yok: standart ekran açılır.
        val fallback = HsDeliveryTargets.launchFirst(targets, canResolve = { !it.vendorSpecific }, start = { started.add(it); true })
        assertEquals(HsDeliveryTargets.ACTION_APP_DETAILS, fallback?.action)
        assertEquals(1, started.size)

        // İlk bileşen çözülüyor ama başlatma SecurityException atıyor: sonraki adaya geçilir.
        started.clear()
        val second = HsDeliveryTargets.launchFirst(
            targets,
            canResolve = { true },
            start = { if (it == targets.first()) throw SecurityException("dışa açık değil") else started.add(it) },
        )
        assertEquals(targets[1], second)

        // Çözümleme istisna atarsa aday atlanır; hiçbiri açılamazsa null.
        assertNull(HsDeliveryTargets.launchFirst(targets, canResolve = { throw IllegalStateException("pm") }, start = { true }))
        assertNull(HsDeliveryTargets.launchFirst(targets, canResolve = { true }, start = { false }))
    }

    @Test
    fun `manifest queries listesi uretici paketleriyle aynidir`() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val queries = manifest.substringAfter("<queries>").substringBefore("</queries>")
        val declared = Regex("<package android:name=\"([^\"]+)\"").findAll(queries).map { it.groupValues[1] }.toSortedSet()
        assertEquals(HsDeliveryTargets.vendorPackages, declared)
    }
}
