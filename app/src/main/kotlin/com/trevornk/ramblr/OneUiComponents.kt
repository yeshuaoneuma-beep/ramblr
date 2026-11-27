package com.trevornk.ramblr

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.annotation.ColorRes
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog

/**
 * Programmatic sleek hairline UI components & unified `#141418` pop-up surfaces (Phase 3A.3).
 *
 * Uses zero XML layouts, zero `RadioButton`/`RadioGroup` widgets, and matches the exact Home
 * screen hairline aesthetic (`1px` `withAlpha(textColorPrimary, 0x40)` dividers, `@color/oneui_canvas`,
 * `@color/oneui_accent_cobalt`).
 */
object OneUiComponents {

    private const val LP_MATCH = LinearLayout.LayoutParams.MATCH_PARENT
    private const val LP_WRAP = LinearLayout.LayoutParams.WRAP_CONTENT

    fun dp(context: Context, n: Int): Int =
        (n * context.resources.displayMetrics.density).toInt()

    fun dpFloat(context: Context, n: Float): Int =
        (n * context.resources.displayMetrics.density).toInt().coerceAtLeast(1)

    fun resColor(context: Context, @ColorRes resId: Int): Int =
        ContextCompat.getColor(context, resId)

    fun attrColor(context: Context, attr: Int): Int {
        val ta = context.obtainStyledAttributes(intArrayOf(attr))
        val color = ta.getColor(0, Color.WHITE)
        ta.recycle()
        return color
    }

    fun withAlpha(color: Int, alpha: Int): Int = (color and 0x00FFFFFF) or (alpha shl 24)

    fun hairlineColor(context: Context): Int =
        withAlpha(attrColor(context, android.R.attr.textColorPrimary), 0x40)

    /** Crisp 1px hairline divider matching the Home screen inset-divider formula. */
    fun hairlineDivider(context: Context, insetStartDp: Int = 24, insetEndDp: Int = 24): View =
        View(context).apply {
            layoutParams = LinearLayout.LayoutParams(LP_MATCH, 1).apply {
                marginStart = dp(context, insetStartDp)
                marginEnd = dp(context, insetEndDp)
            }
            setBackgroundColor(hairlineColor(context))
        }

    fun applySelectableRipple(view: View) {
        val outValue = TypedValue()
        view.context.theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
        view.setBackgroundResource(outValue.resourceId)
    }

    /** Flat bottom-underlined EditText background (zero boxy containers). */
    fun flatUnderlineBg(context: Context, color: Int = resColor(context, R.color.popup_border)): LayerDrawable =
        LayerDrawable(arrayOf(ColorDrawable(color))).apply {
            setLayerGravity(0, Gravity.BOTTOM or Gravity.FILL_HORIZONTAL)
            setLayerHeight(0, dp(context, 1).coerceAtLeast(1))
        }

