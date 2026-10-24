/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.phrases

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.widget.EditText
import com.osfans.trime.R
import com.osfans.trime.databinding.ActivityClipEditBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import splitties.systemservices.inputMethodManager

/**
 * A small, dialog-themed activity used to add or edit a single common phrase.
 *
 * Using a real activity (instead of a window glued to the IME) means it owns a
 * valid window token, so the soft keyboard works normally and nothing covers the
 * keyboard. [EXTRA_INDEX] < 0 adds a new phrase; otherwise it edits that index.
 */
class PhraseEditActivity : Activity() {
    private val scope: CoroutineScope = MainScope()
    private var index: Int = -1
    private lateinit var editText: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        index = intent.getIntExtra(EXTRA_INDEX, -1)
        window.attributes.gravity = Gravity.TOP
        val binding =
            ActivityClipEditBinding.inflate(layoutInflater).apply {
                editText = clipEditText
                clipEditText.minLines = 8
                clipEditText.setHint(R.string.phrase_hint)
                clipEditCancel.setOnClickListener { finish() }
                clipEditOk.setOnClickListener { finishEditing() }
            }
        setContentView(binding.root)
        setTitle(if (index < 0) R.string.phrase_add else R.string.edit)
        editText.setText(intent.getStringExtra(EXTRA_TEXT) ?: "")
        inputMethodManager.showSoftInput(editText, 0)
    }

    private fun finishEditing() {
        val text = editText.editableText.toString().trim()
        scope.launch {
            withContext(Dispatchers.IO) {
                val phrases = PhraseManager.load(this@PhraseEditActivity)
                if (text.isNotEmpty()) {
                    if (index in phrases.indices) phrases[index] = text else phrases.add(text)
                    PhraseManager.save(this@PhraseEditActivity, phrases)
                }
            }
            finish()
        }
    }

    override fun onStop() {
        super.onStop()
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    companion object {
        const val EXTRA_INDEX = "phrase_index"
        const val EXTRA_TEXT = "phrase_text"
    }
}
