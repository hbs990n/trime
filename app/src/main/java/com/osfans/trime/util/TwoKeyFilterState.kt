/*
 * 双键布局：拼音组筛选状态。
 *
 * 引擎通过 speller.algebra derive 为同一串代表码生成多组拼音候选。
 * 单字候选带 comment（全拼），词组候选不带 comment；这里先用候选里的
 * 单字 comment 建立「字->拼音」映射，再用它把词组候选还原成拼音组合，
 * 从而把所有读法（wo le / qie le ...）摆到泡泡里，按 Rime 候选顺序（词频）排序。
 * 点选只过滤候选栏，不改引擎输入，后续输入仍由 Rime 原生消歧。
 */
package com.osfans.trime.util

import com.osfans.trime.core.Candidates
import com.osfans.trime.core.CandidateProto

object TwoKeyFilterState {
    /** 是否处于双键拼音组模式。 */
    @Volatile
    var enabled: Boolean = false
        private set

    /** 当前锁定的拼音组合（全拼，音节间空格分隔），null 表示尚未锁定。 */
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

    private const val MAX_GROUPS = 6

    /**
     * 常用字->拼音兜底表：词组候选没有 comment，靠它逐字还原。
     * 候选里的单字 comment 会覆盖这里的同名字。
     */
    private val FALLBACK_PINYIN =
        mapOf(
            '我' to "wo", '握' to "wo", '窝' to "wo", '喔' to "wo", '卧' to "wo", '沃' to "wo",
            '切' to "qie", '且' to "qie", '窃' to "qie", '怯' to "qie",
            '了' to "le", '乐' to "le", '勒' to "le",
            '你' to "ni", '尼' to "ni", '呢' to "ni", '泥' to "ni", '拟' to "ni",
            '的' to "de", '得' to "de", '德' to "de",
            '不' to "bu", '步' to "bu", '部' to "bu", '布' to "bu", '补' to "bu",
            '是' to "shi", '事' to "shi", '时' to "shi", '十' to "shi", '实' to "shi",
            '说' to "shuo", '在' to "zai", '再' to "zai", '载' to "zai",
            '有' to "you", '又' to "you", '友' to "you", '右' to "you",
            '和' to "he", '何' to "he", '河' to "he", '合' to "he",
            '会' to "hui", '回' to "hui", '要' to "yao", '也' to "ye", '还' to "hai",
            '就' to "jiu", '都' to "dou", '而' to "er", '及' to "ji", '与' to "yu",
            '这' to "zhe", '那' to "na", '大' to "da", '小' to "xiao",
            '上' to "shang", '下' to "xia", '中' to "zhong", '人' to "ren",
            '为' to "wei", '个' to "ge", '国' to "guo", '年' to "nian",
            '天' to "tian", '地' to "di", '可' to "ke", '以' to "yi",
            '能' to "neng", '没' to "mei", '很' to "hen", '好' to "hao",
            '来' to "lai", '去' to "qu", '他' to "ta", '她' to "ta", '它' to "ta",
            '们' to "men", '什' to "shen", '么' to "me", '怎' to "zen", '样' to "yang",
            '才' to "cai", '已' to "yi", '经' to "jing", '过' to "guo",
            '出' to "chu", '发' to "fa", '生' to "sheng", '自' to "zi", '己' to "ji",
            '想' to "xiang", '知' to "zhi", '道' to "dao", '看' to "kan", '见' to "jian",
        )

    /** 从带 comment 的单字候选建立「字->拼音」映射。 */
    private fun buildCharMap(candidates: Array<CandidateProto>): Map<Char, String> {
        val map = HashMap<Char, String>()
        for (cand in candidates) {
            val py = cand.comment.trim()
            if (py.isNotEmpty() && cand.text.length == 1) {
                map[cand.text[0]] = py
            }
        }
        return map
    }

    /** 推导候选的拼音组合；无法推导返回空串。 */
    private fun pinyinOf(
        cand: CandidateProto,
        charMap: Map<Char, String>,
    ): String {
        val direct = cand.comment.trim()
        if (direct.isNotEmpty()) return direct
        if (cand.text.isEmpty()) return ""
        val parts = ArrayList<String>(cand.text.length)
        for (ch in cand.text) {
            val py = charMap[ch] ?: FALLBACK_PINYIN[ch] ?: return ""
            parts.add(py)
        }
        return parts.joinToString(" ")
    }

    /** 计算所有候选的拼音组合。 */
    private fun pinyinsOf(candidates: Array<CandidateProto>): Array<String> {
        val charMap = buildCharMap(candidates)
        return Array(candidates.size) { i -> pinyinOf(candidates[i], charMap) }
    }

    /** 从候选中提取不重复的拼音组合，按候选顺序（即 Rime 词频权重）保序。 */
    fun extractGroups(candidates: Array<CandidateProto>): List<String> {
        val groups = LinkedHashSet<String>()
        for (py in pinyinsOf(candidates)) {
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
     * 按当前锁定的拼音组合过滤候选列表。
     * 返回 null 表示无需过滤（非双键模式、只有一组、或过滤结果为空）。
     */
    @Synchronized
    fun filter(data: Candidates.Bulk): Candidates.Bulk? {
        if (!enabled) return null
        val groups = extractGroups(data.candidates)
        if (groups.size <= 1) return null
        val group = selected?.takeIf { groups.contains(it) } ?: groups[0]
        val pinyins = pinyinsOf(data.candidates)
        val picked = ArrayList<CandidateProto>()
        val indices = ArrayList<Int>()
        data.candidates.forEachIndexed { i, cand ->
            if (pinyins[i] == group) {
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
        val pinyins = pinyinsOf(data.candidates)
        val picked = ArrayList<CandidateProto>()
        val indices = ArrayList<Int>()
        data.candidates.forEachIndexed { i, cand ->
            if (pinyins[i] == group) {
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
