package com.bitchat.droid

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.Deflater
import java.util.zip.Inflater

internal data class MeshPacket(
    val type: Int,
    val senderId: ByteArray,
    val timestamp: Long,
    val payload: ByteArray,
    val ttl: Int,
    val recipientId: ByteArray? = null,
    val signature: ByteArray? = null,
    val version: Int = 1,
    val route: List<ByteArray> = emptyList(),
    val isRsr: Boolean = false
)

internal object MeshMessageType {
    const val ANNOUNCE = 0x01
    const val MESSAGE = 0x02
    const val FRAGMENT = 0x20
    const val REQUEST_SYNC = 0x21
}

internal object MeshPacketCodec {
    private const val FLAG_RECIPIENT = 0x01
    private const val FLAG_SIGNATURE = 0x02
    private const val FLAG_COMPRESSED = 0x04
    private const val FLAG_ROUTE = 0x08
    private const val FLAG_RSR = 0x10
    private const val MAX_DECODED_PAYLOAD = 1_048_576
    private const val MAX_DEFLATE_RATIO = 1032L
    private const val SIGNATURE_SIZE = 64

    fun encode(packet: MeshPacket, padding: Boolean = true): ByteArray? {
        if (packet.version !in 1..2 || packet.senderId.size != 8) return null
        if (packet.recipientId?.size?.let { it != 8 } == true) return null
        val route = if (packet.version == 2) packet.route else emptyList()
        if (route.size > 255 || route.any { it.size != 8 }) return null
        if (packet.signature?.size?.let { it != SIGNATURE_SIZE } == true) return null

        val compressed = compressPayload(packet.payload)
        val encodedPayload = compressed?.first ?: packet.payload
        val originalSize = compressed?.second
        val prefixSize = if (originalSize == null) 0 else if (packet.version == 2) 4 else 2
        val payloadLength = encodedPayload.size + prefixSize
        if (packet.version == 1 && payloadLength > 0xFFFF) return null

        val output = ByteArrayOutputStream()
        output.write(packet.version)
        output.write(packet.type)
        output.write(packet.ttl)
        writeUnsigned(output, packet.timestamp, 8)

        var flags = 0
        if (packet.recipientId != null) flags = flags or FLAG_RECIPIENT
        if (packet.signature != null) flags = flags or FLAG_SIGNATURE
        if (originalSize != null) flags = flags or FLAG_COMPRESSED
        if (route.isNotEmpty()) flags = flags or FLAG_ROUTE
        if (packet.isRsr) flags = flags or FLAG_RSR
        output.write(flags)
        writeUnsigned(output, payloadLength.toLong(), if (packet.version == 2) 4 else 2)
        output.write(packet.senderId)
        packet.recipientId?.let { output.write(it) }
        if (route.isNotEmpty()) {
            output.write(route.size)
            route.forEach { output.write(it) }
        }
        if (originalSize != null) writeUnsigned(output, originalSize.toLong(), prefixSize)
        output.write(encodedPayload)
        packet.signature?.let { output.write(it) }

        val bytes = output.toByteArray()
        if (!padding) return bytes
        val target = listOf(256, 512, 1024, 2048).firstOrNull { bytes.size + 16 <= it } ?: bytes.size
        val count = target - bytes.size
        if (count !in 1..255) return bytes
        return bytes + ByteArray(count) { count.toByte() }
    }

