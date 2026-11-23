/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.composition

import android.content.Context
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.text.buildSpannedString
import com.osfans.trime.core.CompositionProto
import com.osfans.trime.data.theme.Theme
import com.osfans.trime.data.theme.ThemeScope
import com.osfans.trime.util.TwoKeyDiag
import com.osfans.trime.util.roundedRippleDrawable
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
     * 双键布局下的拼音组行：与 [preedit] 同处编码区，取代原始编码显示。
     * 数据来自引擎候选自带的拼音注释（全拼），如 wo / qie，多个并排、可点选。
     */
    private val pinyinRow =
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
                add(pinyinRow, lParams())
            }
        }

    private var pinyinPick: ((Int) -> Unit)? = null
    var pinyinMode = false
        private set

    /** 拼音组样式跟随键盘按键配色（夜间即黑底白字），选中项用高亮按键色。 */
    private fun styleCell(
        cell: TextView,
        selected: Boolean,
    ) {
        val back = if (selected) scope.colors.hilitedKeyBackColor else scope.colors.keyBackColor
        val fg = if (selected) scope.colors.hilitedKeyTextColor else scope.colors.keyTextColor
        cell.setTextColor(fg)
        cell.background =
            roundedRippleDrawable(
                back,
                ctx.dp(6).toFloat(),
                back,
            )
    }

    /** 在编码区显示多组拼音（如 WO / QIE），并排可点。 */
    fun showPinyinGroups(
        groups: List<String>,
        selectedIndex: Int,
        onPick: (Int) -> Unit,
    ) {
        if (groups.isEmpty()) {
            hidePinyinGroups()
            return
        }
        pinyinMode = true
        pinyinPick = onPick
        TwoKeyDiag.log("ui", "showPinyinGroups groups=$groups selected=$selectedIndex")
        preedit.visibility = View.GONE
        pinyinRow.removeAllViews()
        groups.forEachIndexed { index, label ->
            val cell =
                TextView(ctx).apply {
                    text = label.uppercase()
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, theme.preedit.foreground.fontSize)
                    typeface = theme.fonts.text
                    this@PreeditUi.styleCell(this, index == selectedIndex)
                    setPadding(ctx.dp(9), ctx.dp(3), ctx.dp(9), ctx.dp(3))
                    // 候选浮窗通过透明 PopupWindow 转发裸 MotionEvent，
                    // TextView 的 click 手势不会被触发，直接处理 UP。
                    setOnTouchListener { _, event ->
                        if (event.actionMasked == MotionEvent.ACTION_UP) {
                            TwoKeyDiag.log("ui", "TOUCH pinyin index=$index label=$label")
                            pinyinPick?.invoke(index)
                        }
                        true
                    }
                }
            pinyinRow.addView(
                cell,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { setMargins(ctx.dp(2), 0, ctx.dp(2), 0) },
            )
        }
        pinyinRow.visibility = View.VISIBLE
    }

    /** 更新拼音组的高亮项 */
    fun selectPinyin(index: Int) {
        for (i in 0 until pinyinRow.childCount) {
            val cell = pinyinRow.getChildAt(i) as? TextView ?: continue
            styleCell(cell, i == index)
        }
    }

    fun hidePinyinGroups() {
        if (!pinyinMode) return
        pinyinMode = false
        pinyinPick = null
        pinyinRow.visibility = View.GONE
        pinyinRow.removeAllViews()
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
        visible = hasPreedit || pinyinMode
        if (!hasPreedit) {
            // 编码被清空（选字上屏或退格）后，拼音组使命完成，恢复正常编码显示
            if (pinyinMode) hidePinyinGroups()
            updateTextView("", false)
            return
        }
        // 拼音组模式：编码区只显示拼音组，不再回显代表码（如 qo 这种非法拼音）
        if (pinyinMode) return
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
