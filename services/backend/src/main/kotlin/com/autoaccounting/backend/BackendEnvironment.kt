package com.autoaccounting.backend

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

internal object BackendEnvironment {
    private val keyPattern = Regex("[A-Za-z_][A-Za-z0-9_]*")

    fun load(
        processEnvironment: Map<String, String> = System.getenv(),
        workingDirectory: Path = Path.of("").toAbsolutePath()
    ): Map<String, String> {
        val dotEnvPath = findDotEnv(workingDirectory.normalize()) ?: return processEnvironment
        val fileEnvironment = parseDotEnv(dotEnvPath)
        return fileEnvironment + processEnvironment
    }

    private fun findDotEnv(workingDirectory: Path): Path? {
        val backendDirectory = if (
            workingDirectory.fileName?.toString() == "backend" &&
            workingDirectory.parent?.fileName?.toString() == "services"
        ) {
            workingDirectory
        } else {
            workingDirectory.resolve("services").resolve("backend")
        }
        return backendDirectory.resolve(".env").takeIf(Files::isRegularFile)
    }

    private fun parseDotEnv(path: Path): Map<String, String> {
        val values = linkedMapOf<String, String>()
        Files.readAllLines(path, StandardCharsets.UTF_8).forEachIndexed { index, line ->
            val trimmedLine = line.trim()
            if (trimmedLine.isBlank() || trimmedLine.startsWith("#")) return@forEachIndexed

            val assignment = if (trimmedLine.startsWith("export ")) {
                trimmedLine.removePrefix("export ").trimStart()
            } else {
                trimmedLine
            }
            val separator = assignment.indexOf('=')
            if (separator <= 0) invalidEntry(path, index)

            val key = assignment.substring(0, separator).trim()
            if (!keyPattern.matches(key)) invalidEntry(path, index)
            values[key] = parseValue(assignment.substring(separator + 1).trim(), path, index)
        }
        return values
    }

    private fun parseValue(value: String, path: Path, lineIndex: Int): String {
        if (value.isEmpty()) return value
        val quote = value.first()
        if (quote != '\'' && quote != '"') return value
        if (value.length < 2 || value.last() != quote) invalidEntry(path, lineIndex)
        return value.substring(1, value.length - 1)
    }

    private fun invalidEntry(path: Path, lineIndex: Int): Nothing {
        error("Invalid .env entry at ${path.fileName}:${lineIndex + 1}.")
    }
}
