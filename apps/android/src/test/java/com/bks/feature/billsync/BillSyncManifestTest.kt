package com.bks.feature.billsync

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BillSyncManifestTest {
    @Test
    fun accessibilityManifestIsGenericAndRemovesUnusedAssistsCapabilities() {
        val root = projectRoot()
        val manifest = root.resolve("apps/android/src/main/AndroidManifest.xml").readText()
        val service = root.resolve("apps/android/src/main/res/xml/bill_sync_accessibility_service.xml").readText()
        val build = root.resolve("apps/android/build.gradle.kts").readText()
        val versions = root.resolve("gradle/libs.versions.toml").readText()
        val proguard = root.resolve("apps/android/proguard-rules.pro").readText()

        assertTrue(manifest.contains("android:exported=\"false\""))
        assertTrue(manifest.contains("com.tencent.mm"))
        assertFalse(manifest.contains("com.eg.android.AlipayGphone"))
        assertTrue(manifest.contains("android.permission.READ_CONTACTS\" tools:node=\"remove\""))
        assertTrue(manifest.contains("android.permission.WRITE_CONTACTS\" tools:node=\"remove\""))
        assertTrue(manifest.contains("com.ven.assists.utils.AssistsFileProvider"))
        assertTrue(manifest.contains("com.ven.assists.ui.ClipboardActivity"))
        assertFalse(service.contains("packageNames"))
        assertFalse(service.contains("canTakeScreenshot"))
        assertTrue(build.contains("io.github.ven-coder:assists-base:3.5.5"))
        assertFalse(build.contains("mlkit.text.recognition"))
        assertFalse(versions.contains("mlkit", ignoreCase = true))
        assertFalse(proguard.contains("mlkit", ignoreCase = true))
        assertFalse(build.contains("assists-mp"))
        assertFalse(build.contains("assists-opcv"))
    }

    private fun projectRoot(): File {
        val userDirectory = requireNotNull(System.getProperty("user.dir"))
        return generateSequence(File(userDirectory)) { it.parentFile?.absoluteFile }
            .first { it.resolve("settings.gradle.kts").exists() }
    }
}
