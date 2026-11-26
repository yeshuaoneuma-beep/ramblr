package com.trevornk.ramblr

import android.content.Context
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
    }

    @Synchronized
    fun markAllRead(context: Context) {
        val appCtx = context.applicationContext ?: context
        val current = all(appCtx)
        if (current.none { !it.read }) return
        val updated = current.map { if (it.read) it else it.copy(read = true) }
        prefs(appCtx).edit().putString(KEY_NOTIFICATIONS, serialize(updated)).apply()
    }

    @Synchronized
    fun clear(context: Context) {
        val appCtx = context.applicationContext ?: context
        prefs(appCtx).edit().remove(KEY_NOTIFICATIONS).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
