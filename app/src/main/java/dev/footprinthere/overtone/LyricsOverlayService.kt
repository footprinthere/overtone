package dev.footprinthere.overtone

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import java.util.concurrent.Executors

/** 가사 창을 띄워 두는 동안 돌아가는 서비스. 빠른 설정 타일이나 앱 화면에서 켜고 끈다. */
class LyricsOverlayService : Service(), LyricsOverlay.Actions {

    companion object {
        const val ACTION_STOP = "dev.footprinthere.overtone.STOP"
        const val ACTION_TOGGLE_CLICK_THROUGH = "dev.footprinthere.overtone.TOGGLE_CLICK_THROUGH"
        private const val TAG = "Overtone"
        private const val CHANNEL_ID = "lyrics_overlay"
        private const val NOTI_ID = 1
        private const val LEAD_MS = 250L // 가사를 살짝 앞당겨 표시
        private const val FETCH_DELAY_MS = 400L
        private const val NOT_FOUND = "LRCLIB·벅스·지니·멜론에서 가사를 찾지 못했어요.\n위쪽 ⋯ 메뉴의 '다른 가사'에서 직접 골라 보세요."

        @Volatile
        var instance: LyricsOverlayService? = null
        val running: Boolean get() = instance != null
    }

    private val handler = Handler(Looper.getMainLooper())
    private val pool = Executors.newCachedThreadPool()

    private lateinit var prefs: Prefs
    private lateinit var repo: LyricsRepository
    private lateinit var overlay: LyricsOverlay
    private lateinit var watcher: PlayerWatcher

    /** 곡이 바뀔 때마다 올려서, 지난 곡을 위해 진행 중이던 요청 결과를 버린다. */
    @Volatile
    private var generation = 0
    private var track: Track? = null
    private var lyrics: Lyrics? = null
    private var offsetMs = 0L
    private var hadSession = false
    private var listenerAccessMissing = false

    private val tick = Runnable { syncHighlight() }
    private val fetch = Runnable { fetchLyrics() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        repo = LyricsRepository(this)
        overlay = LyricsOverlay(this, prefs, this)
        watcher = PlayerWatcher(this, handler, ::onPlayerChanged)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_TOGGLE_CLICK_THROUGH -> {
                if (instance == null) {
                    stopSelf()
                } else {
                    prefs.clickThrough = !prefs.clickThrough
                    onSettingsChanged()
                }
                return START_NOT_STICKY
            }
        }
        startForegroundCompat()
        if (!Permissions.hasOverlay(this)) {
            Toast.makeText(this, "'다른 앱 위에 표시' 권한이 필요해요", Toast.LENGTH_LONG).show()
            stopSelf()
            return START_NOT_STICKY
        }
        if (instance == null) {
            instance = this
            listenerAccessMissing = !watcher.start()
            overlay.show()
            onPlayerChanged()
            LyricsTileService.requestUpdate(this)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        generation++
        handler.removeCallbacksAndMessages(null)
        watcher.stop()
        runCatching { overlay.hide() }
        pool.shutdownNow()
        instance = null
        LyricsTileService.requestUpdate(this)
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        overlay.onConfigurationChanged()
    }

    /** 앱 설정 화면이나 알림 버튼에서 설정을 바꿨을 때. */
    fun onSettingsChanged() {
        overlay.applySettings()
        getSystemService(NotificationManager::class.java).notify(NOTI_ID, buildNotification())
    }

    // ---------- 포그라운드 알림 ----------

