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
 * 유튜브 뮤직의 재생 세션을 지켜보다가 곡·재생 상태가 바뀌거나 세션이 생기고 사라질 때 [onChange] 를 부른다.
 * 주기적으로 묻지 않고 안드로이드가 알려줄 때만 깨어난다.
 */
class YtmWatcher(
    context: Context,
    private val handler: Handler,
    private val onChange: () -> Unit,
) {
    var controller: MediaController? = null
        private set

    private val msm = context.getSystemService(MediaSessionManager::class.java)
    private val component = ComponentName(context, LyricsListenerService::class.java)

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { list ->
        attach(list?.firstOrNull { it.packageName == YTM_PACKAGE })
    }

    private val callback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = onChange()
        override fun onPlaybackStateChanged(state: PlaybackState?) = onChange()
        override fun onSessionDestroyed() = attach(null)
    }

    /** '알림 접근' 권한이 없으면 false. */
    fun start(): Boolean = try {
        msm.addOnActiveSessionsChangedListener(sessionsListener, component, handler)
        attach(msm.getActiveSessions(component).firstOrNull { it.packageName == YTM_PACKAGE })
        true
    } catch (e: SecurityException) {
        Log.w(TAG, "알림 접근 권한 없음", e)
        false
    }

    fun stop() {
        runCatching { msm.removeOnActiveSessionsChangedListener(sessionsListener) }
        controller?.unregisterCallback(callback)
        controller = null
    }

    private fun attach(c: MediaController?) {
        if (c?.sessionToken == controller?.sessionToken) return
        controller?.unregisterCallback(callback)
        controller = c
        c?.registerCallback(callback, handler)
        onChange()
    }

    companion object {
        private const val TAG = "LyricsFloat"
        private const val YTM_PACKAGE = "com.google.android.apps.youtube.music"
    }
}
