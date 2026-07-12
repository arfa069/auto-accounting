package com.autoaccounting.feature.compliance

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
fun ComplianceMaterialsScreen(
    modifier: Modifier = Modifier,
    materials: ComplianceMaterials = AUTO_ACCOUNTING_COMPLIANCE,
    onBack: (() -> Unit)? = null
) {
    ComplianceAndPrivacyScreen(
        isDebugBuild = false,
        onBack = onBack ?: {},
        modifier = modifier,
        materials = materials
    )
}
