package io.github.tigaotg.openkinguard

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Alignment
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.io.OutputStreamWriter

/** Visual levels stay simple: category panel -> outlined destination -> compact sub-item. */
enum class SettingsDestination(val label: String, val summary: String) {
    HOME("设置", ""),
    QUICK("功能指南", "了解常用功能和入口"),
    PAIRING("连接家人", "绑定家人的手机、修改昵称与授权"),
    REMOTE_ASSIST("远程协助", "屏幕共享、远程点击权限、会话状态与操作指引"),
    SELF_CHECK("应用权限", "通知、定位、蓝牙权限与本机检测"),
    DAILY("失联与电量提醒", "调整提醒时间和电量阈值"),
    NOTICES("通知设置", "通知权限、弹窗和本机提醒测试"),
    PRIVATE("家庭私人中继", "扫码加入路由器、查看跨网连接"),
    PUBLIC("公共节点与 P2P", "公共信令、UDP 直连与测试"),
    BACKGROUND("后台保持连接", "监听状态与系统省电设置"),
    WEARABLE_ALERTS("心率预警设置", "前台心率阈值试验与通知"),
    WEARABLE_HISTORY("健康记录", "本机加密保存、查看与导出"),
    WEARABLE_TECH("穿戴兼容与诊断", "标准蓝牙接口、设备协议及连接日志"),
    MAP("地图打开方式", "Google 地图与国内地图分别跳转"),
    FIND("找回手机", "设备厂商的官方查找入口"),
    MEDICAL("病历文件", "仅在本机管理授权文件夹"),
    HISTORY("求助与定位历史", "查看历史、调整保存时间"),
    CONTACTS("紧急联系人", "本机电话号码及手动呼叫"),
    WIDGETS("桌面小组件", "桌面快捷操作和状态"),
    THEMES("外观", "轻量主题和首页显示方式"),
    ADVANCED("连接诊断", "设备指纹、握手记录和详细结果"),
    ABOUT("关于软件", "开源宗旨、作者、功能说明和 GitHub"),
}

/** A single, compact group row is shown until the user opens it. No animation or extra UI runtime. */
internal data class SettingsGroup(val title: String, val description: String,
    val destinations: List<SettingsDestination>)

internal object SettingsMenuLayout {
    val groups = listOf(
        SettingsGroup("家人和设备", "连接家人、找回手机", listOf(
            SettingsDestination.PAIRING, SettingsDestination.FIND)),
        SettingsGroup("权限和提醒", "应用权限、通知、失联提醒", listOf(
            SettingsDestination.SELF_CHECK, SettingsDestination.NOTICES,
            SettingsDestination.DAILY)),
        SettingsGroup("网络", "私人中继、公共节点、P2P 与后台连接", listOf(
            SettingsDestination.PRIVATE, SettingsDestination.PUBLIC,
            SettingsDestination.BACKGROUND)),
        SettingsGroup("穿戴和健康", "预警、加密记录、蓝牙兼容信息", listOf(
            SettingsDestination.WEARABLE_ALERTS, SettingsDestination.WEARABLE_HISTORY,
            SettingsDestination.WEARABLE_TECH)),
        SettingsGroup("资料与隐私", "病历、位置、历史和联系人", listOf(
            SettingsDestination.MEDICAL, SettingsDestination.HISTORY,
            SettingsDestination.CONTACTS, SettingsDestination.MAP)),
        SettingsGroup("界面与帮助", "桌面组件、外观、使用说明、诊断", listOf(
            SettingsDestination.WIDGETS, SettingsDestination.THEMES,
            SettingsDestination.QUICK, SettingsDestination.ADVANCED)),
    )

    fun search(query: String): List<SettingsDestination> {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return emptyList()
        return SettingsDestination.entries.drop(1).filter {
            it.label.contains(trimmed, ignoreCase = true) ||
                it.summary.contains(trimmed, ignoreCase = true)
        }
    }
}

