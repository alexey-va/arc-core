package ru.arc.telemetry

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.zip.DeflaterOutputStream
import java.util.zip.InflaterInputStream

/** Bounded binary map for validated semantic attributes. SQL NULL represents an empty map. */
internal object PlayerTelemetryCompactAttributes {
    private const val RAW = 0
    private const val DEFLATE = 1
    private const val MAX_BYTES = 24 * 1024

    fun encode(attributes: Map<String, String>): ByteArray? {
        if (attributes.isEmpty()) return null
        require(attributes.size <= PlayerTelemetryEvent.MAX_ATTRIBUTES)
        val raw = ByteArrayOutputStream()
        DataOutputStream(raw).use { output ->
            output.writeByte(attributes.size)
            attributes.toSortedMap().forEach { (name, value) ->
                output.writeUTF(name)
                output.writeUTF(value)
            }
        }
        val payload = raw.toByteArray()
        require(payload.size <= MAX_BYTES) { "Telemetry attributes exceed the compact row limit" }
        if (payload.size >= 128) {
            val compressed = ByteArrayOutputStream()
            compressed.write(DEFLATE)
            DeflaterOutputStream(compressed).use { it.write(payload) }
            if (compressed.size() < payload.size + 1) return compressed.toByteArray()
        }
        return byteArrayOf(RAW.toByte()) + payload
    }

    fun decode(bytes: ByteArray?): Map<String, String> {
        if (bytes == null) return emptyMap()
        require(bytes.size in 2..(MAX_BYTES + 1)) { "Invalid compact telemetry attribute length" }
        val source = ByteArrayInputStream(bytes, 1, bytes.size - 1)
        val input = when (bytes.first().toInt()) {
            RAW -> source
            DEFLATE -> InflaterInputStream(source).use {
                val expanded = it.readNBytes(MAX_BYTES + 1)
                require(expanded.size <= MAX_BYTES) { "Inflated telemetry attributes exceed the compact row limit" }
                ByteArrayInputStream(expanded)
            }
            else -> error("Unsupported compact telemetry attribute encoding")
        }
        return DataInputStream(input).use { data ->
            val count = data.readUnsignedByte()
            require(count in 1..PlayerTelemetryEvent.MAX_ATTRIBUTES)
            buildMap {
                repeat(count) {
                    val key = data.readUTF()
                    check(put(key, data.readUTF()) == null) { "Duplicate compact telemetry attribute" }
                }
                require(data.read() == -1) { "Trailing compact telemetry attributes" }
            }
        }
    }
}
