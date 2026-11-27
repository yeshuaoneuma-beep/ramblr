package com.trevornk.ramblr

import android.content.Context
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Dedicated actionable Notifications screen (Phase 3A.3).
 *
 * Displays non-debug actionable alerts from [AppNotificationStore] (live disconnects, cloud/local
 * fallbacks, updates, setup reminders, failed jobs/downloads), marks all read on resume, and
 * offers a flat top-bar `"Clear all"` action.
 */
class NotificationsActivity : BaseSettingsActivity() {

    private lateinit var listContainer: LinearLayout
    private lateinit var clearAllButton: TextView

    private val timestampFormat by lazy {
        SimpleDateFormat("MMM d, h:mm a", Locale.getDefault())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        listContainer = vertical(0, 0)
        clearAllButton = TextView(this).apply {
            text = "Clear all"
            textSize = 14f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(attrColor(com.google.android.material.R.attr.colorPrimary))
            setPadding(dp(10), dp(8), dp(4), dp(8))
            isClickable = true
            isFocusable = true
            OneUiComponents.applySelectableRipple(this)
            setOnClickListener {
                UiTapHaptics.tick(it)
                AppNotificationStore.clear(this@NotificationsActivity)
                toast("Notifications cleared")
                render()
            }
        }

        setOneUiScreenContent(
            title = "Notifications",
            showBack = true,
            icon = R.drawable.ic_settings_notifications,
            trailingAction = clearAllButton,
            content = listContainer,
        )

        AppNotificationStore.markAllRead(this)
        render()
    }

    override fun onResume() {
        super.onResume()
        AppNotificationStore.markAllRead(this)
        render()
    }

    private fun render() {
        listContainer.removeAllViews()
        val entries = AppNotificationStore.all(this)
        clearAllButton.visibility = if (entries.isEmpty()) View.GONE else View.VISIBLE

        if (entries.isEmpty()) {
            listContainer.addView(TextView(this).apply {
                text = "No notifications yet"
                textSize = 15f
                setTextColor(attrColor(android.R.attr.textColorSecondary))
                setPadding(dp(24), dp(28), dp(24), dp(24))
            })
            return
        }

        val primaryText = attrColor(android.R.attr.textColorPrimary)
        val secondaryText = attrColor(android.R.attr.textColorSecondary)
        val accent = attrColor(com.google.android.material.R.attr.colorPrimary)

        entries.forEachIndexed { idx, entry ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(24), dp(14), dp(24), dp(14))
            }
            val metaRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            metaRow.addView(TextView(this).apply {
                text = categoryLabel(entry.category)
                textSize = 11f
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                setTextColor(accent)
                layoutParams = LinearLayout.LayoutParams(0, LP_WRAP, 1f)
            })
            metaRow.addView(TextView(this).apply {
                text = timestampFormat.format(Date(entry.timestampMs))
                textSize = 11f
                setTextColor(secondaryText)
            })
            row.addView(metaRow)

            row.addView(TextView(this).apply {
                text = entry.title
                textSize = 16f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(primaryText)
                setPadding(0, dp(4), 0, 0)
            })
            if (entry.body.isNotBlank()) {
                row.addView(TextView(this).apply {
                    text = entry.body
                    textSize = 14f
                    setLineSpacing(dp(2).toFloat(), 1f)
                    setTextColor(secondaryText)
                    setPadding(0, dp(3), 0, 0)
                })
            }
            listContainer.addView(row)
            if (idx < entries.lastIndex) {
                listContainer.addView(hairlineDivider(24, 24))
            }
        }
    }

    private fun categoryLabel(category: AppNotificationCategory): String = when (category) {
        AppNotificationCategory.LIVE_DISCONNECT -> "Live disconnect"
        AppNotificationCategory.FALLBACK -> "Fallback"
        AppNotificationCategory.UPDATE -> "Update"
        AppNotificationCategory.SETUP_REMINDER -> "Setup"
        AppNotificationCategory.DICTATION_ALERT -> "Alert"
    }

    companion object {
        fun subtitle(context: Context): String {
            val all = AppNotificationStore.all(context)
            return notificationsMainRowSubtitleText(
                totalCount = all.size,
                unreadCount = all.count { !it.read },
            )
        }
    }
}
