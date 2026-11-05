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
 * Connects the recognition controller with the input method service: the current editing context
 * is handed to the recogniser and the transcript is written back into the editor.
 *
 * The recogniser keeps re-sending the whole transcript and keeps refining the beginning, so the
 * text is kept in the editor as a composing region. That region would otherwise grow without
 * bounds during a long dictation, so finished sentences are committed every so often; only the
 * tail stays editable by the recogniser.
 */
class AsrImeBridge(private val service: TrimeInputMethodService) {

    /** Whether the composing region currently holds the recogniser's draft. */
    private var hasComposingText = false

    /**
     * The editor this round is bound to. Callbacks may arrive after the user moved to another
     * field, so never route the transcript through [TrimeInputMethodService.currentInputConnection].
     */
    private var targetConnection: InputConnection? = null

    /** Latest full transcript reported by the recogniser. */
    private var transcript: String = ""

    /** How many characters of [transcript] have already been committed to the editor. */
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
                    val start = committedCount.coerceAtMost(full.length)
                    val remainder = full.substring(start)
                    runCatching {
                        if (remainder.isNotEmpty()) ic.commitText(remainder, 1)
                        ic.finishComposingText()
                    }
                }
                resetDraft()
            }

            override fun onError(message: String) {
                Timber.w("ASR failed: $message")
                commitDraft()
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
     * Ends the running round and keeps whatever has been recognised so far. The draft is
     * committed right away so hiding the keyboard never throws the recogniser's work away.
     */
    fun finish() {
        commitDraft()
        when (controller.currentState) {
            AsrController.State.RECORDING -> controller.stop()
            AsrController.State.CONNECTING -> cancel()
            else -> Unit
        }
    }

    /** Ends the running round and drops the partial transcript of the current sentence. */
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
     * Sentences that are already complete are committed so the composing region stays short, and
     * only the unfinished tail keeps following the recogniser's revisions.
     */
    private fun applyTranscript(text: String) {
        val ic = targetConnection ?: return
        if (text.isEmpty()) return
        transcript = text

        val tailStart = committedCount.coerceAtMost(text.length)
        var boundary = tailStart
        val tailLength = text.length - tailStart

        if (tailLength >= RELEASE_MIN_CHARS) {
            val lastBreak = text.lastIndexOfAny(SENTENCE_BREAKS, startIndex = tailStart)
            if (lastBreak >= tailStart) boundary = lastBreak + 1
        }
        if (boundary == tailStart && tailLength >= RELEASE_HARD_CHARS) {
            boundary = text.length - KEEP_TAIL_CHARS
        }

        if (boundary > tailStart) {
            runCatching { ic.commitText(text.substring(tailStart, boundary), 1) }
            committedCount = boundary
            hasComposingText = false
        }

        val remainder = text.substring(committedCount.coerceAtMost(text.length))
        if (remainder.isNotEmpty()) {
            runCatching { ic.setComposingText(remainder, 1) }
            hasComposingText = true
        } else if (hasComposingText) {
            runCatching { ic.finishComposingText() }
            hasComposingText = false
        }
    }

    /** Commits everything recognised so far, so a later final result only appends the delta. */
    private fun commitDraft() {
        val ic = targetConnection
        if (ic != null && hasComposingText) {
            val remainder = transcript.substring(committedCount.coerceAtMost(transcript.length))
            runCatching {
                if (remainder.isNotEmpty()) ic.commitText(remainder, 1)
                ic.finishComposingText()
            }
        }
        committedCount = transcript.length
        hasComposingText = false
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

        /** Release finished sentences once the draft grows past this size. */
        const val RELEASE_MIN_CHARS = 24

        /** Without any punctuation, force a release at this size to bound the draft. */
        const val RELEASE_HARD_CHARS = 80

        /** Characters kept as a draft after a forced release, so the tail stays editable. */
        const val KEEP_TAIL_CHARS = 10

        val SENTENCE_BREAKS = charArrayOf(
            '。', '！', '？', '；', '，', '、', '：', '…', '\n',
            '.', '!', '?', ';', ',', ':',
        )
    }
}
