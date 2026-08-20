package com.bks.feature.compliance

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
fun ComplianceMaterialsScreen(
    modifier: Modifier = Modifier,
    materials: ComplianceMaterials = BKS_COMPLIANCE,
    onBack: (() -> Unit)? = null
) {
    ComplianceAndPrivacyScreen(
        isDebugBuild = false,
        onBack = onBack ?: {},
        modifier = modifier,
        materials = materials
    )
}
