package com.example.lyricsfloat

import android.content.ComponentName
import android.content.Context
import android.provider.Settings

object Permissions {
    fun hasOverlay(ctx: Context): Boolean = Settings.canDrawOverlays(ctx)

    fun hasListenerAccess(ctx: Context): Boolean {
        val flat = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners")
            ?: return false
        return flat.split(":").any {
            ComponentName.unflattenFromString(it)?.packageName == ctx.packageName
        }
    }
}
