/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.asr

import android.net.Uri
import android.os.Build
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Connection metadata for the Doubao IME speech channel.
 *
 * The credentials below are the ones the official input method presents. The server rotates them
 * periodically, so a stale value shows up as a rejected handshake rather than a crash.
 */
object AsrConfig {
    const val HOST = "frontier-audio-ime-ws.doubao.com"
    const val PATH = "/ocean/api/v1/ws"
    const val TOKEN = "OrnqKvSSrs"
    const val DEVICE_ID = "692058704868971"
    const val INSTALL_ID = "692058705102443"
    const val APP_ID = "401734"
    const val APP_VERSION = "1.4.6"
    const val CHANNEL = "huawei_401734_64_ziran"
    const val USER_AGENT = "oime/1.4.6 (Linux; Android 13) SAMICORE/android-14.91.30-alpha.10-oinput"

    /** The service only accepts 16 kHz mono PCM. */
    const val SAMPLE_RATE = 16000

    /** Frames are 20 ms long; the server expects a steady cadence. */
    const val FRAME_MS = 20
    const val FRAME_SAMPLES = SAMPLE_RATE * FRAME_MS / 1000
    const val FRAME_BYTES = FRAME_SAMPLES * 2

    /**
     * Hot words biasing the recogniser towards AI product names.
     */
    val BRAND_WORDS: List<String> = listOf(
        "GPT", "Codex", "Claude", "DeepSeek", "ChatGPT", "Claude Code", "OpenClaw", "Gemini",
        "Kimi", "Grok", "GLM", "DeepSeek V4", "MiniMax", "WorkBuddy", "RAG", "Spark", "Cursor",
        "DeepSeek V4 Pro", "Qwen", "Qwen 3", "ChatGPT Plus", "MCP", "NotebookLM", "Gemma",
        "Midjourney", "Gemini Pro", "Embedding", "Gemini 3.1", "Runway", "Sora", "Subagent",
        "Qwen 3.5", "Canva", "Claude Opus", "Manus", "Llama", "Stable Diffusion", "SFT",
        "CodeBuddy", "Dola", "Coding Agent", "Monica", "Solar", "DeepSeek V3", "Perplexity",
        "DeepL", "SDXL", "GitHub Copilot", "MiniMax M2", "OpenAgent", "MiniMax M3", "GPTs",
        "Vibe Coding", "Claude Opus 4.7", "KV Cache", "Qwen 2", "Grok 4", "CapCut", "RLHF",
        "Claude 3", "Gemini 1.5", "Cloud AI", "GPT-4", "A2A", "Qwen Code", "AutoGPT", "Devin",
        "Falcon", "DeepSeek Code", "Agentic AI", "AgentGPT", "Claude Sonnet", "Context Window",
        "Cursor Free", "Gemini 3.0", "GPT-3", "GPT-3.5", "GPT-3.5 Turbo", "GPT-4 Turbo",
        "GPT-4o", "GPT-5", "OpenClaw Pro", "CodeGeeX", "DALL·E", "Finetune",
        "Stable Diffusion 3", "ElevenLabs", "DataAgent", "Tianyi", "Alpaca", "Mistral",
        "MultiAgent", "Remini", "RLVR", "SuperAgent", "Zephyr", "Dev Agent", "Grammarly",
        "Mixtral", "OpenJourney",
    )

    /** Context handed to the recogniser: what the user already typed in the current field. */
    data class Context(
        val appPackage: String,
        val textBeforeCursor: String = "",
        val cursorPosition: Int = 0,
        val inputType: String = "TYPE_CLASS_TEXT",
    )

    fun buildUrl(): String = Uri.Builder()
        .scheme("wss")
        .authority(HOST)
        .port(443)
        .path(PATH)
        .appendQueryParameter("aid", APP_ID)
        .appendQueryParameter("app_name", "oime")
        .appendQueryParameter("channel", CHANNEL)
        .appendQueryParameter("device_brand", "Android")
        .appendQueryParameter("device_id", DEVICE_ID)
        .appendQueryParameter("device_platform", "android")
        .appendQueryParameter("device_type", deviceType())
        .appendQueryParameter("did", DEVICE_ID)
        .appendQueryParameter("forwarded", "")
        .appendQueryParameter("iid", INSTALL_ID)
        .appendQueryParameter("install_id", INSTALL_ID)
        .appendQueryParameter("mobile", "")
        .appendQueryParameter("os_version", Build.VERSION.RELEASE.ifEmpty { "13" })
        .appendQueryParameter("target", "")
        .appendQueryParameter("update_version_code", "100406010")
        .appendQueryParameter("user_agent", "")
        .appendQueryParameter("version_code", "100406010")
        .appendQueryParameter("version_name", APP_VERSION)
        .build()
        .toString()

