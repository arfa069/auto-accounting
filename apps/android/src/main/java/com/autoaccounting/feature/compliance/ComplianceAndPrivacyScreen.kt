package com.autoaccounting.feature.compliance

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import com.autoaccounting.ui.components.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.activity.compose.BackHandler
import com.autoaccounting.feature.beta.InternalBetaReadinessScreen
import com.autoaccounting.feature.diagnostics.DiagnosticLogsScreen
import com.autoaccounting.ui.components.SlidePageTransition

enum class ComplianceMaterialPage(val title: String) {
    PrivacyPolicy("隐私政策"),
    PersonalInformation("个人信息收集清单"),
    ThirdPartyServices("第三方服务清单"),
    PermissionExplanations("权限说明")
}

@Composable
fun ComplianceAndPrivacyScreen(
    isDebugBuild: Boolean,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    materials: ComplianceMaterials = AUTO_ACCOUNTING_COMPLIANCE
) {
    var selectedPage by remember { mutableStateOf<ComplianceMaterialPage?>(null) }
    var showDeveloperTools by remember { mutableStateOf(false) }
    var showDiagnosticLogs by remember { mutableStateOf(false) }

    BackHandler(enabled = selectedPage != null || showDeveloperTools || showDiagnosticLogs) {
        selectedPage = null
        showDeveloperTools = false
        showDiagnosticLogs = false
    }

    val page = selectedPage?.let(CompliancePage::Material)
        ?: when {
            showDeveloperTools -> CompliancePage.DeveloperTools
            showDiagnosticLogs -> CompliancePage.DiagnosticLogs
            else -> CompliancePage.Overview
        }

    SlidePageTransition(
        targetState = page,
        modifier = modifier.fillMaxSize()
    ) { targetPage ->
        when (targetPage) {
            is CompliancePage.Material -> ComplianceMaterialDetailScreen(
                page = targetPage.page,
                materials = materials,
                onBack = { selectedPage = null }
            )

            CompliancePage.DeveloperTools -> DeveloperToolsScreen(
                onBack = { showDeveloperTools = false }
            )

            CompliancePage.DiagnosticLogs -> DiagnosticLogsScreen(
                isDebugBuild = isDebugBuild,
                onBack = { showDiagnosticLogs = false }
            )

            CompliancePage.Overview -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                TextButton(onClick = onBack) { Text("返回") }
                Text("合规与隐私", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                Text("以下材料在本地模式和账号模式均可查阅。")
                ComplianceMaterialPage.entries.forEach { materialPage ->
                    EntryCard(
                        title = materialPage.title,
                        tag = "compliance-entry-${materialPage.name}",
                        onClick = { selectedPage = materialPage }
                    )
                }
                EntryCard(
                    title = "诊断日志",
                    summary = "本机加密的自动记账诊断记录、筛选、导出与清空",
                    tag = "diagnostic-logs-entry",
                    onClick = { showDiagnosticLogs = true }
                )
                if (isDebugBuild) {
                    EntryCard(
                        title = "开发者工具",
                        summary = "内测日志、设备矩阵、权限留存和质量指标",
                        tag = "developer-tools-entry",
                        onClick = { showDeveloperTools = true }
                    )
                }
            }
        }
    }
}

private sealed interface CompliancePage {
    data object Overview : CompliancePage

    data class Material(val page: ComplianceMaterialPage) : CompliancePage

    data object DeveloperTools : CompliancePage

    data object DiagnosticLogs : CompliancePage
}

@Composable
private fun ComplianceMaterialDetailScreen(
    page: ComplianceMaterialPage,
    materials: ComplianceMaterials,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        TextButton(onClick = onBack) { Text("返回") }
        Text(page.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        when (page) {
            ComplianceMaterialPage.PrivacyPolicy -> materials.privacyPolicySections.forEach {
                MaterialCard(it.title, it.body)
            }
            ComplianceMaterialPage.PersonalInformation -> materials.personalInformationItems.forEach {
                val retention = it.retentionAndDeletion
                    .takeIf(String::isNotBlank)
                    ?.let { value -> "；保存与删除：$value" }
                    .orEmpty()
                MaterialCard(it.name, "${it.purpose}；${it.requiredState}；${it.processingMethod}$retention")
            }
            ComplianceMaterialPage.ThirdPartyServices -> materials.thirdPartyServices.forEach {
                MaterialCard(
                    it.name,
                    "${it.purpose}；涉及：${it.personalInformationCategory}；方式：${it.processingMethod}"
                )
            }
            ComplianceMaterialPage.PermissionExplanations -> materials.permissionExplanations.forEach {
                MaterialCard(it.title, "${it.purpose}\n${it.boundary}")
            }
        }
    }
}

@Composable
private fun DeveloperToolsScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxSize()) {
        TextButton(onClick = onBack, modifier = Modifier.padding(horizontal = 20.dp)) { Text("返回开发者工具") }
        InternalBetaReadinessScreen(modifier = Modifier.weight(1f))
    }
}

@Composable
private fun EntryCard(
    title: String,
    tag: String,
    onClick: () -> Unit,
    summary: String? = null
) {
    Card(
        modifier = Modifier.fillMaxWidth().testTag(tag).clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            summary?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        }
    }
}

@Composable
private fun MaterialCard(title: String, body: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(body, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
