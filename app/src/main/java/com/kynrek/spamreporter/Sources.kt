package com.kynrek.spamreporter

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings

/**
 * Optional message sources. Each one is a service the app can switch off entirely (component disabled),
 * so when it's off Android doesn't list it under Accessibility / Notification access and it can't run at all.
 *  - Spam-folder scan: Accessibility access, reads Google Messages' Spam & blocked folder (incl. RCS). Off by default.
 *  - Live capture: Notification access, saves new texts as they arrive and FCC ticket numbers from Gmail. On by default.
 */
object Sources {
    private fun cn(ctx: Context, c: Class<*>) = ComponentName(ctx, c)
    private fun state(ctx: Context, c: Class<*>) = ctx.packageManager.getComponentEnabledSetting(cn(ctx, c))
    private fun set(ctx: Context, c: Class<*>, on: Boolean) = ctx.packageManager.setComponentEnabledSetting(cn(ctx, c),
        if (on) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
        PackageManager.DONT_KILL_APP)

    /** Manifest default is disabled, so it's on only if the user turned it on in Settings. */
    fun folderScan(ctx: Context) = state(ctx, MessagesAccessibilityService::class.java) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
    fun setFolderScan(ctx: Context, on: Boolean) = set(ctx, MessagesAccessibilityService::class.java, on)

    /** Manifest default is enabled. */
    fun capture(ctx: Context) = state(ctx, CaptureNotificationListener::class.java) != PackageManager.COMPONENT_ENABLED_STATE_DISABLED
    fun setCapture(ctx: Context, on: Boolean) = set(ctx, CaptureNotificationListener::class.java, on)

    /** Bulk text blocking (Accessibility, phone's Blocked numbers screen only). Off by default. */
    fun bulkBlock(ctx: Context) = state(ctx, BlockListAccessibilityService::class.java) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
    fun setBulkBlock(ctx: Context, on: Boolean) = set(ctx, BlockListAccessibilityService::class.java, on)
    fun bulkBlockGranted(ctx: Context) = a11yGranted(ctx, BlockListAccessibilityService::class.java)

    fun a11yGranted(ctx: Context, svc: Class<*> = MessagesAccessibilityService::class.java): Boolean {
        val want = cn(ctx, svc).flattenToString()
        return (Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: "").split(':').any { it.equals(want, true) }
    }
    fun listenerGranted(ctx: Context): Boolean {
        val want = cn(ctx, CaptureNotificationListener::class.java).flattenToString()
        return (Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners") ?: "").split(':').any { it.equals(want, true) }
    }
}
