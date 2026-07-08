package com.autoaccounting.feature.compliance

data class ComplianceMaterials(
    val privacyPolicySections: List<ComplianceSection>,
    val personalInformationItems: List<PersonalInformationItem>,
    val thirdPartyServices: List<ThirdPartyService>,
    val permissionExplanations: List<PermissionExplanation>,
    val storeReviewNotes: List<StoreReviewNote>
)

data class ComplianceSection(
    val title: String,
    val body: String
)

data class PersonalInformationItem(
    val name: String,
    val purpose: String,
    val requiredState: String,
    val processingMethod: String
)

data class ThirdPartyService(
    val name: String,
    val purpose: String,
    val personalInformationCategory: String,
    val processingMethod: String,
    val declarationTokens: Set<String> = emptySet()
)

enum class PermissionExplanationId {
    NotificationListening,
    AccessibilityBillSync,
    ContinuousMonitoring,
    CloudAi,
    BackgroundKeepAlive
}

data class PermissionExplanation(
    val id: PermissionExplanationId,
    val title: String,
    val purpose: String,
    val boundary: String
)

data class StoreReviewNote(
    val title: String,
    val body: String
)

data class ComplianceFinding(
    val token: String,
    val reason: String
)

val AUTO_ACCOUNTING_COMPLIANCE = ComplianceMaterials(
    privacyPolicySections = listOf(
        ComplianceSection(
            title = "政策范围",
            body = "本应用以本地账本为主，交易金额、商户/标题、账单文本、分类、资金账户和备注按敏感个人信息保护。"
        ),
        ComplianceSection(
            title = "数据保存与删除",
            body = "本地账本保留在设备内；账号注销会在 7 天冷静期后删除云端账号、注册设备、云端配置和 AI 分类日志。"
        ),
        ComplianceSection(
            title = "用户控制",
            body = "用户可查看收集清单、导出 CSV、导出/导入加密备份、关闭云端 AI、注销账号或单独删除本机数据。"
        )
    ),
    personalInformationItems = listOf(
        PersonalInformationItem(
            name = "手机号",
            purpose = "账号注册、登录、找回密码和注销校验",
            requiredState = "账号模式必需，本地模式不需要",
            processingMethod = "提交到后端账号服务"
        ),
        PersonalInformationItem(
            name = "微信/支付宝支付通知内容",
            purpose = "生成待确认账目",
            requiredState = "仅开启通知监听后使用",
            processingMethod = "在设备上解析为待确认账目"
        ),
        PersonalInformationItem(
            name = "微信/支付宝账单页内容",
            purpose = "手动账单同步，补充漏记或历史账目",
            requiredState = "仅用户发起同步时使用",
            processingMethod = "通过无障碍服务在设备上读取并解析"
        ),
        PersonalInformationItem(
            name = "账本和待确认账目",
            purpose = "本地记账、报表、备份和导出",
            requiredState = "核心记账功能必需",
            processingMethod = "优先存储在本机数据库"
        ),
        PersonalInformationItem(
            name = "AI 分类 payload 与日志",
            purpose = "云端 AI 分类建议和内测质量改进",
            requiredState = "可选，需显式开启云端 AI",
            processingMethod = "默认仅上传最小字段，增强上下文需再次选择"
        )
    ),
    thirdPartyServices = listOf(
        ThirdPartyService(
            name = "SMS provider",
            purpose = "发送账号注册和找回密码验证码",
            personalInformationCategory = "手机号、设备/IP 风控信息",
            processingMethod = "通过后端服务调用",
            declarationTokens = setOf("sms")
        ),
        ThirdPartyService(
            name = "Cloud AI provider",
            purpose = "提供云端 AI 分类建议",
            personalInformationCategory = "最小交易字段，可选增强上下文",
            processingMethod = "通过后端代理调用",
            declarationTokens = setOf("openai", "ai", "model")
        ),
        ThirdPartyService(
            name = "Crash/log provider",
            purpose = "内测稳定性排查",
            personalInformationCategory = "崩溃日志、设备和应用版本信息",
            processingMethod = "内测接入前需补充具体服务商",
            declarationTokens = setOf("crash", "log", "sentry", "bugly")
        ),
        ThirdPartyService(
            name = "App distribution statistics provider",
            purpose = "应用商店分发和基础安装统计",
            personalInformationCategory = "应用分发统计信息",
            processingMethod = "由目标应用商店提供",
            declarationTokens = setOf("analytics", "distribution")
        )
    ),
    permissionExplanations = listOf(
        PermissionExplanation(
            id = PermissionExplanationId.NotificationListening,
            title = "通知监听",
            purpose = "用于识别微信、支付宝的收付款通知，生成待确认账目。",
            boundary = "只处理支付相关通知，不读取无关通知内容。"
        ),
        PermissionExplanation(
            id = PermissionExplanationId.AccessibilityBillSync,
            title = "无障碍账单同步",
            purpose = "仅在你手动同步时读取微信、支付宝账单页面，补充漏记或历史账目。",
            boundary = "不读取聊天内容，不发送消息，不发起付款或转账。"
        ),
        PermissionExplanation(
            id = PermissionExplanationId.ContinuousMonitoring,
            title = "连续监控",
            purpose = "开启后会持续观察支付相关页面，提高自动捕获完整度；可随时关闭。",
            boundary = "高级可选模式，不进入首次默认引导。"
        ),
        PermissionExplanation(
            id = PermissionExplanationId.CloudAi,
            title = "云端 AI",
            purpose = "开启后会上传必要交易信息用于分类建议，可选择是否提供更多上下文。",
            boundary = "默认关闭，最小字段优先，增强上下文单独选择。"
        ),
        PermissionExplanation(
            id = PermissionExplanationId.BackgroundKeepAlive,
            title = "后台保活",
            purpose = "建议允许后台运行，避免通知捕获中断；不同手机设置入口可能不同。",
            boundary = "只用于提升支付捕获稳定性。"
        )
    ),
    storeReviewNotes = listOf(
        StoreReviewNote(
            title = "通知监听审核说明",
            body = "通知监听只用于识别微信、支付宝支付通知，并生成待确认账目。"
        ),
        StoreReviewNote(
            title = "无障碍审核说明",
            body = "无障碍服务只在用户手动账单同步时读取账单页，不读取聊天，不发送消息，不发起付款或转账。"
        ),
        StoreReviewNote(
            title = "连续监控审核说明",
            body = "连续监控是高级可选功能，用户可随时关闭，默认引导不强制开启。"
        )
    )
)

fun ComplianceMaterials.permissionPurpose(id: PermissionExplanationId): String {
    return requireNotNull(permissionExplanations.find { it.id == id }).purpose
}

fun findUnlistedSdkOrNetworkServices(
    buildTexts: List<String>,
    manifestText: String,
    declaredServices: List<ThirdPartyService>
): List<ComplianceFinding> {
    val declaredTokens = declaredServices.flatMap { it.declarationTokens }.map { it.lowercase() }.toSet()
    val scannedText = (buildTexts + manifestText).joinToString("\n").lowercase()
    val riskyTokens = listOf(
        "umeng",
        "firebase",
        "bugly",
        "sentry",
        "crashlytics",
        "flurry",
        "adjust",
        "appsflyer",
        "facebook",
        "wechat-sdk",
        "alipay-sdk",
        "okhttp",
        "retrofit"
    )
    return riskyTokens
        .filter { token -> scannedText.contains(token) && token !in declaredTokens }
        .map { token ->
            ComplianceFinding(
                token = token,
                reason = "Build or manifest references $token but the third-party service list does not declare it."
            )
        }
}
