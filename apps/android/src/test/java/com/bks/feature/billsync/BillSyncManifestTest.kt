package com.bks.feature.billsync

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class BillSyncManifestTest {
    @Test
    fun paymentSourcePackagesAreVisibleToTheLauncherFlow() {
        val manifest = projectRoot()
            .resolve("apps/android/src/main/AndroidManifest.xml")
            .readText()

        BillSyncSource.entries.forEach { source ->
            assertTrue(
                "Manifest must declare package visibility for ${source.packageName}",
                manifest.contains("<package android:name=\"${source.packageName}\" />")
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
