/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.asr

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * Thin client for the Doubao IME speech channel.
 *
 * The session is a plain WebSocket carrying protobuf frames:
 * `StartTask` -> `StartSession` -> `TaskRequest`* -> `FinishSession`.
 */
class DoubaoAsrClient(
    private val scope: CoroutineScope,
    private val sessionJson: ByteArray,
    private val listener: Listener,
) {
    interface Listener {
        /** The server accepted the session and audio may be sent. */
        fun onSessionStarted()

        /** A recognition result; [isFinal] is true once the server stopped refining it. */
        fun onResult(text: String, isFinal: Boolean)

        /** The server closed the session. */
        fun onSessionFinished()

        /** The connection failed. */
        fun onFailure(message: String)
    }

    private val httpClient get() = SharedHttpClient.instance

    private val sessionId = AsrConfig.newSessionId()

    private var socket: WebSocket? = null

    fun connect() {
        val builder = Request.Builder().url(AsrConfig.buildUrl())
        AsrConfig.buildHeaders().forEach { (name, value) -> builder.header(name, value) }
        val request = builder.build()

        socket = httpClient.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    Timber.i("ASR: websocket opened")
                    webSocket.send(AsrMessages.startTask(sessionId).toBinary())
                    scope.launch {
                        // The server expects a short gap between the task and the session handshake.
                        delay(120)
                        webSocket.send(AsrMessages.startSession(sessionJson).toBinary())
                    }
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    Timber.d("ASR: unexpected text frame: $text")
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    handleFrame(bytes.toByteArray())
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(code, reason)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    Timber.i("ASR: websocket closed ($code $reason)")
                    listener.onSessionFinished()
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    Timber.w(t, "ASR: websocket failed")
                    listener.onFailure(t.message ?: t.javaClass.simpleName)
                }
            },
        )
    }

    /** Sends one chunk of PCM audio. */
    fun sendAudio(frame: ByteArray) {
        socket?.send(AsrMessages.taskRequest(frame, System.currentTimeMillis()).toBinary())
    }

    /** Asks the server to produce the final transcript. */
    fun finish() {
        socket?.send(AsrMessages.finishSession(sessionId).toBinary())
    }

    fun cancel() {
        socket?.cancel()
        socket = null
    }

    private fun handleFrame(data: ByteArray) {
        val downlink = AsrProto.parseDownlink(data)
        when (downlink.method) {
            "TaskStarted" -> Timber.d("ASR: task started")
            "SessionStarted" -> {
                Timber.i("ASR: session started")
                listener.onSessionStarted()
            }

            "SessionFinished" -> {
                Timber.i("ASR: session finished")
                listener.onSessionFinished()
            }
        }

        val payload = downlink.payload ?: return
        val json = runCatching { JSONObject(String(payload, Charsets.UTF_8)) }.getOrNull() ?: return
        val results = json.optJSONArray("results") ?: return
        if (results.length() == 0) return
        // Only the primary entry carries the transcript; the others are non streaming extras.
        val primary = results.optJSONObject(0) ?: return
        val text = primary.optString("text", "")
        val isFinal = !primary.optBoolean("is_interim", true)
        listener.onResult(text, isFinal)
    }

    private fun ByteArray.toBinary(): ByteString = toByteString()

    /** One client for the whole process; every round only opens a new socket. */
    private object SharedHttpClient {
        val instance: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .pingInterval(0, TimeUnit.MILLISECONDS)
                .build()
        }
    }
}
