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
import com.osfans.trime.ime.broadcast.InputBroadcaster
import com.osfans.trime.ime.core.TouchEventReceiverWindow
import com.osfans.trime.util.TwoKeyDiag
import com.osfans.trime.util.TwoKeyFilterState
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
    private val broadcaster: InputBroadcaster by instance()

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

    /**
     * 引擎已在 preedit 里插入空格表示进入第二音节。此时必须退出拼音组模式，
     * 否则泡泡会吞掉编码回显、筛选会把整句候选筛成单字，看起来像无法续输。
     */
    private var composingMultiSyllable = false

    /**
     * 双键布局下，编码区显示引擎候选自带的拼音组（如 WO / QIE），并排可点。
     * 拼音来自 candidate.comment（全拼）；点选只切换候选栏筛选，不直接上屏。
     * 仅单音节阶段启用；多音节（连续输入/整句）阶段交还给引擎原生候选。
     */
    override fun onCandidateListUpdate(data: Candidates.Bulk) {
        val schemaId = rime.run { statusCached }.schemaId
        val isTwoKey = schemaId == UserDataManager.TWOKEY_SCHEMA_ID && !composingMultiSyllable
        val comments = data.candidates.take(8).joinToString(" | ") {
            "${it.text}<${it.comment}>"
        }
        TwoKeyDiag.log(
            "delegate",
            "onCandidateListUpdate schema=$schemaId isTwoKey=$isTwoKey n=${data.candidates.size} " +
                "sample=[$comments]",
        )
        val (groups, selectedIndex) = TwoKeyFilterState.onCandidates(isTwoKey, data)
        if (!isTwoKey || groups.isEmpty()) {
            if (ui.pinyinMode) {
                ui.hidePinyinGroups()
                ui.root.visibility = View.INVISIBLE
            }
            return
        }
        TwoKeyDiag.log("delegate", "GROUPS -> $groups selected=$selectedIndex")
        ui.showPinyinGroups(groups, selectedIndex) { idx ->
            val group = groups.getOrNull(idx) ?: return@showPinyinGroups
            // 只锁定筛选目标；候选栏由下面的重新广播按新目标过滤。
            TwoKeyFilterState.pick(group)
            ui.selectPinyin(idx)
            TwoKeyFilterState.lastBulk?.let { broadcaster.onCandidateListUpdate(it) }
        }
        ui.root.visibility = View.VISIBLE
        touchEventReceiverWindow.show()
    }

    override fun onCompositionUpdate(data: CompositionProto) {
        TwoKeyDiag.log(
            "delegate",
            "onCompositionUpdate preedit='${data.preedit}' len=${data.length} pinyinMode=${ui.pinyinMode}",
        )
        // preedit 中出现空格 = 引擎已分词，进入第二音节，转为整句输入
        composingMultiSyllable = data.preedit?.contains(' ') == true
        if (ui.pinyinMode && (data.length == 0 || composingMultiSyllable)) {
            ui.hidePinyinGroups()
            TwoKeyFilterState.reset()
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
