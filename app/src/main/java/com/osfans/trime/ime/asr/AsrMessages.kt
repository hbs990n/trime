/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.asr

/**
 * Builders for the uplink messages of the Doubao IME speech channel.
 */
internal object AsrMessages {
    /** Opens a task. The server answers with `TaskStarted`. */
    fun startTask(uid: String): ByteArray = AsrProto.string(2, AsrConfig.TOKEN) +
        AsrProto.string(3, "ASR") +
        AsrProto.string(5, "StartTask") +
        AsrProto.string(8, uid)

    /** Opens a recognition session. The server answers with `SessionStarted`. */
    fun startSession(sessionJson: ByteArray): ByteArray = AsrProto.string(2, AsrConfig.TOKEN) +
        AsrProto.string(3, "ASR") +
        AsrProto.string(5, "StartSession") +
        AsrProto.bytes(6, sessionJson)

    /** Carries one audio frame. */
    fun taskRequest(audio: ByteArray, timestampMs: Long): ByteArray {
        val meta = "{\"timestamp_ms\":$timestampMs}".toByteArray(Charsets.UTF_8)
        return AsrProto.string(3, "ASR") +
            AsrProto.string(5, "TaskRequest") +
            AsrProto.bytes(6, meta) +
            AsrProto.bytes(7, audio)
    }

    /** Closes the session. The server answers with `SessionFinished`. */
    fun finishSession(uid: String): ByteArray = AsrProto.string(2, AsrConfig.TOKEN) +
        AsrProto.string(3, "ASR") +
        AsrProto.string(5, "FinishSession") +
        AsrProto.string(8, uid)
}
