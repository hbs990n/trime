/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.composition

import android.content.Context
import android.util.TypedValue
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.text.buildSpannedString
import com.osfans.trime.core.CompositionProto
import com.osfans.trime.data.theme.Theme
import com.osfans.trime.data.theme.ThemeScope
import com.osfans.trime.util.rippleDrawable
import splitties.dimensions.dp
import splitties.views.dsl.core.Ui
import splitties.views.dsl.core.add
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.view

open class PreeditUi(
    final override val ctx: Context,
    private val scope: ThemeScope,
    private val setupPreeditView: (TextView.() -> Unit)? = null,
    private val onMoveCursor: ((Int) -> Unit)? = null,
) : Ui {
    private val theme: Theme
        get() = scope.theme

    // Read at use time so a scheme switch re-renders with the new colors.
    private val textColor: Int get() = scope.colors.textColor
    private val highlightTextColor: Int get() = scope.colors.hilitedTextColor

    val preedit =
        view(::PreeditTextView) {
            setTextColor(textColor)
            textSize = theme.preedit.foreground.fontSize
            typeface = theme.fonts.text
            setupPreeditView?.invoke(this)
            onMoveCursor = this@PreeditUi.onMoveCursor
        }

    /**
     * 多义键消歧的拼音冒泡行：与 [preedit] 并列放在编码区，默认隐藏。
     * 用户点某个拼音后，下方候选区显示该拼音对应的字。
     */
    private val bubbleRow =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
        }

    override val root =
        object : LinearLayout(ctx) {
            override fun onInterceptTouchEvent(ev: MotionEvent?): Boolean = false

            init {
                orientation = HORIZONTAL
                add(preedit, lParams())
                add(bubbleRow, lParams())
            }
        }

    private var bubblePick: ((Int) -> Unit)? = null
    var bubbleMode = false
        private set

    /** 在编码区显示拼音冒泡（如 WO / QP），取代普通编码文本。 */
    fun showBubbles(
        labels: List<String>,
        onPick: (Int) -> Unit,
    ) {
        bubbleMode = true
        bubblePick = onPick
        preedit.visibility = View.GONE
        bubbleRow.removeAllViews()
        labels.forEachIndexed { index, label ->
            val cell =
                TextView(ctx).apply {
                    text = label
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                    setTextColor(
                        if (index == 0) scope.colors.hilitedCandidateTextColor else scope.colors.candidateTextColor,
                    )
                    background =
                        rippleDrawable(
                            if (index == 0) scope.colors.hilitedCandidateBackColor else scope.colors.backColor,
                        )
                    setPadding(ctx.dp(10), ctx.dp(4), ctx.dp(10), ctx.dp(4))
                    setOnClickListener { bubblePick?.invoke(index) }
                }
            bubbleRow.addView(
                cell,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { setMargins(ctx.dp(2), 0, ctx.dp(2), 0) },
            )
        }
        bubbleRow.visibility = View.VISIBLE
    }

    /** 更新冒泡的高亮选中项 */
    fun selectBubble(index: Int) {
        for (i in 0 until bubbleRow.childCount) {
            val cell = bubbleRow.getChildAt(i) as? TextView ?: continue
            cell.setTextColor(
                if (i == index) scope.colors.hilitedCandidateTextColor else scope.colors.candidateTextColor,
            )
            cell.background =
                rippleDrawable(
                    if (i == index) scope.colors.hilitedCandidateBackColor else scope.colors.backColor,
                )
        }
    }

    fun hideBubbles() {
        if (!bubbleMode) return
        bubbleMode = false
        bubblePick = null
        bubbleRow.visibility = View.GONE
        bubbleRow.removeAllViews()
        preedit.visibility = View.VISIBLE
    }

    private fun CompositionProto.toSpannedString() = buildSpannedString {
        if (!preedit.isNullOrEmpty()) {
            append(preedit)
            setSpan(ForegroundColorSpan(highlightTextColor), selStart, selEnd, Spanned.SPAN_INCLUSIVE_EXCLUSIVE)
        }
    }

    var visible = false
        private set

    /** Re-applies the text color after a scheme switch. */
    fun refreshColors() {
        preedit.setTextColor(textColor)
    }

    private fun updateTextView(
        str: CharSequence,
        visible: Boolean,
    ) = preedit.run {
        text = str
        visibility = if (visible) View.VISIBLE else View.GONE
    }

    fun update(composition: CompositionProto) {
        val string = composition.toSpannedString()
        val cursorPos = composition.cursorPos
        val hasPreedit = composition.length > 0
        visible = hasPreedit
        if (!hasPreedit) {
            // 编码被清空（选字上屏或退格）后，拼音冒泡使命完成，恢复正常编码显示
            if (bubbleMode) hideBubbles()
            updateTextView("", false)
            return
        }
        // 冒泡模式：候选区由引擎里已送入的默认拼音驱动，编码区只显示拼音冒泡，
        // 不再回显原始按键编码（否则会把 qo 这种非法拼音显示出来）。
        if (bubbleMode) return
        val stringWithCursor =
            if (cursorPos == 0 || cursorPos == string.length) {
                string
            } else {
                buildSpannedString {
                    if (cursorPos > 0) append(string, 0, cursorPos)
                    append(string, cursorPos, string.length)
                }
            }
        updateTextView(stringWithCursor, true)
    }
}
