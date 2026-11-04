package io.github.tigaotg.openkinguard

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

@Composable
fun WidgetSettingsSection() {
    val context = LocalContext.current
    val widgetManager = remember { AppWidgetManager.getInstance(context) }
    var result by remember { mutableStateOf("") }

    Text("可以把常用操作放到手机桌面。小组件显示最近验证的在线设备，" +
        "但不能替代真正的紧急呼叫。")
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text("家人在线状态 + 两个快捷按钮", style = MaterialTheme.typography.titleMedium)
            Text("展示最多三名最近已验签在线的家人及时间。" +
                "如果未持续监听、断网或状态过期，会提示“待验证”。")
            Button(onClick = {
                result = requestWidget(context, widgetManager, GuardStatusWidgetProvider::class.java)
            }) { Text("添加状态小组件到桌面") }
        }
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text("SOS / 我已安全快捷小组件", style = MaterialTheme.typography.titleMedium)
            Text("适合空间较小的桌面。两个按钮都先打开 OpenKinGuard 二次确认。")
            Button(onClick = {
                result = requestWidget(context, widgetManager, GuardQuickWidgetProvider::class.java)
            }) { Text("添加快捷小组件到桌面") }
        }
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text("单按钮：测试 SOS", style = MaterialTheme.typography.titleMedium)
            Text("点按后打开应用确认，不会自动发送。")
            Button(onClick = {
                result = requestWidget(context, widgetManager, GuardSosWidgetProvider::class.java)
            }) { Text("添加独立 SOS 小组件") }
        }
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text("单按钮：报平安", style = MaterialTheme.typography.titleMedium)
            Text("点按后打开应用确认对象，再由你主动发送。")
            Button(onClick = {
                result = requestWidget(context, widgetManager,
                    GuardCheckinWidgetProvider::class.java)
            }) { Text("添加独立报平安小组件") }
        }
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text("隐私家人在线数量", style = MaterialTheme.typography.titleMedium)
            Text("不显示姓名，只显示最近已验签在线数量及更新时间，不是实时状态。")
            Button(onClick = {
                result = requestWidget(context, widgetManager,
                    GuardPresenceWidgetProvider::class.java)
            }) { Text("添加隐私在线小组件") }
        }
    }
    if (result.isNotBlank()) Text(result, color = MaterialTheme.colorScheme.primary)
    Text("如果手机桌面不支持一键添加：长按桌面空白处 → 小组件/窗口小工具 → " +
        "OpenKinGuard → 从五种小组件中选择。不同系统名称可能不同。")
    Text("隐私提示：桌面会直接显示家人昵称，旁人可能看见。" +
        "只在你主动添加小组件后展示；不显示精确位置、心率、电话号码、蓝牙地址。" +
        "Android 限制了桌面后台更新频率，显示状态带更新时间，不能理解成实时生命监测。",
        style = MaterialTheme.typography.bodySmall)
}

private fun requestWidget(context: android.content.Context, manager: AppWidgetManager,
    receiver: Class<*>): String = runCatching {
    if (!manager.isRequestPinAppWidgetSupported) {
        "当前桌面不支持应用请求添加，请长按桌面空白处手动添加小组件。"
    } else if (manager.requestPinAppWidget(ComponentName(context, receiver), null, null)) {
        "已请求桌面添加小组件，请在系统弹窗中确认。"
    } else "桌面未接受请求，请尝试长按桌面空白处手动添加。"
}.getOrElse { "添加失败：${it.message ?: "请通过桌面的小组件列表手动添加"}" }
