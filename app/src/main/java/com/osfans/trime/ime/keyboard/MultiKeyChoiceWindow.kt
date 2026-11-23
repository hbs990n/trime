/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.keyboard

import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.osfans.trime.R
import com.osfans.trime.data.theme.ThemeScope
import com.osfans.trime.ime.core.TrimeInputMethodService
import com.osfans.trime.ime.window.BoardWindow
import com.osfans.trime.ime.window.BoardWindowManager
import com.osfans.trime.util.rippleDrawable
import org.kodein.di.DI
import org.kodein.di.instance
import splitties.dimensions.dp

/**
 * 多义键消歧的「第一阶段」选择界面：把枚举出的合法拼音（如 wo / qp）列成一排，
 * 用户点选某个拼音后，将其送入引擎编码区进入常规选字流程，随后切回键盘。
 */
class MultiKeyChoiceWindow(
    di: DI,
    private val choices: List<MultiKeyEngine.Choice>,
    private val engine: MultiKeyEngine,
) : BoardWindow.NoBarBoardWindow(di) {
    private val service: TrimeInputMethodService by instance()
    private val scope: ThemeScope by instance()
    private val windowManager: BoardWindowManager by instance()

    override fun onCreateView(): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundColor(scope.colors.backColor)
        minimumHeight = context.dp(56)

        val title = TextView(context).apply {
            text = context.getString(R.string.multikey_choose_pinyin)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(scope.colors.commentTextColor)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                setMargins(0, context.dp(6), 0, context.dp(2))
            }
        }
        addView(title)

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }
        for (choice in choices) {
            val cell = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                background = rippleDrawable(scope.colors.hilitedCandidateBackColor)
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f,
                ).apply {
                    setMargins(context.dp(4), 0, context.dp(4), context.dp(4))
                }
                setOnClickListener {
                    engine.commitChoice(choice.pinyin)
                    windowManager.attachWindow(KeyboardWindow)
                }
            }
            val pinyinView = TextView(context).apply {
                text = choice.pinyin
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
                setTextColor(scope.colors.candidateTextColor)
                gravity = Gravity.CENTER
            }
            val previewView = TextView(context).apply {
                text = choice.preview
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(scope.colors.commentTextColor)
                gravity = Gravity.CENTER
            }
            cell.addView(pinyinView)
            cell.addView(previewView)
            row.addView(cell)
        }
        addView(row)
    }

    override fun onAttached() {}

    override fun onDetached() {}
}
