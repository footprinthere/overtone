package com.example.lyricsfloat

import android.content.Context

/** 창 위치·크기, 표시 방식, 곡별 싱크 보정값. 위치·크기는 픽셀, -1 이면 아직 저장된 적 없음. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var compact: Boolean
        get() = sp.getBoolean("compact", false)
        set(v) = sp.edit().putBoolean("compact", v).apply()

    var clickThrough: Boolean
        get() = sp.getBoolean("clickThrough", false)
        set(v) = sp.edit().putBoolean("clickThrough", v).apply()

    /** 배경 불투명도 % */
    var opacity: Int
        get() = sp.getInt("opacity", 90)
        set(v) = sp.edit().putInt("opacity", v).apply()

    var fontSp: Int
        get() = sp.getInt("fontSp", 16)
        set(v) = sp.edit().putInt("fontSp", v).apply()

    fun bounds(compact: Boolean): IntArray {
        val p = if (compact) "c_" else "f_"
        return intArrayOf(sp.getInt(p + "x", -1), sp.getInt(p + "y", -1), sp.getInt(p + "w", -1), sp.getInt(p + "h", -1))
    }

    fun saveBounds(compact: Boolean, x: Int, y: Int, w: Int, h: Int) {
        val p = if (compact) "c_" else "f_"
        sp.edit().putInt(p + "x", x).putInt(p + "y", y).putInt(p + "w", w).putInt(p + "h", h).apply()
    }

    fun resetBounds() {
        sp.edit().apply { listOf("c_", "f_").forEach { p -> "xywh".forEach { remove(p + it) } } }.apply()
    }

    fun offsetMs(trackKey: String): Long = sp.getLong("offset:$trackKey", 0L)

    fun setOffsetMs(trackKey: String, ms: Long) {
        sp.edit().apply { if (ms == 0L) remove("offset:$trackKey") else putLong("offset:$trackKey", ms) }.apply()
    }
}
