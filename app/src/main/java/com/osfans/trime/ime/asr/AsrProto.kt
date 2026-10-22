/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.asr

import java.io.ByteArrayOutputStream

/**
 * Minimal writer/reader for the "inner protobuf" framing used by the Doubao IME ASR channel.
 *
 * The wire format has no magic number, no CRC and no outer envelope: a WebSocket binary frame
 * carries the protobuf body directly. Only a handful of fields are used, so a full protobuf
 * runtime is unnecessary.
 */
internal object AsrProto {
    private const val WIRE_TYPE_VARINT = 0
    private const val WIRE_TYPE_FIXED64 = 1
    private const val WIRE_TYPE_LENGTH_DELIMITED = 2
    private const val WIRE_TYPE_FIXED32 = 5

    fun varint(value: Long): ByteArray {
        val out = ByteArrayOutputStream(10)
        var v = value
        while (true) {
            val b = (v and 0x7FL).toInt()
            v = v ushr 7
            if (v != 0L) {
                out.write(b or 0x80)
            } else {
                out.write(b)
                break
            }
        }
        return out.toByteArray()
    }

    private fun tag(field: Int, wireType: Int): ByteArray = varint(((field.toLong() shl 3) or wireType.toLong()))

    /** Length delimited string field (protobuf `string` and `bytes` share the same wire type). */
    fun string(field: Int, value: String): ByteArray =
        stringBytes(field, value.toByteArray(Charsets.UTF_8))

    fun bytes(field: Int, value: ByteArray): ByteArray = stringBytes(field, value)

    private fun stringBytes(field: Int, value: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(value.size + 16)
        out.write(tag(field, WIRE_TYPE_LENGTH_DELIMITED))
        out.write(varint(value.size.toLong()))
        out.write(value)
        return out.toByteArray()
    }

    /** A parsed downlink message. */
    data class Downlink(
        val method: String?,
        val status: String?,
        val payload: ByteArray?,
    )

    /**
     * Parses the downlink frame.
     *
     * Only the fields the ASR channel actually uses are extracted: field 4 is the method name,
     * field 6 the status and field 7 the JSON payload. Unknown fields are skipped.
     */
    fun parseDownlink(data: ByteArray): Downlink {
        var method: String? = null
        var status: String? = null
        var payload: ByteArray? = null

        var index = 0
        while (index < data.size) {
            val (tagValue, nextTag) = readVarint(data, index) ?: break
            index = nextTag
            val field = (tagValue ushr 3).toInt()
            when ((tagValue and 0x7L).toInt()) {
                WIRE_TYPE_LENGTH_DELIMITED -> {
                    val (length, nextLength) = readVarint(data, index) ?: break
                    index = nextLength
                    if (length < 0 || length > (data.size - index).toLong()) break
                    val end = index + length.toInt()
                    val value = data.copyOfRange(index, end)
                    index = end
                    when (field) {
                        4 -> method = value.toString(Charsets.UTF_8)
                        6 -> status = value.toString(Charsets.UTF_8)
                        7 -> payload = value
                    }
                }

                WIRE_TYPE_VARINT -> {
                    val (_, next) = readVarint(data, index) ?: break
                    index = next
                }

                WIRE_TYPE_FIXED64 -> {
                    if (index + 8 > data.size) break
                    index += 8
                }

                WIRE_TYPE_FIXED32 -> {
                    if (index + 4 > data.size) break
                    index += 4
                }

                else -> break
            }
        }
        return Downlink(method, status, payload)
    }

    private fun readVarint(data: ByteArray, start: Int): Pair<Long, Int>? {
        var shift = 0
        var result = 0L
        var index = start
        while (index < data.size) {
            val b = data[index].toInt() and 0xFF
            index++
            result = result or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) return result to index
            shift += 7
            if (shift > 63) return null
        }
        return null
    }
}
