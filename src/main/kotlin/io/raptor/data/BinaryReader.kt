package io.raptor.data

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class BinaryReader(file: File) {
    private val buffer: ByteBuffer = ByteBuffer.wrap(file.readBytes())

    init {
        buffer.order(ByteOrder.LITTLE_ENDIAN)
    }

    fun readMagic(expected: String) {
        val bytes = ByteArray(4)
        buffer.get(bytes)
        val magic = String(bytes)
        if (magic != expected) throw IllegalStateException("Bad format : expected $expected, got $magic")
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