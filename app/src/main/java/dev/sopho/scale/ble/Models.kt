package dev.sopho.scale.ble

/** A single weight measurement recovered from the scale. */
data class ScaleReading(
    /** Weight in kilograms, already unit-converted. */
    val weightKg: Double,
    /** True when the scale reported the value as settled rather than mid-measurement. */
    val stabilized: Boolean,
    /** Body-fat impedance in ohms, when the hardware exposes it (S400/S800, not the S200). */
    val impedanceOhm: Int? = null,
    /** Xiaomi user/profile slot the scale assigned this weighing to, when reported. */
    val profileId: Int? = null,
    /** Wall-clock time of the measurement. */
    val measuredAt: Long,
    /** Human readable name of the framing that produced this reading. */
    val protocol: String,
)

/** Everything we could work out about one advertisement, including failures, for the debug log. */
data class MiBeaconFrame(
    val productId: Int,
    val model: String?,
    val frameVersion: Int,
    val counter: Int,
    val encrypted: Boolean,
    val authMode: Int,
    /** MAC advertised inside the frame, when the frame carried one. */
    val mac: String?,
    /** True when the frame carried an object payload at all; false for the scale's idle beacon. */
    val hasPayload: Boolean,
    /** Raw 0xFE95 payload, kept so the debug screen can show exactly what arrived. */
    val rawHex: String,
    val reading: ScaleReading? = null,
    /** True when the scale reported "nobody on the scale", which ends a weighing session. */
    val weightCleared: Boolean = false,
    /** Populated when a payload was present but could not be decoded. */
    val error: String? = null,
) {
    val isIdle: Boolean get() = !hasPayload
}

/** Reasons a payload could not be turned into a reading, surfaced verbatim in the UI. */
object DecodeError {
    const val NO_BIND_KEY = "广告数据已加密，但尚未配置 bindkey"
    const val BAD_BIND_KEY_LENGTH = "bindkey 必须是 16 字节（32 位十六进制）"
    const val AUTH_FAILED = "解密失败：bindkey 不正确，或该报文不属于此设备"
    const val NO_WEIGHT_OBJECT = "报文中没有体重数据（可能是空闲广播）"
}
