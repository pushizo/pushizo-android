package com.pushizo.sdk

import android.util.Log

/** SDK günlüğü; varsayılan seviye WARN. Token ve gizli değerler hiçbir seviyede yazılmaz. */
object HsLog {
    enum class Level(val priority: Int) { NONE(0), ERROR(1), WARN(2), INFO(3), DEBUG(4) }

    @Volatile var level: Level = Level.WARN
    private const val TAG = "Pushizo"

    fun e(msg: String, t: Throwable? = null) { if (level.priority >= 1) Log.e(TAG, msg, t) }
    fun w(msg: String, t: Throwable? = null) { if (level.priority >= 2) Log.w(TAG, msg, t) }
    fun i(msg: String) { if (level.priority >= 3) Log.i(TAG, msg) }
    fun d(msg: String) { if (level.priority >= 4) Log.d(TAG, msg) }
}
