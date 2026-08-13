package com.autoaccounting.feature.diagnostics

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.autoaccounting.ui.components.OutlinedButton
import com.autoaccounting.ui.components.TextButton
import java.text.DateFormat
import java.util.Date

internal data class DiagnosticLogsContentState(
    val enabled: Boolean,
    val stats: DiagnosticLogStats,
    val showSensitive: Boolean,
    val statusMessage: String?
)

internal class DiagnosticLogsContentActions(
    val onBack: () -> Unit,
    val onEnabledChange: (Boolean) -> Unit,
    val onRefresh: () -> Unit,
    val onExportClick: () -> Unit,
    val onClearClick: () -> Unit,
    val onToggleSensitive: () -> Unit
)

@Composable
internal fun DiagnosticLogsContent(
    state: DiagnosticLogsContentState,
    events: List<DiagnosticEvent>,
    actions: DiagnosticLogsContentActions,
    modifier: Modifier = Modifier
) {
    var query by rememberSaveable { mutableStateOf("") }
    var levelFilter by rememberSaveable { mutableStateOf<DiagnosticLevel?>(null) }
    var componentFilter by rememberSaveable { mutableStateOf<DiagnosticComponent?>(null) }
    val filtered = events.filter { event ->
        (levelFilter == null || event.metadata.level == levelFilter) &&
            (componentFilter == null || event.metadata.component == componentFilter) &&
            queryMatches(event, query)
    }

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(20.dp).testTag("diagnostic-event-list"),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { TextButton(onClick = actions.onBack) { Text("返回合规与隐私") } }
        item {
            Text("诊断日志", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        }
        item { Text("日志仅保存在本机加密目录；关闭后保留历史，清空会同时删除密文和设备密钥。") }
        item {
            DiagnosticLogsStatusRow(
                enabled = state.enabled,
                stats = state.stats,
                onEnabledChange = actions.onEnabledChange
            )
        }
        item {
            DiagnosticLogsActionRow(
                showSensitive = state.showSensitive,
                onRefresh = actions.onRefresh,
                onExportClick = actions.onExportClick,
                onClearClick = actions.onClearClick,
                onToggleSensitive = actions.onToggleSensitive
            )
        }
        state.statusMessage?.let { message ->
            item { Text(message, style = MaterialTheme.typography.bodySmall) }
        }
        item {
            DiagnosticLogsSearchField(
                query = query,
                onQueryChange = { query = it }
            )
        }
        item {
            DiagnosticLogsLevelChips(
                levelFilter = levelFilter,
                onLevelFilterChange = { levelFilter = it }
            )
        }
        item {
            DiagnosticLogsComponentChips(
                componentFilter = componentFilter,
                onComponentFilterChange = { componentFilter = it }
            )
        }
        items(filtered, key = { "${it.metadata.timestampEpochMillis}-${it.metadata.traceId}-${it.metadata.event}" }) {
            DiagnosticEventCard(it, state.showSensitive)
        }
    }
}

@Composable
private fun DiagnosticLogsStatusRow(
    enabled: Boolean,
    stats: DiagnosticLogStats,
    onEnabledChange: (Boolean) -> Unit
) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Column {
            Text(if (enabled) "记录已开启" else "记录已关闭", fontWeight = FontWeight.SemiBold)
            Text("${stats.eventCount} 条 · ${stats.segmentCount} 段 · ${formatBytes(stats.encryptedBytes)}")
        }
        Switch(
            checked = enabled,
            onCheckedChange = onEnabledChange,
            modifier = Modifier.testTag("diagnostic-enabled-switch")
        )
    }
}

@Composable
private fun DiagnosticLogsActionRow(
    showSensitive: Boolean,
    onRefresh: () -> Unit,
    onExportClick: () -> Unit,
    onClearClick: () -> Unit,
    onToggleSensitive: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OutlinedButton(onClick = onRefresh) { Text("刷新") }
        OutlinedButton(onClick = onExportClick) { Text("加密导出") }
        OutlinedButton(onClick = onClearClick) { Text("清空") }
        OutlinedButton(onClick = onToggleSensitive) { Text(if (showSensitive) "遮罩内容" else "显示敏感内容") }
    }
}

@Composable
private fun DiagnosticLogsSearchField(query: String, onQueryChange: (String) -> Unit) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        label = { Text("筛选事件、原因、traceId / sessionId") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun DiagnosticLogsLevelChips(
    levelFilter: DiagnosticLevel?,
    onLevelFilterChange: (DiagnosticLevel?) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        FilterChip(selected = levelFilter == null, onClick = { onLevelFilterChange(null) }, label = { Text("全部级别") })
        DiagnosticLevel.entries.forEach { level ->
            FilterChip(
                selected = levelFilter == level,
                onClick = { onLevelFilterChange(level) },
                label = { Text(level.name) }
            )
        }
    }
}

@Composable
private fun DiagnosticLogsComponentChips(
    componentFilter: DiagnosticComponent?,
    onComponentFilterChange: (DiagnosticComponent?) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        FilterChip(selected = componentFilter == null, onClick = { onComponentFilterChange(null) }, label = { Text("全部组件") })
        DiagnosticComponent.entries.forEach { component ->
            FilterChip(
                selected = componentFilter == component,
                onClick = { onComponentFilterChange(component) },
                label = { Text(component.name) }
            )
        }
    }
}

@Composable
private fun DiagnosticEventCard(event: DiagnosticEvent, showSensitive: Boolean) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("${event.metadata.level.name} · ${event.metadata.component.name} · ${event.metadata.event}", fontWeight = FontWeight.SemiBold)
            Text(DateFormat.getDateTimeInstance().format(Date(event.metadata.timestampEpochMillis)))
            Text("reason=${event.metadata.reason.orEmpty()} outcome=${event.metadata.outcome.orEmpty()}")
            if (event.metadata.count != null || event.metadata.durationMillis != null) {
                Text(
                    "count=${event.metadata.count ?: 0} durationMs=${event.metadata.durationMillis ?: 0}"
                )
            }
            Text("traceId=${event.metadata.traceId}")
            event.metadata.sessionId?.let { Text("sessionId=$it") }
            if (event.metadata.suppressedCount > 0) Text("合并重复 ${event.metadata.suppressedCount} 次")
            if (event.sensitivePayload.fields.isNotEmpty()) {
                if (showSensitive) {
                    event.sensitivePayload.fields.forEach { (field, value) ->
                        Text("${field.name}: $value")
                    }
                    if (event.truncatedFields.isNotEmpty()) {
                        Text("已截断：${event.truncatedFields.joinToString { it.name }}")
                    }
                } else {
                    Text("敏感内容：••••••")
                }
            }
        }
    }
}

private fun queryMatches(event: DiagnosticEvent, query: String): Boolean {
    if (query.isBlank()) return true
    val needle = query.trim()
    return listOf(
        event.metadata.event,
        event.metadata.reason,
        event.metadata.traceId,
        event.metadata.sessionId
    ).any { it?.contains(needle, ignoreCase = true) == true }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    bytes >= 1024 -> "%.1f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}
