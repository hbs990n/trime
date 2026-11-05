/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.asr

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.osfans.trime.R
import com.osfans.trime.ime.core.TrimeInputMethodService
import com.osfans.trime.ui.main.MainActivity
import timber.log.Timber

/**
 * Connects the recognition controller with the input method service.
 *
 * The recogniser keeps re-sending the whole transcript and keeps refining its beginning, so the
 * uncommitted part is shown as a composing region (the underlined draft) exactly like before.
 * To stop that draft from growing during a long dictation, a finished sentence is released by
 * ending the composing region — `finishComposingText()` keeps every character that is already in
 * the editor, so nothing can be lost or duplicated. The following sentences then start a new
 * composing region.
 */
class AsrImeBridge(private val service: TrimeInputMethodService) {

    /** Whether a composing region currently holds the recogniser's draft. */
    private var hasComposingText = false

    /**
     * The editor this round is bound to. Callbacks may arrive after the user moved to another
     * field, so never route the transcript through [TrimeInputMethodService.currentInputConnection].
     */
    private var targetConnection: InputConnection? = null

    /** Latest full transcript reported by the recogniser. */
    private var transcript: String = ""

    /** How many characters of [transcript] the editor already holds as plain (released) text. */
    private var committedCount: Int = 0

    private val controller = AsrController(
        service.lifecycleScope,
        object : AsrController.Callback {
            override fun onStateChanged(state: AsrController.State) {
                Timber.d("ASR state: $state")
                service.setVoiceInputIndicator(state != AsrController.State.IDLE)
            }

            override fun onPartialText(text: String) {
                applyTranscript(text)
            }

            override fun onFinalText(text: String) {
                val ic = targetConnection
                val full = text.ifEmpty { transcript }
                if (ic != null) {
                    val tail = full.substring(committedCount.coerceAtMost(full.length))
                    runCatching {
                        if (tail.isNotEmpty()) ic.setComposingText(tail, 1)
                        ic.finishComposingText()
                    }
                }
                resetDraft()
            }

            override fun onError(message: String) {
                Timber.w("ASR failed: $message")
                closeDraft()
                resetDraft()
                Toast.makeText(service, message, Toast.LENGTH_SHORT).show()
            }
        },
    )

    val isActive: Boolean
        get() = controller.currentState != AsrController.State.IDLE

    /** Starts a recognition round, or stops the running one. */
    fun toggle() {
        when (controller.currentState) {
            AsrController.State.IDLE -> start()
            AsrController.State.CONNECTING -> cancel()
            AsrController.State.RECORDING -> finish()
            AsrController.State.FINALIZING -> Unit
        }
    }

    /**
     * Ends the running round and keeps whatever has been recognised so far. The current draft is
     * released right away (without being rewritten), so hiding the keyboard never throws the
     * recogniser's work away.
     */
    fun finish() {
        closeDraft()
        when (controller.currentState) {
            AsrController.State.RECORDING -> controller.stop()
            AsrController.State.CONNECTING -> cancel()
            else -> Unit
        }
    }

    /** Ends the running round and drops the partial transcript of the current draft. */
    fun cancel() {
        controller.cancel()
        val ic = targetConnection
        if (hasComposingText && ic != null) {
            runCatching {
                ic.setComposingText("", 1)
                ic.finishComposingText()
            }
        }
        resetDraft()
    }

