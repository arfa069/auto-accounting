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
    val processingMethod: String,
    val retentionAndDeletion: String = ""
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
    ResultNotifications,
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
            body = "本地账本保留在设备内；用户主动开启的诊断日志以设备密钥加密并最多保留 10 MB。删除本机数据会同时清除日志、密钥和 Release 开启偏好，但 Downloads 中已导出的文件需用户自行删除；账号注销会在 7 天冷静期后删除云端账号、注册设备、云端配置和 AI 分类日志。"
        ),
        ComplianceSection(
            title = "用户控制",
            body = "用户可查看收集清单、导出 CSV、导出/导入加密备份、启停/查看/口令加密导出/清空诊断日志、关闭云端 AI、注销账号或单独删除本机数据。"
        )
    ),
    personalInformationItems = listOf(
        PersonalInformationItem(
            name = "手机号",
            purpose = "账号注册、登录、找回密码和注销校验",
            requiredState = "账号模式必需，本地模式不需要",
            processingMethod = "提交到后端账号服务",
            retentionAndDeletion = "账号存在期间保存；解绑手机号不在当前版本范围，账号注销完成后删除"
        ),
        PersonalInformationItem(
            name = "微信账号标识与资料",
            purpose = "微信登录、注册、绑定、合并及展示昵称和头像",
            requiredState = "仅用户主动使用微信账号功能时处理",
            processingMethod = "后端保存 OpenID、可用时的 UnionID、昵称和 HTTPS 头像 URL；Android Session 不保存 OpenID 或 UnionID，也不包含微信 Token",
            retentionAndDeletion = "每次成功授权时刷新；解绑微信或账号注销完成后删除，退出或本机数据删除会清理设备头像缓存"
        ),
        PersonalInformationItem(
            name = "随机安装 UUID",
            purpose = "短信限流、设备去重、安全防护和配置分发",
            requiredState = "账号功能与短信风控使用；不读取硬件标识符",
            processingMethod = "Android 随机生成并提交后端，不用于广告追踪",
            retentionAndDeletion = "设备端保留至本机数据删除或卸载；后端设备记录在账号注销完成后删除"
        ),
        PersonalInformationItem(
            name = "微信/支付宝支付通知内容",
            purpose = "生成待确认账目",
            requiredState = "仅开启通知监听后使用",
            processingMethod = "在设备上解析为待确认账目"
        ),
        PersonalInformationItem(
            name = "微信/支付宝支付结果与账单页内容",
            purpose = "开启自动记账后识别支付结果，或手动补充历史账目",
            requiredState = "仅用户开启自动记账或发起手动同步后使用",
            processingMethod = "优先读取无障碍节点；微信空节点支付结果页可在设备本地瞬时截图 OCR。截图始终立即释放且不保存、不上传；仅当用户主动开启诊断日志时，允许范围内的页面/OCR 文字会在本机加密留存"
        ),
        PersonalInformationItem(
            name = "自动记账敏感诊断日志",
            purpose = "排查通知、无障碍、OCR、解析、去重、持久化和补录故障",
            requiredState = "Debug 默认开启；Release 默认关闭，需用户阅读说明并主动开启",
            processingMethod = "仅设备内 Keystore 加密分段，最多 10 MB，不上传；可关闭、清空或使用至少 8 位口令加密导出。包含支付通知/页面/OCR 文字及交易字段，认证秘密写入前强制脱敏"
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
            name = "腾讯微信开放平台与微信 OpenSDK",
            purpose = "提供微信授权登录、注册和账号绑定",
            personalInformationCategory = "微信授权 code、OpenID、可用时的 UnionID、昵称、HTTPS 头像 URL",
            processingMethod = "Android 仅通过 OpenSDK 发起用户授权，后端向微信服务端换票并获取资料；不向微信发送账本、交易、备份或诊断日志，AppSecret 和微信 Token 不进入 APK",
            declarationTokens = setOf("wechat-sdk", "opensdk", "com.tencent.mm")
        ),
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
            name = "App distribution statistics provider",
            purpose = "应用商店分发和基础安装统计",
            personalInformationCategory = "应用分发统计信息",
            processingMethod = "由目标应用商店提供",
            declarationTokens = setOf("analytics", "distribution")
        ),
        ThirdPartyService(
            name = "Coil / OkHttp 开源头像加载组件",
            purpose = "加载账号管理中的 HTTPS 微信头像",
            personalInformationCategory = "HTTPS 头像 URL 与图片响应字节",
            processingMethod = "仅向头像 URL 指向的服务端发起请求；组件本身不提供遥测或云端存储。图片使用独立的本机 wechat_avatars 缓存，最多 10 MB，并在 URL 更新、退出、解绑、Session 失效或本机数据删除时清理",
            declarationTokens = setOf("okhttp")
        ),
        ThirdPartyService(
            name = "Google ML Kit Chinese Text Recognition（捆绑模型）",
            purpose = "在微信支付结果页不提供可读无障碍节点时进行本地文字识别",
            personalInformationCategory = "瞬时支付结果页像素和识别文本",
            processingMethod = "模型随应用安装并仅在设备本地处理；截图处理后立即释放且不保存、不上传。用户主动开启诊断日志时，允许范围内的 OCR 文字可在设备内加密留存，不发送给 OCR 服务商",
            declarationTokens = setOf("mlkit", "text-recognition")
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
            id = PermissionExplanationId.ResultNotifications,
            title = "记账结果通知",
            purpose = "用于通知自动记账成功或失败结果。",
            boundary = "未授权不影响本地采集；锁屏默认隐藏金额、商户和交易对方。"
        ),
        PermissionExplanation(
            id = PermissionExplanationId.AccessibilityBillSync,
            title = "自动记账无障碍服务",
            purpose = "用于开启自动记账后观察微信、支付宝支付结果和支付记录；微信空节点结果页可在本机瞬时 OCR，也可手动补充历史账目。",
            boundary = "不读取聊天或普通消息，不发送消息，不发起付款、转账或退款；OCR 图片始终不保存、不上传。用户主动开启诊断日志时，仅支付页或当前补录会话的 OCR 文字可在本机加密留存。"
        ),
        PermissionExplanation(
            id = PermissionExplanationId.ContinuousMonitoring,
            title = "自动记账",
            purpose = "开启后会在支付完成时观察受支持的结果页，必要时在本机瞬时 OCR，并生成待确认记录；可随时关闭。",
            boundary = "必须由用户明确开启，只处理支付结果和支付记录；截图不留存。OCR 原文仅在用户另行主动开启诊断日志时于本机加密留存。"
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
            body = "无障碍服务仅在用户开启自动记账后观察微信、支付宝支付结果和支付记录，或用于用户主动补充历史账目；微信空节点支付结果页可使用设备本地瞬时截图 OCR，图片始终立即释放且不保存、不上传；不读取聊天或普通消息，不发送消息，不发起付款、转账或退款。用户另行主动开启诊断日志后，仅允许页面/会话的 OCR 文字可在设备内加密留存。"
        ),
        StoreReviewNote(
            title = "自动记账审核说明",
            body = "自动记账由用户明确开启，可随时关闭；所有捕获结果先进入待确认队列。"
        ),
        StoreReviewNote(
            title = "诊断日志审核说明",
            body = "Release 的诊断日志默认关闭，用户需在合规与隐私页面阅读字段、用途、10 MB 上限、长期保留和导出风险后主动开启。日志不上传，使用 Android Keystore 加密；截图永不保存，聊天/普通通知正文不记录，密码、验证码、Token、Cookie、Authorization、API Key、备份口令和私钥写入前强制脱敏。"
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
        "com.adjust",
        "appsflyer",
        "facebook",
        "wechat-sdk",
        "alipay-sdk",
        "mlkit",
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
