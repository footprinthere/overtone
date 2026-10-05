package com.example.lyricsfloat

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors

class LyricsOverlayService : Service() {

    companion object {
        const val ACTION_STOP = "com.example.lyricsfloat.STOP"
        private const val CHANNEL_ID = "lyrics_overlay"
        private const val NOTI_ID = 1
        private const val YTM_PACKAGE = "com.google.android.apps.youtube.music"
        private const val LEAD_MS = 250L // 가사를 살짝 앞당겨 표시

        @Volatile
        var instance: LyricsOverlayService? = null
        val running: Boolean get() = instance != null
    }

    private lateinit var wm: WindowManager
    private lateinit var params: WindowManager.LayoutParams
    private var root: View? = null
    private lateinit var titleView: TextView
    private lateinit var lyricsView: TextView
    private lateinit var scrollView: ScrollView

    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()

    private var controller: MediaController? = null
    private var listenerAccessMissing = false
    private var tickCount = 0
    private var currentKey: String? = null
    private var curTitle = ""
    private var curArtist = ""
    private var lines: List<LrcLine> = emptyList()
    private var lastIndex = -2

    private val tick = object : Runnable {
        override fun run() {
            try {
                tickOnce()
            } catch (e: Exception) {
                // 한 번 실패해도 다음 틱에서 계속
            }
            handler.postDelayed(this, 500)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startAsForeground()
        if (!Permissions.hasOverlay(this)) {
            Toast.makeText(this, "'다른 앱 위에 표시' 권한이 필요해요", Toast.LENGTH_LONG).show()
            stopSelf()
            return START_NOT_STICKY
        }
        if (root == null) {
            createOverlay()
            instance = this
            handler.post(tick)
            LyricsTileService.requestUpdate(this)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        root?.let { runCatching { wm.removeView(it) } }
        root = null
        instance = null
        executor.shutdownNow()
        LyricsTileService.requestUpdate(this)
        super.onDestroy()
    }

    // ---------- 포그라운드 알림 ----------
    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "가사 플로팅", NotificationManager.IMPORTANCE_LOW)
        )
        val stop = PendingIntent.getService(
            this, 0,
            Intent(this, LyricsOverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("가사 플로팅 실행 중")
            .setContentText("탭하면 종료돼요")
            .setContentIntent(stop)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTI_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTI_ID, n)
        }
    }

    // ---------- 오버레이 UI ----------
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun headerButton(label: String, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setOnClickListener { onClick() }
        }

    private fun createOverlay() {
        wm = getSystemService(WINDOW_SERVICE) as WindowManager

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#E6121212"))
                cornerRadius = dp(16).toFloat()
            }
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(6), dp(6), dp(6))
        }
        titleView = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            text = "유튜브 뮤직 대기 중"
        }
        header.addView(
            titleView,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        header.addView(headerButton("웹") { searchWeb() })
        header.addView(headerButton("✕") { stopSelf() })

        scrollView = ScrollView(this)
        lyricsView = TextView(this).apply {
            setTextColor(Color.parseColor("#99FFFFFF"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setLineSpacing(0f, 1.25f)
            setPadding(dp(16), dp(8), dp(16), dp(24))
            text = "유튜브 뮤직에서 노래를 재생해 주세요"
        }
        scrollView.addView(lyricsView)

        container.addView(header)
        container.addView(
            scrollView,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        )

        params = WindowManager.LayoutParams(
            resources.displayMetrics.widthPixels - dp(32),
            dp(280),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(16)
            y = dp(120)
        }

        // 헤더를 잡고 드래그해서 창 이동
        header.setOnTouchListener(object : View.OnTouchListener {
            var startX = 0
            var startY = 0
            var downX = 0f
            var downY = 0f
            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = params.x; startY = params.y
                        downX = e.rawX; downY = e.rawY
                    }
                    MotionEvent.ACTION_MOVE -> {
                        params.x = startX + (e.rawX - downX).toInt()
                        params.y = startY + (e.rawY - downY).toInt()
                        wm.updateViewLayout(container, params)
                    }
                }
                return true
            }
        })

        wm.addView(container, params)
        root = container
    }

    private fun searchWeb() {
        val q = Uri.encode("$curTitle $curArtist 가사".trim())
        val i = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=$q"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { startActivity(i) }
    }

    // ---------- 재생 정보 추적 ----------
    private fun findController(): MediaController? {
        return try {
            listenerAccessMissing = false
            val msm = getSystemService(MediaSessionManager::class.java)
            val list = msm.getActiveSessions(ComponentName(this, LyricsListenerService::class.java))
            list.firstOrNull { it.packageName == YTM_PACKAGE }
        } catch (e: SecurityException) {
            listenerAccessMissing = true
            null
        }
    }

    private fun position(c: MediaController): Long {
        val s = c.playbackState ?: return 0L
        var pos = s.position
        if (s.state == PlaybackState.STATE_PLAYING) {
            pos += ((SystemClock.elapsedRealtime() - s.lastPositionUpdateTime) * s.playbackSpeed).toLong()
        }
        return pos
    }

    private fun tickOnce() {
        tickCount++
        if (controller == null || tickCount % 4 == 0) controller = findController()
        val c = controller
        if (c == null) {
            resetTrack()
            showStatus(
                if (listenerAccessMissing) "알림 접근 권한을 켜 주세요 (앱 화면에서 설정)"
                else "유튜브 뮤직에서 노래를 재생해 주세요"
            )
            return
        }
        val md = c.metadata
        val title = md?.getString(MediaMetadata.METADATA_KEY_TITLE)
        if (md == null || title.isNullOrBlank()) {
            resetTrack()
            showStatus("유튜브 뮤직에서 노래를 재생해 주세요")
            return
        }
        val artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: md.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST) ?: ""
        val album = md.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: ""
        val durationMs = md.getLong(MediaMetadata.METADATA_KEY_DURATION)

        val key = "$title|$artist|$durationMs"
        if (key != currentKey) {
            currentKey = key
            onNewTrack(title, artist, album, durationMs)
        }
        if (lines.isNotEmpty()) updateHighlight(position(c))
    }

    private fun resetTrack() {
        currentKey = null
        lines = emptyList()
        lastIndex = -2
        curTitle = ""
        curArtist = ""
        titleView.text = "유튜브 뮤직 대기 중"
    }

    private fun showStatus(msg: String) {
        if (lyricsView.text.toString() != msg) lyricsView.text = msg
    }

    private fun onNewTrack(title: String, artist: String, album: String, durationMs: Long) {
        curTitle = title
        curArtist = artist
        titleView.text = if (artist.isBlank()) title else "$title — $artist"
        lines = emptyList()
        lastIndex = -2
        lyricsView.text = "가사를 찾는 중…"
        scrollView.scrollTo(0, 0)

        val key = currentKey
        executor.execute {
            val r = try {
                LyricsRepository.fetch(title, artist, album, (durationMs / 1000).toInt())
            } catch (e: Exception) {
                null
            }
            handler.post {
                if (key == currentKey && root != null) applyResult(r)
            }
        }
    }

    private fun applyResult(r: LyricsResult?) {
        val notFound = "가사를 찾지 못했어요.\n오른쪽 위 '웹' 버튼으로 검색해 보세요."
        if (r == null) {
            lyricsView.text = notFound
            return
        }
        val plain = r.plain
        when {
            r.synced.isNotEmpty() -> {
                lines = r.synced
                lastIndex = -2 // 다음 틱에서 렌더링
            }
            plain != null -> {
                lyricsView.text = plain
                scrollView.scrollTo(0, 0)
            }
            r.instrumental -> lyricsView.text = "♪ 연주곡이에요"
            else -> lyricsView.text = notFound
        }
    }

    private fun updateHighlight(posMs: Long) {
        var idx = -1
        for (i in lines.indices) {
            if (lines[i].timeMs <= posMs + LEAD_MS) idx = i else break
        }
        if (idx != lastIndex) {
            lastIndex = idx
            render(idx)
        }
    }

    private fun render(idx: Int) {
        val sb = SpannableStringBuilder()
        var curStart = 0
        for ((i, l) in lines.withIndex()) {
            val start = sb.length
            sb.append(if (l.text.isBlank()) "♪" else l.text)
            if (i == idx) {
                curStart = start
                val end = sb.length
                sb.setSpan(ForegroundColorSpan(Color.WHITE), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.setSpan(StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.setSpan(RelativeSizeSpan(1.15f), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            sb.append("\n")
        }
        lyricsView.text = sb
        lyricsView.post {
            val layout = lyricsView.layout ?: return@post
            val y = layout.getLineTop(layout.getLineForOffset(curStart)) - scrollView.height / 3
            scrollView.smoothScrollTo(0, maxOf(0, y))
        }
    }
}
