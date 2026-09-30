package dev.sopho.scale

import dev.sopho.scale.ble.DecodeError
import dev.sopho.scale.ble.LegacyScaleParser
import dev.sopho.scale.ble.MiBeaconParser
import dev.sopho.scale.ble.normalizeMac
import dev.sopho.scale.ble.parseBindKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The MiBeacon vectors here are the frames published in the `xiaomi-ble` project's own test suite,
 * the reference implementation Home Assistant uses. The S200 case carries both the bind key that
 * decrypts it and the weight it decodes to, so a passing test pins down the frame layout, the
 * AES-CCM nonce construction and the weight scaling at once.
 */
class MiBeaconParserTest {

    private fun hex(text: String): ByteArray =
        text.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    // --- Xiaomi Smart Scale S200 (MJTZC02YM), the scale this app targets -------------------------

    /** Real capture: 62.25 kg, profile 1, measured 2025-11-09T22:54:02Z. */
    private val s200Frame =
        hex("48 59 04 4c 01 9a 80 a2 75 93 90 10 f0 ab c4 fa dc 06 00 00 3d 29 c0 44")
    private val s200Mac = "D0:7B:6F:27:D7:29"
    private val s200BindKey = parseBindKey("653b1b10e1cb35e4ac5e60fa45f3bf29")!!

    @Test
    fun `decrypts an S200 weighing`() {
        val frame = MiBeaconParser.parse(s200Frame, s200Mac, s200BindKey)
        assertNotNull(frame)
        frame!!

        assertEquals(5, frame.frameVersion)
        assertEquals(0x4C04, frame.productId)
        assertEquals("Smart Scale S200 (MJTZC02YM)", frame.model)
        assertTrue(frame.encrypted)
        assertTrue(frame.hasPayload)
        assertNull(frame.error)

        val reading = frame.reading
        assertNotNull(reading)
        assertEquals(62.25, reading!!.weightKg, 1e-9)
        assertEquals(1, reading.profileId)
        assertEquals("MiBeacon v5 / S200", reading.protocol)
        // 0x69111B8A = 2025-11-09T22:54:02Z, taken from the frame rather than the phone clock.
        assertEquals(1762728842000L, reading.measuredAt)
    }

    @Test
    fun `rejects a frame when the bind key is wrong`() {
        val wrongKey = parseBindKey("00000000000000000000000000000000")!!
        val frame = MiBeaconParser.parse(s200Frame, s200Mac, wrongKey)!!
        assertNull(frame.reading)
        assertEquals(DecodeError.AUTH_FAILED, frame.error)
    }

    @Test
    fun `reports a missing bind key instead of guessing`() {
        val frame = MiBeaconParser.parse(s200Frame, s200Mac, null)!!
        assertNull(frame.reading)
        assertEquals(DecodeError.NO_BIND_KEY, frame.error)
    }

    @Test
    fun `ignores the idle beacon the scale emits between weighings`() {
        // Real capture from an S200: no object payload, so there is nothing to report.
        val idle = hex("10 5b c9 45 01 6e 95 0a 6f 7b d0")
        val frame = MiBeaconParser.parse(idle, "D0:7B:6F:0A:95:6E", s200BindKey)!!

        assertEquals(0x45C9, frame.productId)
        assertEquals("Smart Scale S200 (MJTZC02YM)", frame.model)
        assertTrue(frame.isIdle)
        assertFalse(frame.encrypted)
        assertNull(frame.reading)
        // The frame carries the identity MAC, which is what the nonce needs when a later MAC-less
        // frame is decrypted.
        assertEquals("D0:7B:6F:0A:95:6E", frame.mac)
    }

    @Test
    fun `recognises an encrypted S200 weighing frame before it is decrypted`() {
        // Second real capture: encrypted, no MAC, objects present, product id 0x45C9.
        val frame = MiBeaconParser.parse(
            hex("48 59 c9 45 10 81 9b 36 06 26 14 da 1e 1e 6c 52 39 00 00 00 03 ee fb 53"),
            "D0:7B:6F:5A:05:FC",
            null,
        )!!
        assertEquals(0x45C9, frame.productId)
        assertTrue(frame.encrypted)
        assertTrue(frame.hasPayload)
        assertEquals(DecodeError.NO_BIND_KEY, frame.error)
    }

    @Test
    fun `decrypts when the identity MAC is supplied as an extra candidate`() {
        // Android can report a different address than the one the CCM nonce needs. Offering the
        // real MAC alongside it must still decrypt, and the reported address must not win.
        val frame = MiBeaconParser.parse(
            s200Frame,
            listOf("AA:BB:CC:DD:EE:FF", s200Mac),
            s200BindKey,
        )!!
        assertEquals(62.25, frame.reading!!.weightKg, 1e-9)
    }

    @Test
    fun `rejects every candidate when the bind key is wrong`() {
        val wrongKey = parseBindKey("11111111111111111111111111111111")!!
        val frame = MiBeaconParser.parse(
            s200Frame,
            listOf("AA:BB:CC:DD:EE:FF", "11:22:33:44:55:66", s200Mac),
            wrongKey,
        )!!
        assertNull(frame.reading)
        assertEquals(DecodeError.AUTH_FAILED, frame.error)
    }

    @Test
    fun `normalises a pasted MAC address`() {
        assertEquals("AA:BB:CC:DD:EE:FF", normalizeMac("AA:BB:CC:DD:EE:FF"))
        assertEquals("AA:BB:CC:DD:EE:FF", normalizeMac("aa-bb-cc-dd-ee-ff"))
        assertEquals("AA:BB:CC:DD:EE:FF", normalizeMac("aabbccddeeff"))
        assertNull(normalizeMac("D0:7B:6F:46:56"))
        assertNull(normalizeMac("D0:7B:6F:46:56:4Z"))
        assertNull(normalizeMac(""))
    }

