package com.bks.backend

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.sql.SQLException
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

internal const val POSTGRES_WORKER_JDBC_URL_ENV = "BKS_POSTGRES_TEST_WORKER_URL"
internal const val POSTGRES_WORKER_USERNAME_ENV = "BKS_POSTGRES_TEST_WORKER_USER"
internal const val POSTGRES_WORKER_PASSWORD_ENV = "BKS_POSTGRES_TEST_WORKER_PASSWORD"
internal const val POSTGRES_WORKER_READY_PATH_ENV = "BKS_POSTGRES_TEST_WORKER_READY_PATH"
internal const val POSTGRES_WORKER_START_PATH_ENV = "BKS_POSTGRES_TEST_WORKER_START_PATH"
internal const val POSTGRES_WORKER_FAILURE_PATH_ENV = "BKS_POSTGRES_TEST_WORKER_FAILURE_PATH"
private const val START_TIMEOUT_SECONDS = 15L

internal object PostgresMigrationWorker {
    @JvmStatic
    fun main(@Suppress("UNUSED_PARAMETER") arguments: Array<String>) {
        val environment = System.getenv()
        val failurePath = environment[POSTGRES_WORKER_FAILURE_PATH_ENV]?.let(Path::of)
        try {
            val readyPath = Path.of(environment.required(POSTGRES_WORKER_READY_PATH_ENV))
            val startPath = Path.of(environment.required(POSTGRES_WORKER_START_PATH_ENV))
            Files.writeString(
                readyPath,
                ProcessHandle.current().pid().toString(),
                StandardOpenOption.CREATE_NEW
            )
            awaitStart(startPath)
            runMigrations(
                jdbcUrl = environment.required(POSTGRES_WORKER_JDBC_URL_ENV),
                username = environment[POSTGRES_WORKER_USERNAME_ENV].orEmpty(),
                password = environment[POSTGRES_WORKER_PASSWORD_ENV].orEmpty(),
                migrations = listOf(
                    Migration(
                        version = 1,
                        statements = listOf(
                            "SELECT pg_sleep(1)",
                            "CREATE TABLE postgres_migration_probe (id INTEGER PRIMARY KEY)"
                        )
                    )
                )
            )
        } catch (error: Throwable) {
            failurePath?.let { path ->
                runCatching { Files.writeString(path, error.sanitizedDescription()) }
            }
            exitProcess(1)
        }
    }

    private fun awaitStart(startPath: Path) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(START_TIMEOUT_SECONDS)
        while (!Files.exists(startPath)) {
            check(System.nanoTime() < deadline) { "PostgreSQL migration worker start timed out." }
            Thread.sleep(25)
        }
    }

    private fun Map<String, String>.required(name: String): String {
        return get(name) ?: error("Missing required worker environment.")
    }

    private fun Throwable.sanitizedDescription(): String {
        val sqlError = generateSequence(this) { it.cause }.filterIsInstance<SQLException>().firstOrNull()
        return if (sqlError == null) {
            javaClass.name
        } else {
            "SQLException(SQLState=${sqlError.sqlState ?: "unknown"},errorCode=${sqlError.errorCode})"
        }
    }
}
