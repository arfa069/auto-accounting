package com.autoaccounting.feature.beta

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InternalBetaHardeningTest {
    @Test
    fun qualityMetricsCalculateCaptureDedupeReviewAndPermissionRetention() {
        val metrics = calculateInternalBetaMetrics(
            sample = InternalBetaMetricSample(
                expectedTransactions = 100,
                capturedTransactions = 96,
                expectedDuplicatePairs = 20,
                correctlyMergedDuplicatePairs = 19,
                falseMergedPairs = 1,
                reviewedEntries = 40,
                reviewDurationSeconds = 400,
                usersWithPermissionEnabledAtStart = 50,
                usersWithPermissionEnabledAfterSevenDays = 45
            )
        )

        assertEquals(0.96, metrics.captureAccuracy, 0.0001)
        assertEquals(0.90, metrics.deduplicationAccuracy, 0.0001)
        assertEquals(10.0, metrics.averageReviewSeconds, 0.0001)
        assertEquals(0.90, metrics.permissionRetention, 0.0001)
    }

    @Test
    fun betaReadinessChecklistCoversAllHardeningAreas() {
        val report = buildInternalBetaReadinessReport()

        assertTrue(report.items.any { it.id == BetaReadinessItemId.CrashAndLogIntegration })
        assertTrue(report.items.any { it.id == BetaReadinessItemId.DeviceMatrixTesting })
        assertTrue(report.items.any { it.id == BetaReadinessItemId.PermissionRetentionMeasurement })
        assertTrue(report.items.any { it.id == BetaReadinessItemId.CaptureAccuracyMeasurement })
        assertTrue(report.items.any { it.id == BetaReadinessItemId.DeduplicationAccuracyMeasurement })
        assertTrue(report.items.any { it.id == BetaReadinessItemId.ReviewEfficiencyMeasurement })
        assertTrue(report.items.any { it.id == BetaReadinessItemId.StoreCompliancePackageReview })
        assertTrue(report.knownRisks.isNotEmpty())
    }

    @Test
    fun currentClientAndBackendSourcesDoNotContainSecretLikeValues() {
        val root = projectRoot()
        val findings = scanForSecretLikeValues(
            files = listOf(
                root.resolve("apps/android/src/main"),
                root.resolve("services/backend/src/main"),
                root.resolve("shared/api/src/main"),
                root.resolve("apps/android/build.gradle.kts"),
                root.resolve("services/backend/build.gradle.kts")
            )
        )

        assertTrue(findings.isEmpty())
    }

    private fun projectRoot(): File {
        return generateSequence(File(System.getProperty("user.dir"))) { current ->
            current.parentFile?.absoluteFile
        }.first { it.resolve("settings.gradle.kts").exists() }
    }
}
