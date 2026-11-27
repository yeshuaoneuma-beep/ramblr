package com.trevornk.ramblr

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Actionable, non-debug user alert categories persisted in [AppNotificationStore].
 *
 * Strictly excludes routine successful dictations, transcript text, debug API error logs, and
 * ongoing foreground-service progress notifications.
 */
enum class AppNotificationCategory {
    LIVE_DISCONNECT,
    FALLBACK,
    UPDATE,
    SETUP_REMINDER,
    DICTATION_ALERT,
}

data class AppNotificationEntry(
    val id: String,
    val timestampMs: Long,
    val category: AppNotificationCategory,
    val title: String,
    val body: String,
    val read: Boolean = false,
)

/**
 * Persistent ring buffer of up to [MAX_ENTRIES] newest-first actionable notifications stored in
 * the shared `"ramblr"` SharedPreferences file under [KEY_NOTIFICATIONS].
 */
object AppNotificationStore {
    private const val PREFS_NAME = "ramblr"
    const val KEY_NOTIFICATIONS = "app_notifications_v1"
    const val MAX_ENTRIES = 50

    fun serialize(entries: List<AppNotificationEntry>): String {
        val array = JSONArray()
        entries.take(MAX_ENTRIES).forEach { entry ->
            array.put(
                JSONObject().apply {
                    put("id", entry.id)
                    put("timestampMs", entry.timestampMs)
                    put("category", entry.category.name)
                    put("title", entry.title)
                    put("body", entry.body)
                    put("read", entry.read)
                }
            )
        }
        return array.toString()
    }

    fun deserialize(raw: String?): List<AppNotificationEntry> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { i ->
                val obj = array.optJSONObject(i) ?: return@mapNotNull null
                val id = obj.optString("id", "").trim()
                val title = obj.optString("title", "").trim()
                if (id.isEmpty() || title.isEmpty()) return@mapNotNull null
                val category = try {
                    AppNotificationCategory.valueOf(obj.optString("category", ""))
                } catch (_: IllegalArgumentException) {
                    return@mapNotNull null
                }
                AppNotificationEntry(
                    id = id,
                    timestampMs = obj.optLong("timestampMs", 0L),
                    category = category,
                    title = title,
                    body = obj.optString("body", ""),
                    read = obj.optBoolean("read", false),
                )
            }.take(MAX_ENTRIES)
        } catch (_: Exception) {
            emptyList()
        }
    }

    @Synchronized
    fun all(context: Context): List<AppNotificationEntry> =
        deserialize(prefs(context).getString(KEY_NOTIFICATIONS, null))

    @Synchronized
    fun unreadCount(context: Context): Int = all(context).count { !it.read }

    @Synchronized
    fun record(
        context: Context,
        category: AppNotificationCategory,
        title: String,
        body: String,
        nowMs: Long = System.currentTimeMillis(),
        id: String = UUID.randomUUID().toString(),
        postShadeAlert: Boolean = true,
    ) {
        val cleanTitle = title.trim()
        if (cleanTitle.isEmpty()) return
        val appCtx = context.applicationContext ?: context
        val current = all(appCtx)
        val entry = AppNotificationEntry(
            id = id,
            timestampMs = nowMs,
            category = category,
            title = cleanTitle,
            body = body.trim(),
            read = false,
        )
        val updated = (listOf(entry) + current).take(MAX_ENTRIES)
        prefs(appCtx).edit().putString(KEY_NOTIFICATIONS, serialize(updated)).apply()
        if (postShadeAlert && category != AppNotificationCategory.DICTATION_ALERT) {
            val unread = updated.count { !it.read }
            AppAlertNotifications.postUnread(appCtx, cleanTitle, entry.body, unread)
        }
    }

    @Synchronized
    fun markAllRead(context: Context) {
        val appCtx = context.applicationContext ?: context
        val current = all(appCtx)
        if (current.any { !it.read }) {
            val updated = current.map { if (it.read) it else it.copy(read = true) }
            prefs(appCtx).edit().putString(KEY_NOTIFICATIONS, serialize(updated)).apply()
        }
        AppAlertNotifications.cancelAllSynced(appCtx)
    }

    @Synchronized
    fun clear(context: Context) {
        val appCtx = context.applicationContext ?: context
        prefs(appCtx).edit().remove(KEY_NOTIFICATIONS).apply()
        AppAlertNotifications.cancelAllSynced(appCtx)
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}

/**
 * Syncs actionable in-app [AppNotificationStore] alerts with the Android notification shade and
 * launcher badge, and cancels them cleanly when notifications are read or cleared.
 *
 * Strictly never carries user transcript text.
 */
object AppAlertNotifications {
    const val ALERT_CHANNEL_ID = "rhema_cadence_alerts"
    /** Distinct from 0x1C04..0x1C0A used by other app notifications. */
    const val ALERT_NOTIFICATION_ID = 0x1C0B

    fun ensureChannel(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(ALERT_CHANNEL_ID, "Alerts", NotificationManager.IMPORTANCE_DEFAULT).apply {
                setShowBadge(true)
            }
        )
    }

    fun postUnread(ctx: Context, title: String, body: String, unreadCount: Int) {
        runCatching {
            val nm = NotificationManagerCompat.from(ctx)
            if (!nm.areNotificationsEnabled()) return
            ensureChannel(ctx)
            val intent = Intent(ctx, NotificationsActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val pendingIntent = PendingIntent.getActivity(
                ctx,
                ALERT_NOTIFICATION_ID,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val notification = NotificationCompat.Builder(ctx, ALERT_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_mic)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setNumber(unreadCount.coerceAtLeast(1))
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setContentIntent(pendingIntent)
                .build()
            try {
                nm.notify(ALERT_NOTIFICATION_ID, notification)
            } catch (_: SecurityException) {
            }
        }
    }

    fun cancel(ctx: Context) {
        runCatching {
            try {
                NotificationManagerCompat.from(ctx).cancel(ALERT_NOTIFICATION_ID)
            } catch (_: SecurityException) {
            }
        }
    }

    fun cancelAllSynced(ctx: Context) {
        runCatching {
            try {
                val nm = NotificationManagerCompat.from(ctx)
                nm.cancel(ALERT_NOTIFICATION_ID)
                nm.cancel(BackgroundDictationNotifications.RESULT_ID)
            } catch (_: SecurityException) {
            }
        }
    }
}
