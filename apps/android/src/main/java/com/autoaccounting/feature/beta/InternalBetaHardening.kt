package com.autoaccounting.feature.beta

import java.io.File

data class InternalBetaMetricSample(
    val expectedTransactions: Int,
    val capturedTransactions: Int,
    val expectedDuplicatePairs: Int,
    val correctlyMergedDuplicatePairs: Int,
    val falseMergedPairs: Int,
    val reviewedEntries: Int,
    val reviewDurationSeconds: Int,
    val usersWithPermissionEnabledAtStart: Int,
    val usersWithPermissionEnabledAfterSevenDays: Int
)

data class InternalBetaMetrics(
    val captureAccuracy: Double,
    val deduplicationAccuracy: Double,
    val averageReviewSeconds: Double,
    val permissionRetention: Double
)

enum class BetaReadinessItemId {
    CrashAndLogIntegration,
    DeviceMatrixTesting,
    PermissionRetentionMeasurement,
    CaptureAccuracyMeasurement,
    DeduplicationAccuracyMeasurement,
    ReviewEfficiencyMeasurement,
    StoreCompliancePackageReview,
    NoSecretsInRepository
}

data class BetaReadinessItem(
    val id: BetaReadinessItemId,
    val title: String,
    val status: BetaReadinessStatus,
    val detail: String
)

enum class BetaReadinessStatus {
    Ready,
    NeedsManualValidation
}

data class InternalBetaReadinessReport(
    val items: List<BetaReadinessItem>,
    val deviceMatrix: List<String>,
    val knownRisks: List<String>
)

data class SecretFinding(
    val path: String,
    val patternName: String
)

fun calculateInternalBetaMetrics(sample: InternalBetaMetricSample): InternalBetaMetrics {
    return InternalBetaMetrics(
        captureAccuracy = ratio(sample.capturedTransactions, sample.expectedTransactions),
        deduplicationAccuracy = ratio(
            sample.correctlyMergedDuplicatePairs - sample.falseMergedPairs,
            sample.expectedDuplicatePairs
        ).coerceAtLeast(0.0),
        averageReviewSeconds = ratio(sample.reviewDurationSeconds, sample.reviewedEntries),
        permissionRetention = ratio(
            sample.usersWithPermissionEnabledAfterSevenDays,
            sample.usersWithPermissionEnabledAtStart
        )
    )
}

fun buildInternalBetaReadinessReport(): InternalBetaReadinessReport = InternalBetaReadinessReport(
    items = listOf(
        BetaReadinessItem(
            id = BetaReadinessItemId.CrashAndLogIntegration,
            title = "本地崩溃/日志脱敏",
            status = BetaReadinessStatus.Ready,
            detail = "真实诊断仓库使用设备内加密分段；认证秘密写入前脱敏，敏感内容不进入 Logcat。"
        ),
        BetaReadinessItem(
            id = BetaReadinessItemId.DeviceMatrixTesting,
            title = "设备矩阵测试",
            status = BetaReadinessStatus.NeedsManualValidation,
            detail = "覆盖 Android 10-15、主流国产 ROM 和无障碍账单补录。"
        ),
        BetaReadinessItem(
            id = BetaReadinessItemId.PermissionRetentionMeasurement,
            title = "权限留存测量",
            status = BetaReadinessStatus.Ready,
            detail = "记录启用权限用户数和 7 日后仍保持权限用户数。"
        ),
        BetaReadinessItem(
            id = BetaReadinessItemId.CaptureAccuracyMeasurement,
            title = "捕获准确率测量",
            status = BetaReadinessStatus.Ready,
            detail = "按真实交易数和捕获交易数计算。"
        ),
        BetaReadinessItem(
            id = BetaReadinessItemId.DeduplicationAccuracyMeasurement,
            title = "去重准确率测量",
            status = BetaReadinessStatus.Ready,
            detail = "按正确合并、误合并和应合并交易对计算。"
        ),
        BetaReadinessItem(
            id = BetaReadinessItemId.ReviewEfficiencyMeasurement,
            title = "复核效率测量",
            status = BetaReadinessStatus.Ready,
            detail = "用复核总耗时除以已复核账目数。"
        ),
        BetaReadinessItem(
            id = BetaReadinessItemId.StoreCompliancePackageReview,
            title = "商店合规包复核",
            status = BetaReadinessStatus.NeedsManualValidation,
            detail = "隐私政策、收集清单、第三方服务清单、权限说明和审核说明需人工复核。"
        ),
        BetaReadinessItem(
            id = BetaReadinessItemId.NoSecretsInRepository,
            title = "无密钥入库",
            status = BetaReadinessStatus.Ready,
            detail = "构建前扫描客户端、后端和共享源码中的密钥形态。"
        )
    ),
    deviceMatrix = listOf(
        "Android 10 baseline",
        "Android 12 domestic ROM",
        "Android 14 domestic ROM",
        "Android 15 target SDK smoke test"
    ),
    knownRisks = listOf(
        "无障碍权限在不同 ROM 上的授权与服务连接行为需要真机验证。",
        "AI 分类日志保留策略在公开发布前需要重新复核。",
        "手动补录无障碍服务属于敏感能力，商店审核材料需要附录屏说明。"
    )
)

fun scanForSecretLikeValues(files: List<File>): List<SecretFinding> {
    return files
        .flatMap { it.sourceFiles() }
        .flatMap { file ->
            val text = file.readText()
            SECRET_PATTERNS.mapNotNull { pattern ->
                if (pattern.regex.containsMatchIn(text)) {
                    SecretFinding(file.path, pattern.name)
                } else {
                    null
                }
            }
        }
}

private fun ratio(numerator: Int, denominator: Int): Double {
    if (denominator <= 0) return 0.0
    return numerator.toDouble() / denominator.toDouble()
}

private fun File.sourceFiles(): List<File> {
    if (!exists()) return emptyList()
    if (isFile) return listOf(this).filter { it.isScannableSource() }
    return walkTopDown()
        .filter { it.isFile && it.isScannableSource() }
        .toList()
}

private fun File.isScannableSource(): Boolean {
    return extension in setOf("kt", "kts", "xml", "properties", "gradle")
}

private data class SecretPattern(
    val name: String,
    val regex: Regex
)

private val SECRET_PATTERNS = listOf(
    SecretPattern("OpenAI style API key", Regex("""sk-[A-Za-z0-9_-]{20,}""")),
    SecretPattern("Private key block", Regex("""-----BEGIN [A-Z ]*PRIVATE KEY-----""")),
    SecretPattern("Hard-coded API key assignment", Regex("""api[_-]?key\s*=\s*["'][^"']{12,}["']""", RegexOption.IGNORE_CASE)),
    SecretPattern("Hard-coded secret assignment", Regex("""secret\s*=\s*["'][^"']{12,}["']""", RegexOption.IGNORE_CASE))
)
