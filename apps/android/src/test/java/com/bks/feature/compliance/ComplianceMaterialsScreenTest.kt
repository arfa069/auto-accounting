package com.bks.feature.compliance

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ComplianceMaterialsScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun complianceMaterialsExposeAllRequiredPages() {
        composeRule.setContent {
            ComplianceMaterialsScreen()
        }

        composeRule.onNodeWithText("隐私政策").assertIsDisplayed()
        composeRule.onNodeWithText("个人信息收集清单").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("第三方服务清单").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("权限说明").performScrollTo().assertIsDisplayed()
        composeRule.onAllNodesWithText("商店审核说明").assertCountEquals(0)
    }

    @Test
    fun backActionIsReachable() {
        var backed = false
        composeRule.setContent {
            ComplianceMaterialsScreen(onBack = { backed = true })
        }

        composeRule.onNodeWithText("返回").performClick()

        assert(backed)
    }

    @Test
    fun wechatIdentityAndProviderDisclosuresAreVisible() {
        composeRule.setContent {
            ComplianceMaterialsScreen()
        }

        composeRule.onNodeWithTag("compliance-entry-PersonalInformation")
            .performScrollTo()
            .performClick()
        composeRule.onNodeWithText("微信账号标识与资料")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText("OpenID", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText("返回").performClick()

        composeRule.onNodeWithTag("compliance-entry-ThirdPartyServices")
            .performScrollTo()
            .performClick()
        composeRule.onNodeWithText("腾讯微信开放平台与微信 OpenSDK")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText("不向微信发送账本", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
    }
}