    fun buildHeaders(): Map<String, String> = mapOf(
        "User-Agent" to USER_AGENT,
        "Proto-Version" to "v2",
        "X-Device-Id" to DEVICE_ID,
        "X-Install-Id" to INSTALL_ID,
        "X-App-Id" to APP_ID,
        "Origin" to "https://doubao.com",
    )

    private fun deviceType(): String = Build.MODEL.ifEmpty { "AOSP on ARM64" }

    /**
     * Builds the session description the server expects in the `StartSession` message.
     *
     * `audio_info.format` is `raw`, i.e. plain little endian 16 bit PCM; the server accepts it
     * without any Opus encoder.
     */
    fun buildSessionJson(context: Context): ByteArray {
        val audioInfo = JSONObject()
            .put("channel", 1)
            .put("format", "raw")
            .put("sample_rate", SAMPLE_RATE)

        val asrParams = JSONObject()
            .put("brand_words", JSONArray(BRAND_WORDS))
            .put("enable_global_tracking", true)

        val extra = JSONObject()
            .put("app_name", context.appPackage)
            .put("app_version", APP_VERSION)
            .put("asr_params", asrParams.toString())
            .put("cell_compress_rate", 8)
            .put("context", encodeContext(context))
            .put("device_brand", "Android")
            .put("device_model", deviceType())
            .put("did", DEVICE_ID)
            .put("disable_user_words", false)
            .put("enable_asr_threepass", true)
            .put("enable_asr_twopass", true)
            .put("enable_print_chinese", false)
            .put("end_smooth_window_ms", 800)
            .put("finish_wait_offline_time", 1000)
            .put("format_space_at_newline", true)
            .put("input_mode", "voice")
            .put("join_user_experience_improve_program", false)
            .put("last_uid", "")
            .put("max_wait_switch_offline_time", 1000)
            .put("network_change", JSONObject()
                .put("switch_network_ping_timeout", 2000)
                .put("switch_network_quality_threshold", 4)
                .put("switch_network_rtt_threshold", 273))
            .put("offline_wait_online_interval_time", 5000)
            .put("offline_wait_online_time", 5000)
            .put("os", "Android")
            .put("os_version", Build.VERSION.RELEASE.ifEmpty { "13" })
            .put("remove_space_between_han_eng", false)
            .put("remove_space_between_han_num", false)
            .put("retry_server_code", JSONArray(listOf(40100000, 40100004, 50000000)))
            .put("s2a_send_commands", JSONArray(listOf("帮我发送")))
            .put("s2a_send_enable", false)
            .put("strong_ddc", false)
            .put("use_twopass_retry", true)

        return JSONObject()
            .put("audio_info", audioInfo)
            .put("enable_punctuation", true)
            .put("enable_speech_rejection", false)
            .put("extra", extra)
            .toString()
            .toByteArray(Charsets.UTF_8)
    }

    private fun encodeContext(context: Context): String {
        val userInput = JSONObject()
            .put("cursor_position", context.cursorPosition)
            .put("text", context.textBeforeCursor)
            .toString()

        val chatItem = JSONObject()
            .put("type", "user_input")
            .put("data", userInput)
            .put("time", System.currentTimeMillis().toString())
            .put("app_apk_name", context.appPackage)

        val payload = JSONObject()
            .put("chat", JSONArray().put(chatItem))
            .put("loc_info", JSONObject().put("ip", ""))
            .put(
                "ime_info",
                JSONObject()
                    .put("app_apk_name", context.appPackage)
                    .put("input_type", context.inputType),
            )
            .toString()

        return Base64.encodeToString(payload.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
    }

    fun newSessionId(): String = UUID.randomUUID().toString()
}
