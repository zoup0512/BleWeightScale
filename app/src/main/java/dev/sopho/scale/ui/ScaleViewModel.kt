package dev.sopho.scale.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.sopho.scale.ble.RawAdvertisement
import dev.sopho.scale.ble.ScaleReading
import dev.sopho.scale.ble.ScaleScanEvent
import dev.sopho.scale.ble.ScaleScanner
import dev.sopho.scale.ble.ScannerState
import dev.sopho.scale.ble.normalizeMac
import dev.sopho.scale.data.Person
import dev.sopho.scale.data.PersonRepository
import dev.sopho.scale.data.SettingsStore
import dev.sopho.scale.data.WeightRecord
import dev.sopho.scale.data.WeightRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

data class DeviceInfo(
    val address: String,
    val name: String?,
    val model: String?,
    val productId: Int?,
    val rssi: Int,
    val lastSeenAt: Long,
)

class ScaleViewModel(application: Application) : AndroidViewModel(application) {
    private val settings = SettingsStore(application)
    private val repository = WeightRepository(application)
    private val personRepository = PersonRepository(application)
    private val selectionPrefs = application.getSharedPreferences("selected_person", 0)
    private val scanner = ScaleScanner(
        context = application,
        bindKeyProvider = { settings.bindKey() },
        deviceMacProvider = { settings.configuredMac() },
    )

    val bindKeyText: StateFlow<String> = settings.bindKeyText
    val macText: StateFlow<String> = settings.macText
    val autoSave: StateFlow<Boolean> = settings.autoSave
    val history: StateFlow<List<WeightRecord>> = repository.records
    val people: StateFlow<List<Person>> = personRepository.people
    val rawLog: StateFlow<List<RawAdvertisement>> = scanner.rawLog
    val scannerState: StateFlow<ScannerState> = scanner.state
    val advertisementsSeen: StateFlow<Int> = scanner.advertisementsSeen

    private val _selectedPersonId = MutableStateFlow(selectionPrefs.getString("id", null))
    val selectedPersonId: StateFlow<String?> = _selectedPersonId.asStateFlow()
    private val _liveReading = MutableStateFlow<ScaleReading?>(null)
    val liveReading: StateFlow<ScaleReading?> = _liveReading.asStateFlow()
    private val _livePersonId = MutableStateFlow<String?>(null)
    val livePersonId: StateFlow<String?> = _livePersonId.asStateFlow()
    private val _device = MutableStateFlow<DeviceInfo?>(null)
    val device: StateFlow<DeviceInfo?> = _device.asStateFlow()
    private val _decodeNotice = MutableStateFlow<String?>(null)
    val decodeNotice: StateFlow<String?> = _decodeNotice.asStateFlow()
    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast.asStateFlow()
    private val _hasBindKey = MutableStateFlow(settings.hasValidKey)
    val hasBindKey: StateFlow<Boolean> = _hasBindKey.asStateFlow()

    private var pendingReading: ScaleReading? = null
    private var pendingPersonId: String? = null
    private var pendingDeviceMac: String? = null
    private var commitJob: Job? = null

    init {
        viewModelScope.launch {
            personRepository.load()
            repository.load()
            if (_selectedPersonId.value != null && people.value.none { it.id == _selectedPersonId.value }) {
                selectPerson(null)
            }
        }
        viewModelScope.launch { scanner.events.collect { it?.let(::handleEvent) } }
    }

    fun selectedPerson(): Person? = people.value.firstOrNull { it.id == _selectedPersonId.value }
    fun selectPerson(id: String?) {
        _selectedPersonId.value = id
        selectionPrefs.edit().putString("id", id).apply()
    }

