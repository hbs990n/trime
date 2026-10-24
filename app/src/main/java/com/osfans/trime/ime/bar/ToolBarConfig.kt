/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.bar

import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.data.theme.Theme
import com.osfans.trime.data.theme.model.ToolBar
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * One configurable candidate-bar button.
 *
 * [enabled] is false once the user long-presses it away. Deleted entries are
 * kept so they can be restored and are always displayed after the active ones.
 */
@Serializable
data class ToolBarEntry(
    val action: String,
    val enabled: Boolean = true,
)

object ToolBarConfig {
    private val json = Json { ignoreUnknownKeys = true }

    private val prefs get() = AppPrefs.defaultInstance().keyboard

    fun load(): List<ToolBarEntry> = prefs.toolBarConfig.getValue()
        .takeIf { it.isNotBlank() }
        ?.let { raw -> runCatching { json.decodeFromString<List<ToolBarEntry>>(raw) }.getOrNull() }
        ?: emptyList()

    fun save(entries: List<ToolBarEntry>) {
        prefs.toolBarConfig.setValue(json.encodeToString(entries))
    }

    /**
     * Merge the saved order with the actions the current theme provides.
     * Active entries come first (in saved order), followed by newly available
     * actions and finally the deleted entries.
     */
    fun resolve(availableVisualOrder: List<String>): List<ToolBarEntry> {
        val available = availableVisualOrder.toSet()
        val saved = load().filter { it.action in available }
        val known = saved.mapTo(mutableSetOf()) { it.action }
        val newcomers = availableVisualOrder
            .filter { it !in known }
            .map { ToolBarEntry(it) }
        return saved.filter { it.enabled } + newcomers + saved.filter { !it.enabled }
    }

    /**
     * The final list the bar consumes: the fixed first button (e.g. the menu),
     * followed by the enabled configurable buttons in their saved left-to-right
     * order. This matches the order shown in [ToolBarConfigDialog].
     */
    fun resolveButtons(theme: Theme): List<ToolBar.Button> {
        val all = theme.toolBar.buttons
        if (all.isEmpty()) return emptyList()
        val fixed = all.first()
        val configurable = all.drop(1)
        if (configurable.isEmpty()) return listOf(fixed)
        // `order` is the left-to-right order of the configurable buttons as they
        // appear in the bar. Both this method and the config dialog walk the same
        // order so "top row = leftmost button" holds.
        val order = configurable.map { it.action }
        val byAction = configurable.associateBy { it.action }
        val visual = resolve(order)
            .filter { it.enabled }
            .mapNotNull { byAction[it.action] }
        return listOf(fixed) + visual
    }

    private val actionLabels = mapOf(
        "Mode_switch" to "中英切换",
        "liquid_keyboard_emoji" to "表情",
        "clipboard_window" to "剪贴板",
        "Keyboard_editor" to "编辑",
        "asr_voice" to "语音",
        "Zenkaku_Hankaku" to "全角/半角",
    )

    fun labelOf(theme: Theme, action: String): String =
        actionLabels[action]
            ?: theme.presetKeys[action]?.label?.takeIf { it.isNotBlank() }
            ?: action
}
