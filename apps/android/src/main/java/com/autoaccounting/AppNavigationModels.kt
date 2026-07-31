package com.autoaccounting

import com.autoaccounting.feature.categorization.CategorizationRule
import com.autoaccounting.feature.profile.ProfileDestination

internal enum class AppTab(
    val label: String,
    val iconRes: Int,
    val backgroundRes: Int
) {
    Review(
        label = "待确认",
        iconRes = R.drawable.aa_nav_review_outlined,
        backgroundRes = R.drawable.aa_bg_review
    ),
    Ledger(
        label = "账本",
        iconRes = R.drawable.aa_nav_ledger_outlined,
        backgroundRes = R.drawable.aa_bg_ledger
    ),
    Reports(
        label = "报表",
        iconRes = R.drawable.aa_nav_reports_outlined,
        backgroundRes = R.drawable.aa_bg_reports
    ),
    Profile(
        label = "我的",
        iconRes = R.drawable.aa_nav_profile_outlined,
        backgroundRes = R.drawable.aa_bg_profile
    )
}

internal data class AppRoute(
    val tab: AppTab?,
    val profileDestination: ProfileDestination?,
    val manualEntryOpen: Boolean
)

internal fun List<CategorizationRule>.upsert(rule: CategorizationRule): List<CategorizationRule> {
    return if (any { it.id == rule.id }) {
        map { existing -> if (existing.id == rule.id) rule else existing }
    } else {
        this + rule
    }
}

internal fun com.autoaccounting.feature.categorization.AiCategorizationFailureReason.toAiSettingsMessage(): String =
    when (this) {
        com.autoaccounting.feature.categorization.AiCategorizationFailureReason.BACKEND_NOT_CONFIGURED ->
            "云端后端地址尚未配置"
        com.autoaccounting.feature.categorization.AiCategorizationFailureReason.INVALID_SESSION ->
            "登录状态已失效，请重新登录"
        com.autoaccounting.feature.categorization.AiCategorizationFailureReason.ACCOUNT_DELETION_PENDING ->
            "账号注销冷静期内，云端设置已暂停"
        com.autoaccounting.feature.categorization.AiCategorizationFailureReason.RATE_LIMITED ->
            "请求过于频繁，请稍后重试"
        com.autoaccounting.feature.categorization.AiCategorizationFailureReason.NETWORK_FAILURE ->
            "网络连接失败，云端 AI 设置未更改"
        com.autoaccounting.feature.categorization.AiCategorizationFailureReason.SERVICE_UNAVAILABLE ->
            "云端服务暂时不可用，设置未更改"
        else -> "云端 AI 设置同步失败，请稍后重试"
    }
