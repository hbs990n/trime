/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.main.settings

import android.os.Bundle
import androidx.annotation.Keep
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceScreen
import com.osfans.trime.R
import com.osfans.trime.core.data.FuzzyPinyinConfig
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.data.prefs.PreferenceDelegate
import com.osfans.trime.data.prefs.PreferenceDelegateFragment
import com.osfans.trime.ui.common.withLoadingDialog
import com.osfans.trime.ui.main.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Fuzzy pinyin switches for the 小鹤双拼 schema. Each change rewrites
 * `double_pinyin_flypy.custom.yaml` and redeploys Rime so the dictionaries pick up the new
 * spelling rules.
 */
class FuzzyPinyinSettingsFragment :
    PreferenceDelegateFragment(AppPrefs.defaultInstance().fuzzyPinyin) {

    private val prefs = AppPrefs.defaultInstance().fuzzyPinyin

    private val viewModel: MainViewModel by activityViewModels()

    @Keep
    private val onFuzzyChanged = PreferenceDelegate.OnChangeListener<Boolean> { _, _ ->
        applyConfig()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs.enEng.registerOnChangeListener(onFuzzyChanged)
        prefs.inIng.registerOnChangeListener(onFuzzyChanged)
    }

    override fun onDestroy() {
        prefs.enEng.unregisterOnChangeListener(onFuzzyChanged)
        prefs.inIng.unregisterOnChangeListener(onFuzzyChanged)
        super.onDestroy()
    }

    override fun onPreferenceUiCreated(screen: PreferenceScreen) {
        screen.addPreference(
            Preference(screen.context).apply {
                isIconSpaceReserved = false
                isSingleLineTitle = false
                isSelectable = false
                setTitle(R.string.fuzzy_pinyin_summary)
            },
        )
    }

    private fun applyConfig() {
        lifecycleScope.launch {
            withLoadingDialog(requireContext(), R.string.deploy_progress) {
                if (withContext(Dispatchers.IO) { FuzzyPinyinConfig.write() }) {
                    // A normal restart lets the deployer rebuild the changed schema only,
                    // instead of recompiling every dictionary with a full check.
                    viewModel.rime.runOnReady { updateConfig() }
                }
            }
        }
    }
}
