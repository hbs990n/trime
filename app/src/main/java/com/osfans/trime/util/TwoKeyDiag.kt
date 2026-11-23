/*
 * Temporary diagnostics for the 13-key (双键) layout port.
 * Removes themselves once the layout is verified; safe to delete in one go.
 */
package com.osfans.trime.util

import com.osfans.trime.TrimeApplication
import timber.log.Timber
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Writes a plain-text trace to /sdcard/Android/data/<pkg>/files/twokey_diag.log
 * so it can be pulled with adb after one reproduction pass.
 *
 * Every write goes through log() which always mirrors to Timber/logcat.
 */
object TwoKeyDiag {
    private const val TAG = "TwoKeyDiag"
    private const val FILE_NAME = "twokey_diag.log"
    // Hot-patch verification marker: change this string to prove a swapped dex is live.
    private const val BUILD_MARKER = "HOTPATCH_AOSP_V1"
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.getDefault())
    private val lock = Any()

    private val file: File
        get() = File(TrimeApplication.getInstance().externalFilesDir, FILE_NAME)

    /** Mark a fresh session. Called once from Rime startup. */
    fun session(tag: String) {
        val line = "===== SESSION $tag [$BUILD_MARKER] @ ${fmt.format(Date())} ====="
        Timber.d("$TAG $line")
        synchronized(lock) {
            runCatching { file.appendText(line + "\n") }
        }
    }

    fun log(tag: String, msg: String) {
        val line = "${fmt.format(Date())} [$tag] $msg"
        Timber.d("$TAG $line")
        synchronized(lock) {
            runCatching { file.appendText(line + "\n") }
        }
    }

    /** Dump the on-device config so we can verify what Rime actually deployed. */
    fun dumpConfig(label: String, text: String) {
        val normalized = text.replace("\r", "")
        log(label, ">>>\n${normalized.take(4000)}\n<<<")
    }
}