    fun roundedBg(
        fillColor: Int,
        radiusPx: Float,
        strokeWidthPx: Int = 0,
        strokeColor: Int = 0,
    ): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = radiusPx
        setColor(fillColor)
        if (strokeWidthPx > 0) setStroke(strokeWidthPx, strokeColor)
    }

    /**
     * Sleek circular lock indicator: `34dp x 40dp` transparent touch target containing a centered
     * `18dp x 18dp` empty round circle (NO `✓`, NO `○`).
     *  - Off: `1.5dp` muted grey ring
     *  - Locked: solid `@color/oneui_locked_green`
     */
    fun lockCircle(
        context: Context,
        locked: Boolean,
        enabled: Boolean = true,
        contentDescription: String,
        onClick: () -> Unit,
    ): FrameLayout {
        val dot = View(context).apply {
            layoutParams = FrameLayout.LayoutParams(dp(context, 18), dp(context, 18), Gravity.CENTER)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                if (locked && enabled) {
                    setColor(resColor(context, R.color.oneui_locked_green))
                } else {
                    setColor(Color.TRANSPARENT)
                    setStroke(dpFloat(context, 1.5f), resColor(context, R.color.oneui_unlocked_grey))
                }
            }
        }
        return FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(context, 34), dp(context, 40))
            this.contentDescription = contentDescription
            tag = if (locked && enabled) "locked" else "unlocked"
            isSelected = locked && enabled
            isEnabled = enabled
            isClickable = enabled
            isFocusable = enabled
            alpha = if (enabled) 1f else 0.4f
            addView(dot)
            if (enabled) setOnClickListener {
                UiTapHaptics.tick(this)
                onClick()
            }
        }
    }

    /**
     * Sleek circular selection indicator replacing EVERY `RadioButton`/`MaterialRadioButton`:
     * `36dp x 40dp` transparent touch target containing a centered `18dp x 18dp` empty round circle.
     *  - Off: `1.5dp` muted grey ring
     *  - Selected: solid `colorPrimary` (`@color/oneui_accent_cobalt`)
     */
    fun selectionDot(
        context: Context,
        selected: Boolean,
        contentDescription: String? = null,
    ): FrameLayout {
        val dot = View(context).apply {
            tag = "dot_circle"
            layoutParams = FrameLayout.LayoutParams(dp(context, 18), dp(context, 18), Gravity.CENTER)
        }
        return FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(context, 36), dp(context, 40))
            if (!contentDescription.isNullOrBlank()) this.contentDescription = contentDescription
            addView(dot)
            setSelectionDotChecked(this, selected)
        }
    }

    /** Updates a [selectionDot] indicator's checked state in place. */
    fun setSelectionDotChecked(container: FrameLayout, selected: Boolean) {
        val context = container.context
        val circle = container.findViewWithTag<View>("dot_circle") ?: container.getChildAt(0)
        circle?.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            if (selected) {
                setColor(attrColor(context, com.google.android.material.R.attr.colorPrimary))
            } else {
                setColor(Color.TRANSPARENT)
                setStroke(dpFloat(context, 1.5f), resColor(context, R.color.oneui_unlocked_grey))
            }
        }
        container.isSelected = selected
        container.tag = if (selected) "selected" else "unselected"
    }

    /**
     * Circular attempt badge: `34dp x 40dp` transparent touch target containing a centered
     * `22dp x 22dp` solid `colorPrimary` circle with `11sp` bold white `"1"`, `"2"`, or `"3"`
     * (`includeFontPadding = false`, NO `"x"` suffix).
     */
    fun attemptBadge(
        context: Context,
        attempts: Int,
        allowedAttempts: List<Int>,
        enabled: Boolean = true,
        onSelect: (Int) -> Unit,
    ): FrameLayout {
        val accent = attrColor(context, com.google.android.material.R.attr.colorPrimary)
        val muted = resColor(context, R.color.oneui_unlocked_grey)
        val clamped = attempts.coerceIn(
            AttemptBudgetCalculator.MIN_SLOT_ATTEMPTS,
            AttemptBudgetCalculator.MAX_SLOT_ATTEMPTS,
        )
        val circle = TextView(context).apply {
            text = clamped.toString()
            textSize = 11f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            includeFontPadding = false
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (enabled) accent else muted)
            }
            layoutParams = FrameLayout.LayoutParams(dp(context, 22), dp(context, 22), Gravity.CENTER)
        }
        return FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(context, 34), dp(context, 40))
            contentDescription = "Attempts: $clamped"
            isEnabled = enabled
            isClickable = enabled
            isFocusable = enabled
            alpha = if (enabled) 1f else 0.4f
            addView(circle)
            if (enabled) {
                setOnClickListener {
                    UiTapHaptics.tick(this)
                    showAttemptsSelectorSheet(context, clamped, allowedAttempts, onSelect)
                }
            }
        }
    }

    /**
     * Unread count badge for Home screen rows (Notifications, History, Diagnostics):
     * `18dp` height, `18dp` minWidth circle (`9dp` radius, `@color/badge_unread_red`),
     * `10sp` bold white count (`includeFontPadding = false`), `0dp` horizontal padding for 1 digit
     * and `5dp` horizontal padding for 2+ digits (`"99+"` cap), `View.GONE` when `0`.
     */
    fun unreadBadge(context: Context, count: Int = 0): TextView = TextView(context).apply {
        tag = "unread_badge"
        textSize = 10f
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        includeFontPadding = false
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        minWidth = dp(context, 18)
        minimumHeight = dp(context, 18)
        background = roundedBg(resColor(context, R.color.badge_unread_red), dp(context, 9).toFloat())
        layoutParams = LinearLayout.LayoutParams(LP_WRAP, dp(context, 18))
        updateUnreadBadge(this, count)
    }

    fun updateUnreadBadge(badge: TextView, count: Int) {
        if (count <= 0) {
            badge.visibility = View.GONE
            badge.text = ""
            return
        }
        val label = if (count > 99) "99+" else count.toString()
        val padH = if (label.length <= 1) 0 else dp(badge.context, 5)
        badge.setPadding(padH, 0, padH, 0)
        badge.text = label
        badge.visibility = View.VISIBLE
    }

    /**
     * Flat hairline single-choice `android.app.AlertDialog` replacing `setSingleChoiceItems`
     * app-wide so platform radio buttons never render anywhere in the app.
     */
    fun flatSingleChoiceDialog(
        context: Context,
        title: String,
        items: List<String>,
        checkedIndex: Int,
        message: String? = null,
        negativeLabel: String = "Cancel",
        onCancel: (() -> Unit)? = null,
        onSelect: (Int) -> Unit,
    ): AlertDialog {
        val primaryText = attrColor(context, android.R.attr.textColorPrimary)
        val secondaryText = attrColor(context, android.R.attr.textColorSecondary)
        val accent = attrColor(context, com.google.android.material.R.attr.colorPrimary)

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(context, 4), 0, dp(context, 4))
        }
        if (!message.isNullOrBlank()) {
            container.addView(TextView(context).apply {
                text = message
                textSize = 13f
                setLineSpacing(dp(context, 2).toFloat(), 1f)
                setTextColor(secondaryText)
                setPadding(dp(context, 24), dp(context, 4), dp(context, 24), dp(context, 12))
            })
            container.addView(hairlineDivider(context, insetStartDp = 24, insetEndDp = 24))
        }

        lateinit var dialog: AlertDialog
        items.forEachIndexed { index, label ->
            val selected = index == checkedIndex
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(context, 24), dp(context, 12), dp(context, 16), dp(context, 12))
                minimumHeight = dp(context, 48)
                isClickable = true
                isFocusable = true
                applySelectableRipple(this)
                setOnClickListener {
                    UiTapHaptics.tick(this)
                    dialog.dismiss()
                    onSelect(index)
                }
            }
            row.addView(TextView(context).apply {
                tag = "choice_label"
                text = label
                textSize = 15f
                typeface = Typeface.create("sans-serif-medium", if (selected) Typeface.BOLD else Typeface.NORMAL)
                setTextColor(if (selected) accent else primaryText)
                layoutParams = LinearLayout.LayoutParams(0, LP_WRAP, 1f).apply {
                    marginEnd = dp(context, 12)
                }
            })
            row.addView(selectionDot(context, selected))
            container.addView(row)
            if (index < items.lastIndex) {
                container.addView(hairlineDivider(context, insetStartDp = 24, insetEndDp = 24))
            }
        }

        val scroll = ScrollView(context).apply { addView(container) }
        val builder = AlertDialog.Builder(context)
            .setTitle(title)
            .setView(scroll)
            .setNegativeButton(negativeLabel) { _, _ -> onCancel?.invoke() }
        if (onCancel != null) {
            builder.setOnCancelListener { onCancel() }
        }
        dialog = builder.show()
        dialog.window?.setDimAmount(0.6f)
        return dialog
    }

    private fun showAttemptsSelectorSheet(
        context: Context,
        currentAttempts: Int,
        allowedAttempts: List<Int>,
        onSelect: (Int) -> Unit,
    ) {
        val dialog = BottomSheetDialog(context)
        val primaryText = attrColor(context, android.R.attr.textColorPrimary)
        val secondaryText = attrColor(context, android.R.attr.textColorSecondary)
        val accent = attrColor(context, com.google.android.material.R.attr.colorPrimary)

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_bottom_sheet_surface)
            setPadding(0, dp(context, 18), 0, dp(context, 20))
        }

        root.addView(TextView(context).apply {
            text = "Select attempts"
            textSize = 18f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(primaryText)
            setPadding(dp(context, 24), 0, dp(context, 24), dp(context, 4))
        })
        root.addView(TextView(context).apply {
            text = "Locked slots across this lane share a 3-attempt pool. 1 = 1 call total."
            textSize = 13f
            setTextColor(secondaryText)
            setPadding(dp(context, 24), 0, dp(context, 24), dp(context, 12))
        })
        root.addView(hairlineDivider(context, insetStartDp = 24, insetEndDp = 24))

        val range = AttemptBudgetCalculator.MIN_SLOT_ATTEMPTS..AttemptBudgetCalculator.MAX_SLOT_ATTEMPTS
        for (count in range) {
            val isAllowed = count in allowedAttempts
            val isSelected = count == currentAttempts
            val desc = when (count) {
                1 -> "1 call total"
                2 -> "Up to 2 calls total on transient error"
                else -> "Up to 3 calls total (uses full 3/3 pool)"
            }
            val option = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(context, 24), dp(context, 14), dp(context, 16), dp(context, 14))
                alpha = if (isAllowed) 1f else 0.4f
                isEnabled = isAllowed
                isClickable = isAllowed
                isFocusable = isAllowed
                if (isAllowed) {
                    applySelectableRipple(this)
                    setOnClickListener {
                        UiTapHaptics.tick(this)
                        dialog.dismiss()
                        onSelect(count)
                    }
                }
            }
            val col = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LP_WRAP, 1f).apply {
                    marginEnd = dp(context, 12)
                }
            }
            col.addView(TextView(context).apply {
                text = if (count == 1) "1 attempt" else "$count attempts"
                textSize = 15f
                typeface = Typeface.create("sans-serif-medium", if (isSelected) Typeface.BOLD else Typeface.NORMAL)
                setTextColor(if (isSelected) accent else primaryText)
            })
            col.addView(TextView(context).apply {
                text = if (isAllowed) desc else "Not enough remaining attempts in 3/3 pool"
                textSize = 12f
                setTextColor(secondaryText)
                setPadding(0, dp(context, 2), 0, 0)
            })
            option.addView(col)
            option.addView(selectionDot(context, isSelected))
            root.addView(option)
            if (count < range.last) {
                root.addView(hairlineDivider(context, insetStartDp = 24, insetEndDp = 24))
            }
        }

        val baseBottom = dp(context, 20)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val navBottom = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
            v.setPadding(0, dp(context, 18), 0, baseBottom + navBottom)
            insets
        }

        dialog.setContentView(root)
        dialog.setOnShowListener {
            dialog.window?.setDimAmount(0.6f)
            dialog.window?.navigationBarColor = resColor(context, R.color.popup_surface)
            val sheet = dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            if (sheet != null) {
                sheet.setBackgroundColor(Color.TRANSPARENT)
                val behavior = BottomSheetBehavior.from(sheet)
                behavior.state = BottomSheetBehavior.STATE_EXPANDED
                behavior.skipCollapsed = true
            }
            ViewCompat.requestApplyInsets(root)
        }
        dialog.show()
    }

    /**
     * Flat hairline tap-to-open selector row (NO rounded boxes, NO `▾` arrows, NO pills).
     * Optional compact 11sp status caption (e.g. green `"Active"` or muted `"No key in vault"`)
     * sits inline beside the label.
     */
    fun flatSelectorContent(
        context: Context,
        label: String,
        valueText: String?,
        statusText: String? = null,
        statusColor: Int? = null,
        placeholder: String = "Select...",
        enabled: Boolean = true,
        onClick: () -> Unit,
    ): LinearLayout {
        val primaryText = attrColor(context, android.R.attr.textColorPrimary)
        val secondaryText = attrColor(context, android.R.attr.textColorSecondary)

        val col = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            minimumHeight = dp(context, 44)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(context, 6), dp(context, 8), dp(context, 6))
            isEnabled = enabled
            isClickable = enabled
            isFocusable = enabled
            alpha = if (enabled) 1f else 0.45f
            if (enabled) {
                applySelectableRipple(this)
                setOnClickListener {
                    UiTapHaptics.tick(this)
                    onClick()
                }
            }
        }

        val labelRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        if (label.isNotBlank()) {
            labelRow.addView(TextView(context).apply {
                text = label
                textSize = 12f
                setTextColor(secondaryText)
            })
        }
        if (!statusText.isNullOrBlank()) {
            labelRow.addView(TextView(context).apply {
                text = if (label.isNotBlank()) " \u00b7 $statusText" else statusText
                textSize = 11f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(statusColor ?: secondaryText)
            })
        }
        if (labelRow.childCount > 0) col.addView(labelRow)

        val hasValue = !valueText.isNullOrBlank()
        col.addView(TextView(context).apply {
            text = if (hasValue) valueText else placeholder
            textSize = 15f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(if (hasValue) primaryText else secondaryText)
            setPadding(0, dp(context, 2), 0, 0)
        })

        return col
    }

    /**
     * Flat hairline Execution Order strip with plain-text `Pool X/3` indicator (NO boxes, NO pills).
     */
    fun executionOrderRow(
        context: Context,
        title: String,
        orderText: String,
        usedAttempts: Int,
        maxAttempts: Int = AttemptBudgetCalculator.MAX_CLOUD_ATTEMPTS_PER_LANE,
    ): LinearLayout {
        val primaryText = attrColor(context, android.R.attr.textColorPrimary)
        val secondaryText = attrColor(context, android.R.attr.textColorSecondary)
        val fullPool = usedAttempts >= maxAttempts
        val poolColor = if (fullPool) resColor(context, R.color.oneui_locked_green)
        else secondaryText

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 24), dp(context, 10), dp(context, 24), dp(context, 10))
        }

        val headerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        headerRow.addView(TextView(context).apply {
            text = title
            textSize = 13f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(secondaryText)
            layoutParams = LinearLayout.LayoutParams(0, LP_WRAP, 1f)
        })
        headerRow.addView(TextView(context).apply {
            text = "Pool $usedAttempts/$maxAttempts"
            textSize = 12f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(poolColor)
        })
        container.addView(headerRow)

        container.addView(TextView(context).apply {
            text = orderText
            textSize = 14f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(primaryText)
            setPadding(0, dp(context, 3), 0, 0)
        })

        return container
    }

    /** Flat hairline action row (e.g. `+ Add provider`, `+ Add cleanup slot`, `+ Add Custom OpenAI-Compatible`). */
    fun flatActionRow(
        context: Context,
        label: String,
        subtitle: String? = null,
        enabled: Boolean = true,
        titleTag: Boolean = false,
        onClick: () -> Unit,
    ): LinearLayout {
        val accent = attrColor(context, com.google.android.material.R.attr.colorPrimary)
        val secondaryText = attrColor(context, android.R.attr.textColorSecondary)
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 24), dp(context, 16), dp(context, 24), dp(context, 16))
            minimumHeight = dp(context, 52)
            isEnabled = enabled
            isClickable = enabled
            isFocusable = enabled
            alpha = if (enabled) 1f else 0.45f
            if (enabled) {
                applySelectableRipple(this)
                setOnClickListener {
                    UiTapHaptics.tick(this)
                    onClick()
                }
            }
        }
        row.addView(TextView(context).apply {
            if (titleTag) tag = "title"
            text = label
            textSize = 16f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(if (enabled) accent else secondaryText)
        })
        if (!subtitle.isNullOrBlank()) {
            row.addView(TextView(context).apply {
                if (titleTag) tag = "subtitle"
                text = subtitle
                textSize = 13f
                setTextColor(secondaryText)
                setPadding(0, dp(context, 3), 0, 0)
            })
        }
        return row
    }

    /**
     * Reusable keyboard-safe flat-hairline searchable BottomSheetDialog picker for providers & models.
     */
    object SearchablePickerSheet {

        data class PickerItem(
            val id: String,
            val title: String,
            val subtitle: String? = null,
            val metaLine: String? = null,
            val badges: List<String> = emptyList(),
            val selected: Boolean = false,
            val enabled: Boolean = true,
            val searchTokens: String = "",
        )

        fun show(
            context: Context,
            title: String,
            items: List<PickerItem>,
            searchHint: String = "Search...",
            skipOptionLabel: String? = null,
            skipSelected: Boolean = false,
            onSkipSelected: (() -> Unit)? = null,
            onRefreshRequested: ((onDone: (List<PickerItem>, String?) -> Unit) -> Unit)? = null,
            allowCustomModelId: Boolean = false,
            customModelWarning: ((String) -> String?)? = null,
            onSelect: (PickerItem) -> Unit,
            onCustomIdSelected: ((String) -> Unit)? = null,
        ): BottomSheetDialog {
            val dialog = BottomSheetDialog(context)
            dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

            val accent = attrColor(context, com.google.android.material.R.attr.colorPrimary)
            val warningColor = resColor(context, R.color.oneui_warning_amber)
            val primaryText = attrColor(context, android.R.attr.textColorPrimary)
            val secondaryText = attrColor(context, android.R.attr.textColorSecondary)

            var currentItems = items

            val root = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundResource(R.drawable.bg_bottom_sheet_surface)
                setPadding(0, dp(context, 18), 0, dp(context, 16))
            }

            // Header + optional Refresh live models flat text action
            val headerRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(context, 24), 0, dp(context, 20), dp(context, 10))
            }
            headerRow.addView(TextView(context).apply {
                text = title
                textSize = 18f
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                setTextColor(primaryText)
                layoutParams = LinearLayout.LayoutParams(0, LP_WRAP, 1f)
            })

            val refreshSpinner = ProgressBar(context).apply {
                visibility = View.GONE
                layoutParams = LinearLayout.LayoutParams(dp(context, 18), dp(context, 18)).apply {
                    marginEnd = dp(context, 6)
                }
            }
            val refreshStatus = TextView(context).apply {
                textSize = 12f
                setTextColor(secondaryText)
                visibility = View.GONE
                setPadding(dp(context, 24), 0, dp(context, 24), dp(context, 6))
            }

            lateinit var renderList: (String) -> Unit
            lateinit var searchInput: EditText

            if (onRefreshRequested != null) {
                headerRow.addView(refreshSpinner)
                val refreshBtn = TextView(context).apply {
                    text = "Refresh"
                    textSize = 13f
                    typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                    setTextColor(accent)
                    setPadding(dp(context, 8), dp(context, 6), dp(context, 4), dp(context, 6))
                    applySelectableRipple(this)
                    setOnClickListener { btn ->
                        btn.isEnabled = false
                        refreshSpinner.visibility = View.VISIBLE
                        refreshStatus.visibility = View.GONE
                        onRefreshRequested { updatedItems, statusMsg ->
                            btn.post {
                                btn.isEnabled = true
                                refreshSpinner.visibility = View.GONE
                                if (updatedItems.isNotEmpty()) {
                                    currentItems = updatedItems
                                }
                                if (!statusMsg.isNullOrBlank()) {
                                    refreshStatus.text = statusMsg
                                    refreshStatus.visibility = View.VISIBLE
                                }
                                renderList(searchInput.text?.toString().orEmpty())
                            }
                        }
                    }
                }
                headerRow.addView(refreshBtn)
            }
            root.addView(headerRow)
            root.addView(refreshStatus)

            // Flat underlined search input
            searchInput = EditText(context).apply {
                hint = searchHint
                textSize = 15f
                setTextColor(primaryText)
                setHintTextColor(secondaryText)
                isSingleLine = true
                background = flatUnderlineBg(context)
                setPadding(dp(context, 4), dp(context, 8), dp(context, 4), dp(context, 10))
                layoutParams = LinearLayout.LayoutParams(LP_MATCH, LP_WRAP).apply {
                    marginStart = dp(context, 24)
                    marginEnd = dp(context, 24)
                    bottomMargin = dp(context, 8)
                }
            }
            root.addView(searchInput)

            // Optional pinned Skip / None row (flat hairline + selectionDot)
            if (skipOptionLabel != null && onSkipSelected != null) {
                val skipRow = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(context, 24), dp(context, 12), dp(context, 16), dp(context, 12))
                    applySelectableRipple(this)
                    setOnClickListener {
                        UiTapHaptics.tick(this)
                        dialog.dismiss()
                        onSkipSelected()
                    }
                }
                skipRow.addView(TextView(context).apply {
                    text = skipOptionLabel
                    textSize = 14f
                    typeface = Typeface.create("sans-serif-medium", if (skipSelected) Typeface.BOLD else Typeface.NORMAL)
                    setTextColor(if (skipSelected) accent else primaryText)
                    layoutParams = LinearLayout.LayoutParams(0, LP_WRAP, 1f).apply {
                        marginEnd = dp(context, 12)
                    }
                })
                skipRow.addView(selectionDot(context, skipSelected))
                root.addView(skipRow)
                root.addView(hairlineDivider(context, insetStartDp = 24, insetEndDp = 24))
            }

            // Scrollable flat hairline items list
            val listContainer = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
            }
            val maxScrollHeight = (context.resources.displayMetrics.heightPixels * 0.50f).toInt().coerceAtLeast(dp(context, 240))
            val scrollView = ScrollView(context).apply {
                layoutParams = LinearLayout.LayoutParams(LP_MATCH, LP_WRAP).apply {
                    weight = 1f
                }
                addView(listContainer)
            }
            val scrollHost = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(LP_MATCH, maxScrollHeight)
                addView(scrollView)
            }
            root.addView(scrollHost)

            renderList = { rawQuery ->
                val q = rawQuery.trim().lowercase()
                listContainer.removeAllViews()
                val filtered = if (q.isEmpty()) {
                    currentItems
                } else {
                    currentItems.filter { item ->
                        q in item.title.lowercase() ||
                            q in item.id.lowercase() ||
                            (item.subtitle != null && q in item.subtitle.lowercase()) ||
                            (item.metaLine != null && q in item.metaLine.lowercase()) ||
                            item.badges.any { q in it.lowercase() } ||
                            q in item.searchTokens.lowercase()
                    }
                }
                if (filtered.isEmpty()) {
                    listContainer.addView(TextView(context).apply {
                        text = "No matching items."
                        textSize = 14f
                        setTextColor(secondaryText)
                        setPadding(dp(context, 24), dp(context, 16), dp(context, 24), dp(context, 16))
                    })
                } else {
                    filtered.forEachIndexed { idx, item ->
                        val row = LinearLayout(context).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = Gravity.CENTER_VERTICAL
                            setPadding(dp(context, 24), dp(context, 12), dp(context, 16), dp(context, 12))
                            alpha = if (item.enabled) 1f else 0.45f
                            isEnabled = item.enabled
                            isClickable = item.enabled
                            isFocusable = item.enabled
                            if (item.enabled) {
                                applySelectableRipple(this)
                                setOnClickListener {
                                    UiTapHaptics.tick(this)
                                    dialog.dismiss()
                                    onSelect(item)
                                }
                            }
                        }
                        val textCol = LinearLayout(context).apply {
                            orientation = LinearLayout.VERTICAL
                            layoutParams = LinearLayout.LayoutParams(0, LP_WRAP, 1f).apply {
                                marginEnd = dp(context, 12)
                            }
                        }
                        textCol.addView(TextView(context).apply {
                            text = item.title
                            textSize = 15f
                            typeface = Typeface.create("sans-serif-medium", if (item.selected) Typeface.BOLD else Typeface.NORMAL)
                            setTextColor(if (item.selected) accent else primaryText)
                        })

                        val metaSummary = buildList {
                            if (!item.metaLine.isNullOrBlank()) add(item.metaLine)
                            if (item.badges.isNotEmpty()) add(item.badges.take(3).joinToString(" \u00b7 "))
                        }.joinToString(" \u00b7 ")
                        if (metaSummary.isNotBlank()) {
                            textCol.addView(TextView(context).apply {
                                text = metaSummary
                                textSize = 12f
                                setTextColor(accent)
                                setPadding(0, dp(context, 2), 0, 0)
                            })
                        }

                        if (!item.subtitle.isNullOrBlank()) {
                            textCol.addView(TextView(context).apply {
                                text = item.subtitle
                                textSize = 12f
                                maxLines = 2
                                ellipsize = TextUtils.TruncateAt.END
                                setTextColor(secondaryText)
                                setPadding(0, dp(context, 2), 0, 0)
                            })
                        }
                        row.addView(textCol)
                        row.addView(selectionDot(context, item.selected))
                        listContainer.addView(row)
                        if (idx < filtered.lastIndex) {
                            listContainer.addView(hairlineDivider(context, insetStartDp = 24, insetEndDp = 24))
                        }
                    }
                }
            }

            searchInput.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    renderList(s?.toString().orEmpty())
                }
            })

            // Optional custom model ID escape hatch (flat underlined)
            if (allowCustomModelId && onCustomIdSelected != null) {
                root.addView(hairlineDivider(context, insetStartDp = 24, insetEndDp = 24))
                val customContainer = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(context, 24), dp(context, 10), dp(context, 24), 0)
                }
                val customRow = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                val customInput = EditText(context).apply {
                    hint = "Custom model ID"
                    textSize = 14f
                    isSingleLine = true
                    setTextColor(primaryText)
                    setHintTextColor(secondaryText)
                    background = flatUnderlineBg(context)
                    setPadding(dp(context, 4), dp(context, 8), dp(context, 4), dp(context, 8))
                    layoutParams = LinearLayout.LayoutParams(0, LP_WRAP, 1f).apply {
                        marginEnd = dp(context, 12)
                    }
                }
                val warningText = TextView(context).apply {
                    textSize = 12f
                    setTextColor(warningColor)
                    visibility = View.GONE
                    setPadding(0, dp(context, 4), 0, 0)
                }
                customInput.addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                    override fun afterTextChanged(s: Editable?) {
                        val typed = s?.toString()?.trim().orEmpty()
                        val warning = customModelWarning?.invoke(typed)
                        if (warning != null) {
                            warningText.text = warning
                            warningText.visibility = View.VISIBLE
                        } else {
                            warningText.visibility = View.GONE
                        }
                    }
                })
                val useCustomBtn = TextView(context).apply {
                    text = "Use"
                    textSize = 14f
                    typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                    setTextColor(accent)
                    setPadding(dp(context, 8), dp(context, 8), dp(context, 4), dp(context, 8))
                    applySelectableRipple(this)
                    setOnClickListener {
                        val typed = customInput.text?.toString()?.trim().orEmpty()
                        if (typed.isNotEmpty()) {
                            UiTapHaptics.tick(this)
                            dialog.dismiss()
                            onCustomIdSelected(typed)
                        }
                    }
                }
                customRow.addView(customInput)
                customRow.addView(useCustomBtn)
                customContainer.addView(customRow)
                customContainer.addView(warningText)
                root.addView(customContainer)
            }

            // Edge-to-edge bottom fill + keyboard-safe IME bottom padding (underlying captions never peek)
            val baseBottom = dp(context, 16)
            ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
                val imeBottom = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
                val navBottom = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
                v.setPadding(0, dp(context, 18), 0, baseBottom + maxOf(imeBottom, navBottom))
                insets
            }

            renderList("")
            dialog.setContentView(root)
            dialog.setOnShowListener {
                dialog.window?.setDimAmount(0.6f)
                dialog.window?.navigationBarColor = resColor(context, R.color.popup_surface)
                val sheet = dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
                if (sheet != null) {
                    sheet.setBackgroundColor(Color.TRANSPARENT)
                    val behavior = BottomSheetBehavior.from(sheet)
                    behavior.state = BottomSheetBehavior.STATE_EXPANDED
                    behavior.skipCollapsed = true
                }
                ViewCompat.requestApplyInsets(root)
            }
            dialog.show()
            return dialog
        }
    }
}
