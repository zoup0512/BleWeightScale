package dev.sopho.scale.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.sopho.scale.data.Person
import dev.sopho.scale.ble.normalizeMac

@Composable
fun PeopleScreen(viewModel: ScaleViewModel, onBack: () -> Unit) {
    val people by viewModel.people.collectAsStateWithLifecycle()
    val selectedId by viewModel.selectedPersonId.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<Person?>(null) }
    var adding by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<Person?>(null) }

    if (adding || editing != null) {
        PersonEditor(
            person = editing,
            defaultMac = viewModel.macText.collectAsStateWithLifecycle().value,
            onDismiss = { adding = false; editing = null },
            onSave = { id, name, height, slot, mac, eligible ->
                viewModel.savePerson(id, name, height, slot, mac, eligible)
                adding = false
                editing = null
            },
        )
    }
    deleting?.let { person ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("删除 ${person.name}？") },
            text = { Text("其称重记录不会丢失，将转入“未认领数据”。") },
            confirmButton = { TextButton(onClick = { viewModel.deletePerson(person.id); deleting = null }) { Text("删除") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } },
        )
    }

    Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("家庭成员", style = MaterialTheme.typography.headlineSmall)
            TextButton(onClick = onBack) { Text("返回") }
        }
        Text("每个人独立保存身高和记录。只有绑定了秤侧用户编号的成员才会自动认领新称重。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
        people.forEach { person ->
            Card(Modifier.fillMaxWidth().padding(bottom = 10.dp).clickable { viewModel.selectPerson(person.id); onBack() }) {
                Column(Modifier.padding(16.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(person.name + if (selectedId == person.id) "  ✓" else "", style = MaterialTheme.typography.titleLarge)
                        TextButton(onClick = { editing = person }) { Text("编辑") }
                    }
                    Text(
                        listOfNotNull(
                            person.heightCm?.let { "身高 %.0f cm".format(it) },
                            person.profileId?.let { "秤侧用户 $it" },
                            person.deviceMac,
                        ).ifEmpty { listOf("未绑定秤侧用户编号") }.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (!person.bmiEligible) Text("不适用成人 BMI 参考", color = MaterialTheme.colorScheme.secondary)
                    TextButton(onClick = { deleting = person }) { Text("删除成员", color = MaterialTheme.colorScheme.error) }
                }
            }
        }
        if (people.isEmpty()) Text("还没有成员。添加姓名与身高后可查看 BMI 报告。")
        Button(onClick = { adding = true }, modifier = Modifier.fillMaxWidth()) { Text("添加成员") }
        Spacer(Modifier.height(16.dp))
        Text("提示：秤侧用户编号可从“日志”中的称重报文或“记录”的用户槽位查看；不确定时留空，称重后手动认领。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun PersonEditor(
    person: Person?,
    defaultMac: String,
    onDismiss: () -> Unit,
    onSave: (String?, String, Double?, Int?, String?, Boolean) -> Unit,
) {
    var name by remember(person) { mutableStateOf(person?.name.orEmpty()) }
    var height by remember(person) { mutableStateOf(person?.heightCm?.toString().orEmpty()) }
    var slot by remember(person) { mutableStateOf(person?.profileId?.toString().orEmpty()) }
    var mac by remember(person) { mutableStateOf(person?.deviceMac ?: defaultMac) }
    var eligible by remember(person) { mutableStateOf(person?.bmiEligible ?: true) }
    val valid = name.isNotBlank() &&
        (height.isBlank() || height.toDoubleOrNull()?.let { it in 100.0..230.0 } == true) &&
        (slot.isBlank() || slot.toIntOrNull()?.let { it in 0..255 } == true) &&
        (mac.isBlank() || normalizeMac(mac) != null) &&
        (slot.isBlank() || normalizeMac(mac) != null)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (person == null) "添加成员" else "编辑成员") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(name, { name = it }, label = { Text("姓名") }, singleLine = true)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(height, { height = it }, label = { Text("身高 cm（成人 BMI 必填）") }, singleLine = true)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(slot, { slot = it }, label = { Text("秤侧用户编号（可选）") }, singleLine = true)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(mac, { mac = it }, label = { Text("秤 MAC（绑定编号时必填）") }, singleLine = true)
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { eligible = !eligible }) {
                    Checkbox(checked = eligible, onCheckedChange = { eligible = it })
                    Text("适用成人 BMI 参考")
                }
                Text("儿童、孕期等情况请关闭 BMI 参考。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = {
                onSave(person?.id, name, height.toDoubleOrNull(), slot.toIntOrNull(), mac, eligible)
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
fun PersonPicker(
    people: List<Person>,
    selectedId: String?,
    onSelect: (String?) -> Unit,
    onManage: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择成员") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                people.forEach { person ->
                    TextButton(onClick = { onSelect(person.id); onDismiss() }, modifier = Modifier.fillMaxWidth()) {
                        Text(person.name + if (person.id == selectedId) " ✓" else "")
                    }
                    HorizontalDivider()
                }
                TextButton(onClick = { onSelect(null); onDismiss() }, modifier = Modifier.fillMaxWidth()) { Text("未认领数据") }
                TextButton(onClick = { onManage(); onDismiss() }, modifier = Modifier.fillMaxWidth()) { Text("管理家庭成员 +") }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}
