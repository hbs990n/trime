/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.asr

import java.io.ByteArrayOutputStream

/**
 * Builders for the uplink messages of the Doubao IME speech channel.
 *
 * Audio frames are produced 50 times a second. Building one by concatenating temporary
 * byte arrays allocates a handful of objects per frame and shows up as CPU/GC spikes on
 * low-end devices. Frames are therefore written straight into one reused buffer; the
 * uncommon handshake messages keep the simple immutable form.
 */
internal object AsrMessages {
    /** Reused scratch buffer for the high-frequency audio framing path. Not thread-safe. */
    private val frameBuffer = ByteArrayOutputStream(AsrConfig.FRAME_BYTES + 128)

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

    /**
     * Carries one audio frame. The returned array is only valid until the next call, so callers
     * must hand it to a synchronous consumer (OkHttp copies it before the async send).
     */
    fun taskRequest(audio: ByteArray, timestampMs: Long): ByteArray {
        val buffer = frameBuffer
        buffer.reset()
        AsrProto.writeString(buffer, 3, "ASR")
        AsrProto.writeString(buffer, 5, "TaskRequest")
        AsrProto.writeString(buffer, 6, "{\"timestamp_ms\":$timestampMs}")
        AsrProto.writeBytes(buffer, 7, audio)
        return buffer.toByteArray()
    }

    /** Closes the session. The server answers with `SessionFinished`. */
    fun finishSession(uid: String): ByteArray = AsrProto.string(2, AsrConfig.TOKEN) +
        AsrProto.string(3, "ASR") +
        AsrProto.string(5, "FinishSession") +
        AsrProto.string(8, uid)
}
