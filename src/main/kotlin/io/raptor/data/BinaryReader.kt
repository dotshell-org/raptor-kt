package io.raptor.data

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class BinaryReader(inputStream: InputStream) {
    private val buffer: ByteBuffer = ByteBuffer.wrap(inputStream.readBytes())

    init {
        buffer.order(ByteOrder.LITTLE_ENDIAN)
    }

    fun readMagic(expected: String) {
        val bytes = ByteArray(4)
        buffer.get(bytes)
        val magic = String(bytes)
        if (magic != expected) throw IllegalStateException("Bad format : expected $expected, got $magic")
    }

    /**
     * Reads 4-byte magic without consuming — returns the magic string and resets position.
     */
    fun peekMagic(): String {
        val pos = buffer.position()
        val bytes = ByteArray(4)
        buffer.get(bytes)
        buffer.position(pos)
        return String(bytes)
    }

    fun readUInt16(): Int = buffer.short.toInt() and 0xFFFF
    fun readUInt32(): Int = buffer.int
    fun readInt32(): Int = buffer.int
    fun readFloat64(): Double = buffer.double

    fun readUTF8(length: Int): String {
        val bytes = ByteArray(length)
        buffer.get(bytes)
        return String(bytes, Charsets.UTF_8)
    }

}
