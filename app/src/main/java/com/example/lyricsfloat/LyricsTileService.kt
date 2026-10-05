package com.example.lyricsfloat

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

class LyricsTileService : TileService() {

    companion object {
        fun requestUpdate(ctx: Context) {
            TileService.requestListeningState(
                ctx, ComponentName(ctx, LyricsTileService::class.java)
            )
        }
    }

    override fun onStartListening() {
        setTile(LyricsOverlayService.running)
    }

    override fun onClick() {
        if (LyricsOverlayService.running) {
            LyricsOverlayService.instance?.stopSelf()
            setTile(false)
            return
        }
        if (!Permissions.hasOverlay(this) || !Permissions.hasListenerAccess(this)) {
            openMain()
            return
        }
        try {
            startForegroundService(Intent(this, LyricsOverlayService::class.java))
            setTile(true)
        } catch (e: Exception) {
            openMain()
        }
    }

    private fun setTile(active: Boolean) {
        qsTile?.let {
            it.state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            it.updateTile()
        }
    }

    private fun openMain() {
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(
                PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
            )
        } else {
            @SuppressLint("StartActivityAndCollapseDeprecated")
            startActivityAndCollapse(intent)
        }
    }
}