    // --- Object decoding, exercised on unencrypted frames so it is tested on its own -------------

    /** Frame control 0x5940: MiBeacon v5, objects included, plaintext, no MAC, no capability byte. */
    private fun plainFrame(objects: String) = hex("40 59 04 4c 01 " + objects)

    @Test
    fun `decodes a weight object`() {
        // Same 62.25 kg object as the encrypted vector above, profile 1.
        val frame = MiBeaconParser.parse(
            plainFrame("16 4e 09 01 51 18 00 00 8a 1b 11 69"),
            s200Mac,
            null,
        )!!
        assertEquals(62.25, frame.reading!!.weightKg, 1e-9)
    }

    @Test
    fun `reports a zero weight as stepping off rather than as a reading`() {
        // Profile 2, weight 0 kg: the scale saying the person stepped off.
        val frame = MiBeaconParser.parse(
            plainFrame("16 4e 09 02 00 00 00 00 8a 1b 11 69"),
            s200Mac,
            null,
        )!!
        assertNull(frame.reading)
        assertTrue(frame.weightCleared)
        assertNull(frame.error)
    }

    @Test
    fun `walks past unknown objects to reach the weight`() {
        // An unrecognised 4 byte object followed by the weight object.
        val frame = MiBeaconParser.parse(
            plainFrame("99 99 04 de ad be ef 16 4e 09 01 51 18 00 00 8a 1b 11 69"),
            s200Mac,
            null,
        )!!
        assertEquals(62.25, frame.reading!!.weightKg, 1e-9)
    }

    @Test
    fun `ignores a truncated object list instead of throwing`() {
        // Announces 9 bytes of value but supplies 2.
        val frame = MiBeaconParser.parse(plainFrame("16 4e 09 01 51"), s200Mac, null)!!
        assertNull(frame.reading)
        assertFalse(frame.weightCleared)
    }

    // --- Older plaintext formats -----------------------------------------------------------------

    @Test
    fun `decodes the Mi Body Composition Scale plaintext format`() {
        // Published capture: 95.2 kg with a 486 ohm impedance reading.
        val payload = hex("02 26 b2 07 01 03 13 1a 15 e6 01 60 4a")
        val reading = LegacyScaleParser.parseBodyComposition(payload)!!

        assertEquals(95.2, reading.weightKg, 1e-9)
        assertEquals(486, reading.impedanceOhm)
        assertTrue(reading.stabilized)
        assertEquals("GATT 0x181B", reading.protocol)
        // The clock was never set on that scale, so the year reads 1970 and the phone clock wins.
        assertTrue(reading.measuredAt > 1_600_000_000_000L)
    }

    @Test
    fun `decodes the Mi Smart Scale plaintext format in kilograms`() {
        // Control 0x22 = kilograms + settled, weight 0xA2 0x30 = 12450 * 0.005 kg = 62.25 kg,
        // then a 2024-05-06 07:08:09 timestamp.
        val payload = hex("22 a2 30 e8 07 05 06 07 08 09")
        val reading = LegacyScaleParser.parseWeightScale(payload)!!

        assertEquals(62.25, reading.weightKg, 1e-9)
        assertTrue(reading.stabilized)
        assertEquals("GATT 0x181D", reading.protocol)
    }

    @Test
    fun `converts pounds as the scale asks`() {
        // Control 0x21 = pounds + settled.
        val pounds = LegacyScaleParser.parseWeightScale(hex("21 a2 30 e8 07 05 06 07 08 09"))!!
        assertEquals(12450 / 100.0 * 0.45359237, pounds.weightKg, 1e-9)
    }

    @Test
    fun `treats a reading the scale is still revising as unstable`() {
        // Bit 7 set means the value is not final, even though bit 5 says settled.
        val payload = hex("a2 a2 30 e8 07 05 06 07 08 09")
        val reading = LegacyScaleParser.parseWeightScale(payload)!!
        assertFalse(reading.stabilized)
        assertEquals(62.25, reading.weightKg, 1e-9)
    }

    @Test
    fun `ignores a plaintext frame with no weight on the scale`() {
        assertNull(LegacyScaleParser.parseWeightScale(hex("a2 00 00 e8 07 05 06 07 08 09")))
        assertNull(LegacyScaleParser.parseBodyComposition(hex("02 26 b2 07 01 03 13 1a 15 e6 01 00 00")))
    }

    // --- Bind key parsing ------------------------------------------------------------------------

    @Test
    fun `accepts a bind key in the shapes users paste`() {
        val expected = parseBindKey("653b1b10e1cb35e4ac5e60fa45f3bf29")!!
        assertEquals(expected.toList(), parseBindKey("653B1B10E1CB35E4AC5E60FA45F3BF29")!!.toList())
        assertEquals(
            expected.toList(),
            parseBindKey("65:3b:1b:10:e1:cb:35:e4:ac:5e:60:fa:45:f3:bf:29")!!.toList(),
        )
    }

    @Test
    fun `rejects bind keys that are not 16 bytes`() {
        assertNull(parseBindKey(""))
        assertNull(parseBindKey("653b1b10"))
        assertNull(parseBindKey("653b1b10e1cb35e4ac5e60fa45f3bf29aa"))
        assertNull(parseBindKey("653b1b10e1cb35e4ac5e60fa45f3bfg9"))
    }
}
