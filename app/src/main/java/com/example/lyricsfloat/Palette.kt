package com.example.lyricsfloat

import android.graphics.Color

/** 앱 화면과 가사 창이 함께 쓰는 색. 어두운 유리 위에 지금 줄만 호박색으로 빛나는 것이 기본 인상이다. */
object Palette {
    val BG = Color.parseColor("#0F1016")
    val SURFACE = Color.parseColor("#1B1C26")
    val HERO_TOP = Color.parseColor("#2A2340")
    val HERO_BOTTOM = Color.parseColor("#1A1B26")
    val ACCENT = Color.parseColor("#FFB867")
    val ON_ACCENT = Color.parseColor("#2A1A05")
    val TEXT = Color.parseColor("#F2F2F6")
    val MUTED = Color.parseColor("#8A8BA0")
    val OK = Color.parseColor("#8FE3B0")
    val TRACK = Color.parseColor("#33354A")

    /** 가사 창 바탕. 불투명도는 설정값으로 정한다. */
    const val GLASS_R = 22
    const val GLASS_G = 20
    const val GLASS_B = 34
    val GLASS_EDGE = Color.parseColor("#1FFFFFFF")
    val GLASS_TITLE = Color.parseColor("#A9A6C4")
    val LYRIC_DIM = Color.parseColor("#80FFFFFF")
    val CHIP = Color.parseColor("#1AFFFFFF")
}
