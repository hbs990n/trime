/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.keyboard

import com.osfans.trime.core.RimeApi
import com.osfans.trime.daemon.RimeSession
import com.osfans.trime.daemon.launchOnReady
import timber.log.Timber

/**
 * 双键布局的「多义键消歧」引擎。
 *
 * 13 键布局把 26 个字母压缩到 14 个物理键，每个键代表一组字母（如 QW 键代表 {q,w}）。
 * 在双拼输入法中一个音节恰好两个字母，因此用户连按两个多义键后，把所有字母组合
 * （如 qo/qp/wo/wp）枚举出来，用引擎词库静默探测出其中合法的拼音（如 wo/qp），
 * 让用户先选拼音、再选字——与讯飞双键布局的内部消歧链路等价。
 *
 * 探测全程走 [RimeApi.probeCandidates]，不触发 UI 刷新或上屏。
 */
class MultiKeyEngine(
    private val rime: RimeSession,
) {
    /** 一次候选选择：既列出拼音，也附带该拼音的首个字词，便于界面展示 */
    data class Choice(
        val pinyin: String,
        val preview: String,
    )

    fun interface Callback {
        /** 枚举完成。[choices] 为空表示没有合法拼音，调用方应回退到默认输入。 */
        fun onResult(choices: List<Choice>, rawLetters: String)
    }

    /** 当前已按下、尚未消歧的字母集（每个元素是一个键代表的候选字符集） */
    private val pending = mutableListOf<String>()

    var callback: Callback? = null

    /** 是否正处于多义键输入过程中（有未完成的待定字母） */
    val isPending: Boolean get() = pending.isNotEmpty()

    /** 当前双拼一个音节需要的字母位数（固定 2）。全拼模式不启用本引擎。 */
    private val syllableLength = 2

    /** 判断当前方案是否为双拼（仅在双拼下启用消歧，全拼保持逐字母输入） */
    fun isShuangpinMode(): Boolean {
        val id = rime.run { statusCached }.schemaId
        return id.contains("double_pinyin", ignoreCase = true)
    }

    /**
     * 计入一次多义键点击。[letters] 是该键代表的字母集（小写，如 "qw"）。
     * 集齐一个音节的位数后触发枚举探测。
     */
    fun accept(letters: String) {
        if (!isShuangpinMode()) {
            pending.clear()
            return
        }
        pending.add(letters.lowercase())
        Timber.d("MultiKey accept: $letters, pending=$pending")
        if (pending.size >= syllableLength) {
            val snapshot = pending.take(syllableLength)
            pending.clear()
            resolve(snapshot)
        }
    }

    /** 用户按退格等键时取消待定状态 */
    fun cancel() {
        if (pending.isNotEmpty()) {
            pending.clear()
            Timber.d("MultiKey cancelled")
        }
    }

    private fun resolve(sets: List<String>) {
        // 枚举笛卡尔积：qw × op -> qo, qp, wo, wp
        var combos = listOf("")
        for (set in sets) {
            combos = combos.flatMap { prefix -> set.map { prefix + it } }
        }
        val rawLetters = combos.joinToString("")
        Timber.d("MultiKey combos: $combos")

        rime.launchOnReady { api ->
            val choices = probe(api, combos)
            Timber.d("MultiKey valid choices: ${choices.map { it.pinyin }}")
            callback?.onResult(choices, rawLetters)
        }
    }

    private suspend fun probe(api: RimeApi, combos: List<String>): List<Choice> {
        // 编码区已有内容时不消歧，避免破坏用户正在输入的其它内容
        if (api.getRawInput().isNotEmpty()) return emptyList()
        val seen = LinkedHashSet<String>()
        val result = mutableListOf<Choice>()
        for (combo in combos) {
            if (!seen.add(combo)) continue
            val cands = api.probeCandidates(combo)
            if (cands.isNotEmpty()) {
                result.add(Choice(combo, cands.first().text))
            }
        }
        return result
    }

    /** 选定某个拼音后，把它正常送入引擎编码区，进入常规选字流程 */
    fun commitChoice(pinyin: String) {
        rime.launchOnReady { api ->
            api.clearComposition()
            api.simulateKeySequence(pinyin)
        }
    }
}
