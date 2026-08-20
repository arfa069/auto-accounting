package com.bks.feature.sync

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class LedgerSyncManifestTest {
    @Test
    fun cleartextManifestFollowsExplicitLedgerSyncOptIn() {
        val root = projectRoot()
        val manifest = root.resolve("apps/android/src/main/AndroidManifest.xml").readText()
        val buildScript = root.resolve("apps/android/build.gradle.kts").readText()

        assertTrue(manifest.contains("android:usesCleartextTraffic=\"\${allowHttpLedgerSync}\""))
        assertTrue(buildScript.contains("manifestPlaceholders[\"allowHttpLedgerSync\"] = allowHttpLedgerSync"))
    }

    private fun projectRoot(): File {
        val userDirectory = requireNotNull(System.getProperty("user.dir"))
        return generateSequence(File(userDirectory)) { current ->
            current.parentFile?.absoluteFile
        }
            .first { it.resolve("settings.gradle.kts").exists() }
    }
}
