package dev.sopho.scale.data

enum class BmiCategory { UNDERWEIGHT, NORMAL, OVERWEIGHT, OBESE }

data class TrendSummary(
    val records: List<WeightRecord>,
    val count: Int,
    val firstWeightKg: Double?,
    val latestWeightKg: Double?,
    val minWeightKg: Double?,
    val maxWeightKg: Double?,
    val averageWeightKg: Double?,
    val changeKg: Double?,
)

/** Adult BMI reference only; callers must suppress BMI for children, pregnancy, and other exclusions. */
object WeightMetrics {
    fun bmi(weightKg: Double, heightCm: Double?): Double? {
        if (!weightKg.isFinite() || weightKg <= 0 || heightCm == null || !heightCm.isFinite() || heightCm <= 0) return null
        val metres = heightCm / 100
        return (weightKg / (metres * metres)).takeIf { it.isFinite() }
    }

    fun targetWeight(heightCm: Double?): Double? {
        if (heightCm == null || !heightCm.isFinite() || heightCm <= 0) return null
        val metres = heightCm / 100
        return (22 * metres * metres).takeIf { it.isFinite() }
    }

    fun category(bmi: Double?): BmiCategory? = when {
        bmi == null || !bmi.isFinite() || bmi <= 0 -> null
        bmi < 18.5 -> BmiCategory.UNDERWEIGHT
        bmi < 24 -> BmiCategory.NORMAL
        bmi < 28 -> BmiCategory.OVERWEIGHT
        else -> BmiCategory.OBESE
    }

    /** Positive means above the BMI-22 reference weight. */
    fun delta(weightKg: Double, heightCm: Double?): Double? =
        targetWeight(heightCm)?.let { (weightKg - it).takeIf(Double::isFinite) }

    fun advice(weightKg: Double, heightCm: Double?): String? = when (category(bmi(weightKg, heightCm))) {
        BmiCategory.UNDERWEIGHT -> "低于成人参考范围；保持均衡饮食，如持续偏低请咨询医生。"
        BmiCategory.NORMAL -> "处于成人参考范围；保持规律饮食和运动。"
        BmiCategory.OVERWEIGHT -> "高于成人参考范围；建议规律活动并关注饮食。"
        BmiCategory.OBESE -> "明显高于成人参考范围；建议咨询专业医护人员。"
        null -> null
    }

    /** Inclusive wall-clock range; first/latest follow measurement time rather than input order. */
    fun trend(records: List<WeightRecord>, startMillis: Long? = null, endMillis: Long? = null): TrendSummary {
        val selected = records.filter {
            (startMillis == null || it.measuredAt >= startMillis) &&
                (endMillis == null || it.measuredAt <= endMillis)
        }.sortedWith(compareBy<WeightRecord> { it.measuredAt }.thenBy { it.id })
        return TrendSummary(
            records = selected,
            count = selected.size,
            firstWeightKg = selected.firstOrNull()?.weightKg,
            latestWeightKg = selected.lastOrNull()?.weightKg,
            minWeightKg = selected.minOfOrNull { it.weightKg },
            maxWeightKg = selected.maxOfOrNull { it.weightKg },
            averageWeightKg = selected.takeIf { it.isNotEmpty() }?.map { it.weightKg }?.average(),
            changeKg = selected.takeIf { it.size >= 2 }?.let { it.last().weightKg - it.first().weightKg },
        )
    }
}