    private fun start() {
        if (!hasMicrophonePermission()) {
            Toast.makeText(service, R.string.asr_microphone_permission_required, Toast.LENGTH_LONG).show()
            service.startActivity(
                Intent(service, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(MainActivity.EXTRA_REQUEST_MIC_PERMISSION, true),
            )
            return
        }
        resetDraft()
        targetConnection = service.currentInputConnection
        // The recogniser owns the composing region while it runs.
        service.postRimeJob { clearComposition() }
        controller.start(buildContext())
    }

    private fun resetDraft() {
        transcript = ""
        committedCount = 0
        hasComposingText = false
        targetConnection = null
    }

    /**
     * Writes the newest transcript into the editor.
     *
     * Only the part that is not released yet is kept as composing text, so the recogniser can
     * still refine it. When the draft reaches a sentence boundary, it is released as plain text
     * and the next sentence starts a fresh composing region.
     */
    private fun applyTranscript(text: String) {
        val ic = targetConnection ?: return
        if (text.isEmpty()) return
        transcript = text

        val start = committedCount.coerceAtMost(text.length)
        val tail = text.substring(start)

        if (tail.isEmpty()) {
            if (hasComposingText) {
                runCatching { ic.finishComposingText() }
                hasComposingText = false
            }
            return
        }

        val sentenceFinished = endsWithSentenceBreak(tail)
        val tooLong = tail.length >= RELEASE_HARD_CHARS
        if ((sentenceFinished && tail.length >= RELEASE_MIN_CHARS) || tooLong) {
            // Release everything the editor already holds; the text itself is not rewritten.
            runCatching { ic.finishComposingText() }
            hasComposingText = false
            committedCount = text.length
            return
        }

        runCatching { ic.setComposingText(tail, 1) }
        hasComposingText = true
    }

    /**
     * Ends the current drafting region so its characters stay as plain text. Later results are
     * only appended relative to [committedCount], which avoids rewriting (and losing) them.
     */
    private fun closeDraft() {
        val ic = targetConnection
        if (ic != null && hasComposingText) {
            runCatching { ic.finishComposingText() }
        }
        hasComposingText = false
        committedCount = transcript.length
    }

    /** True when [text] ends with a sentence terminator, ignoring trailing quotes/brackets. */
    private fun endsWithSentenceBreak(text: String): Boolean {
        var index = text.length - 1
        while (index >= 0 && text[index] in TRAILING_CHARS) index--
        return index >= 0 && text[index] in SENTENCE_BREAKS
    }

    private fun buildContext(): AsrConfig.Context {
        val editorInfo: EditorInfo? = service.currentInputEditorInfo
        val before = runCatching {
            targetConnection?.getTextBeforeCursor(CONTEXT_LENGTH, 0)?.toString()
        }.getOrNull().orEmpty()
        return AsrConfig.Context(
            appPackage = editorInfo?.packageName?.toString() ?: service.packageName,
            textBeforeCursor = before,
            cursorPosition = before.length,
            inputType = editorInfo?.inputType?.let { inputTypeName(it) } ?: "TYPE_CLASS_TEXT",
        )
    }

    private fun hasMicrophonePermission(): Boolean = ContextCompat.checkSelfPermission(
        service,
        Manifest.permission.RECORD_AUDIO,
    ) == PackageManager.PERMISSION_GRANTED

    private fun inputTypeName(inputType: Int): String = when (inputType and android.text.InputType.TYPE_MASK_CLASS) {
        android.text.InputType.TYPE_CLASS_TEXT -> "TYPE_CLASS_TEXT"
        android.text.InputType.TYPE_CLASS_NUMBER -> "TYPE_CLASS_NUMBER"
        android.text.InputType.TYPE_CLASS_PHONE -> "TYPE_CLASS_PHONE"
        else -> "TYPE_CLASS_TEXT"
    }

    private companion object {
        const val CONTEXT_LENGTH = 200

        /** Release a finished sentence only after the draft has at least this many characters. */
        const val RELEASE_MIN_CHARS = 8

        /** Without any sentence break, release the draft at this size to bound it. */
        const val RELEASE_HARD_CHARS = 60

        /** Sentence terminators that allow a draft to be released. */
        val SENTENCE_BREAKS = charArrayOf('。', '！', '？', '；', '…', '\n', '.', '!', '?', ';')

        /** Characters that may follow a terminator, e.g. closing quotes. */
        val TRAILING_CHARS = charArrayOf('”', '’', '」', '』', '）', ')', '】', ']', '"', '\'', ' ')
    }
}
