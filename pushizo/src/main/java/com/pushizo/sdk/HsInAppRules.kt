package com.pushizo.sdk

/** Değerlendirmeyi başlatan durum: anlık (oturum başı, olay, süre zamanlayıcısı) veya durum değişimi (ekran, trigger). */
internal sealed class HsInAppCause {
    object SessionStart : HsInAppCause()
    data class Event(val name: String) : HsInAppCause()
    data class Screen(val name: String) : HsInAppCause()
    data class TriggerChange(val keys: Set<String>) : HsInAppCause()
    /** Oturum süresi zamanlayıcısı doldu (veya bir mesaj kapandı); `session_duration` tetikleri yeniden değerlendirilir. */
    object SessionDuration : HsInAppCause()
}

/** Değerlendirme anındaki durum: geçerli ekran, `addTrigger` değerleri ve ön plan oturumunun süresi (saniye). */
internal data class HsInAppState(
    val screen: String? = null,
    val triggers: Map<String, String> = emptyMap(),
    val sessionSeconds: Long = 0,
)

/** Son listeden bu yana yereldeki gösterimler ve etkileşim (tıklama/kapatma); sunucu bir sonraki yenilemede günceller. */
internal data class HsLocalImpressions(val count: Int = 0, val lastAt: Long? = null, val interacted: Boolean = false)

/**
 * In-app tetik ve sıklık kuralları (saf mantık; ortak vektörler `sdk/testdata/messaging-vectors.json`).
 *
 * - Mesajın tetik grupları VEYA ile bağlıdır: herhangi bir grup sağlanırsa mesaj adaydır ([matchesAny]). Grup içinde
 *   ([matches]) mesaj yalnızca tetiklerinden birini "ateşleyen" bir değerlendirmede aday olur: oturum başı →
 *   `session_start`, olay → aynı adlı `event`, ekran → yeni ekranla eşleşen `screen`, trigger değişimi → değişen anahtarlı
 *   `trigger`, süre zamanlayıcısı → süresi dolmuş `session_duration`. Tetiksiz grup oturum başında adaydır.
 * - Aday grubun tüm koşulları sağlanmalıdır: `session_start` oturum boyunca sağlanır; `event` yalnızca o olay anında;
 *   `screen` geçerli ekran; `trigger` karşılaştırması (`eq|neq|exists|gt|lt`); `session_duration` oturum süresi
 *   eşiğe ulaştıysa. Tanınmayan tür hiç sağlanmaz.
 * - Karşılaştırma: iki taraf da ondalık sayıysa (`-12.5`, bilimsel gösterim hariç) sayısal, değilse metin (büyük/küçük
 *   harf duyarlı). `neq` anahtar yoksa sağlanır; `gt`/`lt` sayısal olmayan değerde sağlanmaz.
 * - App geneli aralık: son gösterimden (herhangi bir mesaj) bu yana `in_app.min_interval_seconds` geçmediyse hiçbir
 *   mesaj gösterilmez ([globalIntervalAllows]).
 */
internal object HsInAppRules {
    private val DECIMAL = Regex("^-?\\d+(\\.\\d+)?$")

    /** VEYA grupları: herhangi biri sağlanırsa true. Boş liste tetiksiz mesaj gibi (yalnızca oturum başı) davranır. */
    fun matchesAny(groups: List<List<HsInAppTrigger>>, cause: HsInAppCause, state: HsInAppState): Boolean {
        if (groups.isEmpty()) return matches(emptyList(), cause, state)
        return groups.any { matches(it, cause, state) }
    }

    fun matches(triggers: List<HsInAppTrigger>, cause: HsInAppCause, state: HsInAppState): Boolean {
        if (triggers.isEmpty()) return cause is HsInAppCause.SessionStart
        if (triggers.none { fires(it, cause, state) }) return false
        return triggers.all { holds(it, cause, state) }
    }

    private fun fires(t: HsInAppTrigger, cause: HsInAppCause, state: HsInAppState): Boolean = when (t) {
        is HsInAppTrigger.SessionStart -> cause is HsInAppCause.SessionStart
        is HsInAppTrigger.Event -> cause is HsInAppCause.Event && cause.name == t.name
        is HsInAppTrigger.Screen -> cause is HsInAppCause.Screen && cause.name == t.name && state.screen == t.name
        is HsInAppTrigger.Condition -> cause is HsInAppCause.TriggerChange && t.key in cause.keys
        is HsInAppTrigger.SessionDuration -> cause is HsInAppCause.SessionDuration && state.sessionSeconds >= t.seconds
        is HsInAppTrigger.Unsupported -> false
    }

