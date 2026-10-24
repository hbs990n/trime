/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.phrases

import android.content.Context
import kotlinx.serialization.json.Json
import java.io.IOException

/**
 * Persists the user's common-phrase list. Everything is kept in the app's
 * internal (private) storage only — never on external storage — so the phrases
 * stay confidential and are removed together with the app.
 */
object PhraseManager {
    private const val FILE_NAME = "phrases.json"
    private val json = Json { ignoreUnknownKeys = true }

    /** Loads the saved phrases, or an empty list when none exist / on error. */
    fun load(context: Context): MutableList<String> {
        return try {
            context.openFileInput(FILE_NAME).bufferedReader().use { reader ->
                val raw = reader.readText()
                if (raw.isBlank()) {
                    mutableListOf()
                } else {
                    runCatching { json.decodeFromString<List<String>>(raw) }
                        .getOrDefault(emptyList())
                        .toMutableList()
                }
            }
        } catch (_: IOException) {
            mutableListOf()
        }
    }

    /** Replaces the saved phrases with [phrases] and writes them to disk. */
    fun save(context: Context, phrases: List<String>) {
        runCatching {
            context.openFileOutput(FILE_NAME, Context.MODE_PRIVATE).use { stream ->
                stream.write(json.encodeToString(phrases).toByteArray())
            }
        }
    }
}
