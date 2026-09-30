package dev.sopho.scale.data

import android.content.Context
import dev.sopho.scale.ble.normalizeMac
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

data class Person(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val heightCm: Double? = null,
    val profileId: Int? = null,
    val deviceMac: String? = null,
    val bmiEligible: Boolean = true,
)

/** Local-only people; S200 profile slots are unique within a particular physical scale. */
class PersonRepository(private val file: File) {
    constructor(context: Context) : this(File(context.applicationContext.filesDir, "people.json"))

    private val mutex = Mutex()
    private val _people = MutableStateFlow<List<Person>>(emptyList())
    val people: StateFlow<List<Person>> = _people.asStateFlow()
    private var loaded = false

    suspend fun load(): Unit = mutex.withLock { withContext(Dispatchers.IO) { loadLocked() } }

    suspend fun upsert(person: Person): Unit = mutex.withLock {
        withContext(Dispatchers.IO) {
            loadLocked()
            require(person.id.isNotBlank()) { "Person ID cannot be blank" }
            require(person.name.isNotBlank()) { "Person name cannot be blank" }
            require(person.heightCm == null || person.heightCm.isFinite() && person.heightCm > 0) { "Invalid height" }
            require(person.profileId == null || person.profileId >= 0) { "Invalid profile ID" }
            val mac = person.deviceMac?.let { normalizeMac(it) ?: throw IllegalArgumentException("Invalid device MAC") }
            require(person.profileId == null || mac != null) { "A profile slot requires a device MAC" }
            val updated = person.copy(name = person.name.trim(), deviceMac = mac)
            require(_people.value.none {
                it.id != updated.id && mac != null && it.deviceMac == mac && it.profileId != null && it.profileId == updated.profileId
            }) { "This device profile is already assigned" }
            val next = _people.value.filterNot { it.id == updated.id } + updated
            writeJsonAtomically(file, JsonCodec.stringify(next.map(Person::toMap)))
            _people.value = next
        }
    }

    suspend fun delete(id: String): Unit = mutex.withLock {
        withContext(Dispatchers.IO) {
            loadLocked()
            val next = _people.value.filterNot { it.id == id }
            if (next.size != _people.value.size) {
                writeJsonAtomically(file, JsonCodec.stringify(next.map(Person::toMap)))
                _people.value = next
            }
        }
    }

    private fun loadLocked() {
        if (loaded) return
        val parsed = if (!file.exists()) emptyList() else jsonArray(JsonCodec.parse(file.readText())).map {
            val obj = jsonObject(it)
            Person(
                id = obj.requiredString("id"),
                name = obj.requiredString("name"),
                heightCm = obj.optionalNumber("heightCm")?.toDouble(),
                profileId = obj.optionalNumber("profileId")?.toInt(),
                deviceMac = obj.optionalString("deviceMac"),
                bmiEligible = obj["bmiEligible"] as? Boolean ?: true,
            )
        }
        _people.value = parsed
        loaded = true
    }
}

private fun Person.toMap(): Map<String, Any?> = linkedMapOf(
    "id" to id,
    "name" to name,
    "heightCm" to heightCm,
    "profileId" to profileId,
    "deviceMac" to deviceMac,
    "bmiEligible" to bmiEligible,
)
