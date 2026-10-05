package com.example.lyricsfloat

import android.Manifest
import android.app.Activity
import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast

/** 권한 설정, 타일 추가, 가사 창 설정 화면. 켜고 끄는 건 주로 빠른 설정 타일로 한다. */
class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var prefs: Prefs
    private lateinit var compactBox: CheckBox
    private lateinit var throughBox: CheckBox

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        val pad = (20 * resources.displayMetrics.density).toInt()
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        status = TextView(this).apply { textSize = 15f }
        col.addView(status)

        col.addView(button("1. 다른 앱 위에 표시 허용") {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        })
        col.addView(button("2. 알림 접근 허용 (재생 정보 읽기용)") {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        })
        if (Build.VERSION.SDK_INT >= 33) {
            col.addView(button("3. 알림 표시 허용") {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            })
        }
        col.addView(button("빠른 설정에 타일 추가") { addTile() })
        col.addView(button("가사 창 켜기 / 끄기") { toggle() })

        col.addView(header("가사 창 설정"))
        col.addView(slider("배경 불투명도", 30, 100, prefs.opacity, "%") { prefs.opacity = it })
        col.addView(slider("글자 크기", 12, 28, prefs.fontSp, "sp") { prefs.fontSp = it })
        compactBox = checkBox("얇은 띠 모드 (지금 줄과 다음 줄만)") { prefs.compact = it }
        throughBox = checkBox("터치 통과 (가사 창 밑의 앱을 누를 수 있게)") { prefs.clickThrough = it }
        col.addView(compactBox)
        col.addView(throughBox)
        col.addView(button("창 위치·크기 초기화") {
            prefs.resetBounds()
            LyricsOverlayService.instance?.onSettingsChanged()
        })

        setContentView(ScrollView(this).apply {
            fitsSystemWindows = true
            addView(col)
        })
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun button(label: String, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            isAllCaps = false
            setOnClickListener { onClick() }
        }

    private fun header(label: String) = TextView(this).apply {
        text = label
        textSize = 17f
        val top = (24 * resources.displayMetrics.density).toInt()
        setPadding(0, top, 0, top / 3)
    }

    private fun checkBox(label: String, onChange: (Boolean) -> Unit) = CheckBox(this).apply {
        text = label
        setOnCheckedChangeListener { _, checked ->
            onChange(checked)
            LyricsOverlayService.instance?.onSettingsChanged()
        }
    }

    private fun slider(label: String, min: Int, max: Int, value: Int, unit: String, onChange: (Int) -> Unit) =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val caption = TextView(context).apply { text = "$label: $value$unit" }
            addView(caption)
            addView(SeekBar(context).apply {
                this.max = max - min
                progress = value - min
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(bar: SeekBar, p: Int, fromUser: Boolean) {
                        if (!fromUser) return
                        caption.text = "$label: ${p + min}$unit"
                        onChange(p + min)
                        LyricsOverlayService.instance?.onSettingsChanged()
                    }

                    override fun onStartTrackingTouch(bar: SeekBar) {}
                    override fun onStopTrackingTouch(bar: SeekBar) {}
                })
            })
        }

    private fun refresh() {
        fun mark(ok: Boolean) = if (ok) "✅" else "❌"
        status.text = buildString {
            appendLine("${mark(Permissions.hasOverlay(this@MainActivity))} 다른 앱 위에 표시")
            appendLine("${mark(Permissions.hasListenerAccess(this@MainActivity))} 알림 접근")
            appendLine("${mark(LyricsOverlayService.running)} 가사 창 실행 중")
            appendLine()
            append("설정이 끝나면 빠른 설정 패널의 '가사 플로팅' 타일로 켜고 끌 수 있어요.")
        }
        // 가사 창이나 알림에서 바뀌었을 수 있어 다시 읽는다. 리스너가 다시 저장하는 것은 같은 값이라 문제없다.
        compactBox.isChecked = prefs.compact
        throughBox.isChecked = prefs.clickThrough
    }

    private fun toggle() {
        if (LyricsOverlayService.running) {
            LyricsOverlayService.instance?.stopSelf()
        } else if (!Permissions.hasOverlay(this) || !Permissions.hasListenerAccess(this)) {
            Toast.makeText(this, "1, 2번 권한을 먼저 허용해 주세요", Toast.LENGTH_SHORT).show()
        } else {
            startForegroundService(Intent(this, LyricsOverlayService::class.java))
        }
        status.postDelayed({ refresh() }, 400)
    }

    private fun addTile() {
        if (Build.VERSION.SDK_INT >= 33) {
            getSystemService(StatusBarManager::class.java).requestAddTileService(
                ComponentName(this, LyricsTileService::class.java),
                "가사 플로팅",
                Icon.createWithResource(this, R.drawable.ic_tile),
                mainExecutor
            ) { }
        } else {
            Toast.makeText(
                this,
                "빠른 설정 패널의 편집(연필) 버튼에서 '가사 플로팅' 타일을 추가해 주세요",
                Toast.LENGTH_LONG
            ).show()
        }
    }
}