    fun decode(frame: ByteArray): MeshPacket? {
        if (frame.size < 22) return null
        return try {
            val input = ByteBuffer.wrap(frame).order(ByteOrder.BIG_ENDIAN)
            val version = input.get().toInt() and 0xFF
            if (version !in 1..2) return null
            val headerSize = if (version == 2) 16 else 14
            if (frame.size < headerSize + 8) return null
            val type = input.get().toInt() and 0xFF
            val ttl = input.get().toInt() and 0xFF
            val timestamp = input.long
            val flags = input.get().toInt() and 0xFF
            val payloadLength = if (version == 2) input.int.toLong() and 0xFFFF_FFFFL else input.short.toLong() and 0xFFFF
            if (payloadLength > MAX_DECODED_PAYLOAD || payloadLength > input.remaining()) return null

            val senderId = ByteArray(8).also(input::get)
            val recipient = if ((flags and FLAG_RECIPIENT) != 0) ByteArray(8).also(input::get) else null
            val route = if (version == 2 && (flags and FLAG_ROUTE) != 0) {
                if (!input.hasRemaining()) return null
                val count = input.get().toInt() and 0xFF
                if (count * 8 > input.remaining()) return null
                List(count) { ByteArray(8).also(input::get) }
            } else {
                emptyList()
            }

            if (payloadLength > input.remaining()) return null
            val payloadEnd = input.position() + payloadLength.toInt()
            val decodedPayload = if ((flags and FLAG_COMPRESSED) != 0) {
                val sizeField = if (version == 2) 4 else 2
                if (payloadLength < sizeField) return null
                val originalSize = if (version == 2) input.int.toLong() and 0xFFFF_FFFFL else input.short.toLong() and 0xFFFF
                if (
                    originalSize > MAX_DECODED_PAYLOAD ||
                    originalSize > (payloadLength - sizeField) * MAX_DEFLATE_RATIO
                ) return null
                val compressed = ByteArray((payloadEnd - input.position())).also(input::get)
                inflate(compressed, originalSize.toInt()) ?: return null
            } else {
                ByteArray(payloadLength.toInt()).also(input::get)
            }
            val signature = if ((flags and FLAG_SIGNATURE) != 0) {
                if (input.remaining() < SIGNATURE_SIZE) return null
                ByteArray(SIGNATURE_SIZE).also(input::get)
            } else {
                null
            }
            MeshPacket(
                type = type,
                senderId = senderId,
                timestamp = timestamp,
                payload = decodedPayload,
                ttl = ttl,
                recipientId = recipient,
                signature = signature,
                version = version,
                route = route,
                isRsr = (flags and FLAG_RSR) != 0
            )
        } catch (_: java.nio.BufferUnderflowException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    fun signingBytes(packet: MeshPacket): ByteArray? =
        encode(packet.copy(signature = null, ttl = 0, isRsr = false))

    private fun writeUnsigned(output: ByteArrayOutputStream, value: Long, bytes: Int) {
        for (shift in (bytes - 1) * 8 downTo 0 step 8) {
            output.write(((value ushr shift) and 0xFF).toInt())
        }
    }

    private fun compressPayload(payload: ByteArray): Pair<ByteArray, Int>? {
        if (payload.size < 100 || payload.toSet().size.toDouble() / minOf(payload.size, 256) >= 0.9) return null
        val deflater = Deflater()
        return try {
            deflater.setInput(payload)
            deflater.finish()
            val buffer = ByteArray(payload.size + 64)
            val length = deflater.deflate(buffer)
            if (!deflater.finished() || length <= 0 || length >= payload.size) null
            else buffer.copyOf(length) to payload.size
        } finally {
            deflater.end()
        }
    }

    private fun inflate(data: ByteArray, expectedSize: Int): ByteArray? {
        val inflater = Inflater()
        return try {
            inflater.setInput(data)
            val output = ByteArray(expectedSize)
            val length = inflater.inflate(output)
            if (!inflater.finished() || length != expectedSize) null else output
        } catch (_: java.util.zip.DataFormatException) {
            null
        } finally {
            inflater.end()
        }
    }
}

internal class MeshFrameAssembler {
    private val pending = ArrayList<Byte>()
    private val maxFrameBytes = 8 * 1024 * 1024

    fun append(chunk: ByteArray): List<ByteArray> {
        if (chunk.isEmpty()) return emptyList()
        if (pending.size + chunk.size > maxFrameBytes) {
            pending.clear()
            return emptyList()
        }
        chunk.forEach(pending::add)
        val frames = mutableListOf<ByteArray>()

        while (pending.size >= 22) {
            val version = pending[0].toInt() and 0xFF
            if (version !in 1..2) {
                if (!discardPadding()) pending.removeAt(0)
                continue
            }
            val headerSize = if (version == 2) 16 else 14
            if (pending.size < headerSize + 8) break
            val flags = pending[11].toInt() and 0xFF
            val lengthOffset = 12
            val payloadLength = if (version == 2) {
                ((pending[lengthOffset].toLong() and 0xFF) shl 24) or
                    ((pending[lengthOffset + 1].toLong() and 0xFF) shl 16) or
                    ((pending[lengthOffset + 2].toLong() and 0xFF) shl 8) or
                    (pending[lengthOffset + 3].toLong() and 0xFF)
            } else {
                ((pending[lengthOffset].toLong() and 0xFF) shl 8) or (pending[lengthOffset + 1].toLong() and 0xFF)
            }
            if (payloadLength > maxFrameBytes) {
                pending.clear()
                break
            }
            var frameLength = headerSize + 8 + payloadLength.toInt()
            if ((flags and 0x01) != 0) frameLength += 8
            if ((flags and 0x02) != 0) frameLength += 64
            if (version == 2 && (flags and 0x08) != 0) {
                val routeCountOffset = headerSize + 8 + if ((flags and 0x01) != 0) 8 else 0
                if (pending.size <= routeCountOffset) break
                frameLength += 1 + (pending[routeCountOffset].toInt() and 0xFF) * 8
            }
            if (frameLength !in (headerSize + 8)..maxFrameBytes) {
                pending.clear()
                break
            }
            if (pending.size < frameLength) break
            frames += ByteArray(frameLength) { pending[it] }
            repeat(frameLength) { pending.removeAt(0) }
            discardPadding()
        }
        return frames
    }

    private fun discardPadding(): Boolean {
        if (pending.isEmpty()) return false
        val first = pending.first().toInt() and 0xFF
        if (first == 0 || first == 1 || first == 2 || first > pending.size) return false
        if ((0 until first).any { (pending[it].toInt() and 0xFF) != first }) return false
        repeat(first) { pending.removeAt(0) }
        return true
    }
}

internal fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }
