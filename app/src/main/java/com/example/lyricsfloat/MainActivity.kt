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
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** 권한 설정과 타일 추가를 한 번만 하면 되는 설정 화면. 이후엔 빠른 설정 타일로 사용합니다. */
class MainActivity : Activity() {

    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
        col.addView(button("3. 알림 표시 허용") {
            if (Build.VERSION.SDK_INT >= 33) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            }
        })
        col.addView(button("4. 빠른 설정에 타일 추가") { addTile() })
        col.addView(button("플로팅 가사 시작 / 종료") { toggle() })

        setContentView(ScrollView(this).apply { addView(col) })
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

    private fun refresh() {
        fun mark(ok: Boolean) = if (ok) "✅" else "❌"
        status.text = buildString {
            appendLine("${mark(Permissions.hasOverlay(this@MainActivity))} 다른 앱 위에 표시")
            appendLine("${mark(Permissions.hasListenerAccess(this@MainActivity))} 알림 접근")
            appendLine("${mark(LyricsOverlayService.running)} 플로팅 가사 실행 중")
            appendLine()
            append("설정이 끝나면 빠른 설정 패널의 '가사 플로팅' 타일로 켜고 끌 수 있어요.")
        }
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
