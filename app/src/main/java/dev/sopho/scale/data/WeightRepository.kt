package dev.sopho.scale.data

import android.content.Context
import dev.sopho.scale.ble.ScaleReading
import dev.sopho.scale.ble.normalizeMac
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.UUID
import kotlin.math.abs

data class WeightRecord(
    val measuredAt: Long,
    val weightKg: Double,
    val impedanceOhm: Int?,
    val profileId: Int?,
    val protocol: String,
    val id: String = UUID.randomUUID().toString(),
    val personId: String? = null,
    val deviceMac: String? = null,
)

/** JSON history with stable record IDs; all read/modify/write operations are serialized. */
class WeightRepository(private val file: File) {
    constructor(context: Context) : this(File(context.applicationContext.filesDir, "weights.json"))

    private val mutex = Mutex()
    private val _records = MutableStateFlow<List<WeightRecord>>(emptyList())
    val records: StateFlow<List<WeightRecord>> = _records.asStateFlow()
    private var loaded = false

    suspend fun load(): Unit = mutex.withLock { withContext(Dispatchers.IO) { loadLocked() } }

    /** @return true if stored, false if already broadcast within the 30-second repeat window. */
    suspend fun add(reading: ScaleReading, deviceMac: String?, personId: String?): Boolean = mutex.withLock {
        withContext(Dispatchers.IO) {
            loadLocked()
            require(reading.weightKg.isFinite() && reading.weightKg > 0) { "Invalid weight" }
            val mac = deviceMac?.let { normalizeMac(it) ?: throw IllegalArgumentException("Invalid device MAC") }
            val record = WeightRecord(
                measuredAt = reading.measuredAt,
                weightKg = reading.weightKg,
                impedanceOhm = reading.impedanceOhm,
                profileId = reading.profileId,
                protocol = reading.protocol,
                deviceMac = mac,
                personId = personId,
            )
            if (_records.value.any {
                    it.deviceMac == mac && it.profileId == record.profileId && it.personId == personId &&
                        abs(it.measuredAt - record.measuredAt) < DUPLICATE_WINDOW_MS &&
                        abs(it.weightKg - record.weightKg) < 0.005
                }) return@withContext false
            val next = (_records.value + record).sortedByDescending { it.measuredAt }
            persist(next)
            _records.value = next
            true
        }
    }

    /** Compatibility with the existing UI until it passes the observed device identity. */
    suspend fun add(reading: ScaleReading): Boolean = add(reading, null, null)

    suspend fun assign(recordId: String, personId: String?): Unit = mutex.withLock {
        withContext(Dispatchers.IO) {
            loadLocked()
            val next = _records.value.map { if (it.id == recordId) it.copy(personId = personId) else it }
            if (next != _records.value) { persist(next); _records.value = next }
        }
    }

    suspend fun delete(recordId: String): Unit = mutex.withLock {
        withContext(Dispatchers.IO) {
            loadLocked()
            deleteLocked { it.id == recordId }
        }
    }

    /** Compatibility with existing history UI (which identifies records by timestamp). */
    suspend fun delete(measuredAt: Long): Unit = mutex.withLock {
        withContext(Dispatchers.IO) {
            loadLocked()
            deleteLocked { it.measuredAt == measuredAt }
        }
    }

    suspend fun clear(): Unit = mutex.withLock {
        withContext(Dispatchers.IO) {
            loadLocked()
            persist(emptyList())
            _records.value = emptyList()
        }
    }

    private fun deleteLocked(predicate: (WeightRecord) -> Boolean) {
        val next = _records.value.filterNot(predicate)
        if (next != _records.value) { persist(next); _records.value = next }
    }

    private fun loadLocked() {
        if (loaded) return
        if (!file.exists()) { loaded = true; return }
        val source = jsonArray(JsonCodec.parse(file.readText()))
        var migrated = false
        val next = source.mapIndexed { index, element ->
            val obj = jsonObject(element)
            val id = obj.optionalString("id") ?: run {
                migrated = true
                // Content + original position distinguishes identical legacy entries deterministically.
                UUID.nameUUIDFromBytes("legacy-weight:$index:${JsonCodec.stringify(obj)}".toByteArray(StandardCharsets.UTF_8)).toString()
            }
            WeightRecord(
                id = id,
                measuredAt = obj.requiredNumber("measuredAt").toLong(),
                weightKg = obj.requiredNumber("weightKg").toDouble(),
                impedanceOhm = obj.optionalNumber("impedanceOhm")?.toInt(),
                profileId = obj.optionalNumber("profileId")?.toInt(),
                protocol = obj.optionalString("protocol") ?: "unknown",
                personId = obj.optionalString("personId"),
                deviceMac = obj.optionalString("deviceMac"),
            )
        }.sortedByDescending { it.measuredAt }
        require(next.map { it.id }.distinct().size == next.size) { "Duplicate record IDs" }
        if (migrated) persist(next)
        _records.value = next
        loaded = true
    }

    private fun persist(next: List<WeightRecord>) = writeJsonAtomically(file, JsonCodec.stringify(next.map {
        linkedMapOf<String, Any?>(
            "id" to it.id,
            "measuredAt" to it.measuredAt,
            "weightKg" to it.weightKg,
            "impedanceOhm" to it.impedanceOhm,
            "profileId" to it.profileId,
            "protocol" to it.protocol,
            "personId" to it.personId,
            "deviceMac" to it.deviceMac,
        )
    }))

    private companion object { const val DUPLICATE_WINDOW_MS = 30_000L }
}
