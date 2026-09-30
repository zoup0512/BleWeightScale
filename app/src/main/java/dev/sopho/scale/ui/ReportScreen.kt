package dev.sopho.scale.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sopho.scale.data.BmiCategory
import dev.sopho.scale.data.Person
import dev.sopho.scale.data.WeightMetrics
import dev.sopho.scale.data.WeightRecord
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ReportScreen(record: WeightRecord?, person: Person?, onBack: () -> Unit, onHistory: () -> Unit) {
    Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("称重报告", style = MaterialTheme.typography.headlineSmall)
            TextButton(onClick = onBack) { Text("返回") }
        }
        Spacer(Modifier.height(16.dp))
        if (record == null) {
            Text("找不到这条记录，可能已经删除。")
            return@Column
        }
        val bmi = person?.takeIf { it.bmiEligible }?.let { WeightMetrics.bmi(record.weightKg, it.heightCm) }
        val target = person?.takeIf { it.bmiEligible }?.let { WeightMetrics.targetWeight(it.heightCm) }
        val category = WeightMetrics.category(bmi)

        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(24.dp)) {
                Text(person?.name ?: "未认领数据", style = MaterialTheme.typography.titleMedium)
                Text(SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault()).format(Date(record.measuredAt)), style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(28.dp))
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("%.2f".format(record.weightKg), fontSize = 64.sp, fontWeight = FontWeight.Light)
                    Text(" kg", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 10.dp))
                }
                Text("体重", style = MaterialTheme.typography.bodyMedium)
            }
        }
        Spacer(Modifier.height(16.dp))
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp)) {
                Text("身体质量指数 BMI", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(12.dp))
                Text(if (bmi == null) "未计算" else "%.1f   %s".format(bmi, category.label()), style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(8.dp))
                Text(
                    if (bmi == null) "请为成人成员设置身高后查看 BMI；儿童和孕期等人群不适用此成人参考。"
                    else "BMI = 体重(kg) ÷ 身高(m)²；中国成人参考区间：18.5–23.9。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp)) {
                Text("体重控制建议", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(16.dp))
                ReportItem("参考目标体重", target?.let { "%.2f kg".format(it) } ?: "--")
                ReportItem("目标差额", target?.let { "%+.2f kg".format(it - record.weightKg) } ?: "--")
                Spacer(Modifier.height(12.dp))
                Text(
                    if (person?.bmiEligible == true) WeightMetrics.advice(record.weightKg, person.heightCm) ?: "设置身高后可查看建议。"
                    else "不适用成人 BMI 建议。",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(14.dp))
                Text("参考目标按 BMI 22 计算，与米家官方的“标准体重”算法可能不同。以上仅供健康管理参考，不能替代医疗建议。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(16.dp))
        Button(onClick = onHistory, modifier = Modifier.fillMaxWidth()) { Text("查看历史趋势与数据对比") }
        Spacer(Modifier.height(10.dp))
        Text("数据来源：小米体重秤 S200 · ${record.protocol}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ReportItem(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontWeight = FontWeight.SemiBold)
    }
}

private fun BmiCategory?.label(): String = when (this) {
    BmiCategory.UNDERWEIGHT -> "偏瘦"
    BmiCategory.NORMAL -> "标准"
    BmiCategory.OVERWEIGHT -> "超重"
    BmiCategory.OBESE -> "肥胖"
    null -> ""
}
