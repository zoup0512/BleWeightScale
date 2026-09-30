package dev.sopho.scale.ble

import java.io.ByteArrayOutputStream
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * Minimal AES-CCM (RFC 3610) decryption.
 *
 * MiBeacon v4/v5 frames are AES-128-CCM encrypted with a 4 byte authentication tag, and Android's
 * public JCA providers do not expose CCM on every API level in a way that is portable back to
 * API 26, so the mode is implemented here on top of AES-ECB.
 *
 * Only the decrypt direction is implemented, which is all the MiBeacon parser needs.
 */
internal object AesCcm {

    /**
     * Decrypts [input] (ciphertext followed by its [tagLength] byte tag) and verifies the tag.
     *
     * @return the plaintext, or null when the tag does not match — which in practice means the
     *   encryption key is wrong or the frame is not the one it claims to be.
     */
    fun decrypt(
        key: ByteArray,
        nonce: ByteArray,
        input: ByteArray,
        associatedData: ByteArray,
        tagLength: Int,
    ): ByteArray? {
        require(key.size == 16) { "AES-128 key must be 16 bytes, was ${key.size}" }
        require(nonce.size in 7..13) { "CCM nonce must be 7..13 bytes, was ${nonce.size}" }
        require(tagLength in 4..16 && tagLength % 2 == 0) { "CCM tag must be 4..16 even" }
        if (input.size < tagLength) return null

        val l = 15 - nonce.size
        val cipherText = input.copyOfRange(0, input.size - tagLength)
        val receivedTag = input.copyOfRange(input.size - tagLength, input.size)

        // CCM is a two-pass mode: CTR-decrypt first, then authenticate the recovered plaintext.
        val s0 = encryptBlock(key, counterBlock(nonce, 0, l))
        val plainText = ByteArray(cipherText.size)
        var counter = 1
        var offset = 0
        while (offset < cipherText.size) {
            val stream = encryptBlock(key, counterBlock(nonce, counter, l))
            counter++
            val n = minOf(16, cipherText.size - offset)
            for (i in 0 until n) {
                plainText[offset + i] = (cipherText[offset + i].toInt() xor stream[i].toInt()).toByte()
            }
            offset += n
        }

        val b0 = ByteArray(16)
        b0[0] = (
            (if (associatedData.isNotEmpty()) 0x40 else 0)
                or (((tagLength - 2) / 2) shl 3)
                or (l - 1)
            ).toByte()
        System.arraycopy(nonce, 0, b0, 1, nonce.size)
        var messageLength = cipherText.size.toLong()
        for (i in 0 until l) {
            b0[15 - i] = (messageLength and 0xFF).toByte()
            messageLength = messageLength ushr 8
        }

        val mac = cbcMac(key, b0, associatedData, plainText)
        var diff = 0
        for (i in 0 until tagLength) {
            diff = diff or (mac[i].toInt() xor receivedTag[i].toInt() xor s0[i].toInt())
        }
        return if (diff == 0) plainText else null
    }

    /** CBC-MAC over `B0 || length-prefixed AAD || message`, each segment zero-padded to 16 bytes. */
    private fun cbcMac(
        key: ByteArray,
        b0: ByteArray,
        associatedData: ByteArray,
        message: ByteArray,
    ): ByteArray {
        val buffer = ByteArrayOutputStream(b0.size + associatedData.size + message.size + 32)
        buffer.write(b0)
        if (associatedData.isNotEmpty()) {
            if (associatedData.size < 0xFF00) {
                buffer.write((associatedData.size ushr 8) and 0xFF)
                buffer.write(associatedData.size and 0xFF)
            } else {
                buffer.write(0xFF)
                buffer.write(0xFE)
                buffer.write((associatedData.size ushr 24) and 0xFF)
                buffer.write((associatedData.size ushr 16) and 0xFF)
                buffer.write((associatedData.size ushr 8) and 0xFF)
                buffer.write(associatedData.size and 0xFF)
            }
            buffer.write(associatedData)
            padToBlockBoundary(buffer)
        }
        buffer.write(message)
        padToBlockBoundary(buffer)

        val blocks = buffer.toByteArray()
        var state = ByteArray(16)
        var offset = 0
        while (offset < blocks.size) {
            val xored = ByteArray(16)
            for (i in 0 until 16) {
                xored[i] = (state[i].toInt() xor blocks[offset + i].toInt()).toByte()
            }
            state = encryptBlock(key, xored)
            offset += 16
        }
        return state
    }

    private fun padToBlockBoundary(buffer: ByteArrayOutputStream) {
        val remainder = buffer.size() % 16
        if (remainder != 0) {
            repeat(16 - remainder) { buffer.write(0) }
        }
    }

    /** Counter block `A_i`: the `l-1` flags byte, the nonce, then the counter as `l` big-endian bytes. */
    private fun counterBlock(nonce: ByteArray, counter: Int, l: Int): ByteArray {
        val block = ByteArray(16)
        block[0] = (l - 1).toByte()
        System.arraycopy(nonce, 0, block, 1, nonce.size)
        var value = counter.toLong()
        for (i in 0 until l) {
            block[15 - i] = (value and 0xFF).toByte()
            value = value ushr 8
        }
        return block
    }

    fun encryptBlock(key: ByteArray, block: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
        return cipher.doFinal(block)
    }
}
