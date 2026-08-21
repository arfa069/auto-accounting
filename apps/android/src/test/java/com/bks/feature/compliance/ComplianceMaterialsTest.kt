package com.bks.feature.compliance

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ComplianceMaterialsTest {
    @Test
    fun compliancePackageContainsRequiredInternalBetaMaterials() {
        assertTrue(BKS_COMPLIANCE.privacyPolicySections.isNotEmpty())
        assertTrue(BKS_COMPLIANCE.personalInformationItems.isNotEmpty())
        assertTrue(BKS_COMPLIANCE.thirdPartyServices.isNotEmpty())
        assertTrue(BKS_COMPLIANCE.permissionExplanations.isNotEmpty())
        assertTrue(BKS_COMPLIANCE.storeReviewNotes.isNotEmpty())
    }

    @Test
    fun permissionCopyMatchesProductDecisions() {
        val copies = BKS_COMPLIANCE.permissionExplanations.associateBy { it.id }

        val accessibilityCopy = copies.getValue(PermissionExplanationId.AccessibilityBillSync)
        assertEquals("自动记账无障碍服务", accessibilityCopy.title)
        assertEquals(
            "用于用户开启自动记账后，在设备本地识别其他应用当前活动窗口中的已完成交易。",
            accessibilityCopy.purpose
        )
        assertEquals(
            "只读取可见、非密码、非输入框文字；不截图、不点击、不滚动、不启动其他应用，不保存或上传原始页面文字。识别结果只进入待确认。",
            accessibilityCopy.boundary
        )
        assertEquals(
            "开启后会上传必要交易信息用于分类建议，可选择是否提供更多上下文。",
            copies.getValue(PermissionExplanationId.CloudAi).purpose
        )

        val storeNotes = BKS_COMPLIANCE.storeReviewNotes.associateBy { it.title }
        assertEquals(
            "无障碍服务仅在用户主动开启自动记账后运行，读取其他应用当前活动窗口中可见、非密码、非输入框的文字，并只为明确完成的交易生成待确认项；不截图、不操作其他应用、不保存或上传原始页面文字。",
            storeNotes.getValue("无障碍审核说明").body
        )
        assertTrue(storeNotes.containsKey("诊断日志审核说明"))
        assertTrue(
            BKS_COMPLIANCE.thirdPartyServices.any { service ->
                service.name.contains("Assists") &&
                    service.processingMethod.contains("不保存、不上传") &&
                    "assists-base" in service.declarationTokens
            }
        )
        assertTrue(
            BKS_COMPLIANCE.thirdPartyServices.any { service ->
                service.name.contains("微信开放平台") &&
                    service.processingMethod.contains("不向微信发送账本") &&
                    "wechat-sdk" in service.declarationTokens
            }
        )
        assertTrue(
            BKS_COMPLIANCE.thirdPartyServices.any { service ->
                service.name.contains("Coil / OkHttp") &&
                    service.processingMethod.contains("最多 10 MB") &&
                    "okhttp" in service.declarationTokens
            }
        )
        assertTrue(
            BKS_COMPLIANCE.personalInformationItems.any { item ->
                item.name == "微信账号标识与资料" &&
                    item.processingMethod.contains("OpenID") &&
                    item.retentionAndDeletion.contains("解绑微信")
            }
        )
        assertTrue(
            BKS_COMPLIANCE.personalInformationItems.any { item ->
                item.name == "随机安装 UUID" && item.retentionAndDeletion.contains("本机数据删除")
            }
        )
    }

    @Test
    fun complianceScannerFlagsUnlistedRiskySdkNames() {
        val findings = findUnlistedSdkOrNetworkServices(
            buildTexts = listOf(
                """implementation("com.umeng.umsdk:analytics:1.0.0")""",
                """exclude(group = "com.google.mlkit", module = "text-recognition-chinese")"""
            ),
            manifestText = "<manifest />",
            declaredServices = BKS_COMPLIANCE.thirdPartyServices
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
            declaredServices = BKS_COMPLIANCE.thirdPartyServices
        )

        assertTrue(findings.isEmpty())
        val versionCatalog = root.resolve("gradle/libs.versions.toml").readText()
        if (versionCatalog.contains("com.tencent.mm.opensdk")) {
            assertTrue(
                BKS_COMPLIANCE.thirdPartyServices.any { service ->
                    "wechat-sdk" in service.declarationTokens
                }
            )
        }
    }

    private fun projectRoot(): File {
        val userDirectory = requireNotNull(System.getProperty("user.dir"))
        return generateSequence(File(userDirectory)) { current ->
            current.parentFile?.absoluteFile
        }
            .first { it.resolve("settings.gradle.kts").exists() }
    }
}
