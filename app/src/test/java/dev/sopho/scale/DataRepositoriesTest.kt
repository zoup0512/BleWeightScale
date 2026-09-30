package dev.sopho.scale

import dev.sopho.scale.ble.ScaleReading
import dev.sopho.scale.data.BmiCategory
import dev.sopho.scale.data.Person
import dev.sopho.scale.data.PersonRepository
import dev.sopho.scale.data.WeightMetrics
import dev.sopho.scale.data.WeightRecord
import dev.sopho.scale.data.WeightRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.UUID

class DataRepositoriesTest {
    @get:Rule val temp = TemporaryFolder()
    private fun reading(time: Long, weight: Double = 65.0, profile: Int? = 1) = ScaleReading(
        weightKg = weight, stabilized = true, measuredAt = time, protocol = "S200", profileId = profile,
    )

    @Test fun legacyRecordsMigratePreservingAllFieldsAndStableIds() = runBlocking {
        val file = temp.newFile("weights.json")
        val legacy = """[{"measuredAt":1000,"weightKg":61.2,"impedanceOhm":423,"profileId":4,"protocol":"S200"},{"measuredAt":1000,"weightKg":61.2,"impedanceOhm":423,"profileId":4,"protocol":"S200"}]"""
        file.writeText(legacy)
        val first = WeightRepository(file).apply { load() }.records.value
        assertEquals(2, first.size)
        assertNotEquals(first[0].id, first[1].id)
        assertTrue(first.all { UUID.fromString(it.id).toString() == it.id && it.personId == null && it.deviceMac == null })
        assertEquals(423, first[0].impedanceOhm)
        assertEquals(4, first[0].profileId)
        assertEquals("S200", first[0].protocol)
        assertTrue(file.readText().contains("\"id\""))
        val reloaded = WeightRepository(file).apply { load() }.records.value
        assertEquals(first, reloaded)
        // Deterministic before and after persistence: same original source yields the same IDs.
        file.writeText(legacy)
        assertEquals(first.map { it.id }, WeightRepository(file).apply { load() }.records.value.map { it.id })
    }

    @Test fun addAssignDeleteByIdDedupScopeAndPersistence() = runBlocking {
        val file = File(temp.root, "weights.json")
        val repo = WeightRepository(file)
        assertTrue(repo.add(reading(100000), "aa-bb-cc-dd-ee-ff", null))
        assertFalse(repo.add(reading(100100), "AA:BB:CC:DD:EE:FF", null))
        assertTrue(repo.add(reading(100100), "AA:BB:CC:DD:EE:00", null))
        assertTrue(repo.add(reading(100100, profile = 2), "AA:BB:CC:DD:EE:FF", null))
        assertTrue(repo.add(reading(100100), "AA:BB:CC:DD:EE:FF", "person"))
        assertTrue(repo.add(reading(130000), "AA:BB:CC:DD:EE:FF", null))
        val id = repo.records.value.first().id
        repo.assign(id, "person")
        assertEquals("person", repo.records.value.first().personId)
        repo.delete(id)
        assertEquals(4, repo.records.value.size)
        assertEquals(repo.records.value, WeightRepository(file).apply { load() }.records.value)
        repo.clear()
        assertTrue(WeightRepository(file).apply { load() }.records.value.isEmpty())
    }

    @Test fun peoplePersistAdultFlagAndEnforcePerDeviceProfileUniqueness() = runBlocking {
        val file = File(temp.root, "people.json")
        val repo = PersonRepository(file)
        val alice = Person(name = "张\"甲", heightCm = 167.5, profileId = 2, deviceMac = "aabbccddeeff", bmiEligible = false)
        repo.upsert(alice)
        val loaded = PersonRepository(file).apply { load() }.people.value.single()
        assertEquals(alice.copy(deviceMac = "AA:BB:CC:DD:EE:FF"), loaded)
        try {
            repo.upsert(Person(name = "duplicate", profileId = 2, deviceMac = "AA:BB:CC:DD:EE:FF"))
            fail("Expected duplicate slot rejection")
        } catch (_: IllegalArgumentException) { }
        repo.upsert(Person(name = "other scale", profileId = 2, deviceMac = "AA:BB:CC:DD:EE:00"))
        repo.delete(alice.id)
        assertEquals(1, PersonRepository(file).apply { load() }.people.value.size)
    }

    @Test fun concurrentWritesAreSerializedWithoutLostRecords() = runBlocking {
        val file = File(temp.root, "parallel.json")
        val repo = WeightRepository(file)
        (1..40).map { i ->
            async(Dispatchers.Default) { repo.add(reading(i * 30000L, 55.0 + i), "AA:BB:CC:DD:EE:FF", null) }
        }.awaitAll()
        assertEquals(40, repo.records.value.size)
        assertEquals(40, WeightRepository(file).apply { load() }.records.value.size)
    }

    @Test fun metricsAdultThresholdsAndInclusiveChronologicalTrend() {
        assertNull(WeightMetrics.bmi(65.0, null))
        assertNull(WeightMetrics.bmi(65.0, 0.0))
        assertEquals(22.0, WeightMetrics.targetWeight(100.0)!!, 0.00001)
        assertEquals(3.0, WeightMetrics.delta(25.0, 100.0)!!, 0.00001)
        assertEquals(BmiCategory.UNDERWEIGHT, WeightMetrics.category(18.499))
        assertEquals(BmiCategory.NORMAL, WeightMetrics.category(18.5))
        assertEquals(BmiCategory.OVERWEIGHT, WeightMetrics.category(24.0))
        assertEquals(BmiCategory.OBESE, WeightMetrics.category(28.0))
        assertNotNull(WeightMetrics.advice(65.0, 170.0))
        assertNull(WeightMetrics.advice(65.0, null))
        val entries = listOf(WeightRecord(300, 70.0, null, null, "test"), WeightRecord(100, 60.0, null, null, "test"), WeightRecord(200, 65.0, null, null, "test"))
        val trend = WeightMetrics.trend(entries, 100, 200)
        assertEquals(2, trend.count)
        assertEquals(60.0, trend.firstWeightKg!!, 0.0)
        assertEquals(65.0, trend.latestWeightKg!!, 0.0)
        assertEquals(62.5, trend.averageWeightKg!!, 0.0)
        assertEquals(5.0, trend.changeKg!!, 0.0)
        assertNull(WeightMetrics.trend(entries, 400, 500).averageWeightKg)
    }
}
