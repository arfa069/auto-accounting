package com.autoaccounting.backend

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Scans Kotlin source files (non-test) for hardcoded provider keys or secrets.
 * Verifies acceptance criterion: provider keys are not committed or shipped in client code.
 */
class SecretScannerTest {
    @Test
    fun noProviderKeysInNonTestSource() {
        val sourceRoot = File("src/main/kotlin")
        if (!sourceRoot.exists()) return

        val suspiciousPatterns = listOf(
            Regex("""["']sk[-_][a-zA-Z0-9]{20,}["']"""),
            Regex("""["']key[-_][a-zA-Z0-9]{20,}["']"""),
            Regex("""Bearer\s+[a-zA-Z0-9._\-]{20,}"""),
            Regex("""api[_-]?key\s*=\s*["'][^"']{10,}["']""", RegexOption.IGNORE_CASE)
        )

        val violations = mutableListOf<String>()
        sourceRoot.walkTopDown()
            .filter { it.extension == "kt" }
            .forEach { file ->
                file.readLines().forEachIndexed { lineIndex, line ->
                    suspiciousPatterns.forEach { pattern ->
                        if (pattern.containsMatchIn(line)) {
                            violations += "${file.relativeTo(sourceRoot)}:${lineIndex + 1} - ${line.trim()}"
                        }
                    }
                }
            }

        assertTrue(
            "Found potential hardcoded secrets in source files:\n${violations.joinToString("\n")}",
            violations.isEmpty()
        )
    }

    @Test
    fun environmentVariableNamesAreNotHardcodedAsValues() {
        val sourceRoot = File("src/main/kotlin")
        if (!sourceRoot.exists()) return

        val envVarNames = listOf(
            "AUTO_ACCOUNTING_AI_API_KEY",
            "AUTO_ACCOUNTING_SMS_API_KEY"
        )

        val violations = mutableListOf<String>()
        sourceRoot.walkTopDown()
            .filter { it.extension == "kt" }
            .forEach { file ->
                file.readLines().forEachIndexed { lineIndex, line ->
                    envVarNames.forEach { envVar ->
                        // Allow env["KEY_NAME"] but flag "KEY_NAME" used as a value assignment
                        if (line.contains("= \"$envVar\"") || line.contains("= '$envVar'")) {
                            violations += "${file.relativeTo(sourceRoot)}:${lineIndex + 1} - looks like env var name used as value"
                        }
                    }
                }
            }

        assertTrue(
            "Found env var names used as hardcoded values:\n${violations.joinToString("\n")}",
            violations.isEmpty()
        )
    }

    @Test
    fun dotEnvFileIsGitIgnored() {
        val gitignore = File(".gitignore")
        if (!gitignore.exists()) return

        val content = gitignore.readText()
        assertTrue(
            ".env should be listed in .gitignore to prevent accidental key commits",
            content.contains(".env")
        )
    }
}
