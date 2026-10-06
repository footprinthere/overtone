package dev.footprinthere.overtone

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.util.Log

/**
 * 음악 앱들의 재생 세션을 지켜보다가 곡·재생 상태가 바뀌거나 세션이 생기고 사라질 때 [onChange] 를 부른다.
 * 여러 앱이 세션을 갖고 있으면 지금 재생 중인 앱을 따라간다.
 * 주기적으로 묻지 않고 안드로이드가 알려줄 때만 깨어난다.
 */
class PlayerWatcher(
    context: Context,
    private val handler: Handler,
    private val onChange: () -> Unit,
) {
    var controller: MediaController? = null
        private set

    private var watched: List<Pair<MediaController, MediaController.Callback>> = emptyList()

    private val msm = context.getSystemService(MediaSessionManager::class.java)
    private val component = ComponentName(context, LyricsListenerService::class.java)

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { list ->
        watch(list.orEmpty())
    }

    /** '알림 접근' 권한이 없으면 false. */
    fun start(): Boolean = try {
        msm.addOnActiveSessionsChangedListener(sessionsListener, component, handler)
        watch(msm.getActiveSessions(component))
        true
    } catch (e: SecurityException) {
        Log.w(TAG, "알림 접근 권한 없음", e)
        false
    }

    fun stop() {
        runCatching { msm.removeOnActiveSessionsChangedListener(sessionsListener) }
        unwatchAll()
        controller = null
    }

    private fun watch(sessions: List<MediaController>) {
        unwatchAll()
        watched = sessions.filter { it.packageName in MUSIC_PACKAGES }.map { c ->
            c to callbackFor(c).also { c.registerCallback(it, handler) }
        }
        if (!pick()) onChange()
    }

    private fun unwatchAll() {
        watched.forEach { (c, cb) -> c.unregisterCallback(cb) }
        watched = emptyList()
    }

    private fun callbackFor(c: MediaController) = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) {
            if (isCurrent(c)) onChange()
        }

        override fun onPlaybackStateChanged(state: PlaybackState?) {
            if (!pick() && isCurrent(c)) onChange()
        }

        override fun onSessionDestroyed() {
            watched = watched.filterNot { it.first.sessionToken == c.sessionToken }
            pick()
        }
    }

    private fun isCurrent(c: MediaController) = c.sessionToken == controller?.sessionToken

    /** 따라갈 세션을 다시 고른다. 재생 중인 앱 > 지금 따라가던 앱 > 아무 음악 앱 순. 바뀌었으면 [onChange] 를 부르고 true. */
    private fun pick(): Boolean {
        val sessions = watched.map { it.first }
        val next = sessions.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: sessions.firstOrNull(::isCurrent)
            ?: sessions.firstOrNull()
        if (next?.sessionToken == controller?.sessionToken) return false
        controller = next
        Log.d(TAG, "따라가는 앱: ${next?.packageName}")
        onChange()
        return true
    }

    companion object {
        private const val TAG = "Overtone"
        private val MUSIC_PACKAGES = setOf(
            "com.google.android.apps.youtube.music", // 유튜브 뮤직
            "com.iloen.melon", // 멜론
            "com.ktmusic.geniemusic", // 지니
            "com.spotify.music", // 스포티파이
            "com.neowiz.android.bugs", // 벅스
            "skplanet.musicmate", // FLO
            "com.naver.vibe", // VIBE
            "com.apple.android.music", // 애플 뮤직
        )
    }
}
