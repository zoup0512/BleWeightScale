package dev.sopho.scale.ble

import java.util.UUID

/**
 * Decoder for the two plaintext advertisement formats older Xiaomi scales use. The S200 does not
 * speak either of these, but they cost almost nothing to support and they let the app work with the
 * Mi Smart Scale / Mi Body Composition Scale generation as well.
 *
 * Both formats put their payload in service data and need no encryption key.
 */
object LegacyScaleParser {

    /** Mi Smart Scale (XMTZC01HM / XMTZC04HM). */
    val SERVICE_WEIGHT_SCALE: UUID = UUID.fromString("0000181d-0000-1000-8000-00805f9b34fb")

    /** Mi Body Composition Scale (XMTZC02HM / XMTZC05HM / MIBFS / MIBCS). */
    val SERVICE_BODY_COMPOSITION: UUID = UUID.fromString("0000181b-0000-1000-8000-00805f9b34fb")

    private const val POUNDS_TO_KG = 0.45359237
    private const val JIN_TO_KG = 0.5

    /** 10 byte format: `control | weight u16 | year u16 | month | day | hour | minute | second`. */
    fun parseWeightScale(payload: ByteArray): ScaleReading? {
        if (payload.size < 10) return null

        val control = payload[0].toInt() and 0xFF
        val rawWeight = u16le(payload, 1)
        if (rawWeight == 0) return null

        // Bit 5 marks a settled reading, bit 7 marks one the scale is still revising. The weight is
        // reported either way — bit 7 only decides whether it is final.
        val stabilized = control and (1 shl 5) != 0 && control and (1 shl 7) == 0

        val weightKg = when {
            control and 1 != 0 -> rawWeight / 100.0 * POUNDS_TO_KG
            control and (1 shl 4) != 0 -> rawWeight / 100.0 * JIN_TO_KG
            else -> rawWeight / 200.0
        }

        return ScaleReading(
            weightKg = weightKg,
            stabilized = stabilized,
            measuredAt = timestampOrNow(payload, 3),
            protocol = "GATT 0x181D",
        )
    }

    /**
     * 13 byte format:
     * `control0 | control1 | year u16 | month | day | hour | minute | second | impedance u16 | weight u16`.
     */
    fun parseBodyComposition(payload: ByteArray): ScaleReading? {
        if (payload.size < 13) return null

        val control0 = payload[0].toInt() and 0xFF
        val control1 = payload[1].toInt() and 0xFF
        val hasImpedance = control1 and (1 shl 1) != 0
        val stabilized = control1 and (1 shl 5) != 0 && control1 and (1 shl 7) == 0

        val rawWeight = u16le(payload, 11)
        if (rawWeight == 0) return null

        // Catty takes precedence over the unit byte, matching how the scale actually behaves.
        val weightKg = when {
            control1 and (1 shl 6) != 0 -> rawWeight / 100.0 * JIN_TO_KG
            control0 == 0x03 -> rawWeight / 100.0 * POUNDS_TO_KG
            else -> rawWeight / 200.0
        }

        var impedance: Int? = null
        if (hasImpedance) {
            val raw = u16le(payload, 9)
            // 0xFFFD means the scale did not take a reading; anything outside the plausible
            // human range is treated the same way.
            if (raw != 0xFFFD && raw in 1..2999) impedance = raw
        }

        return ScaleReading(
            weightKg = weightKg,
            stabilized = stabilized,
            impedanceOhm = impedance,
            measuredAt = timestampOrNow(payload, 2),
            protocol = "GATT 0x181B",
        )
    }

    private fun timestampOrNow(payload: ByteArray, offset: Int): Long {
        val year = u16le(payload, offset)
        val month = payload[offset + 2].toInt() and 0xFF
        val day = payload[offset + 3].toInt() and 0xFF
        val hour = payload[offset + 4].toInt() and 0xFF
        val minute = payload[offset + 5].toInt() and 0xFF
        val second = payload[offset + 6].toInt() and 0xFF
        // An unset clock shows up as year 1970 / month 0, which is not a usable timestamp.
        if (year < 2020 || month !in 1..12 || day !in 1..31) return System.currentTimeMillis()
        return runCatching {
            java.util.Calendar.getInstance().apply {
                set(year, month - 1, day, hour, minute, second)
                set(java.util.Calendar.MILLISECOND, 0)
            }.timeInMillis
        }.getOrDefault(System.currentTimeMillis())
    }
}
