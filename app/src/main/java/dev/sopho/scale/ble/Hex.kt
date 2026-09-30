package dev.sopho.scale.ble

internal fun ByteArray.toHex(): String = joinToString(" ") { "%02X".format(it) }

internal fun ByteArray.toCompactHex(): String = joinToString("") { "%02x".format(it) }

internal fun u16le(data: ByteArray, offset: Int): Int =
    (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)

internal fun u32le(data: ByteArray, offset: Int): Long =
    (data[offset].toLong() and 0xFF) or
        ((data[offset + 1].toLong() and 0xFF) shl 8) or
        ((data[offset + 2].toLong() and 0xFF) shl 16) or
        ((data[offset + 3].toLong() and 0xFF) shl 24)

/**
 * Parses a user-supplied bind key. Accepts the 32 hex characters Xiaomi tools print, with or
 * without separators, and rejects anything that is not exactly 16 bytes.
 */
fun parseBindKey(text: String): ByteArray? {
    val cleaned = text.filter { it.isLetterOrDigit() }
    if (cleaned.length != 32) return null
    if (!cleaned.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }) return null
    return ByteArray(16) { i ->
        cleaned.substring(i * 2, i * 2 + 2).toInt(16).toByte()
    }
}

fun formatMac(bytes: ByteArray): String =
    bytes.joinToString(":") { "%02X".format(it) }

/** Normalises a pasted MAC to `AA:BB:CC:DD:EE:FF`, or null when it is not six hex bytes. */
fun normalizeMac(text: String): String? {
    val cleaned = text.filter { it.isLetterOrDigit() }
    if (cleaned.length != 12) return null
    if (!cleaned.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }) return null
    return (0 until 6).joinToString(":") { cleaned.substring(it * 2, it * 2 + 2).uppercase() }
}

/** MAC string -> big-endian bytes, as advertised. */
fun macToBytes(mac: String): ByteArray? {
    val parts = mac.split(':', '-')
    if (parts.size != 6) return null
    return try {
        ByteArray(6) { i -> parts[i].toInt(16).toByte() }
    } catch (_: NumberFormatException) {
        null
    }
}
