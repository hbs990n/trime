/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.composition

import android.graphics.drawable.GradientDrawable
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewOutlineProvider
import com.osfans.trime.core.Candidates
import com.osfans.trime.core.CompositionProto
import com.osfans.trime.core.data.UserDataManager
import com.osfans.trime.daemon.RimeSession
import com.osfans.trime.daemon.launchOnReady
import com.osfans.trime.data.theme.Theme
import com.osfans.trime.data.theme.ThemeScope
import com.osfans.trime.ime.broadcast.InputBroadcastReceiver
import com.osfans.trime.ime.core.TouchEventReceiverWindow
import com.osfans.trime.util.TwoKeyDiag
import org.kodein.di.DI
import org.kodein.di.DIAware
import org.kodein.di.instance
import splitties.dimensions.dp
import splitties.views.horizontalPadding

class PreeditDelegate(override val di: DI) :
    DIAware,
    InputBroadcastReceiver {

    private val context: ContextThemeWrapper by instance()
    private val scope: ThemeScope by instance()
    private val rime: RimeSession by instance()

    private val theme: Theme
        get() = scope.theme

    /** Applies the text-background styling; re-applied on scheme refreshes. */
    private fun setupPreeditBackground(view: View) {
        val startRadius = view.dp(theme.preedit.topStartRadius)
        val endRadius = view.dp(theme.preedit.topEndRadius)
        val radii = if (view.layoutDirection == View.LAYOUT_DIRECTION_LTR) {
            floatArrayOf(startRadius, startRadius, endRadius, endRadius, 0f, 0f, 0f, 0f)
        } else {
            floatArrayOf(endRadius, endRadius, startRadius, startRadius, 0f, 0f, 0f, 0f)
        }
        view.background = GradientDrawable().apply {
            setColor(scope.colors.textBackColor)
            shape = GradientDrawable.RECTANGLE
            cornerRadii = radii
        }
        view.clipToOutline = true
        view.outlineProvider = ViewOutlineProvider.BACKGROUND
    }

    val ui =
        PreeditUi(
            context,
            scope,
            setupPreeditView = {
                setupPreeditBackground(this)
                horizontalPadding = dp(theme.preedit.horizontalPadding)
            },
            onMoveCursor = { pos -> rime.launchOnReady { it.moveCursorPos(pos) } },
        ).apply {
            root.alpha = theme.preedit.alpha
            root.visibility = View.INVISIBLE
        }

    /** Restyles the text and its background after a scheme switch. */
    fun refreshColors() {
        ui.refreshColors()
        setupPreeditBackground(ui.preedit)
    }

    private val touchEventReceiverWindow = TouchEventReceiverWindow(ui.root)

    /** 当前拼音组里，每组首个候选在候选栏中的全局索引。 */
    private var pinyinCandidateIndices = intArrayOf()

    /**
     * 双键布局下，编码区显示引擎候选自带的拼音组（如 WO / QIE），并排可点。
     * 拼音来自 candidate.comment（全拼），只在双键方案下启用，标准键盘不受影响。
     */
    override fun onCandidateListUpdate(data: Candidates.Bulk) {
        val schemaId = rime.run { statusCached }.schemaId
        val isTwoKey = schemaId == UserDataManager.TWOKEY_SCHEMA_ID
        val comments = data.candidates.take(8).joinToString(" | ") {
            "${it.text}<${it.comment}>"
        }
        TwoKeyDiag.log(
            "delegate",
            "onCandidateListUpdate schema=$schemaId isTwoKey=$isTwoKey n=${data.candidates.size} " +
                "sample=[$comments]",
        )
        if (!isTwoKey || data.candidates.isEmpty()) {
            if (ui.pinyinMode) {
                ui.hidePinyinGroups()
                ui.root.visibility = View.INVISIBLE
            }
            return
        }
        // 按拼音（comment）去重，保序；最多 5 组，避免编码区太长。
        val groups = LinkedHashMap<String, Int>()
        data.candidates.forEachIndexed { index, cand ->
            val py = cand.comment.trim()
            if (py.isNotEmpty() && !groups.containsKey(py)) {
                groups[py] = index
            }
            if (groups.size >= 5) return@forEachIndexed
        }
        if (groups.isEmpty()) {
            if (ui.pinyinMode) {
                ui.hidePinyinGroups()
                ui.root.visibility = View.INVISIBLE
            }
            return
        }
        pinyinCandidateIndices = groups.values.toIntArray()
        TwoKeyDiag.log("delegate", "GROUPS -> ${groups.keys.toList()} indices=${pinyinCandidateIndices.toList()}")
        ui.showPinyinGroups(groups.keys.toList()) { idx ->
            val candidateIndex = pinyinCandidateIndices.getOrNull(idx) ?: return@showPinyinGroups
            ui.selectPinyin(idx)
            TwoKeyDiag.log("delegate", "PICK idx=$idx candidateIndex=$candidateIndex")
            // 点某组即上屏该拼音对应的首个候选字
            rime.launchOnReady { it.selectCandidate(candidateIndex, true) }
        }
        ui.root.visibility = View.VISIBLE
        touchEventReceiverWindow.show()
    }

    override fun onCompositionUpdate(data: CompositionProto) {
        TwoKeyDiag.log(
            "delegate",
            "onCompositionUpdate preedit='${data.preedit}' len=${data.length} pinyinMode=${ui.pinyinMode}",
        )
        // 拼音组模式下，编码清空（选字上屏/退格）后收起拼音组
        if (ui.pinyinMode && data.length == 0) {
            ui.hidePinyinGroups()
        }
        ui.update(data)
        ui.root.visibility = if (ui.visible) View.VISIBLE else View.INVISIBLE
        if (ui.visible) {
            touchEventReceiverWindow.show()
        } else {
            touchEventReceiverWindow.dismiss()
        }
    }
}
