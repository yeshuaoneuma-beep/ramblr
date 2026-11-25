package com.trevornk.ramblr

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
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
 * Programmatic Samsung One UI AMOLED components (zero XML layouts).
 *
 * Shared across [ApiKeysVaultActivity], [CloudProviderActivity], and [LiveTranscriptionActivity].
 */
object OneUiComponents {

    private const val LP_MATCH = LinearLayout.LayoutParams.MATCH_PARENT
    private const val LP_WRAP = LinearLayout.LayoutParams.WRAP_CONTENT

    fun dp(context: Context, n: Int): Int =
        (n * context.resources.displayMetrics.density).toInt()

    fun resColor(context: Context, @ColorRes resId: Int): Int =
        ContextCompat.getColor(context, resId)

    fun attrColor(context: Context, attr: Int): Int {
        val ta = context.obtainStyledAttributes(intArrayOf(attr))
        val color = ta.getColor(0, Color.WHITE)
        ta.recycle()
        return color
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

    /** Compact capability / badge pill. */
    fun pillBadge(
        context: Context,
        label: String,
        textColor: Int = attrColor(context, android.R.attr.textColorSecondary),
        bgColor: Int = resColor(context, R.color.oneui_pill_surface),
        borderColor: Int? = null,
    ): TextView = TextView(context).apply {
        text = label
        textSize = 11f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(textColor)
        background = roundedBg(
            fillColor = bgColor,
            radiusPx = dp(context, 10).toFloat(),
            strokeWidthPx = if (borderColor != null) dp(context, 1) else 0,
            strokeColor = borderColor ?: 0,
        )
        setPadding(dp(context, 8), dp(context, 3), dp(context, 8), dp(context, 3))
    }

    /**
     * Bright green lock-in button when locked, neutral grey when unlocked/draft.
     */
    fun lockInButton(
        context: Context,
        locked: Boolean,
        enabled: Boolean = true,
        contentDescription: String,
        onClick: () -> Unit,
    ): TextView {
        val fgColor = if (locked) resColor(context, R.color.oneui_locked_green)
        else resColor(context, R.color.oneui_unlocked_grey)
        val bgColor = if (locked) resColor(context, R.color.oneui_locked_green_bg)
        else resColor(context, R.color.oneui_unlocked_grey_bg)

        return TextView(context).apply {
            text = if (locked) "\u2713" else "\u25cb"
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(fgColor)
            this.contentDescription = contentDescription
            background = roundedBg(
                fillColor = bgColor,
                radiusPx = dp(context, 14).toFloat(),
                strokeWidthPx = dp(context, 1),
                strokeColor = fgColor,
            )
            layoutParams = LinearLayout.LayoutParams(dp(context, 40), dp(context, 40))
            isEnabled = enabled
            isClickable = enabled
            isFocusable = enabled
            alpha = if (enabled) 1f else 0.38f
            if (enabled) setOnClickListener { onClick() }
        }
    }

    /**
     * Tappable Attempts pill (`1x`, `2x`, `3x`). Opens a crisp BottomSheetDialog selector showing
     * which attempt counts fit inside the shared 3-attempt pool.
     */
    fun attemptsPill(
        context: Context,
        attempts: Int,
        allowedAttempts: List<Int>,
        enabled: Boolean = true,
        onSelect: (Int) -> Unit,
    ): TextView {
        val accent = resColor(context, R.color.oneui_accent_cobalt)
        val accentBg = resColor(context, R.color.oneui_accent_cobalt_container)
        val mutedFg = resColor(context, R.color.oneui_unlocked_grey)
        val mutedBg = resColor(context, R.color.oneui_pill_surface)
        val clamped = attempts.coerceIn(
            AttemptBudgetCalculator.MIN_SLOT_ATTEMPTS,
            AttemptBudgetCalculator.MAX_SLOT_ATTEMPTS,
        )

        return TextView(context).apply {
            text = "${clamped}x"
            textSize = 13f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(if (enabled) accent else mutedFg)
            contentDescription = "Attempts: ${clamped}x"
            background = roundedBg(
                fillColor = if (enabled) accentBg else mutedBg,
                radiusPx = dp(context, 14).toFloat(),
                strokeWidthPx = dp(context, 1),
                strokeColor = if (enabled) accent else resColor(context, R.color.oneui_card_border),
            )
            minWidth = dp(context, 44)
            minimumHeight = dp(context, 40)
            setPadding(dp(context, 10), dp(context, 6), dp(context, 10), dp(context, 6))
            isEnabled = enabled
            isClickable = enabled
            isFocusable = enabled
            alpha = if (enabled) 1f else 0.45f
            if (enabled) {
                setOnClickListener {
                    showAttemptsSelectorSheet(context, clamped, allowedAttempts, onSelect)
                }
            }
        }
    }

    private fun showAttemptsSelectorSheet(
        context: Context,
        currentAttempts: Int,
        allowedAttempts: List<Int>,
        onSelect: (Int) -> Unit,
    ) {
        val dialog = BottomSheetDialog(context)
        val surface = resColor(context, R.color.oneui_card_surface)
        val border = resColor(context, R.color.oneui_card_border)
        val accent = resColor(context, R.color.oneui_accent_cobalt)
        val accentBg = resColor(context, R.color.oneui_accent_cobalt_container)
        val primaryText = attrColor(context, android.R.attr.textColorPrimary)
        val secondaryText = attrColor(context, android.R.attr.textColorSecondary)

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBg(surface, dp(context, 24).toFloat())
            setPadding(dp(context, 20), dp(context, 16), dp(context, 20), dp(context, 24))
        }

        root.addView(TextView(context).apply {
            text = "Select attempts"
            textSize = 18f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(primaryText)
        })
        root.addView(TextView(context).apply {
            text = "Total locked attempts across this lane share a 3-attempt pool. 1x = 1 call total."
            textSize = 13f
            setTextColor(secondaryText)
            setPadding(0, dp(context, 4), 0, dp(context, 12))
        })

        for (count in AttemptBudgetCalculator.MIN_SLOT_ATTEMPTS..AttemptBudgetCalculator.MAX_SLOT_ATTEMPTS) {
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
                background = roundedBg(
                    fillColor = if (isSelected) accentBg else resColor(context, R.color.oneui_pill_surface),
                    radiusPx = dp(context, 16).toFloat(),
                    strokeWidthPx = dp(context, 1),
                    strokeColor = if (isSelected) accent else border,
                )
                setPadding(dp(context, 14), dp(context, 12), dp(context, 14), dp(context, 12))
                layoutParams = LinearLayout.LayoutParams(LP_MATCH, LP_WRAP).apply {
                    bottomMargin = dp(context, 8)
                }
                alpha = if (isAllowed) 1f else 0.4f
                isEnabled = isAllowed
                isClickable = isAllowed
                if (isAllowed) {
                    setOnClickListener {
                        dialog.dismiss()
                        onSelect(count)
                    }
                }
            }
            val col = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LP_WRAP, 1f)
            }
            col.addView(TextView(context).apply {
                text = "${count}x attempts"
                textSize = 15f
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                setTextColor(if (isSelected) accent else primaryText)
            })
            col.addView(TextView(context).apply {
                text = if (isAllowed) desc else "Not enough remaining attempts in 3/3 pool"
                textSize = 12f
                setTextColor(secondaryText)
            })
            option.addView(col)
            if (isSelected) {
                option.addView(TextView(context).apply {
                    text = "\u2713"
                    textSize = 16f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(accent)
                })
            }
            root.addView(option)
        }

        dialog.setContentView(root)
        dialog.show()
    }

    /**
     * Crisp 1-line tap-to-open selector box that collapses cleanly on selection.
     */
    fun selectorBox(
        context: Context,
        label: String,
        valueText: String?,
        badgeText: String? = null,
        placeholder: String = "Select...",
        enabled: Boolean = true,
        onClick: () -> Unit,
    ): LinearLayout {
        val pillSurface = resColor(context, R.color.oneui_pill_surface)
        val border = resColor(context, R.color.oneui_card_border)
        val primaryText = attrColor(context, android.R.attr.textColorPrimary)
        val secondaryText = attrColor(context, android.R.attr.textColorSecondary)

        val box = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = roundedBg(
                fillColor = pillSurface,
                radiusPx = dp(context, 14).toFloat(),
                strokeWidthPx = dp(context, 1),
                strokeColor = border,
            )
            setPadding(dp(context, 12), dp(context, 8), dp(context, 10), dp(context, 8))
            minimumHeight = dp(context, 44)
            isEnabled = enabled
            isClickable = enabled
            isFocusable = enabled
            alpha = if (enabled) 1f else 0.45f
            if (enabled) setOnClickListener { onClick() }
        }

        val textCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LP_WRAP, 1f)
        }
        if (label.isNotBlank()) {
            textCol.addView(TextView(context).apply {
                text = label
                textSize = 11f
                setTextColor(secondaryText)
            })
        }
        val lineRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val hasValue = !valueText.isNullOrBlank()
        lineRow.addView(TextView(context).apply {
            text = if (hasValue) valueText else placeholder
            textSize = 14f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            typeface = Typeface.create("sans-serif-medium", if (hasValue) Typeface.NORMAL else Typeface.NORMAL)
            setTextColor(if (hasValue) primaryText else secondaryText)
            layoutParams = LinearLayout.LayoutParams(0, LP_WRAP, 1f)
        })
        if (!badgeText.isNullOrBlank()) {
            lineRow.addView(
                pillBadge(
                    context = context,
                    label = badgeText,
                    textColor = resColor(context, R.color.oneui_accent_cobalt),
                    bgColor = resColor(context, R.color.oneui_accent_cobalt_container),
                ).apply {
                    layoutParams = LinearLayout.LayoutParams(LP_WRAP, LP_WRAP).apply {
                        marginStart = dp(context, 6)
                    }
                }
            )
        }
        textCol.addView(lineRow)
        box.addView(textCol)

        box.addView(TextView(context).apply {
            text = "\u25be"
            textSize = 14f
            setTextColor(secondaryText)
            setPadding(dp(context, 6), 0, 0, 0)
        })

        return box
    }

    /**
     * Compact live Execution Order strip with pool usage badge (`Pool X/3`).
     */
    fun executionOrderStrip(
        context: Context,
        title: String,
        orderText: String,
        usedAttempts: Int,
        maxAttempts: Int = AttemptBudgetCalculator.MAX_CLOUD_ATTEMPTS_PER_LANE,
    ): LinearLayout {
        val primaryText = attrColor(context, android.R.attr.textColorPrimary)
        val secondaryText = attrColor(context, android.R.attr.textColorSecondary)
        val fullPool = usedAttempts >= maxAttempts
        val poolFg = if (fullPool) resColor(context, R.color.oneui_locked_green)
        else resColor(context, R.color.oneui_accent_cobalt)
        val poolBg = if (fullPool) resColor(context, R.color.oneui_locked_green_bg)
        else resColor(context, R.color.oneui_accent_cobalt_container)

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBg(
                fillColor = resColor(context, R.color.oneui_pill_surface),
                radiusPx = dp(context, 16).toFloat(),
                strokeWidthPx = dp(context, 1),
                strokeColor = resColor(context, R.color.oneui_card_border),
            )
            setPadding(dp(context, 14), dp(context, 10), dp(context, 14), dp(context, 10))
        }

        val headerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        headerRow.addView(TextView(context).apply {
            text = title
            textSize = 12f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(secondaryText)
            layoutParams = LinearLayout.LayoutParams(0, LP_WRAP, 1f)
        })
        headerRow.addView(pillBadge(context, "Pool $usedAttempts/$maxAttempts", poolFg, poolBg))
        container.addView(headerRow)

        container.addView(TextView(context).apply {
            text = orderText
            textSize = 14f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(primaryText)
            setPadding(0, dp(context, 4), 0, 0)
        })

        return container
    }

    /** Primary One UI cobalt button. If [titleTag] is true, its TextView carries `tag = "title"`. */
    fun cobaltButton(
        context: Context,
        label: String,
        subtitle: String? = null,
        enabled: Boolean = true,
        titleTag: Boolean = false,
        onClick: () -> Unit,
    ): LinearLayout {
        val accent = resColor(context, R.color.oneui_accent_cobalt)
        val btn = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = roundedBg(
                fillColor = if (enabled) accent else resColor(context, R.color.oneui_unlocked_grey_bg),
                radiusPx = dp(context, 20).toFloat(),
            )
            setPadding(dp(context, 16), dp(context, 12), dp(context, 16), dp(context, 12))
            minimumHeight = dp(context, 48)
            isEnabled = enabled
            isClickable = enabled
            isFocusable = enabled
            alpha = if (enabled) 1f else 0.55f
            if (enabled) setOnClickListener { onClick() }
        }
        btn.addView(TextView(context).apply {
            if (titleTag) tag = "title"
            text = label
            textSize = 15f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(if (enabled) Color.WHITE else attrColor(context, android.R.attr.textColorSecondary))
            gravity = Gravity.CENTER
        })
        if (!subtitle.isNullOrBlank()) {
            btn.addView(TextView(context).apply {
                if (titleTag) tag = "subtitle"
                text = subtitle
                textSize = 12f
                setTextColor(if (enabled) Color.argb(220, 255, 255, 255) else attrColor(context, android.R.attr.textColorSecondary))
                gravity = Gravity.CENTER
                setPadding(0, dp(context, 2), 0, 0)
            })
        }
        return btn
    }

    /**
     * Reusable keyboard-safe searchable BottomSheetDialog picker for providers and models.
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

            val surface = resColor(context, R.color.oneui_card_surface)
            val pillSurface = resColor(context, R.color.oneui_pill_surface)
            val border = resColor(context, R.color.oneui_card_border)
            val accent = resColor(context, R.color.oneui_accent_cobalt)
            val accentBg = resColor(context, R.color.oneui_accent_cobalt_container)
            val warningColor = resColor(context, R.color.oneui_warning_amber)
            val primaryText = attrColor(context, android.R.attr.textColorPrimary)
            val secondaryText = attrColor(context, android.R.attr.textColorSecondary)

            var currentItems = items

            val root = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                background = roundedBg(surface, dp(context, 24).toFloat())
                setPadding(dp(context, 16), dp(context, 14), dp(context, 16), dp(context, 16))
            }

            // Header + optional Refresh live models button
            val headerRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(context, 4), 0, dp(context, 4), dp(context, 10))
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
                layoutParams = LinearLayout.LayoutParams(dp(context, 20), dp(context, 20)).apply {
                    marginEnd = dp(context, 6)
                }
            }
            val refreshStatus = TextView(context).apply {
                textSize = 12f
                setTextColor(secondaryText)
                visibility = View.GONE
                setPadding(dp(context, 4), 0, dp(context, 4), dp(context, 8))
            }

            lateinit var renderList: (String) -> Unit
            lateinit var searchInput: EditText

            if (onRefreshRequested != null) {
                headerRow.addView(refreshSpinner)
                val refreshBtn = TextView(context).apply {
                    text = "Refresh live models"
                    textSize = 12f
                    typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                    setTextColor(accent)
                    background = roundedBg(accentBg, dp(context, 12).toFloat())
                    setPadding(dp(context, 10), dp(context, 6), dp(context, 10), dp(context, 6))
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

            // Search box
            searchInput = EditText(context).apply {
                hint = searchHint
                textSize = 15f
                setTextColor(primaryText)
                setHintTextColor(secondaryText)
                isSingleLine = true
                background = roundedBg(pillSurface, dp(context, 14).toFloat(), dp(context, 1), border)
                setPadding(dp(context, 14), dp(context, 10), dp(context, 14), dp(context, 10))
                layoutParams = LinearLayout.LayoutParams(LP_MATCH, LP_WRAP).apply {
                    bottomMargin = dp(context, 10)
                }
            }
            root.addView(searchInput)

            // Optional pinned Skip / None row
            if (skipOptionLabel != null && onSkipSelected != null) {
                val skipRow = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    background = roundedBg(
                        fillColor = if (skipSelected) accentBg else pillSurface,
                        radiusPx = dp(context, 14).toFloat(),
                        strokeWidthPx = dp(context, 1),
                        strokeColor = if (skipSelected) accent else border,
                    )
                    setPadding(dp(context, 14), dp(context, 10), dp(context, 14), dp(context, 10))
                    layoutParams = LinearLayout.LayoutParams(LP_MATCH, LP_WRAP).apply {
                        bottomMargin = dp(context, 10)
                    }
                    setOnClickListener {
                        dialog.dismiss()
                        onSkipSelected()
                    }
                }
                skipRow.addView(TextView(context).apply {
                    text = skipOptionLabel
                    textSize = 14f
                    typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                    setTextColor(if (skipSelected) accent else primaryText)
                    layoutParams = LinearLayout.LayoutParams(0, LP_WRAP, 1f)
                })
                if (skipSelected) {
                    skipRow.addView(TextView(context).apply {
                        text = "\u2713"
                        textSize = 15f
                        typeface = Typeface.DEFAULT_BOLD
                        setTextColor(accent)
                    })
                }
                root.addView(skipRow)
            }

            // Scrollable items list
            val listContainer = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
            }
            val maxScrollHeight = (context.resources.displayMetrics.heightPixels * 0.48f).toInt().coerceAtLeast(dp(context, 220))
            val scrollView = ScrollView(context).apply {
                layoutParams = LinearLayout.LayoutParams(LP_MATCH, LP_WRAP).apply {
                    weight = 1f
                }
                addView(listContainer)
            }
            // Limit ScrollView max height smoothly on large lists
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
                        setPadding(dp(context, 8), dp(context, 16), dp(context, 8), dp(context, 16))
                    })
                } else {
                    filtered.forEach { item ->
                        val row = LinearLayout(context).apply {
                            orientation = LinearLayout.VERTICAL
                            background = roundedBg(
                                fillColor = if (item.selected) accentBg else pillSurface,
                                radiusPx = dp(context, 16).toFloat(),
                                strokeWidthPx = dp(context, 1),
                                strokeColor = if (item.selected) accent else border,
                            )
                            setPadding(dp(context, 14), dp(context, 10), dp(context, 14), dp(context, 10))
                            layoutParams = LinearLayout.LayoutParams(LP_MATCH, LP_WRAP).apply {
                                bottomMargin = dp(context, 8)
                            }
                            alpha = if (item.enabled) 1f else 0.45f
                            isEnabled = item.enabled
                            isClickable = item.enabled
                            if (item.enabled) {
                                setOnClickListener {
                                    dialog.dismiss()
                                    onSelect(item)
                                }
                            }
                        }
                        val topRow = LinearLayout(context).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = Gravity.CENTER_VERTICAL
                        }
                        topRow.addView(TextView(context).apply {
                            text = item.title
                            textSize = 15f
                            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                            setTextColor(if (item.selected) accent else primaryText)
                            layoutParams = LinearLayout.LayoutParams(0, LP_WRAP, 1f)
                        })
                        if (item.selected) {
                            topRow.addView(TextView(context).apply {
                                text = "\u2713"
                                textSize = 16f
                                typeface = Typeface.DEFAULT_BOLD
                                setTextColor(accent)
                            })
                        }
                        row.addView(topRow)

                        if (!item.metaLine.isNullOrBlank()) {
                            row.addView(TextView(context).apply {
                                text = item.metaLine
                                textSize = 12f
                                setTextColor(accent)
                                setPadding(0, dp(context, 2), 0, 0)
                            })
                        }

                        if (item.badges.isNotEmpty()) {
                            val badgeRow = LinearLayout(context).apply {
                                orientation = LinearLayout.HORIZONTAL
                                setPadding(0, dp(context, 4), 0, 0)
                            }
                            item.badges.take(4).forEach { badge ->
                                badgeRow.addView(
                                    pillBadge(context, badge).apply {
                                        layoutParams = LinearLayout.LayoutParams(LP_WRAP, LP_WRAP).apply {
                                            marginEnd = dp(context, 6)
                                        }
                                    }
                                )
                            }
                            row.addView(badgeRow)
                        }

                        if (!item.subtitle.isNullOrBlank()) {
                            row.addView(TextView(context).apply {
                                text = item.subtitle
                                textSize = 12f
                                maxLines = 2
                                ellipsize = TextUtils.TruncateAt.END
                                setTextColor(secondaryText)
                                setPadding(0, dp(context, 3), 0, 0)
                            })
                        }
                        listContainer.addView(row)
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

            // Optional custom model ID escape hatch
            if (allowCustomModelId && onCustomIdSelected != null) {
                val customContainer = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(0, dp(context, 10), 0, 0)
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
                    background = roundedBg(pillSurface, dp(context, 12).toFloat(), dp(context, 1), border)
                    setPadding(dp(context, 12), dp(context, 8), dp(context, 12), dp(context, 8))
                    layoutParams = LinearLayout.LayoutParams(0, LP_WRAP, 1f).apply {
                        marginEnd = dp(context, 8)
                    }
                }
                val warningText = TextView(context).apply {
                    textSize = 12f
                    setTextColor(warningColor)
                    visibility = View.GONE
                    setPadding(dp(context, 4), dp(context, 4), dp(context, 4), 0)
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
                    setTextColor(Color.WHITE)
                    background = roundedBg(accent, dp(context, 12).toFloat())
                    setPadding(dp(context, 14), dp(context, 9), dp(context, 14), dp(context, 9))
                    setOnClickListener {
                        val typed = customInput.text?.toString()?.trim().orEmpty()
                        if (typed.isNotEmpty()) {
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

            // Keyboard-safe IME bottom padding
            val baseBottom = dp(context, 16)
            ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
                val imeBottom = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
                val navBottom = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
                v.setPadding(
                    dp(context, 16),
                    dp(context, 14),
                    dp(context, 16),
                    baseBottom + maxOf(imeBottom, navBottom),
                )
                insets
            }

            renderList("")
            dialog.setContentView(root)
            dialog.setOnShowListener {
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
