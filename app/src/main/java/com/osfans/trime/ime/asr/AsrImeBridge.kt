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
 * The recogniser keeps re-sending the whole transcript and may later insert punctuation into a
 * sentence that was already shown. Released text therefore cannot be addressed by a plain
 * character offset: the anchor is the released text with punctuation and whitespace stripped.
 * Only the tail after that anchor stays in a composing region and can still be refined.
 *
 * Streaming results often carry no punctuation at all (the server adds it in a later pass), so a
 * terminator is appended whenever a tail is released without one. That is what keeps consecutive
 * released sentences separated instead of running into each other.
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

    /** Text already released to the editor this round, including punctuation appended by us. */
    private var releasedText: String = ""

    /** Whether [releasedText] ends with punctuation, so a repeated one from the server is skipped. */
    private var releasedEndsWithBreak = false

    /** Exactly what the composing region currently holds, so releasing it never loses it. */
    private var draftText: String = ""

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
                    val tail = tailAfterReleased(full)
                    runCatching {
                        if (tail.isNotEmpty()) ic.setComposingText(ensureSentenceBreak(tail, '。'), 1)
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
        releasedText = ""
        releasedEndsWithBreak = false
        draftText = ""
        hasComposingText = false
        targetConnection = null
    }

    /**
     * Writes the newest transcript into the editor.
     *
     * Only the part that is not released yet is kept as composing text, so the recogniser can
     * still refine it. When a sentence ends (or the draft grows too long) the newest tail is
     * written first, given a terminator if needed, and then released as plain text.
     */
    private fun applyTranscript(text: String) {
        val ic = targetConnection ?: return
        if (text.isEmpty()) return
        transcript = text

        val tail = tailAfterReleased(text)
        if (tail.isEmpty()) {
            if (hasComposingText) {
                // The server shortened the transcript; release whatever the editor still holds.
                val released = ensureSentenceBreak(draftText, '。')
                runCatching {
                    if (released != draftText) ic.setComposingText(released, 1)
                    ic.finishComposingText()
                }
                releasedText += released
                releasedEndsWithBreak = endsWithSentenceBreak(releasedText)
                hasComposingText = false
                draftText = ""
            }
            return
        }

        val plainLength = plainOf(tail).length
        val sentenceFinished = endsWithSentenceBreak(tail)
        val shouldRelease = (sentenceFinished && plainLength >= RELEASE_MIN_CHARS) ||
            plainLength >= RELEASE_HARD_CHARS
        if (shouldRelease) {
            // Always write the newest tail before ending the composing region. Ending it without
            // this write would release the previous result and drop everything added by this one.
            val mark = if (sentenceFinished) '。' else '，'
            val released = ensureSentenceBreak(tail, mark)
            runCatching {
                ic.setComposingText(released, 1)
                ic.finishComposingText()
            }
            hasComposingText = false
            draftText = ""
            releasedText += released
            releasedEndsWithBreak = endsWithSentenceBreak(releasedText)
            return
        }

        runCatching { ic.setComposingText(tail, 1) }
        draftText = tail
        hasComposingText = true
    }

    /**
     * Releases the current draft as plain text and keeps [releasedText] in sync with the editor,
     * so a later final result only appends what is genuinely missing.
     */
    private fun closeDraft() {
        val ic = targetConnection
        if (ic != null && hasComposingText) {
            val tail = tailAfterReleased(transcript)
            val released = if (tail.isEmpty()) "" else ensureSentenceBreak(tail, '。')
            runCatching {
                if (released.isNotEmpty()) ic.setComposingText(released, 1)
                ic.finishComposingText()
            }
            releasedText += released
            releasedEndsWithBreak = endsWithSentenceBreak(releasedText)
        }
        hasComposingText = false
        draftText = ""
    }

    /**
     * Returns the part of [full] that has not been released yet.
     *
     * Counting letters/digits instead of raw characters keeps the anchor stable when the server
     * inserts punctuation into an already released sentence. Punctuation immediately after the
     * anchor is skipped because we appended our own terminator there.
     */
    private fun tailAfterReleased(full: String): String {
        val anchorLength = plainOf(releasedText).length
        if (anchorLength == 0) return full
        var seen = 0
        for (index in full.indices) {
            if (!full[index].isLetterOrDigit()) continue
            seen++
            if (seen == anchorLength) {
                var start = index + 1
                if (releasedEndsWithBreak) {
                    while (start < full.length && !full[start].isLetterOrDigit()) start++
                }
                return full.substring(start)
            }
        }
        return ""
    }

    /** The recogniser's text without punctuation or whitespace, used only for alignment. */
    private fun plainOf(text: String): String = text.filter { it.isLetterOrDigit() }

    /** True when [text] ends with a terminator, ignoring trailing quotes/brackets. */
    private fun endsWithSentenceBreak(text: String): Boolean {
        var index = text.length - 1
        while (index >= 0 && text[index] in TRAILING_CHARS) index--
        return index >= 0 && text[index] in SENTENCE_BREAKS
    }

    /** Appends [mark] when [text] does not already end with punctuation. */
    private fun ensureSentenceBreak(text: String, mark: Char): String {
        val trimmed = text.trimEnd()
        if (trimmed.isEmpty()) return text
        var index = trimmed.length - 1
        while (index >= 0 && trimmed[index] in TRAILING_CHARS) index--
        if (index < 0) return text
        return if (trimmed[index] in SENTENCE_BREAKS) trimmed else "$trimmed$mark"
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

        /** Terminators and short pauses that allow a draft to be released. */
        val SENTENCE_BREAKS = charArrayOf(
            '。', '！', '？', '；', '…', '，', '、', '：', '\n',
            '.', '!', '?', ';', ',', ':',
        )

        /** Characters that may follow a terminator, e.g. closing quotes. */
        val TRAILING_CHARS = charArrayOf('”', '’', '」', '』', '）', ')', '】', ']', '"', '\'', ' ')
    }
}
