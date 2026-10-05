package com.example.lyricsfloat

import android.Manifest
import android.app.Activity
import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Icon
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/** 권한 설정, 타일 추가, 가사 창 설정 화면. 켜고 끄는 건 주로 빠른 설정 타일로 한다. */
class MainActivity : Activity() {

    /** 준비 단계 한 줄. done 이 null 이면 완료 여부를 알 수 없는 단계(타일 추가). */
    private class Step(val state: TextView, val done: () -> Boolean?)

    private lateinit var prefs: Prefs
    private lateinit var heroStatus: TextView
    private lateinit var heroButton: TextView
    private lateinit var setupCount: TextView
    private val steps = mutableListOf<Step>()
    private lateinit var compactSwitch: Switch
    private lateinit var throughSwitch: Switch

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        window.statusBarColor = Palette.BG
        window.navigationBarColor = Palette.BG

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(32))
        }
        col.addView(text(28f, Palette.TEXT, bold = true).apply { text = getString(R.string.app_name) })
        col.addView(text(14f, Palette.MUTED).apply {
            text = "유튜브 뮤직에서 듣는 곡의 가사를 다른 앱 위에 띄워요"
            setPadding(0, dp(4), 0, dp(20))
        })
        col.addView(hero())

        setupCount = text(13f, Palette.MUTED)
        col.addView(sectionLabel("준비", setupCount))
        col.addView(card().apply {
            addView(step("다른 앱 위에 표시", "가사 창을 띄우는 데 필요해요", { Permissions.hasOverlay(this@MainActivity) }) {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            })
            addView(step("알림 접근", "유튜브 뮤직이 재생 중인 곡을 알아내요", { Permissions.hasListenerAccess(this@MainActivity) }) {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            })
            if (Build.VERSION.SDK_INT >= 33) {
                addView(step("알림 표시", "실행 중 알림에서 바로 끌 수 있어요", {
                    checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
                }) { requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1) })
            }
            addView(step("빠른 설정 타일", "상단 패널에서 바로 켜고 꺼요", { null }) { addTile() })
        })

        col.addView(sectionLabel("가사 창", null))
        col.addView(card().apply {
            addView(slider("배경 불투명도", 30, 100, prefs.opacity, "%") { prefs.opacity = it })
            addView(slider("글자 크기", 12, 28, prefs.fontSp, "sp") { prefs.fontSp = it })
            compactSwitch = switch("얇은 띠 모드", "지금 줄과 다음 줄만 보여요") { prefs.compact = it }
            throughSwitch = switch("터치 통과", "가사 창 밑의 앱을 누를 수 있어요") { prefs.clickThrough = it }
            addView(compactSwitch)
            addView(throughSwitch)
            addView(text(15f, Palette.ACCENT).apply {
                text = "창 위치·크기 초기화"
                setPadding(dp(16), dp(14), dp(16), dp(14))
                background = ripple()
                setOnClickListener {
                    prefs.resetBounds()
                    LyricsOverlayService.instance?.onSettingsChanged()
                    Toast.makeText(this@MainActivity, "가사 창을 처음 위치로 돌렸어요", Toast.LENGTH_SHORT).show()
                }
            })
        })

        col.addView(text(13f, Palette.MUTED).apply {
            text = "빠른 설정 패널에 '${getString(R.string.tile_label)}' 타일을 두면 앱을 열지 않고도 켜고 끌 수 있어요."
            setPadding(dp(4), dp(20), dp(4), 0)
            setLineSpacing(0f, 1.3f)
        })

        setContentView(ScrollView(this).apply {
            fitsSystemWindows = true
            setBackgroundColor(Palette.BG)
            addView(col)
        })
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    // ---------- 구성 요소 ----------

    private fun hero() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(18), dp(18), dp(18))
        background = GradientDrawable(
            GradientDrawable.Orientation.TL_BR, intArrayOf(Palette.HERO_TOP, Palette.HERO_BOTTOM)
        ).apply { cornerRadius = dp(20).toFloat() }
        addView(text(13f, Palette.MUTED).apply { text = "가사 창" })
        heroStatus = text(20f, Palette.TEXT, bold = true).apply { setPadding(0, dp(2), 0, dp(14)) }
        addView(heroStatus)
        heroButton = text(16f, Palette.ON_ACCENT, bold = true).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(13), 0, dp(13))
            setOnClickListener { toggle() }
        }
        addView(heroButton, LinearLayout.LayoutParams(MATCH, WRAP))
    }

    private fun sectionLabel(label: String, trailing: TextView?) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.BOTTOM
        setPadding(dp(4), dp(28), dp(4), dp(10))
        addView(text(13f, Palette.MUTED, bold = true).apply {
            text = label
            letterSpacing = 0.04f
        }, LinearLayout.LayoutParams(0, WRAP, 1f))
        trailing?.let { addView(it) }
    }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(4), 0, dp(4))
        background = rounded(Palette.SURFACE, 16)
        clipToOutline = true
    }

    private fun step(title: String, desc: String, done: () -> Boolean?, onClick: () -> Unit): View {
        val state = text(14f, Palette.ACCENT, bold = true)
        steps += Step(state, done)
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = ripple()
            setOnClickListener { onClick() }
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(text(15f, Palette.TEXT).apply { text = title })
                addView(text(12f, Palette.MUTED).apply { text = desc })
            }, LinearLayout.LayoutParams(0, WRAP, 1f))
            addView(state)
        }
    }

    private fun slider(label: String, min: Int, max: Int, value: Int, unit: String, onChange: (Int) -> Unit) =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(12), 0, dp(6))
            val valueView = text(14f, Palette.MUTED).apply { text = "$value$unit" }
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dp(16), 0, dp(16), 0)
                addView(text(15f, Palette.TEXT).apply { text = label }, LinearLayout.LayoutParams(0, WRAP, 1f))
                addView(valueView)
            })
            addView(SeekBar(context).apply {
                this.max = max - min
                progress = value - min
                progressTintList = ColorStateList.valueOf(Palette.ACCENT)
                thumbTintList = ColorStateList.valueOf(Palette.ACCENT)
                progressBackgroundTintList = ColorStateList.valueOf(Palette.TRACK)
                setPadding(dp(16), dp(10), dp(16), dp(6))
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(bar: SeekBar, p: Int, fromUser: Boolean) {
                        if (!fromUser) return
                        valueView.text = "${p + min}$unit"
                        onChange(p + min)
                        LyricsOverlayService.instance?.onSettingsChanged()
                    }

                    override fun onStartTrackingTouch(bar: SeekBar) {}
                    override fun onStopTrackingTouch(bar: SeekBar) {}
                })
            })
        }

    private fun switch(label: String, desc: String, onChange: (Boolean) -> Unit) = Switch(this).apply {
        text = SpannableString("$label\n$desc").apply {
            val start = label.length + 1
            setSpan(RelativeSizeSpan(0.8f), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(ForegroundColorSpan(Palette.MUTED), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        setTextColor(Palette.TEXT)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        setLineSpacing(dp(2).toFloat(), 1f)
        setPadding(dp(16), dp(12), dp(16), dp(12))
        background = ripple()
        val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        thumbTintList = ColorStateList(states, intArrayOf(Palette.ACCENT, Palette.MUTED))
        trackTintList = ColorStateList(states, intArrayOf(Palette.ACCENT, Palette.TRACK))
        setOnCheckedChangeListener { _, checked ->
            onChange(checked)
            LyricsOverlayService.instance?.onSettingsChanged()
        }
    }

    // ---------- 상태 ----------

    private fun refresh() {
        val ready = Permissions.hasOverlay(this) && Permissions.hasListenerAccess(this)
        val running = LyricsOverlayService.running
        heroStatus.text = when {
            running -> "떠 있어요"
            ready -> "꺼져 있어요"
            else -> "아래 준비를 먼저 마쳐 주세요"
        }
        heroButton.text = if (running) "끄기" else "켜기"
        heroButton.setTextColor(if (running) Palette.TEXT else Palette.ON_ACCENT)
        heroButton.background = rounded(if (running) Palette.CHIP else Palette.ACCENT, 99)
        heroButton.alpha = if (running || ready) 1f else 0.5f

        var known = 0
        var done = 0
        for (s in steps) {
            val d = s.done()
            if (d == null) {
                s.state.text = "추가 ›"
                s.state.setTextColor(Palette.ACCENT)
                continue
            }
            known++
            if (d) done++
            s.state.text = if (d) "완료" else "허용 ›"
            s.state.setTextColor(if (d) Palette.OK else Palette.ACCENT)
        }
        setupCount.text = "$done/$known 완료"
        setupCount.setTextColor(if (done == known) Palette.OK else Palette.MUTED)

        // 가사 창이나 알림에서 바뀌었을 수 있어 다시 읽는다. 리스너가 다시 저장하는 것은 같은 값이라 문제없다.
        compactSwitch.isChecked = prefs.compact
        throughSwitch.isChecked = prefs.clickThrough
    }

    private fun toggle() {
        if (LyricsOverlayService.running) {
            LyricsOverlayService.instance?.stopSelf()
        } else if (!Permissions.hasOverlay(this) || !Permissions.hasListenerAccess(this)) {
            Toast.makeText(this, "'다른 앱 위에 표시'와 '알림 접근'을 먼저 허용해 주세요", Toast.LENGTH_SHORT).show()
        } else {
            startForegroundService(Intent(this, LyricsOverlayService::class.java))
        }
        heroStatus.postDelayed({ refresh() }, 400)
    }

    private fun addTile() {
        val label = getString(R.string.tile_label)
        if (Build.VERSION.SDK_INT >= 33) {
            getSystemService(StatusBarManager::class.java).requestAddTileService(
                ComponentName(this, LyricsTileService::class.java),
                label,
                Icon.createWithResource(this, R.drawable.ic_tile),
                mainExecutor
            ) { }
        } else {
            Toast.makeText(
                this,
                "빠른 설정 패널의 편집(연필) 버튼에서 '$label' 타일을 추가해 주세요",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    // ---------- 작은 도우미 ----------

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun text(sp: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun rounded(color: Int, radiusDp: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
    }

    private fun ripple() = RippleDrawable(ColorStateList.valueOf(Palette.CHIP), null, rounded(Palette.TEXT, 0))

    companion object {
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }
}
