/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.asr

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.view.inputmethod.EditorInfo
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
 */
class AsrImeBridge(private val service: TrimeInputMethodService) {

    private var hasComposingText = false

    private val controller = AsrController(
        service.lifecycleScope,
        object : AsrController.Callback {
            override fun onStateChanged(state: AsrController.State) {
                Timber.d("ASR state: $state")
            }

            override fun onPartialText(text: String) {
                if (text.isEmpty()) return
                service.currentInputConnection?.setComposingText(text, 1)
                hasComposingText = true
            }

            override fun onFinalText(text: String) {
                val ic = service.currentInputConnection
                if (text.isNotEmpty()) {
                    ic?.setComposingText(text, 1)
                    hasComposingText = true
                }
                if (hasComposingText) {
                    ic?.finishComposingText()
                    hasComposingText = false
                }
            }

            override fun onError(message: String) {
                Timber.w("ASR failed: $message")
                service.currentInputConnection?.finishComposingText()
                hasComposingText = false
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
            AsrController.State.CONNECTING -> controller.cancel()
            AsrController.State.RECORDING -> controller.stop()
            AsrController.State.FINALIZING -> Unit
        }
    }

    fun cancel() {
        controller.cancel()
        service.currentInputConnection?.finishComposingText()
        hasComposingText = false
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
        // The recogniser owns the composing region while it runs.
        service.postRimeJob { clearComposition() }
        controller.start(buildContext())
    }

    private fun buildContext(): AsrConfig.Context {
        val editorInfo: EditorInfo? = service.currentInputEditorInfo
        val before = runCatching {
            service.currentInputConnection?.getTextBeforeCursor(CONTEXT_LENGTH, 0)?.toString()
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
    }
}
