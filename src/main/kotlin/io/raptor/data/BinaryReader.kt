package io.raptor.data

/**
 * Reads little-endian primitives from an in-memory byte array.
 *
 * Replaces the previous `java.io.InputStream` + `java.nio.ByteBuffer` implementation so the
 * library compiles on every Kotlin Multiplatform target (Android, iOS, …). The `.bin` files
 * are small and already read fully into memory, so a byte-array cursor is equivalent.
 */
class BinaryReader(private val bytes: ByteArray) {

    private var pos = 0

    fun readMagic(expected: String) {
        val magic = peekMagic()
        pos += 4
        if (magic != expected) throw IllegalStateException("Bad format : expected $expected, got $magic")
    }

    /** Reads the 4-byte magic without consuming it. */
    fun peekMagic(): String = bytes.decodeToString(pos, pos + 4)

    fun readUInt16(): Int {
        val v = (bytes[pos].toInt() and 0xFF) or
                ((bytes[pos + 1].toInt() and 0xFF) shl 8)
        pos += 2
        return v
    }

    fun readUInt32(): Int = readInt32()

    fun readInt32(): Int {
        val v = (bytes[pos].toInt() and 0xFF) or
                ((bytes[pos + 1].toInt() and 0xFF) shl 8) or
                ((bytes[pos + 2].toInt() and 0xFF) shl 16) or
                ((bytes[pos + 3].toInt() and 0xFF) shl 24)
        pos += 4
        return v
    }

    fun readFloat64(): Double {
        var bits = 0L
        for (i in 0 until 8) {
            bits = bits or ((bytes[pos + i].toLong() and 0xFF) shl (8 * i))
        }
        pos += 8
        return Double.fromBits(bits)
    }

    fun readUTF8(length: Int): String {
        val s = bytes.decodeToString(pos, pos + length)
        pos += length
        return s
    }
}