@Composable
internal fun SettingsGroupEntry(group: SettingsGroup, expanded: Boolean, onClick: () -> Unit) {
    OutlinedCard(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = MaterialTheme.shapes.medium) {
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(group.title, style = MaterialTheme.typography.titleMedium)
                Text(group.description, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(8.dp))
            Text(if (expanded) "⌃" else "⌄", style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
fun SettingsMenuEntry(destination: SettingsDestination, onClick: () -> Unit) {
    // Subpages are compact list rows, visually subordinate to category cards.
    Surface(modifier = Modifier.fillMaxWidth().padding(start = 12.dp)
        .clickable(onClick = onClick),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(destination.label, style = MaterialTheme.typography.bodyLarge)
                Text(destination.summary, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(10.dp))
            Text("›", style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary)
        }
    }
}

/** A navigation-only explanation row for the Quick Start screen. */
@Composable
fun SettingsGuideLink(title: String, description: String, onClick: () -> Unit) {
    OutlinedCard(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = MaterialTheme.shapes.small) {
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(description, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(8.dp))
            Text("›", color = MaterialTheme.colorScheme.primary)
        }
    }
}

/** Safe, user-driven JSON import. Third-party themes are only color data, not plugins. */
@Composable
fun ThemeSettingsSection(repository: GuardThemeRepository) {
    val context = LocalContext.current
    var status by remember { mutableStateOf("") }
    var exporting by remember { mutableStateOf(repository.selected) }
    var deleteCandidate by remember { mutableStateOf<String?>(null) }
    var showThemeFiles by remember { mutableStateOf(false) }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            status = runCatching {
                val input = context.contentResolver.openInputStream(uri)
                    ?: error("未能打开主题文件")
                val theme = repository.install(GuardThemeFormat.readLimited(input))
                repository.select(theme.id)
                "已导入并启用「${theme.name}」，作者：${theme.author}"
            }.getOrElse { "导入失败：${it.message ?: "文件格式不符合规范"}" }
        }
    }
    val exporter = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) {
            status = runCatching {
                context.contentResolver.openOutputStream(uri)?.use { output ->
                    OutputStreamWriter(output, Charsets.UTF_8).use {
                        it.write(GuardThemeFormat.encode(exporting))
                    }
                } ?: error("无法写入选定位置")
                "已导出「${exporting.name}」主题 JSON，可分享给其他开发者或用户"
            }.getOrElse { "导出失败：${it.message ?: "没有文件写入权限"}" }
        }
    }

    Text("点击主题即可切换；选择会自动保存。", style = MaterialTheme.typography.bodySmall)
    if (status.isNotBlank()) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Text(status, modifier = Modifier.padding(12.dp))
        }
    }
    Text("选择主题", style = MaterialTheme.typography.titleLarge)
    repository.themes.forEach { theme ->
        val active = repository.selectedId == theme.id
        OutlinedCard(modifier = Modifier.fillMaxWidth().clickable {
            repository.select(theme.id)
            status = "已启用「${theme.name}」"
        }) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(theme.name, style = MaterialTheme.typography.titleMedium)
                        Text("作者：${theme.author} · ${if (theme.mode == "dark") "深色" else "浅色"}",
                            style = MaterialTheme.typography.bodySmall)
                    }
                    RadioButton(selected = active, onClick = { repository.select(theme.id) })
                }
                if (active) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(theme.primary, theme.secondary, theme.background, theme.surface)
                        .forEach { hex ->
                            Card(shape = RoundedCornerShape(8.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = androidx.compose.ui.graphics.Color(
                                        android.graphics.Color.parseColor(hex)))) {
                                Spacer(Modifier.width(36.dp).padding(vertical = 12.dp))
                            }
                        }
                }
                FeatureHelp("作者：${theme.author}；授权许可：${theme.license}。" +
                    "主题只包含颜色与显示设置，不执行额外代码。", label = "主题信息")
                if (theme.id !in GuardThemeRepository.included.map { it.id }) {
                    if (deleteCandidate == theme.id) {
                        Text("仅删除本机导入的主题，不影响其他设备。")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = {
                                repository.remove(theme.id)
                                deleteCandidate = null
                                status = "已删除「${theme.name}」"
                            }) { Text("确认删除") }
                            TextButton(onClick = { deleteCandidate = null }) { Text("取消") }
                        }
                    } else {
                        TextButton(onClick = { deleteCandidate = theme.id }) { Text("移除自定义主题") }
                    }
                }
            }
        }
    }
    TextButton(onClick = { showThemeFiles = !showThemeFiles }) {
        Text(if (showThemeFiles) "收起主题文件工具" else "导入或导出主题 ▾")
    }
    if (showThemeFiles) {
        Text("其他人可以制作 JSON 主题文件；仅导入颜色与作者信息。",
            style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                importer.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
            }) { Text("导入 JSON") }
            OutlinedButton(onClick = {
                exporting = repository.selected
                exporter.launch("OpenKinGuard_${exporting.id}.json")
            }) { Text("导出 JSON") }
        }
        FeatureHelp("1. 导出内置主题作为模板。\n" +
            "2. 编辑名称、作者、许可、模式和四种 #RRGGBB 颜色。\n" +
            "3. 以 UTF-8 保存 JSON，发送给朋友导入。\n" +
            "4. 同 ID 再导入会更新旧主题。主题包不得包含代码或外链。",
            label = "如何制作主题")
    }
}
