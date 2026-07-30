package com.autoaccounting.backend

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

internal const val BACKEND_ENV_FILE_KEY = "AUTO_ACCOUNTING_ENV_FILE"
internal const val BACKEND_HOST_KEY = "AUTO_ACCOUNTING_HOST"
internal const val BACKEND_PORT_KEY = "AUTO_ACCOUNTING_PORT"
internal const val BACKEND_TRUST_PROXY_HEADERS_KEY = "AUTO_ACCOUNTING_TRUST_PROXY_HEADERS"

internal object BackendEnvironment {
    private val keyPattern = Regex("[A-Za-z_][A-Za-z0-9_]*")

    fun load(
        processEnvironment: Map<String, String> = System.getenv(),
        workingDirectory: Path = Path.of("").toAbsolutePath()
    ): Map<String, String> {
        val normalizedWorkingDirectory = workingDirectory.normalize()
        val dotEnvPath = explicitDotEnv(processEnvironment, normalizedWorkingDirectory)
            ?: findDotEnv(normalizedWorkingDirectory)
            ?: return processEnvironment
        val fileEnvironment = parseDotEnv(dotEnvPath)
        return fileEnvironment + processEnvironment
    }

    private fun explicitDotEnv(processEnvironment: Map<String, String>, workingDirectory: Path): Path? {
        val configuredPath = processEnvironment[BACKEND_ENV_FILE_KEY]
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?: return null
        val path = runCatching { Path.of(configuredPath) }
            .getOrElse { error("$BACKEND_ENV_FILE_KEY must be a valid path.") }
        val resolvedPath = if (path.isAbsolute) path else workingDirectory.resolve(path)
        check(Files.isRegularFile(resolvedPath)) {
            "$BACKEND_ENV_FILE_KEY must point to a regular file."
        }
        return resolvedPath.normalize()
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

internal data class BackendServerConfig(
    val host: String,
    val port: Int,
    val trustProxyHeaders: Boolean
) {
    companion object {
        private const val DEFAULT_HOST = "0.0.0.0"
        private const val DEFAULT_PORT = 8080
        private val loopbackHosts = setOf("127.0.0.1", "::1", "localhost")

        fun fromEnvironment(env: Map<String, String>): BackendServerConfig {
            val host = env[BACKEND_HOST_KEY]?.trim()?.takeIf(String::isNotEmpty) ?: DEFAULT_HOST
            val port = env[BACKEND_PORT_KEY]?.trim()?.takeIf(String::isNotEmpty)?.let { rawPort ->
                rawPort.toIntOrNull()?.takeIf { it in 1..65535 }
                    ?: error("$BACKEND_PORT_KEY must be an integer between 1 and 65535.")
            } ?: DEFAULT_PORT
            val trustProxyHeaders = env[BACKEND_TRUST_PROXY_HEADERS_KEY]
                ?.trim()
                ?.takeIf(String::isNotEmpty)
                ?.let { rawValue ->
                    when {
                        rawValue.equals("true", ignoreCase = true) -> true
                        rawValue.equals("false", ignoreCase = true) -> false
                        else -> error("$BACKEND_TRUST_PROXY_HEADERS_KEY must be true or false.")
                    }
                } ?: false
            check(!trustProxyHeaders || host in loopbackHosts) {
                "$BACKEND_TRUST_PROXY_HEADERS_KEY requires $BACKEND_HOST_KEY to use a loopback host."
            }
            return BackendServerConfig(host, port, trustProxyHeaders)
        }
    }
}
