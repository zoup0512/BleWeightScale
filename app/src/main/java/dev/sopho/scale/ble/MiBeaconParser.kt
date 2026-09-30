package dev.sopho.scale.ble

import java.util.UUID

/**
 * Decoder for the Xiaomi "MiBeacon" v4/v5 vendor advertisements used by the Smart Scale S200
 * (model MJTZC02YM, advertises as `yunmai.scales.ms106`) and its siblings.
 *
 * The scale broadcasts its result as service data under the Xiaomi service UUID 0xFE95. The
 * payload is a small TLV container, and from MiBeacon v4 onwards the object section is
 * AES-128-CCM encrypted with the 16 byte bind key that Xiaomi hands out per device.
 *
 * Frame layout (offsets are into the service-data payload, *not* the raw AD structure):
 *
 * ```
 *   0..1  frame control (little endian)
 *   2..3  product id (little endian)
 *   4     frame counter
 *   5..   device MAC (6 bytes, reversed)      -- present when frame control bit 4 is set
 *         capability byte(s)                  -- present when frame control bit 5 is set
 *         object section                      -- present when frame control bit 6 is set
 *         extension counter (3 bytes) + MIC (4 bytes)  -- present when bit 3 (encrypted) is set
 * ```
 *
 * Frame control bit map: bit 7 mesh, bits 12..15 version, bits 10..11 auth mode,
 * bit 6 objects included, bit 5 capability included, bit 4 MAC included, bit 3 encrypted.
 */
object MiBeaconParser {

    val SERVICE_UUID: UUID = UUID.fromString("0000fe95-0000-1000-8000-00805f9b34fb")

    /** MiBeacon authenticates the object section with a 4 byte tag and a 1 byte AAD of 0x11. */
    private const val ASSOCIATED_DATA = 0x11.toByte()
    private const val TAG_LENGTH = 4
    private const val EXTENSION_COUNTER_LENGTH = 3

    /** Object type code carrying a weighing, keyed by the model family that emits it. */
    private const val OBJECT_WEIGHT_S200 = 0x4E16
    private const val OBJECT_WEIGHT_S400 = 0x6E16

    /**
     * Product ids Xiaomi has shipped for its scales, so a frame can be attributed to a model even
     * before it decrypts. Only the S200 entries matter for this app; the rest are recognised so
     * they can be reported rather than silently ignored.
     */
    private val PRODUCT_MODELS = mapOf(
        0x45C9 to "Smart Scale S200 (MJTZC02YM)",
        0x4C04 to "Smart Scale S200 (MJTZC02YM)",
        0x4DCB to "Smart Scale S200 (MJTZC02YM)",
        0x30D9 to "Body Composition Scale S400 (MJTZC01YM)",
        0x3BD5 to "Body Composition Scale S400 (MJTZC01YM)",
        0x48CF to "Body Composition Scale S400 (MJTZC01YM)",
        0x4B05 to "Body Composition Scale S800 (MJTZC03YM)",
        0x51E2 to "Body Composition Scale S800",
    )

    fun modelFor(productId: Int): String? = PRODUCT_MODELS[productId]

    /**
     * Decodes one 0xFE95 service-data payload.
     *
     * @param payload the service data exactly as Android strips it out of the AD structure.
     * @param deviceAddress Bluetooth address of the advertising device, used for the CCM nonce
     *   when the frame omits its own MAC.
     * @param bindKey 16 byte Xiaomi bind key, or null when the user has not configured one.
     */
    fun parse(payload: ByteArray, deviceAddress: String, bindKey: ByteArray?): MiBeaconFrame? =
        parse(payload, listOf(deviceAddress), bindKey)