    fun savePerson(
        id: String?,
        name: String,
        heightCm: Double?,
        profileId: Int?,
        deviceMac: String?,
        bmiEligible: Boolean,
    ) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) { _toast.value = "请填写姓名"; return }
        if (heightCm != null && (heightCm < 100.0 || heightCm > 230.0)) {
            _toast.value = "身高请输入 100–230 cm"; return
        }
        if (profileId != null && profileId !in 0..255) {
            _toast.value = "秤侧用户编号请输入 0–255"; return
        }
        val normalized = deviceMac?.takeIf { it.isNotBlank() }?.let(::normalizeMac)
        if (!deviceMac.isNullOrBlank() && normalized == null) {
            _toast.value = "设备 MAC 格式不正确"; return
        }
        val actualMac = if (profileId != null) normalized ?: settings.configuredMac() else normalized
        if (profileId != null && actualMac == null) {
            _toast.value = "绑定秤侧编号时请先设置设备 MAC"; return
        }
        val conflict = people.value.any {
            it.id != id && it.profileId == profileId && profileId != null &&
                normalizeMac(it.deviceMac.orEmpty()) == actualMac
        }
        if (conflict) { _toast.value = "该设备的用户编号已绑定给其他人"; return }
        val person = Person(
            id = id ?: UUID.randomUUID().toString(),
            name = trimmed,
            heightCm = heightCm,
            profileId = profileId,
            deviceMac = actualMac,
            bmiEligible = bmiEligible,
        )
        viewModelScope.launch {
            personRepository.upsert(person)
            if (id == null) selectPerson(person.id)
            _toast.value = "人员资料已保存"
        }
    }

    fun deletePerson(id: String) {
        viewModelScope.launch {
            // Records survive removal of a person and return to the unclaimed list.
            history.value.filter { it.personId == id }.forEach { repository.assign(it.id, null) }
            personRepository.delete(id)
            if (selectedPersonId.value == id) selectPerson(null)
            _toast.value = "人员已删除，相关记录已转为未认领"
        }
    }

    fun assignRecord(recordId: String, personId: String?) {
        viewModelScope.launch {
            repository.assign(recordId, personId)
            _toast.value = if (personId == null) "已转为未认领" else "记录已归属到 ${people.value.firstOrNull { it.id == personId }?.name ?: "该人员"}"
        }
    }

    fun startScanning() = scanner.start()
    fun stopScanning() = scanner.stop()
    fun hasPermission() = scanner.hasPermission()
    fun clearRawLog() = scanner.clearLog()

    fun saveBindKey(text: String) {
        settings.saveBindKey(text)
        _hasBindKey.value = settings.hasValidKey
        _decodeNotice.value = null
        _toast.value = if (settings.hasValidKey) "bindkey 已保存" else "bindkey 格式不正确（需要 32 位十六进制）"
    }

    fun saveDeviceMac(text: String) {
        settings.saveDeviceMac(text)
        _decodeNotice.value = null
        _toast.value = if (settings.configuredMac() != null) "设备 MAC 已保存" else "设备 MAC 格式不正确"
    }

    fun setAutoSave(enabled: Boolean) {
        settings.setAutoSave(enabled)
        _toast.value = if (enabled) "已开启自动记录" else "已关闭自动记录"
    }

    fun deleteRecord(recordId: String) {
        viewModelScope.launch { repository.delete(recordId) }
    }

    fun clearHistory() {
        viewModelScope.launch {
            repository.clear()
            _toast.value = "历史记录已清空"
        }
    }

    fun consumeToast() { _toast.value = null }

    private fun matchPerson(profileId: Int?, deviceMac: String?): String? {
        if (profileId == null || deviceMac == null) return null
        val normalized = normalizeMac(deviceMac) ?: return null
        return people.value.firstOrNull {
            it.profileId == profileId && normalizeMac(it.deviceMac.orEmpty()) == normalized
        }?.id
    }

    private fun handleEvent(event: ScaleScanEvent) {
        val frame = event.miBeacon
        _device.value = DeviceInfo(
            event.address, event.name, frame?.model, frame?.productId, event.rssi, event.receivedAt,
        )
        val reading = frame?.reading ?: event.legacyReading
        when {
            reading != null -> {
                _liveReading.value = reading
                val mac = if (frame != null && reading.profileId != null) {
                    settings.configuredMac() ?: normalizeMac(frame.mac ?: event.address)
                } else {
                    normalizeMac(event.address)
                }
                val personId = matchPerson(reading.profileId, mac)
                _livePersonId.value = personId
                _decodeNotice.value = null
                if (reading.stabilized) {
                    pendingReading = reading
                    pendingPersonId = personId
                    pendingDeviceMac = mac
                    commitJob?.cancel()
                    commitJob = viewModelScope.launch {
                        delay(QUIET_PERIOD_MS)
                        commitPending()
                    }
                }
            }
            frame?.weightCleared == true -> {
                commitJob?.cancel()
                commitJob = viewModelScope.launch { commitPending() }
            }
            frame?.error != null -> _decodeNotice.value = frame.error
        }
    }

    private suspend fun commitPending() {
        val reading = pendingReading ?: return
        val personId = pendingPersonId
        val deviceMac = pendingDeviceMac
        pendingReading = null
        pendingPersonId = null
        pendingDeviceMac = null
        if (!settings.autoSave.value) {
            _toast.value = "测得 %.2f kg（未记录）".format(reading.weightKg)
        } else if (repository.add(reading, deviceMac, personId)) {
            _toast.value = if (personId == null) "测得 %.2f kg，已存入未认领数据".format(reading.weightKg)
            else "已为 ${people.value.firstOrNull { it.id == personId }?.name ?: "用户"} 记录 %.2f kg".format(reading.weightKg)
        }
    }

    override fun onCleared() {
        scanner.stop()
        super.onCleared()
    }

    private companion object { const val QUIET_PERIOD_MS = 8_000L }
}
