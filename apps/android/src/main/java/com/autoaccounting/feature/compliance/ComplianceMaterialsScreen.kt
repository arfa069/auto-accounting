package com.autoaccounting.feature.compliance

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun ComplianceMaterialsScreen(
    modifier: Modifier = Modifier,
    materials: ComplianceMaterials = AUTO_ACCOUNTING_COMPLIANCE,
    onBack: (() -> Unit)? = null
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        onBack?.let {
            TextButton(onClick = it) {
                Text("返回")
            }
        }
        Text(
            text = "隐私与合规材料",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold
        )
        ComplianceSectionCard(title = "隐私政策") {
            materials.privacyPolicySections.forEach { section ->
                Text(section.title, fontWeight = FontWeight.SemiBold)
                Text(section.body, style = MaterialTheme.typography.bodyMedium)
            }
        }
        ComplianceSectionCard(title = "个人信息收集清单") {
            materials.personalInformationItems.forEach { item ->
                Text(item.name, fontWeight = FontWeight.SemiBold)
                Text("${item.purpose}；${item.requiredState}；${item.processingMethod}", style = MaterialTheme.typography.bodyMedium)
            }
        }
        ComplianceSectionCard(title = "第三方服务清单") {
            materials.thirdPartyServices.forEach { service ->
                Text(service.name, fontWeight = FontWeight.SemiBold)
                Text("${service.purpose}；涉及：${service.personalInformationCategory}；方式：${service.processingMethod}", style = MaterialTheme.typography.bodyMedium)
            }
        }
        ComplianceSectionCard(title = "权限说明") {
            materials.permissionExplanations.forEach { permission ->
                Text(permission.title, fontWeight = FontWeight.SemiBold)
                Text(permission.purpose, style = MaterialTheme.typography.bodyMedium)
                Text(permission.boundary, style = MaterialTheme.typography.bodySmall)
            }
        }
        ComplianceSectionCard(title = "商店审核说明") {
            materials.storeReviewNotes.forEach { note ->
                Text(note.title, fontWeight = FontWeight.SemiBold)
                Text(note.body, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun ComplianceSectionCard(
    title: String,
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}
