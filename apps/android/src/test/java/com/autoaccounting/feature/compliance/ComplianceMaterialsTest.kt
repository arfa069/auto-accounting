package com.autoaccounting.feature.compliance

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ComplianceMaterialsTest {
    @Test
    fun compliancePackageContainsRequiredInternalBetaMaterials() {
        assertTrue(AUTO_ACCOUNTING_COMPLIANCE.privacyPolicySections.isNotEmpty())
        assertTrue(AUTO_ACCOUNTING_COMPLIANCE.personalInformationItems.isNotEmpty())
        assertTrue(AUTO_ACCOUNTING_COMPLIANCE.thirdPartyServices.isNotEmpty())
        assertTrue(AUTO_ACCOUNTING_COMPLIANCE.permissionExplanations.isNotEmpty())
        assertTrue(AUTO_ACCOUNTING_COMPLIANCE.storeReviewNotes.isNotEmpty())
    }

    @Test
    fun permissionCopyMatchesProductDecisions() {
        val copies = AUTO_ACCOUNTING_COMPLIANCE.permissionExplanations.associateBy { it.id }

        assertEquals(
            "用于识别微信、支付宝的收付款通知，生成待确认账目。",
            copies.getValue(PermissionExplanationId.NotificationListening).purpose
        )
        assertEquals(
            "用于通知待确认、分类建议、重复合并或识别失败结果。",
            copies.getValue(PermissionExplanationId.ResultNotifications).purpose
        )
        val accessibilityCopy = copies.getValue(PermissionExplanationId.AccessibilityBillSync)
        assertEquals("自动记账无障碍服务", accessibilityCopy.title)
        assertEquals(
            "用于开启自动记账后观察微信、支付宝支付结果和支付记录，也可手动补充历史账目。",
            accessibilityCopy.purpose
        )
        assertEquals(
            "不读取聊天或普通消息，不发送消息，不发起付款、转账或退款。",
            accessibilityCopy.boundary
        )
        assertEquals(
            "开启后会上传必要交易信息用于分类建议，可选择是否提供更多上下文。",
            copies.getValue(PermissionExplanationId.CloudAi).purpose
        )

        val storeNotes = AUTO_ACCOUNTING_COMPLIANCE.storeReviewNotes.associateBy { it.title }
        assertEquals(
            "无障碍服务仅在用户开启自动记账后观察微信、支付宝支付结果和支付记录，或用于用户主动补充历史账目；不读取聊天或普通消息，不发送消息，不发起付款、转账或退款。",
            storeNotes.getValue("无障碍审核说明").body
        )
    }

    @Test
    fun complianceScannerFlagsUnlistedRiskySdkNames() {
        val findings = findUnlistedSdkOrNetworkServices(
            buildTexts = listOf("""implementation("com.umeng.umsdk:analytics:1.0.0")"""),
            manifestText = "<manifest />",
            declaredServices = AUTO_ACCOUNTING_COMPLIANCE.thirdPartyServices
        )

        assertEquals(listOf("umeng"), findings.map { it.token })
    }

    @Test
    fun currentBuildHasNoUnlistedSdkOrNetworkService() {
        val root = projectRoot()
        val findings = findUnlistedSdkOrNetworkServices(
            buildTexts = listOf(
                root.resolve("apps/android/build.gradle.kts").readText(),
                root.resolve("services/backend/build.gradle.kts").readText()
            ),
            manifestText = root.resolve("apps/android/src/main/AndroidManifest.xml").readText(),
            declaredServices = AUTO_ACCOUNTING_COMPLIANCE.thirdPartyServices
        )

        assertTrue(findings.isEmpty())
    }

    private fun projectRoot(): File {
        val userDirectory = requireNotNull(System.getProperty("user.dir"))
        return generateSequence(File(userDirectory)) { current ->
            current.parentFile?.absoluteFile
        }
            .first { it.resolve("settings.gradle.kts").exists() }
    }
}