    private fun startForegroundCompat() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "가사 창", NotificationManager.IMPORTANCE_LOW)
        )
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTI_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTI_ID, buildNotification())
        }
    }

    private fun buildNotification(): Notification {
        fun serviceIntent(action: String, code: Int) = PendingIntent.getService(
            this, code,
            Intent(this, LyricsOverlayService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val icon = Icon.createWithResource(this, R.drawable.ic_tile)
        val through = prefs.clickThrough
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle("가사 창이 떠 있어요")
            .setContentText(if (through) "터치 통과 중이라 가사 창을 누를 수 없어요" else "탭하면 설정 화면이 열려요")
            .setContentIntent(
                PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            )
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(
                    icon, if (through) "터치 통과 끄기" else "터치 통과 켜기",
                    serviceIntent(ACTION_TOGGLE_CLICK_THROUGH, 1),
                ).build()
            )
            .addAction(Notification.Action.Builder(icon, "종료", serviceIntent(ACTION_STOP, 2)).build())
            .build()
    }

    // ---------- 재생 정보 ----------

    private fun onPlayerChanged() {
        val c = watcher.controller
        if (c == null) {
            handler.removeCallbacks(tick)
            handler.removeCallbacks(fetch)
            if (hadSession) {
                // 음악 앱이 종료되면 창을 숨긴다. 다시 재생하면 나타난다.
                overlay.hide()
            } else {
                overlay.setTitle("음악 앱 대기 중")
                overlay.showMessage(
                    if (listenerAccessMissing) "알림 접근 권한을 켜 주세요 (앱 화면에서 설정)"
                    else "음악 앱에서 노래를 재생해 주세요"
                )
            }
            return
        }
        hadSession = true
        overlay.show()

        val md = c.metadata
        val rawTitle = md?.getString(MediaMetadata.METADATA_KEY_TITLE)
        if (md == null || rawTitle.isNullOrBlank()) {
            clearTrack("음악 앱 대기 중", "음악 앱에서 노래를 재생해 주세요")
            return
        }
        if (Track.isLikelyAd(rawTitle)) {
            clearTrack("광고", "광고가 끝나면 가사를 보여 줄게요")
            return
        }
        val rawArtist = md.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: md.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST).orEmpty()
        val t = Track.from(
            rawTitle, rawArtist,
            md.getString(MediaMetadata.METADATA_KEY_ALBUM).orEmpty(),
            md.getLong(MediaMetadata.METADATA_KEY_DURATION),
        )
        if (t.key != track?.key) {
            Log.d(TAG, "새 곡: '$rawTitle' / '$rawArtist' → $t")
            onNewTrack(t)
        } else {
            // 같은 곡이어도 길이가 나중에 채워질 수 있어서 최신 값으로 바꿔 둔다.
            track = t
        }
        syncHighlight()
    }

    private fun clearTrack(title: String, message: String) {
        generation++
        handler.removeCallbacks(tick)
        handler.removeCallbacks(fetch)
        track = null
        lyrics = null
        if (overlay.isPicking) overlay.closePicker()
        overlay.setTitle(title)
        overlay.showMessage(message)
    }

    private fun onNewTrack(t: Track) {
        generation++
        track = t
        lyrics = null
        offsetMs = prefs.offsetMs(t.key)
        if (overlay.isPicking) overlay.closePicker()
        overlay.setTitle(if (t.artist.isBlank()) t.title else "${t.title} — ${t.artist}")
        overlay.setOffset(offsetMs)
        overlay.showMessage("가사를 찾는 중…")
        // 곡을 빠르게 넘기거나 메타데이터가 나눠서 들어올 때 요청이 몰리지 않도록 잠깐 기다린다.
        handler.removeCallbacks(fetch)
        handler.postDelayed(fetch, FETCH_DELAY_MS)
    }

    private fun fetchLyrics() {
        val t = track ?: return
        val gen = generation
        pool.execute {
            val r = runCatching { repo.find(t) { gen != generation } }
                .onFailure { Log.w(TAG, "가사 검색 실패: $t", it) }
                .getOrNull()
            handler.post { if (gen == generation) applyLyrics(r) }
        }
    }

    private fun applyLyrics(r: Lyrics?) {
        lyrics = r
        when {
            r == null -> overlay.showMessage(NOT_FOUND)
            r.lines.isNotEmpty() -> overlay.showSynced(r.lines)
            r.plain != null -> overlay.showPlain(r.plain)
            r.instrumental -> overlay.showMessage("♪ 연주곡이에요")
            else -> overlay.showMessage(NOT_FOUND)
        }
        syncHighlight()
    }

    /** 지금 줄을 표시하고, 재생 중이면 다음 줄이 시작될 때 다시 깨어나도록 예약한다. */
    private fun syncHighlight() {
        handler.removeCallbacks(tick)
        val lines = lyrics?.lines
        val s = watcher.controller?.playbackState
        if (lines.isNullOrEmpty() || s == null || !overlay.isShowing) return

        val pos = position(s) + LEAD_MS + offsetMs
        val idx = lines.indexOfLast { it.timeMs <= pos }
        overlay.highlight(idx)

        if (s.state != PlaybackState.STATE_PLAYING) return
        val next = lines.getOrNull(idx + 1) ?: return
        val speed = s.playbackSpeed.coerceAtLeast(0.1f)
        // 재생 위치가 조금씩 어긋날 수 있어 길어도 1초마다는 다시 맞춘다.
        val delay = ((next.timeMs - pos) / speed).toLong().coerceIn(20L, 1000L)
        handler.postDelayed(tick, delay)
    }

    private fun position(s: PlaybackState): Long {
        var pos = s.position
        if (s.state == PlaybackState.STATE_PLAYING) {
            pos += ((SystemClock.elapsedRealtime() - s.lastPositionUpdateTime) * s.playbackSpeed).toLong()
        }
        return pos
    }

    // ---------- 가사 창 버튼 ----------

    override fun onClose() = stopSelf()

    override fun onOffsetDelta(deltaMs: Long) = setOffset(offsetMs + deltaMs)

    override fun onOffsetReset() = setOffset(0L)

    private fun setOffset(ms: Long) {
        val t = track ?: return
        offsetMs = ms
        prefs.setOffsetMs(t.key, ms)
        overlay.setOffset(ms)
        syncHighlight()
    }

    override fun onPickOther() {
        val t = track ?: return
        val gen = generation
        overlay.showPicker(null, t.durationSec) {}
        pool.execute {
            val list = runCatching { repo.candidates(t) }
                .onFailure { Log.w(TAG, "후보 검색 실패: $t", it) }
                .getOrDefault(emptyList())
            handler.post {
                if (gen != generation || !overlay.isPicking) return@post
                overlay.showPicker(list, t.durationSec) { c -> pick(t, c) }
            }
        }
    }

    private fun pick(t: Track, c: Candidate) {
        val gen = ++generation
        overlay.showMessage("${c.source}에서 가사를 가져오는 중…")
        pool.execute {
            val r = runCatching { repo.pick(t, c) }
                .onFailure { Log.w(TAG, "고른 가사 받기 실패: $c", it) }
                .getOrNull()
            handler.post {
                if (gen != generation) return@post
                if (r == null) overlay.showMessage("${c.source}에서 가사를 가져오지 못했어요.\n'다른 가사'에서 다른 결과를 골라 보세요.")
                else applyLyrics(r)
            }
        }
    }

    override fun onClickThroughChanged() {
        getSystemService(NotificationManager::class.java).notify(NOTI_ID, buildNotification())
    }
}
