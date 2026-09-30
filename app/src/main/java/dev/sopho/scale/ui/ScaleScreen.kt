package dev.sopho.scale.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.sopho.scale.data.WeightMetrics
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.sopho.scale.ble.ScannerStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ScaleScreen(
    viewModel: ScaleViewModel,
    onRequestPermissions: () -> Unit,
    onOpenSettings: () -> Unit,
    onEnableBluetooth: () -> Unit,
    onEnableLocation: () -> Unit,
    onOpenPeople: () -> Unit,
    onOpenReport: (String) -> Unit,
    onOpenHistory: (Boolean) -> Unit,
) {
    val reading by viewModel.liveReading.collectAsStateWithLifecycle()
    val status by viewModel.scannerState.collectAsStateWithLifecycle()
    val notice by viewModel.decodeNotice.collectAsStateWithLifecycle()
    val hasKey by viewModel.hasBindKey.collectAsStateWithLifecycle()
    val autoSave by viewModel.autoSave.collectAsStateWithLifecycle()
    val history by viewModel.history.collectAsStateWithLifecycle()
    val people by viewModel.people.collectAsStateWithLifecycle()
    val selectedId by viewModel.selectedPersonId.collectAsStateWithLifecycle()
    val livePersonId by viewModel.livePersonId.collectAsStateWithLifecycle()
    val person = people.firstOrNull { it.id == selectedId }
    val records = history.filter { it.personId == selectedId }
    val latest = records.maxByOrNull { it.measuredAt }
    val displayReading = reading?.takeIf { livePersonId == selectedId && it.measuredAt >= System.currentTimeMillis() - 90_000L }
    val displayWeight = displayReading?.weightKg ?: latest?.weightKg
    val bmi = person?.takeIf { it.bmiEligible }?.let { WeightMetrics.bmi(displayWeight ?: 0.0, it.heightCm) }
    val target = person?.takeIf { it.bmiEligible }?.let { WeightMetrics.targetWeight(it.heightCm) }
    var showPeople by remember { mutableStateOf(false) }
    if (showPeople) PersonPicker(people, selectedId, viewModel::selectPerson, onOpenPeople) { showPeople = false }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        StatusRow(status.status, status.message) {
            when (status.status) {
                ScannerStatus.NO_PERMISSION -> onRequestPermissions()
                ScannerStatus.BLUETOOTH_OFF -> onEnableBluetooth()
                ScannerStatus.LOCATION_OFF -> onEnableLocation()
                else -> viewModel.startScanning()
            }
        }

        Spacer(Modifier.height(14.dp))
        Card(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text(person?.name ?: "未认领数据", style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = { showPeople = true }) { Text("切换成员 ▾") }
            }
        }
        Spacer(Modifier.height(14.dp))

        WeightDisplay(displayWeight, displayReading?.protocol ?: latest?.protocol, displayReading?.measuredAt ?: latest?.measuredAt)
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("BMI ${bmi?.let { "%.1f".format(it) } ?: "--"}", style = MaterialTheme.typography.titleMedium)
            Text("参考体重 ${target?.let { "%.1f kg".format(it) } ?: "--"}", style = MaterialTheme.typography.titleMedium)
        }
        if (person != null && person.heightCm == null) {
            Text("在成员档案填写身高后可计算 BMI", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(12.dp))
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("体重趋势", style = MaterialTheme.typography.titleMedium)
                WeightChart(records.take(30).reversed())
                if (latest != null) TextButton(onClick = { onOpenReport(latest.id) }) { Text("查看报告 ›") }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = { onOpenHistory(false) }) { Text("历史数据 ›") }
            TextButton(onClick = { onOpenHistory(true) }) { Text("未认领数据 (${history.count { it.personId == null }}) ›") }
        }
        Spacer(Modifier.height(10.dp))

        if (!autoSave) {
            InfoCard(
                title = "自动记录已关闭",
                body = "称重结果会显示在这里，但不会写入历史记录。可在“设置”中打开。",
            )
            Spacer(Modifier.height(12.dp))
        }

        notice?.let {
            NoticeCard(
                title = "无法解析体重数据",
                body = it,
                actionLabel = if (!hasKey) "去设置 bindkey" else null,
                onAction = onOpenSettings,
            )
            Spacer(Modifier.height(12.dp))
        }

        if (!hasKey && notice == null) {
            InfoCard(
                title = "还没有配置 bindkey",
                body = "小米 S200 的体重数据是加密广播的。填入从米家账号导出的 16 字节 bindkey 后，"
                    + "本应用才能解密并显示读数。",
                actionLabel = "去设置",
                onAction = onOpenSettings,
            )
            Spacer(Modifier.height(12.dp))
        }

        DeviceCard(viewModel)

        Spacer(Modifier.height(12.dp))

        Text(
            text = "站上体重秤即可，无需在本应用中手动连接。S200 只在称重时唤醒蓝牙，" +
                "唤醒后约 1 秒内会被本应用捕获。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun WeightDisplay(weightKg: Double?, protocol: String?, measuredAt: Long?) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 32.dp, horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = weightKg?.let { "%.2f".format(it) } ?: "--.--",
                    fontSize = 60.sp,
                    fontWeight = FontWeight.Light,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Text(
                    text = "kg",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(start = 6.dp, bottom = 12.dp),
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = if (weightKg == null) {
                    "等待上秤…"
                } else {
                    val time = measuredAt?.let {
                        SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(it))
                    }
                    listOfNotNull(protocol, time).joinToString(" · ")
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

@Composable
private fun StatusRow(
    status: ScannerStatus,
    message: String?,
    onRetry: () -> Unit,
) {
    val (label, container, content) = when (status) {
        ScannerStatus.SCANNING -> Triple(
            "正在扫描…",
            MaterialTheme.colorScheme.secondaryContainer,
            MaterialTheme.colorScheme.onSecondaryContainer,
        )
        ScannerStatus.IDLE -> Triple(
            "已暂停扫描",
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ScannerStatus.BLUETOOTH_OFF -> Triple(
            message ?: "蓝牙未开启",
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.onErrorContainer,
        )
        ScannerStatus.NO_PERMISSION -> Triple(
            message ?: "缺少蓝牙权限",
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.onErrorContainer,
        )
        ScannerStatus.LOCATION_OFF -> Triple(
            message ?: "需要开启定位服务才能扫描",
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.onErrorContainer,
        )
        ScannerStatus.FAILED -> Triple(
            message ?: "扫描失败",
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.onErrorContainer,
        )
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(color = container, shape = MaterialTheme.shapes.extraLarge) {
            Text(
                text = label,
                color = content,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
        if (status != ScannerStatus.SCANNING) {
            TextButton(onClick = onRetry) {
                Icon(Icons.Default.Refresh, contentDescription = null, Modifier.size(18.dp))
                Spacer(Modifier.size(6.dp))
                Text("重试")
            }
        }
    }
}

@Composable
private fun DeviceCard(viewModel: ScaleViewModel) {
    val device by viewModel.device.collectAsStateWithLifecycle()
    val seen by viewModel.advertisementsSeen.collectAsStateWithLifecycle()
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("设备", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            if (device == null) {
                Text(
                    "尚未发现设备。请站上体重秤唤醒它。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    if (seen == 0) {
                        "已接收 0 条广播：说明扫描没有收到任何数据，请检查系统定位服务是否开启" +
                            "（Android 11 及以下必须开启才能收到扫描结果）。"
                    } else {
                        "已接收 $seen 条广播（含非体重秤设备），说明扫描工作正常。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                val info = device!!
                InfoRow("名称", info.name ?: "（未广播名称）")
                InfoRow("型号", info.model ?: "未识别")
                info.productId?.let { InfoRow("产品 ID", "0x%04X".format(it)) }
                InfoRow("地址", info.address)
                InfoRow("信号", "${info.rssi} dBm")
                InfoRow(
                    "最后收到",
                    SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                        .format(Date(info.lastSeenAt)),
                )
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun InfoCard(
    title: String,
    body: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(6.dp))
            Text(
                body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (actionLabel != null && onAction != null) {
                TextButton(onClick = onAction, modifier = Modifier.padding(top = 4.dp)) {
                    Text(actionLabel)
                }
            }
        }
    }
}

@Composable
private fun NoticeCard(
    title: String,
    body: String,
    actionLabel: String?,
    onAction: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Warning,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            if (actionLabel != null) {
                Button(onClick = onAction, modifier = Modifier.padding(top = 10.dp)) {
                    Text(actionLabel)
                }
            }
        }
    }
}