    /**
     * @param macCandidates every address that could be this device's identity address, most
     *   trustworthy first. Encryption frames usually omit their own MAC, and Android does not
     *   always report the identity address, so each candidate is tried in turn. AES-CCM
     *   authenticates with a 4 byte tag, so a wrong candidate is rejected with probability
     *   1 - 2^-32 rather than silently yielding a bogus weight.
     */
    fun parse(
        payload: ByteArray,
        macCandidates: List<String>,
        bindKey: ByteArray?,
    ): MiBeaconFrame? {
        if (payload.size < 5) return null

        val frameControl = u16le(payload, 0)
        val version = frameControl ushr 12
        if (version < 2) return null
        if ((frameControl ushr 7) and 1 == 1) return null // mesh frames are a different protocol

        val objectsIncluded = (frameControl ushr 6) and 1 == 1
        val capabilityIncluded = (frameControl ushr 5) and 1 == 1
        val macIncluded = (frameControl ushr 4) and 1 == 1
        val encrypted = (frameControl ushr 3) and 1 == 1
        val authMode = (frameControl ushr 10) and 3

        val productId = u16le(payload, 2)
        val counter = payload[4].toInt() and 0xFF
        val rawHex = payload.toHex()

        var index = 5
        var frameMac: String? = null
        if (macIncluded) {
            if (payload.size < index + 6) return null
            frameMac = formatMac(payload.copyOfRange(index, index + 6).reversedArray())
            index += 6
        }
        if (capabilityIncluded) {
            if (payload.size <= index) return null
            val capability = payload[index].toInt() and 0xFF
            index++
            if (capability and 0x20 != 0) index++ // an additional IO capability byte follows
        }

        val base = MiBeaconFrame(
            productId = productId,
            model = modelFor(productId),
            frameVersion = version,
            counter = counter,
            encrypted = encrypted,
            authMode = authMode,
            mac = frameMac,
            hasPayload = objectsIncluded,
            rawHex = rawHex,
        )

        if (!objectsIncluded) return base // the scale's idle beacon: no objects, nothing to report

        val objectSection: ByteArray
        var error: String? = null

        if (encrypted) {
            if (version <= 3) {
                // MiBeacon v2/v3 uses an unauthenticated variant that this app does not need:
                // every S200 seen in the wild advertises as v4/v5.
                return base.copy(error = "不支持的加密版本 v$version")
            }
            if (bindKey == null) {
                return base.copy(error = DecodeError.NO_BIND_KEY)
            }
            if (bindKey.size != 16) {
                return base.copy(error = DecodeError.BAD_BIND_KEY_LENGTH)
            }
            val minimumLength = index + EXTENSION_COUNTER_LENGTH + TAG_LENGTH
            if (payload.size < minimumLength) {
                return base.copy(error = "报文长度不足，无法解密")
            }

            val candidateMacs = buildList {
                frameMac?.let(::add)
                addAll(macCandidates)
            }.mapNotNull(::macToBytes).distinct()
            if (candidateMacs.isEmpty()) {
                return base.copy(error = "无法确定设备 MAC，解密需要它构造 nonce")
            }

            val cipherInput = payload.copyOfRange(index, payload.size - 7) +
                payload.copyOfRange(payload.size - 4, payload.size)
            val objectPrefix = payload.copyOfRange(2, 5)
            val extensionCounter = payload.copyOfRange(payload.size - 7, payload.size - 4)

            var decrypted: ByteArray? = null
            for (identityMac in candidateMacs) {
                decrypted = AesCcm.decrypt(
                    key = bindKey,
                    nonce = identityMac.reversedArray() + objectPrefix + extensionCounter,
                    input = cipherInput,
                    associatedData = byteArrayOf(ASSOCIATED_DATA),
                    tagLength = TAG_LENGTH,
                )
                if (decrypted != null) break
            }
            if (decrypted == null) {
                return base.copy(error = DecodeError.AUTH_FAILED)
            }
            objectSection = decrypted
            error = null
        } else {
            objectSection = payload.copyOfRange(index, payload.size)
        }

        val scanned = readObjects(objectSection)
        return base.copy(
            reading = scanned.reading,
            weightCleared = scanned.weightCleared,
            error = error ?: if (scanned.reading == null && !scanned.weightCleared) {
                DecodeError.NO_WEIGHT_OBJECT
            } else {
                null
            },
        )
    }

    private class ObjectScan(val reading: ScaleReading?, val weightCleared: Boolean)

    /** Walks the `type(2) | length(1) | value(length)` object list and decodes any weighings. */
    private fun readObjects(objectSection: ByteArray): ObjectScan {
        var cursor = 0
        var reading: ScaleReading? = null
        var cleared = false
        while (objectSection.size >= cursor + 3) {
            val typeCode = u16le(objectSection, cursor)
            val length = objectSection[cursor + 2].toInt() and 0xFF
            val next = cursor + 3 + length
            if (objectSection.size < next) break
            val value = objectSection.copyOfRange(cursor + 3, next)

            when (typeCode) {
                OBJECT_WEIGHT_S200 -> {
                    val decoded = readS200Weight(value)
                    if (decoded != null) reading = decoded
                    if (isZeroWeightS200(value)) cleared = true
                }
                OBJECT_WEIGHT_S400 -> readS400Weight(value)?.let { reading = it }
            }
            cursor = next
        }
        return ObjectScan(reading, cleared)
    }

    private fun isZeroWeightS200(value: ByteArray): Boolean =
        value.size == 9 && u32le(value, 1) == 0L

    /**
     * Xiaomi Smart Scale S200: `profile id (1) | weight as kg*100 (4, little endian) | unix time (4)`.
     * A weight of zero means the person stepped off rather than a measurement, so it is reported
     * through [MiBeaconFrame.weightCleared] instead of as a reading.
     */
    private fun readS200Weight(value: ByteArray): ScaleReading? {
        if (value.size != 9) return null
        val profileId = value[0].toInt() and 0xFF
        val rawWeight = u32le(value, 1)
        if (rawWeight == 0L) return null
        val rawTimestamp = u32le(value, 5)

        return ScaleReading(
            weightKg = rawWeight / 100.0,
            // The S200 has no explicit "settled" flag in this object; by the time it broadcasts a
            // non-zero weight the measurement is the one it displays on its own panel.
            stabilized = true,
            impedanceOhm = null, // MJTZC02YM has no BIA electrodes
            profileId = profileId,
            measuredAt = saneTimestamp(rawTimestamp),
            protocol = "MiBeacon v5 / S200",
        )
    }

    /** Xiaomi Body Composition Scale S400: weight is the low 11 bits in units of 0.1 kg. */
    private fun readS400Weight(value: ByteArray): ScaleReading? {
        if (value.size < 6) return null
        val rawWeight = u32le(value, 0) and 0x7FF
        if (rawWeight == 0L) return null
        var impedance: Int? = null
        if (value.size >= 11) {
            val raw = (value[9].toInt() and 0xFF) or ((value[10].toInt() and 0xFF) shl 8)
            if (raw in 1..2999) impedance = raw
        }
        return ScaleReading(
            weightKg = rawWeight / 10.0,
            stabilized = true,
            impedanceOhm = impedance,
            measuredAt = System.currentTimeMillis(),
            protocol = "MiBeacon v5 / S400",
        )
    }

    /**
     * The scale's clock is often unset — it then reports 1970, which would put every record at the
     * bottom of the history list. Fall back to the phone's clock unless the value looks real.
     */
    private fun saneTimestamp(rawUnixSeconds: Long): Long {
        val millis = rawUnixSeconds * 1000L
        val earliestPlausible = 1_600_000_000_000L // 2020-09-13
        val latestPlausible = System.currentTimeMillis() + 24 * 60 * 60 * 1000L
        return if (millis in earliestPlausible..latestPlausible) millis else System.currentTimeMillis()
    }
}