    private fun holds(t: HsInAppTrigger, cause: HsInAppCause, state: HsInAppState): Boolean = when (t) {
        is HsInAppTrigger.SessionStart -> true
        is HsInAppTrigger.Event -> cause is HsInAppCause.Event && cause.name == t.name
        is HsInAppTrigger.Screen -> state.screen == t.name
        is HsInAppTrigger.Condition -> conditionHolds(t, state.triggers)
        is HsInAppTrigger.SessionDuration -> state.sessionSeconds >= t.seconds
        is HsInAppTrigger.Unsupported -> false
    }

    fun conditionHolds(t: HsInAppTrigger.Condition, triggers: Map<String, String>): Boolean {
        val actual = triggers[t.key]
        val expected = t.value
        return when (t.op) {
            "exists" -> actual != null
            "eq" -> actual != null && expected != null && looseEquals(actual, expected)
            "neq" -> actual == null || expected == null || !looseEquals(actual, expected)
            "gt", "lt" -> {
                val a = actual?.let(::number) ?: return false
                val e = expected?.let(::numberOf) ?: return false
                if (t.op == "gt") a > e else a < e
            }
            else -> false
        }
    }

    private fun looseEquals(actual: String, expected: Any): Boolean {
        val a = number(actual)
        val e = numberOf(expected)
        if (a != null && e != null) return a == e
        return expected is String && actual == expected
    }

    private fun numberOf(value: Any): Double? = when (value) {
        is Number -> value.toDouble().takeIf { it.isFinite() }
        is String -> number(value)
        else -> null
    }

    /** Yalnızca düz ondalık gösterim sayı kabul edilir (platformlar arası tutarlılık için). */
    fun number(value: String): Double? {
        val v = value.trim()
        return if (DECIMAL.matches(v)) v.toDoubleOrNull() else null
    }

    /** Sıklık ve geçerlilik: bitiş zamanı, kalan gösterim hakkı ve iki gösterim arası süre. */
    fun canDisplay(message: HsInAppMessage, local: HsLocalImpressions, now: Long): Boolean {
        // Etkileşim sonrası durdurma: yerel tıklama/kapatma, sunucu listesi henüz güncellenmemiş olsa da uygulanır.
        if (message.stopAfterInteraction && local.interacted) return false
        return canDisplay(message.remainingImpressions, message.minIntervalSeconds, message.lastImpressionAt, message.endAt, local, now)
    }

    fun canDisplay(remaining: Int, minIntervalSeconds: Long, serverLast: Long?, endAt: Long?, local: HsLocalImpressions, now: Long): Boolean {
        if (endAt != null && now >= endAt) return false
        if (remaining - local.count <= 0) return false
        val last = listOfNotNull(serverLast, local.lastAt).maxOrNull()
        if (minIntervalSeconds > 0 && last != null && now - last < minIntervalSeconds * 1000) return false
        return true
    }

    /** App geneli aralık (`in_app.min_interval_seconds`): son gösterimden bu yana yeterli süre geçtiyse true. */
    fun globalIntervalAllows(minIntervalSeconds: Long, lastImpressionAt: Long?, now: Long): Boolean =
        minIntervalSeconds <= 0 || lastImpressionAt == null || now - lastImpressionAt >= minIntervalSeconds * 1000

    /**
     * Mesajlardaki `session_duration` eşikleri arasından geçerli oturum süresini aşan en küçüğü (saniye); yoksa null.
     * Zamanlayıcı bu eşikte [HsInAppCause.SessionDuration] ile yeniden değerlendirme yapar.
     */
    fun nextSessionDurationSeconds(messages: List<HsInAppMessage>, sessionSeconds: Long): Long? =
        messages.asSequence()
            .flatMap { m -> m.triggerGroups.asSequence().flatten() }
            .filterIsInstance<HsInAppTrigger.SessionDuration>()
            .map { it.seconds }
            .filter { it > sessionSeconds }
            .minOrNull()

    /**
     * Eşleşen ve gösterilebilir mesajlar arasından en yüksek öncelikli (eşitlikte liste sırası). App geneli aralık
     * ([globalMinIntervalSeconds], [lastImpressionAt]: herhangi bir mesajın son gösterimi) dolmadıysa null.
     */
    fun select(
        messages: List<HsInAppMessage>,
        cause: HsInAppCause,
        state: HsInAppState,
        locals: Map<String, HsLocalImpressions>,
        now: Long,
        globalMinIntervalSeconds: Long = 0,
        lastImpressionAt: Long? = null,
    ): HsInAppMessage? {
        if (!globalIntervalAllows(globalMinIntervalSeconds, lastImpressionAt, now)) return null
        var best: HsInAppMessage? = null
        for (m in messages) {
            if (!matchesAny(m.triggerGroups, cause, state)) continue
            if (!canDisplay(m, locals[m.id] ?: HsLocalImpressions(), now)) continue
            if (best == null || m.priority > best.priority) best = m
        }
        return best
    }
}
