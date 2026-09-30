package dev.sopho.scale.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.sopho.scale.ble.normalizeMac
import dev.sopho.scale.ble.parseBindKey

@Composable
fun SettingsScreen(viewModel: ScaleViewModel) {
    val storedKey by viewModel.bindKeyText.collectAsStateWithLifecycle()
    val storedMac by viewModel.macText.collectAsStateWithLifecycle()
    val autoSave by viewModel.autoSave.collectAsStateWithLifecycle()

    var draft by remember(storedKey) { mutableStateOf(storedKey) }
    var macDraft by remember(storedMac) { mutableStateOf(storedMac) }
    val draftValid = draft.isBlank() || parseBindKey(draft) != null
    val macValid = macDraft.isBlank() || normalizeMac(macDraft) != null

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("bindkey", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                Text(
                    "小米体重秤把体重数据加密后广播，只有填入该设备专属的 16 字节 bindkey 才能解密。"
                        + "它只保存在本机，不会上传到任何服务器。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("bindkey（32 位十六进制）") },
                    placeholder = { Text("输入设备专属的 32 位十六进制密钥") },
                    isError = !draftValid,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Ascii,
                        imeAction = ImeAction.Done,
                    ),
                    supportingText = {
                        Text(
                            if (!draftValid) {
                                "长度应为 32 个十六进制字符（16 字节）"
                            } else {
                                "留空表示暂不配置，此时无法显示 S200 的读数"
                            },
                        )
                    },
                )
                Spacer(Modifier.height(8.dp))
                Row {
                    Button(
                        onClick = { viewModel.saveBindKey(draft) },
                        enabled = draftValid && draft != storedKey,
                    ) { Text("保存") }
                    Spacer(Modifier.padding(horizontal = 4.dp))
                    if (storedKey.isNotBlank()) {
                        Button(
                            onClick = {
                                draft = ""
                                viewModel.saveBindKey("")
                            },
                        ) { Text("清除") }
                    }
                }

                Spacer(Modifier.height(20.dp))

                Text("设备 MAC（可选）", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(6.dp))
                Text(
                    "如果解密一直失败，可在此填入体重秤的 MAC（形如 AA:BB:CC:DD:EE:FF）。"
                        + "解密需要用到它计算 nonce，而 Android 有时上报的地址并非设备本身的 MAC。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = macDraft,
                    onValueChange = { macDraft = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("设备 MAC") },
                    placeholder = { Text("AA:BB:CC:DD:EE:FF") },
                    isError = !macValid,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Ascii,
                        imeAction = ImeAction.Done,
                    ),
                    supportingText = {
                        Text(
                            if (!macValid) {
                                "应为 6 组十六进制，例如 AA:BB:CC:DD:EE:FF"
                            } else {
                                "留空时仅使用扫描到的地址"
                            },
                        )
                    },
                )
                Spacer(Modifier.height(8.dp))
                Row {
                    Button(
                        onClick = { viewModel.saveDeviceMac(macDraft) },
                        enabled = macValid && macDraft != storedMac,
                    ) { Text("保存") }
                    Spacer(Modifier.padding(horizontal = 4.dp))
                    if (storedMac.isNotBlank()) {
                        Button(
                            onClick = {
                                macDraft = ""
                                viewModel.saveDeviceMac("")
                            },
                        ) { Text("清除") }
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        Card(Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("自动记录", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "称重结束后自动写入历史记录。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = autoSave, onCheckedChange = viewModel::setAutoSave)
            }
        }

        Spacer(Modifier.height(12.dp))

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("如何获取 bindkey", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text(
                    buildString {
                        appendLine("bindkey 由小米云端按设备下发，应用本身无法凭空推导，需要从你的米家账号导出一次：")
                        appendLine()
                        appendLine("1. 在电脑上运行任意一个“小米云 token 提取”脚本（例如 GitHub 上的")
                        appendLine("   Xiaomi-cloud-tokens-extractor），用米家账号登录。")
                        appendLine("2. 脚本会列出该账号下的所有设备，找到名为“体重秤 / Scale”的设备。")
                        appendLine("3. 复制其 BLE 密钥（bindkey / beaconkey），是一串 32 位十六进制字符。")
                        appendLine("4. 粘贴到上面的输入框保存。")
                        appendLine()
                        appendLine("建议使用只绑定了体重秤的小号登录，脚本需要账号密码。")
                        appendLine("若不方便导出，也可在“日志”页确认设备已能被发现。")
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("关于", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                Text(
                    buildString {
                        appendLine("支持的协议：")
                        appendLine("· MiBeacon v5（0xFE95）— 小米 S200 / S400 / S800，AES-CCM 加密")
                        appendLine("· GATT 0x181D — 初代小米体重秤，明文")
                        appendLine("· GATT 0x181B — 小米体脂秤，明文（含阻抗）")
                        appendLine()
                        appendLine("S200（MJTZC02YM）没有体脂电极，因此不会产生阻抗/体脂数据。")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
