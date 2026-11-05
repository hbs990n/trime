/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.core.data

import com.osfans.trime.data.prefs.AppPrefs
import java.io.File

/**
 * Writes the fuzzy-pinyin switches into the user's `double_pinyin_flypy.custom.yaml`.
 *
 * Rime only knows fuzzy spelling through `speller/algebra`, so the generated patch carries the
 * whole algebra of the shared schema with the selected `derive` rules inserted before the finals
 * are folded into double-pinyin letters.
 */
object FuzzyPinyinConfig {
    private const val SCHEMA_ID = "double_pinyin_flypy"

    /** Algebra of the shared 小鹤双拼 schema, in the same order. */
    private val BASE_ALGEBRA = listOf(
        "erase/^xx$/",
        "derive/^([jqxy])u$/\$1v/",
        "derive/^([aoe])([ioun])$/\$1\$1\$2/",
        // Fuzzy spelling rules are inserted here.
        "xform/^([aoe])(ng)?$/\$1\$1\$2/",
        "xform/iu$/Q/",
        "xform/(.)ei$/\$1W/",
        "xform/uan$/R/",
        "xform/[uv]e$/T/",
        "xform/un$/Y/",
        "xform/^sh/U/",
        "xform/^ch/I/",
        "xform/^zh/V/",
        "xform/uo$/O/",
        "xform/ie$/P/",
        "xform/i?ong$/S/",
        "xform/ing$|uai$/K/",
        "xform/(.)ai$/\$1D/",
        "xform/(.)en$/\$1F/",
        "xform/(.)eng$/\$1G/",
        "xform/[iu]ang$/L/",
        "xform/(.)ang$/\$1H/",
        "xform/ian$/M/",
        "xform/(.)an$/\$1J/",
        "xform/(.)ou$/\$1Z/",
        "xform/[iu]a$/X/",
        "xform/iao$/N/",
        "xform/(.)ao$/\$1C/",
        "xform/ui$/V/",
        "xform/in$/B/",
        "xlit/QWRTYUIOPSDFGHJKLZXCVBNM/qwrtyuiopsdfghjklzxcvbnm/",
    )

    private val EN_ENG_RULES = listOf(
        "derive/(.)en$/\$1eng/",
        "derive/(.)eng$/\$1en/",
        "derive/^en$/eng/",
        "derive/^eng$/en/",
    )

    private val IN_ING_RULES = listOf(
        "derive/(.)in$/\$1ing/",
        "derive/(.)ing$/\$1in/",
        "derive/^in$/ing/",
        "derive/^ing$/in/",
    )

    private fun customFile() = File(UserDataManager.userDataDir, "$SCHEMA_ID.custom.yaml")

    /**
     * Rewrites (or removes) the schema patch according to the current switches.
     *
     * @return true when the file content changed, i.e. a redeploy is needed.
     */
    fun write(): Boolean {
        val prefs = AppPrefs.defaultInstance().fuzzyPinyin
        val enEng = prefs.enEng.getValue()
        val inIng = prefs.inIng.getValue()
        val file = customFile()

        if (!enEng && !inIng) {
            if (!file.exists()) return false
            file.delete()
            return true
        }

        val content = render(enEng, inIng)
        if (file.exists() && file.readText() == content) return false
        file.parentFile?.mkdirs()
        file.writeText(content)
        return true
    }

    private fun render(
        enEng: Boolean,
        inIng: Boolean,
    ): String = buildString {
        appendLine("# 由同文输入法设置自动生成，修改会被覆盖。")
        appendLine("patch:")
        appendLine("  speller/algebra:")
        BASE_ALGEBRA.forEach { rule ->
            appendLine("    - ${quote(rule)}")
            if (rule == "derive/^([aoe])([ioun])$/\$1\$1\$2/") {
                if (enEng) EN_ENG_RULES.forEach { appendLine("    - ${quote(it)}") }
                if (inIng) IN_ING_RULES.forEach { appendLine("    - ${quote(it)}") }
            }
        }
    }

    /** The rules contain `$` and `/`, so always emit them as double quoted scalars. */
    private fun quote(rule: String) = "\"" + rule.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
