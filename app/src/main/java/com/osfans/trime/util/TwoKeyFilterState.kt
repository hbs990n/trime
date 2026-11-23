/*
 * 双键布局：拼音组筛选状态。
 *
 * 引擎通过 speller.algebra derive 为同一串代表码生成多组拼音候选，
 * 候选以 comment（全拼）区分。这里在 UI 层按 comment 过滤候选栏，
 * 不改引擎输入，这样后续输入仍然由 Rime 原生消歧。
 */
package com.osfans.trime.util

import com.osfans.trime.core.Candidates
import com.osfans.trime.core.CandidateProto

object TwoKeyFilterState {
    /** 是否处于双键拼音组模式。 */
    @Volatile
    var enabled: Boolean = false
        private set

    /** 当前锁定的拼音（对应 candidate.comment），null 表示尚未锁定。 */
    @Volatile
    var selected: String? = null
        private set

    /** 最近一次候选列表，供点击泡泡后原地刷新候选栏。 */
    @Volatile
    var lastBulk: Candidates.Bulk? = null
        private set

    /** 过滤后列表位置 -> 原始候选索引，供点击/长按时选字使用。 */
    @Volatile
    var indexMap: IntArray = intArrayOf()
        private set

    /** 分页候选窗（物理键盘/悬浮候选）使用的索引映射。 */
    @Volatile
    var pagedIndexMap: IntArray = intArrayOf()
        private set

    private const val MAX_GROUPS = 5

    /** 从候选的 comment 中提取拼音组，保序去重。 */
    fun extractGroups(candidates: Array<CandidateProto>): List<String> {
        val groups = LinkedHashSet<String>()
        for (cand in candidates) {
            val py = cand.comment.trim()
            if (py.isNotEmpty()) groups.add(py)
            if (groups.size >= MAX_GROUPS) break
        }
        return groups.toList()
    }

    /**
     * 收到新候选列表时同步状态。若当前锁定的拼音不在新列表中，回落到第一组。
     * 返回当前应当展示的拼音组和高亮下标。
     */
    @Synchronized
    fun onCandidates(
        enabled: Boolean,
        data: Candidates.Bulk,
    ): Pair<List<String>, Int> {
        this.enabled = enabled
        lastBulk = data
        if (!enabled) {
            selected = null
            indexMap = intArrayOf()
            pagedIndexMap = intArrayOf()
            return emptyList<String>() to -1
        }
        val groups = extractGroups(data.candidates)
        if (groups.isEmpty()) {
            selected = null
            indexMap = intArrayOf()
            pagedIndexMap = intArrayOf()
            return emptyList<String>() to -1
        }
        val current = selected
        val chosen = if (current != null && groups.contains(current)) current else groups[0]
        selected = chosen
        return groups to groups.indexOf(chosen)
    }

    /** 点击某个拼音泡泡：只改筛选目标，不动引擎编码。 */
    @Synchronized
    fun pick(group: String) {
        selected = group
        TwoKeyDiag.log("filter", "PICK group=$group")
    }

    /** 编码清空后解除筛选。 */
    @Synchronized
    fun reset() {
        enabled = false
        selected = null
        lastBulk = null
        indexMap = intArrayOf()
        pagedIndexMap = intArrayOf()
    }

    /**
     * 按当前锁定的拼音过滤候选列表。
     * 返回 null 表示无需过滤（非双键模式、只有一组拼音、或过滤结果为空）。
     */
    @Synchronized
    fun filter(data: Candidates.Bulk): Candidates.Bulk? {
        if (!enabled) return null
        val groups = extractGroups(data.candidates)
        if (groups.size <= 1) return null
        val group = selected?.takeIf { groups.contains(it) } ?: groups[0]
        val picked = ArrayList<CandidateProto>()
        val indices = ArrayList<Int>()
        data.candidates.forEachIndexed { i, cand ->
            if (cand.comment.trim() == group) {
                picked.add(cand)
                indices.add(i)
            }
        }
        if (picked.isEmpty()) return null
        indexMap = indices.toIntArray()
        val newHighlight = indices.indexOf(data.highlighted).takeIf { it >= 0 } ?: 0
        TwoKeyDiag.log(
            "filter",
            "FILTER group=$group raw=${data.candidates.size} kept=${picked.size} " +
                "highlight=$newHighlight",
        )
        return Candidates.Bulk(picked.size, newHighlight, picked.toTypedArray())
    }

    /** 分页候选：按当前拼音组过滤当前页，保留原分页标志。 */
    @Synchronized
    fun filterPaged(data: Candidates.Paged): Candidates.Paged? {
        if (!enabled) return null
        val groups = extractGroups(data.candidates)
        if (groups.size <= 1) return null
        val group = selected?.takeIf { groups.contains(it) } ?: groups[0]
        val picked = ArrayList<CandidateProto>()
        val indices = ArrayList<Int>()
        data.candidates.forEachIndexed { i, cand ->
            if (cand.comment.trim() == group) {
                picked.add(cand)
                indices.add(i)
            }
        }
        if (picked.isEmpty()) return null
        pagedIndexMap = indices.toIntArray()
        val newHighlight = indices.indexOf(data.highlighted).takeIf { it >= 0 } ?: 0
        return Candidates.Paged(
            hasPrevPage = data.hasPrevPage,
            hasNextPage = data.hasNextPage,
            isHorizontalLayout = data.isHorizontalLayout,
            highlighted = newHighlight,
            candidates = picked.toTypedArray(),
        )
    }

    /** 把候选栏的显示位置映射回 Rime 原始索引。 */
    fun rawIndex(displayIndex: Int): Int = indexMap.getOrNull(displayIndex) ?: displayIndex

    /** 分页候选窗的显示位置映射。 */
    fun rawPagedIndex(displayIndex: Int): Int = pagedIndexMap.getOrNull(displayIndex) ?: displayIndex
}
