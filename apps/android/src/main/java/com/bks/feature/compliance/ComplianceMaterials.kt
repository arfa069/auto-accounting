package com.bks.feature.compliance

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
    AccessibilityBillSync,
    CloudAi
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

val BKS_COMPLIANCE = ComplianceMaterials(
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
            name = "其他应用当前活动窗口的可见文字",
            purpose = "在用户开启自动记账后识别已完成交易并生成待确认条目",
            requiredState = "自动记账可选，默认关闭；需要用户授予无障碍服务权限",
            processingMethod = "仅在设备内短暂读取可见、非密码、非输入框文字；不截图、不操作其他应用，未命中内容和原始页面文字不保存、不写入诊断日志、不上传"
        ),
        PersonalInformationItem(
            name = "应用故障诊断日志",
            purpose = "排查应用异常和本地持久化故障",
            requiredState = "Debug 默认开启；Release 默认关闭，需用户阅读说明并主动开启",
            processingMethod = "仅设备内 Keystore 加密分段，最多 10 MB，不上传；可关闭、清空或使用至少 8 位口令加密导出。自动记账页面文字不进入诊断日志，认证秘密写入前强制脱敏"
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
            name = "Assists 开源无障碍组件",
            purpose = "接入 Android 无障碍服务并读取当前活动窗口节点",
            personalInformationCategory = "其他应用当前活动窗口中可见、非密码、非输入框的文字",
            processingMethod = "仅在应用进程内使用 assists-base；不使用截图、OCR、自动点击、滚动或应用启动能力，原始页面文字不保存、不上传",
            declarationTokens = setOf("assists-base")
        )
    ),
    permissionExplanations = listOf(
        PermissionExplanation(
            id = PermissionExplanationId.AccessibilityBillSync,
            title = "自动记账无障碍服务",
            purpose = "用于用户开启自动记账后，在设备本地识别其他应用当前活动窗口中的已完成交易。",
            boundary = "只读取可见、非密码、非输入框文字；不截图、不点击、不滚动、不启动其他应用，不保存或上传原始页面文字。识别结果只进入待确认。"
        ),
        PermissionExplanation(
            id = PermissionExplanationId.CloudAi,
            title = "云端 AI",
            purpose = "开启后会上传必要交易信息用于分类建议，可选择是否提供更多上下文。",
            boundary = "默认关闭，最小字段优先，增强上下文单独选择。"
        ),
    ),
    storeReviewNotes = listOf(
        StoreReviewNote(
            title = "无障碍审核说明",
            body = "无障碍服务仅在用户主动开启自动记账后运行，读取其他应用当前活动窗口中可见、非密码、非输入框的文字，并只为明确完成的交易生成待确认项；不截图、不操作其他应用、不保存或上传原始页面文字。"
        ),
        StoreReviewNote(
            title = "诊断日志审核说明",
            body = "Release 的诊断日志默认关闭，用户需在合规与隐私页面阅读字段、用途、10 MB 上限、长期保留和导出风险后主动开启。日志不上传，使用 Android Keystore 加密；自动记账页面文字不记录，密码、验证码、Token、Cookie、Authorization、API Key、备份口令和私钥写入前强制脱敏。"
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
    val scannedText = (buildTexts + manifestText)
        .joinToString("\n")
        .lineSequence()
        .filterNot { "exclude(" in it }
        .joinToString("\n")
        .lowercase()
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
