package io.github.tigaotg.openkinguard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Small, opt-in explanations. Keep primary controls visible and detailed text tucked away. */
@Composable
internal fun FeatureHelp(explanation: String, label: String = "功能说明") {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        TextButton(onClick = { expanded = !expanded },
            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp)) {
            Text(if (expanded) "收起说明 ↑" else "$label ⓘ",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (expanded) {
            Surface(color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth()) {
                Text(explanation, modifier = Modifier.padding(14.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** Feedback belongs immediately below its action; it is never a global page banner. */
@Composable
internal fun InlineActionFeedback(value: String?, modifier: Modifier = Modifier) {
    if (value.isNullOrBlank()) return
    val failure = listOf("失败", "错误", "超时", "断开", "未完成", "无法")
        .any { value.contains(it) }
    val waiting = !failure && listOf("等待", "尚未", "正在", "未确认", "稍后", "待确认")
        .any { value.contains(it) }
    val scheme = MaterialTheme.colorScheme
    val background = when {
        failure -> scheme.errorContainer
        waiting -> scheme.secondaryContainer
        else -> scheme.primaryContainer
    }
    val foreground = when {
        failure -> scheme.onErrorContainer
        waiting -> scheme.onSecondaryContainer
        else -> scheme.onPrimaryContainer
    }
    Surface(modifier = modifier.fillMaxWidth(), color = background,
        shape = MaterialTheme.shapes.small) {
        Text(value, modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            style = MaterialTheme.typography.bodyMedium, color = foreground)
    }
}
