package dev.sopho.scale.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** One advertisement we found interesting enough to report to the UI. */
data class ScaleScanEvent(
    val address: String,
    val name: String?,
    val rssi: Int,
    val receivedAt: Long,
    val miBeacon: MiBeaconFrame? = null,
    val legacyReading: ScaleReading? = null,
) {
    /** True when the advertisement is one this app is looking for. */
    val isXiaomiScale: Boolean
        get() = miBeacon != null ||
            legacyReading != null ||
            name?.contains("MIBFS", ignoreCase = true) == true ||
            name?.contains("MIBCS", ignoreCase = true) == true
}

enum class ScannerStatus { IDLE, SCANNING, BLUETOOTH_OFF, NO_PERMISSION, LOCATION_OFF, FAILED }

data class ScannerState(
    val status: ScannerStatus = ScannerStatus.IDLE,
    val message: String? = null,
)

/**
 * Wraps [android.bluetooth.le.BluetoothLeScanner], doing all decoding on the scan callback thread
 * and publishing decoded events plus a rolling raw log.
 *
 * No scan filters are used on purpose: the scale's advertisement arrives as service data that
 * Android only hands over in full when the filter list is empty, and filtering by device address is
 * not an option because the scale does not advertise under a fixed one on every firmware.
 */
class ScaleScanner(
    context: Context,
    private val bindKeyProvider: () -> ByteArray?,
    private val deviceMacProvider: () -> String? = { null },
) {
    private val appContext = context.applicationContext
    private val bluetoothManager =
        appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val adapter: BluetoothAdapter? get() = bluetoothManager?.adapter

    private val _events = MutableStateFlow<ScaleScanEvent?>(null)
    val events: StateFlow<ScaleScanEvent?> = _events.asStateFlow()

    /** Recent advertisements in raw form, newest first, for the diagnostics screen. */
    private val _rawLog = MutableStateFlow<List<RawAdvertisement>>(emptyList())
    val rawLog: StateFlow<List<RawAdvertisement>> = _rawLog.asStateFlow()

    private val _state = MutableStateFlow(ScannerState())
    val state: StateFlow<ScannerState> = _state.asStateFlow()

    /**
     * Every advertisement seen, including ones that are not scales. Shown in the UI so an empty
     * device list can be told apart from a scan that is not receiving anything at all.
     */
    private val _advertisementsSeen = MutableStateFlow(0)
    val advertisementsSeen: StateFlow<Int> = _advertisementsSeen.asStateFlow()

    private var scanning = false

    /** MAC seen inside a MAC-bearing frame, used if the Android-reported address fails to decrypt. */
    private var lastSeenFrameMac: String? = null

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            handleResult(result)
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach(::handleResult)
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            _state.value = ScannerState(
                status = ScannerStatus.FAILED,
                message = "扫描启动失败，错误码 $errorCode",
            )
        }
    }

    fun hasPermission(): Boolean {
        val required = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        return required.all {
            ContextCompat.checkSelfPermission(appContext, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun isBluetoothEnabled(): Boolean = adapter?.isEnabled == true

    /**
     * Up to Android 11 the platform withholds scan results entirely while location services are
     * off — the scan starts and reports no error, it just never finds anything. Checking this
     * explicitly turns a silent failure into a message the user can act on.
     */
    fun isLocationBlockingScan(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) return false
        val manager = appContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            !manager.isLocationEnabled
        } else {
            !manager.isProviderEnabled(LocationManager.GPS_PROVIDER) &&
                !manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        }
    }

    @SuppressLint("MissingPermission")
    fun start() {
        if (scanning) return
        if (!hasPermission()) {
            _state.value = ScannerState(ScannerStatus.NO_PERMISSION, "缺少蓝牙权限")
            return
        }
        if (!isBluetoothEnabled()) {
            _state.value = ScannerState(ScannerStatus.BLUETOOTH_OFF, "蓝牙未开启")
            return
        }
        if (isLocationBlockingScan()) {
            _state.value = ScannerState(
                ScannerStatus.LOCATION_OFF,
                "请开启系统定位服务，Android 11 及以下要求开启后才能收到蓝牙扫描结果",
            )
            return
        }
        val scanner = adapter?.bluetoothLeScanner
        if (scanner == null) {
            _state.value = ScannerState(ScannerStatus.BLUETOOTH_OFF, "蓝牙未开启")
            return
        }

        _advertisementsSeen.value = 0
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setReportDelay(0)
            .setLegacy(false)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .build()

        try {
            scanner.startScan(emptyList(), settings, callback)
            scanning = true
            _state.value = ScannerState(ScannerStatus.SCANNING, null)
        } catch (e: SecurityException) {
            _state.value = ScannerState(ScannerStatus.NO_PERMISSION, e.message)
        }
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        if (!scanning) return
        scanning = false
        try {
            adapter?.bluetoothLeScanner?.stopScan(callback)
        } catch (e: SecurityException) {
            Log.w(TAG, "stopScan denied", e)
        }
        _state.value = ScannerState(ScannerStatus.IDLE, null)
    }

    fun clearLog() {
        _rawLog.value = emptyList()
    }

    @SuppressLint("MissingPermission")
    private fun handleResult(result: ScanResult) {
        _advertisementsSeen.value++
        val record = result.scanRecord ?: return
        val address = try {
            result.device.address ?: "??:??:??:??:??:??"
        } catch (e: SecurityException) {
            return
        }
        val name = record.deviceName

        var miBeacon: MiBeaconFrame? = null
        var legacy: ScaleReading? = null
        val serviceDataHex = StringBuilder()

        record.serviceData?.forEach { (parcelUuid, data) ->
            if (data == null) return@forEach
            val uuid = parcelUuid.uuid
            serviceDataHex.append("\n  ").append(shortUuid(uuid)).append(": ").append(data.toHex())
            when (uuid) {
                MiBeaconParser.SERVICE_UUID -> {
                    // The identity address the CCM nonce needs is not reliably the one Android
                    // reports, so every plausible source is offered as a candidate.
                    val candidates = buildList {
                        lastSeenFrameMac?.let(::add)
                        deviceMacProvider()?.let(::add)
                        add(address)
                    }.distinct()
                    val frame = MiBeaconParser.parse(data, candidates, bindKeyProvider())
                    if (frame != null) {
                        frame.mac?.let { lastSeenFrameMac = it }
                        miBeacon = frame
                    }
                }
                LegacyScaleParser.SERVICE_WEIGHT_SCALE ->
                    legacy = LegacyScaleParser.parseWeightScale(data)

                LegacyScaleParser.SERVICE_BODY_COMPOSITION ->
                    legacy = LegacyScaleParser.parseBodyComposition(data)
            }
        }

        val event = ScaleScanEvent(
            address = address,
            name = name,
            rssi = result.rssi,
            receivedAt = System.currentTimeMillis(),
            miBeacon = miBeacon,
            legacyReading = legacy,
        )

        if (event.isXiaomiScale) {
            _events.value = event
            appendToLog(event, result, serviceDataHex.toString())
        }
    }

    private fun appendToLog(
        event: ScaleScanEvent,
        result: ScanResult,
        serviceDataHex: String,
    ) {
        val frame = event.miBeacon
        val entry = RawAdvertisement(
            receivedAt = event.receivedAt,
            address = event.address,
            name = event.name,
            rssi = event.rssi,
            summary = frame?.let {
                val model = it.model ?: "未知设备"
                val kind = if (it.isIdle) "空闲广播" else "含数据"
                val enc = if (it.encrypted) "已加密" else "明文"
                "$model · $kind · $enc · PID=0x%04X · v%d · 序号=%d".format(
                    it.productId, it.frameVersion, it.counter,
                )
            } ?: event.legacyReading?.let { "${it.protocol} · 明文" } ?: "其他广播",
            detail = buildString {
                frame?.let { append(it.rawHex) }
                append(serviceDataHex)
                event.legacyReading?.let { append("\n  体重=").append("%.2f kg".format(it.weightKg)) }
                frame?.reading?.let { append("\n  体重=").append("%.2f kg".format(it.weightKg)) }
                frame?.error?.let { append("\n  错误=").append(it) }
                append("\n  RSSI=").append(result.rssi).append(" dBm")
            },
        )
        _rawLog.value = (listOf(entry) + _rawLog.value).take(MAX_LOG_ENTRIES)
    }

    private fun shortUuid(uuid: java.util.UUID): String {
        val s = uuid.toString()
        return if (s.startsWith("0000") && s.endsWith("-0000-1000-8000-00805f9b34fb")) {
            "0x" + s.substring(4, 8).uppercase()
        } else {
            s
        }
    }

    companion object {
        private const val TAG = "ScaleScanner"
        private const val MAX_LOG_ENTRIES = 120
    }
}

/** A raw advertisement, kept verbatim so the diagnostics screen can show the real bytes. */
data class RawAdvertisement(
    val receivedAt: Long,
    val address: String,
    val name: String?,
    val rssi: Int,
    val summary: String,
    val detail: String,
)
