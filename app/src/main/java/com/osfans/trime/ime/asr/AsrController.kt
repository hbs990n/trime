/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.asr

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import kotlin.math.max
import kotlin.math.min

/**
 * Drives one recognition round: connects to the server, streams microphone audio and reports
 * interim and final transcripts.
 */
class AsrController(
    private val scope: CoroutineScope,
    private val callback: Callback,
) {
    interface Callback {
        fun onStateChanged(state: State)
        fun onPartialText(text: String)
        fun onFinalText(text: String)
        fun onError(message: String)
    }

    enum class State {
        IDLE,
        CONNECTING,
        RECORDING,
        FINALIZING,
    }

    /** Per round state; a new round gets a new instance so stale callbacks can be ignored. */
    private inner class Session {
        val started = CompletableDeferred<Unit>()
        val finished = CompletableDeferred<Unit>()
        var finalText: String = ""
    }

    private var session: Session? = null
    private var client: DoubaoAsrClient? = null
    private var captureJob: Job? = null
    private var startJob: Job? = null
    private var state: State = State.IDLE
        set(value) {
            if (field != value) {
                field = value
                callback.onStateChanged(value)
            }
        }

    val currentState: State get() = state

    fun start(context: AsrConfig.Context) {
        if (state != State.IDLE) return

        val newSession = Session()
        session = newSession
        state = State.CONNECTING

        val sessionJson = AsrConfig.buildSessionJson(context)
        val newClient = DoubaoAsrClient(scope, sessionJson, object : DoubaoAsrClient.Listener {
            override fun onSessionStarted() {
                newSession.started.complete(Unit)
            }

            override fun onResult(text: String, isFinal: Boolean) {
                if (isFinal) newSession.finalText = text
                callback.onPartialText(text)
            }

            override fun onSessionFinished() {
                newSession.finished.complete(Unit)
            }

            override fun onFailure(message: String) {
                if (session !== newSession) return
                callback.onError(message)
                cleanup()
            }
        })
        client = newClient
        newClient.connect()

        startJob = scope.launch {
            val started = withTimeoutOrNull(SESSION_START_TIMEOUT) { newSession.started.await() }
            if (started == null) {
                if (session === newSession) {
                    callback.onError("连接超时")
                    cleanup()
                }
                return@launch
            }
            if (session !== newSession) return@launch
            state = State.RECORDING
            captureJob = launch(Dispatchers.IO) { captureAudio(newClient) }
        }
    }

    fun stop() {
        val current = session ?: return
        if (state != State.RECORDING) return
        state = State.FINALIZING
        captureJob?.cancel()
        captureJob = null
        client?.finish()

        scope.launch {
            withTimeoutOrNull(SESSION_FINISH_TIMEOUT) { current.finished.await() }
            if (session !== current) return@launch
            val text = current.finalText
            cleanup()
            callback.onFinalText(text)
        }
    }

    fun cancel() {
        cleanup()
    }

    private fun cleanup() {
        startJob?.cancel()
        startJob = null
        captureJob?.cancel()
        captureJob = null
        client?.cancel()
        client = null
        session = null
        state = State.IDLE
    }

    @SuppressLint("MissingPermission")
    private suspend fun captureAudio(asrClient: DoubaoAsrClient) {
        val recorder = createRecorder()
        if (recorder == null) {
            withContext(Dispatchers.Main) { callback.onError("无法打开麦克风") }
            return
        }
        try {
            recorder.startRecording()
            if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                withContext(Dispatchers.Main) { callback.onError("麦克风启动失败") }
                return
            }
            val buffer = ByteArray(max(AsrConfig.FRAME_BYTES * 4, recorder.bufferSizeInBytes))
            val frame = ByteArray(AsrConfig.FRAME_BYTES)
            var filled = 0
            while (currentCoroutineContext().isActive) {
                val read = recorder.read(buffer, 0, buffer.size)
                if (read <= 0) continue
                var offset = 0
                while (offset < read) {
                    val take = min(AsrConfig.FRAME_BYTES - filled, read - offset)
                    System.arraycopy(buffer, offset, frame, filled, take)
                    filled += take
                    offset += take
                    if (filled == AsrConfig.FRAME_BYTES) {
                        asrClient.sendAudio(frame.copyOf())
                        filled = 0
                    }
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "ASR: audio capture failed")
            withContext(Dispatchers.Main) { callback.onError(e.message ?: "录音失败") }
        } finally {
            try {
                if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    recorder.stop()
                }
            } catch (e: Exception) {
                Timber.w(e, "ASR: stopping the recorder failed")
            }
            recorder.release()
        }
    }

    @SuppressLint("MissingPermission")
    private fun createRecorder(): AudioRecord? {
        val minBuffer = AudioRecord.getMinBufferSize(
            AsrConfig.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) return null
        val bufferSize = max(minBuffer, AsrConfig.FRAME_BYTES * 8)
        for (source in AUDIO_SOURCES) {
            val record = runCatching {
                @Suppress("DEPRECATION")
                AudioRecord(
                    source,
                    AsrConfig.SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize,
                )
            }.getOrNull() ?: continue
            if (record.state == AudioRecord.STATE_INITIALIZED) return record
            record.release()
        }
        return null
    }

    private companion object {
        const val SESSION_START_TIMEOUT = 10_000L
        const val SESSION_FINISH_TIMEOUT = 8_000L

        /** Prefer unprocessed audio; fall back to the plain microphone. */
        val AUDIO_SOURCES = intArrayOf(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.MIC,
            MediaRecorder.AudioSource.DEFAULT,
        )
    }
}
