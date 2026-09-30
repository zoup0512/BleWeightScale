package dev.sopho.scale.ui

import android.app.DatePickerDialog
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.sopho.scale.data.WeightMetrics
import dev.sopho.scale.data.WeightRecord
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private enum class Range(val label: String, val days: Int) {
    WEEK("近7天", 7), MONTH("30天", 30), QUARTER("90天", 90), HALF("半年", 183), CUSTOM("自定义", 0),
}

@Composable
fun HistoryScreen(viewModel: ScaleViewModel, initialUnclaimed: Boolean = false, onReport: (String) -> Unit = {}, onManagePeople: () -> Unit = {}) {
    val all by viewModel.history.collectAsStateWithLifecycle()
    val people by viewModel.people.collectAsStateWithLifecycle()
    val selectedPersonId by viewModel.selectedPersonId.collectAsStateWithLifecycle()
    var unclaimed by rememberSaveable(initialUnclaimed) { mutableStateOf(initialUnclaimed) }
    var picker by remember { mutableStateOf(false) }
    var range by rememberSaveable { mutableStateOf(Range.WEEK) }
    var customStart by rememberSaveable { mutableStateOf<Long?>(null) }
    var customEnd by rememberSaveable { mutableStateOf<Long?>(null) }
    var compareFirst by remember { mutableStateOf<String?>(null) }
    var comparison by remember { mutableStateOf<String?>(null) }
    var assigning by remember { mutableStateOf<WeightRecord?>(null) }
    var deleting by remember { mutableStateOf<WeightRecord?>(null) }
    var clearConfirm by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val now = System.currentTimeMillis()
    val start = if (range == Range.CUSTOM) customStart else now - range.days * 24L * 60 * 60 * 1000
    val end = if (range == Range.CUSTOM) customEnd else now
    val filtered = all.filter { it.personId == if (unclaimed) null else selectedPersonId }
    val trend = WeightMetrics.trend(filtered, start, end)
    val visible = trend.records.asReversed()
    val person = people.firstOrNull { it.id == selectedPersonId }
    val pickerSelect: (String?) -> Unit = { id ->
        if (id != null) viewModel.selectPerson(id)
        unclaimed = id == null
    }

    if (picker) PersonPicker(people, selectedPersonId, pickerSelect, onManagePeople) { picker = false }
    assigning?.let { record ->
        AlertDialog(
            onDismissRequest = { assigning = null }, title = { Text("认领这条记录") },
            text = { Column {
                Text("%.2f kg · %s".format(record.weightKg, formatDate(record.measuredAt)))
                if (people.isEmpty()) Text("请先添加成员。")
                people.forEach { candidate ->
                    TextButton(onClick = { viewModel.assignRecord(record.id, candidate.id); assigning = null }, modifier = Modifier.fillMaxWidth()) { Text(candidate.name) }
                }
                TextButton(onClick = { viewModel.assignRecord(record.id, null); assigning = null }) { Text("设为未认领") }
            } },
            confirmButton = { TextButton(onClick = { assigning = null }) { Text("关闭") } },
        )
    }
    deleting?.let { record ->
        AlertDialog(
            onDismissRequest = { deleting = null }, title = { Text("删除记录？") },
            text = { Text("%.2f kg · %s，删除后无法恢复。".format(record.weightKg, formatDate(record.measuredAt))) },
            confirmButton = { TextButton(onClick = { viewModel.deleteRecord(record.id); deleting = null }) { Text("删除") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } },
        )
    }
    if (clearConfirm) AlertDialog(
        onDismissRequest = { clearConfirm = false }, title = { Text("清空全部记录？") },
        text = { Text("这将删除所有成员和未认领的称重记录，共 ${all.size} 条；人员档案会保留。") },
        confirmButton = { TextButton(onClick = { viewModel.clearHistory(); clearConfirm = false }) { Text("清空") } },
        dismissButton = { TextButton(onClick = { clearConfirm = false }) { Text("取消") } },
    )

    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("历史数据", style = MaterialTheme.typography.headlineSmall)
            TextButton(onClick = { picker = true }) { Text(if (unclaimed) "未认领数据 ▾" else "${person?.name ?: "选择成员"} ▾") }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Range.entries.take(3).forEach { option ->
                    FilterChip(selected = range == option, onClick = { range = option }, label = { Text(option.label, style = MaterialTheme.typography.labelSmall) })
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Range.entries.drop(3).forEach { option ->
                    FilterChip(selected = range == option, onClick = { range = option }, label = { Text(option.label, style = MaterialTheme.typography.labelSmall) })
                }
            }
            if (range == Range.CUSTOM) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = {
                        val c = Calendar.getInstance().apply { timeInMillis = customStart ?: now }
                        DatePickerDialog(context, { _, y, m, d ->
                            customStart = Calendar.getInstance().apply { set(y,m,d,0,0,0); set(Calendar.MILLISECOND,0) }.timeInMillis
                        }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show()
                    }) { Text("起始 ${customStart?.let(::formatDay) ?: "选择日期"}") }
                    TextButton(onClick = {
                        val c = Calendar.getInstance().apply { timeInMillis = customEnd ?: now }
                        DatePickerDialog(context, { _, y, m, d ->
                            customEnd = Calendar.getInstance().apply { set(y,m,d,23,59,59); set(Calendar.MILLISECOND,999) }.timeInMillis
                        }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show()
                    }) { Text("截止 ${customEnd?.let(::formatDay) ?: "选择日期"}") }
                }
            }
            Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) {
                Text("体重", style = MaterialTheme.typography.titleMedium)
                Text(trend.latestWeightKg?.let { "%.2f kg".format(it) } ?: "-- kg", style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.primary)
                WeightChart(visible)
                Text("共 ${trend.count} 次测量", style = MaterialTheme.typography.bodySmall)
            } }
            Spacer(Modifier.height(12.dp))
            Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) {
                Text("趋势变化", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text("区间变化：${trend.changeKg?.let { "%+.2f kg".format(it) } ?: "--"}")
                Text("最低 ${trend.minWeightKg?.let { "%.2f kg".format(it) } ?: "--"}  ·  最高 ${trend.maxWeightKg?.let { "%.2f kg".format(it) } ?: "--"}")
                Text("平均 ${trend.averageWeightKg?.let { "%.2f kg".format(it) } ?: "--"}")
            } }
            Spacer(Modifier.height(10.dp))
            Text("全部记录", style = MaterialTheme.typography.titleLarge)
            if (visible.isEmpty()) Text(if (unclaimed) "暂无未认领数据" else "该区间暂无记录。请上秤称重或切换成员。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (compareFirst != null) Text("再点选一条记录以比较", color = MaterialTheme.colorScheme.secondary)
        }
        items(visible, key = { it.id }) { record ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("%.2f kg".format(record.weightKg), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Medium)
                        TextButton(onClick = { onReport(record.id) }) { Text("报告 ›") }
                    }
                    Text(formatDate(record.measuredAt), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("${record.profileId?.let { "秤侧用户 $it · " } ?: ""}${record.personId?.let { id -> people.firstOrNull { it.id == id }?.name } ?: "未认领"}", style = MaterialTheme.typography.bodySmall)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        TextButton(onClick = { assigning = record }) { Text("认领 / 改派") }
                        TextButton(onClick = {
                            if (compareFirst == null) compareFirst = record.id
                            else {
                                val other = all.firstOrNull { it.id == compareFirst }
                                if (other != null) comparison = "${formatDay(other.measuredAt)} → ${formatDay(record.measuredAt)}：%+.2f kg".format(record.weightKg - other.weightKg)
                                compareFirst = null
                            }
                        }) { Text(if (compareFirst == record.id) "已选" else "对比") }
                        TextButton(onClick = { deleting = record }) { Text("删除", color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
        }
        item {
            if (all.isNotEmpty()) TextButton(onClick = { clearConfirm = true }) { Text("清空全部记录", color = MaterialTheme.colorScheme.error) }
        }
    }
    if (comparison != null) AlertDialog(onDismissRequest = { comparison = null }, title = { Text("数据对比") }, text = { Text(comparison.orEmpty()) }, confirmButton = { TextButton(onClick = { comparison = null }) { Text("完成") } })
}

private fun formatDate(millis: Long) = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(millis))
private fun formatDay(millis: Long) = SimpleDateFormat("MM/dd", Locale.getDefault()).format(Date(millis))
