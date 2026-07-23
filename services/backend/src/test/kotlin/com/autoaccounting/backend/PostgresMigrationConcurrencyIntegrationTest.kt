package com.autoaccounting.backend

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

private const val POSTGRES_TEST_URL_ENV = "AUTO_ACCOUNTING_POSTGRES_TEST_URL"
private const val POSTGRES_TEST_USER_ENV = "AUTO_ACCOUNTING_POSTGRES_TEST_USER"
private const val POSTGRES_TEST_PASSWORD_ENV = "AUTO_ACCOUNTING_POSTGRES_TEST_PASSWORD"
private const val READY_TIMEOUT_SECONDS = 15L
private const val WORKER_TIMEOUT_SECONDS = 30L
private val testSchemaPattern = Regex("codex_migration_test_[0-9a-f]{32}")

class PostgresMigrationConcurrencyIntegrationTest {
    @Test
    fun concurrentProcessesApplyMigrationExactlyOnceOnPostgres() {
        val config = postgresTestConfig()
        val schemaName = "codex_migration_test_${UUID.randomUUID().toString().replace("-", "")}"
        val schemaUrl = schemaUrl(config.jdbcUrl, schemaName)
        val synchronizationDirectory = Files.createTempDirectory("postgres-migration-test-")
        val startPath = synchronizationDirectory.resolve("start")
        val workers = mutableListOf<MigrationWorkerProcess>()
        var schemaCreated = false

        try {
            createSchema(config, schemaName)
            schemaCreated = true
            val classpath = workerClasspath()
            repeat(2) { index ->
                workers += startWorker(
                    index = index,
                    config = config.copy(jdbcUrl = schemaUrl),
                    classpath = classpath,
                    synchronizationDirectory = synchronizationDirectory,
                    startPath = startPath
                )
            }

            workers.forEach(::awaitReady)
            val workerProcessIds = workers.map { Files.readString(it.readyPath).trim().toLong() }
            assertEquals(2, workerProcessIds.distinct().size)

            Files.createFile(startPath)
            workers.forEachIndexed(::awaitSuccess)
            verifyMigrationResult(schemaUrl, config)
        } finally {
            workers.forEach(::stopWorker)
            if (schemaCreated) dropSchema(config, schemaName)
            cleanupSynchronizationDirectory(synchronizationDirectory, workers, startPath)
        }
    }

    private fun verifyMigrationResult(schemaUrl: String, config: PostgresTestConfig) {
        jdbcConnection(schemaUrl, config.username, config.password).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT COUNT(*) FROM schema_migrations WHERE version = 1").use { result ->
                    result.next()
                    assertEquals(1, result.getInt(1))
                }
                statement.executeQuery("SELECT COUNT(*) FROM postgres_migration_probe").use { result ->
                    result.next()
                    assertEquals(0, result.getInt(1))
                }
            }
        }
    }

    private fun postgresTestConfig(): PostgresTestConfig {
        val environment = BackendEnvironment.load()
        val jdbcUrl = environment[POSTGRES_TEST_URL_ENV]
        assumeTrue("$POSTGRES_TEST_URL_ENV is not configured.", !jdbcUrl.isNullOrBlank())
        return PostgresTestConfig(
            jdbcUrl = requireNotNull(jdbcUrl),
            username = environment[POSTGRES_TEST_USER_ENV].orEmpty(),
            password = environment[POSTGRES_TEST_PASSWORD_ENV].orEmpty()
        )
    }

    private fun createSchema(config: PostgresTestConfig, schemaName: String) {
        require(testSchemaPattern.matches(schemaName))
        jdbcConnection(config.jdbcUrl, config.username, config.password).use { connection ->
            connection.createStatement().use { statement -> statement.execute("CREATE SCHEMA $schemaName") }
        }
    }

    private fun dropSchema(config: PostgresTestConfig, schemaName: String) {
        require(testSchemaPattern.matches(schemaName))
        jdbcConnection(config.jdbcUrl, config.username, config.password).use { connection ->
            connection.createStatement().use { statement -> statement.execute("DROP SCHEMA $schemaName CASCADE") }
        }
    }

    private fun schemaUrl(jdbcUrl: String, schemaName: String): String {
        require(jdbcUrl.startsWith("jdbc:postgresql:")) { "$POSTGRES_TEST_URL_ENV must use PostgreSQL." }
        require(!Regex("[?&]currentSchema=", RegexOption.IGNORE_CASE).containsMatchIn(jdbcUrl)) {
            "$POSTGRES_TEST_URL_ENV must not set currentSchema."
        }
        val separator = if ('?' in jdbcUrl) '&' else '?'
        return "$jdbcUrl${separator}currentSchema=$schemaName"
    }

    private fun startWorker(
        index: Int,
        config: PostgresTestConfig,
        classpath: String,
        synchronizationDirectory: Path,
        startPath: Path
    ): MigrationWorkerProcess {
        val readyPath = synchronizationDirectory.resolve("worker-$index.ready")
        val failurePath = synchronizationDirectory.resolve("worker-$index.failure")
        val builder = ProcessBuilder(
            javaExecutable().toString(),
            "-cp",
            classpath,
            PostgresMigrationWorker::class.java.name
        )
        builder.environment().apply {
            this[POSTGRES_WORKER_JDBC_URL_ENV] = config.jdbcUrl
            this[POSTGRES_WORKER_USERNAME_ENV] = config.username
            this[POSTGRES_WORKER_PASSWORD_ENV] = config.password
            this[POSTGRES_WORKER_READY_PATH_ENV] = readyPath.toString()
            this[POSTGRES_WORKER_START_PATH_ENV] = startPath.toString()
            this[POSTGRES_WORKER_FAILURE_PATH_ENV] = failurePath.toString()
        }
        builder.redirectOutput(ProcessBuilder.Redirect.DISCARD)
        builder.redirectError(ProcessBuilder.Redirect.DISCARD)
        return MigrationWorkerProcess(builder.start(), readyPath, failurePath)
    }

    private fun awaitReady(worker: MigrationWorkerProcess) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(READY_TIMEOUT_SECONDS)
        while (!Files.exists(worker.readyPath)) {
            check(worker.process.isAlive) { "PostgreSQL migration worker stopped before ready: ${worker.failure()}" }
            check(System.nanoTime() < deadline) { "PostgreSQL migration worker did not become ready." }
            Thread.sleep(25)
        }
    }

    private fun awaitSuccess(index: Int, worker: MigrationWorkerProcess) {
        val completed = worker.process.waitFor(WORKER_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        assertTrue("PostgreSQL migration worker $index timed out.", completed)
        assertEquals("PostgreSQL migration worker $index failed: ${worker.failure()}", 0, worker.process.exitValue())
    }

    private fun stopWorker(worker: MigrationWorkerProcess) {
        if (!worker.process.isAlive) return
        worker.process.destroyForcibly()
        worker.process.waitFor(5, TimeUnit.SECONDS)
    }

    private fun cleanupSynchronizationDirectory(
        directory: Path,
        workers: List<MigrationWorkerProcess>,
        startPath: Path
    ) {
        Files.deleteIfExists(startPath)
        workers.forEach { worker ->
            Files.deleteIfExists(worker.readyPath)
            Files.deleteIfExists(worker.failurePath)
        }
        Files.deleteIfExists(directory)
    }

    private fun workerClasspath(): String {
        return listOf(
            PostgresMigrationWorker::class.java,
            Migration::class.java,
            org.postgresql.Driver::class.java,
            kotlin.Unit::class.java
        ).map { type ->
            Path.of(requireNotNull(type.protectionDomain.codeSource).location.toURI()).toString()
        }.distinct().joinToString(File.pathSeparator)
    }

    private fun javaExecutable(): Path {
        val executable = if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java"
        return Path.of(System.getProperty("java.home"), "bin", executable)
    }
}

private data class PostgresTestConfig(
    val jdbcUrl: String,
    val username: String,
    val password: String
)

private data class MigrationWorkerProcess(
    val process: Process,
    val readyPath: Path,
    val failurePath: Path
) {
    fun failure(): String {
        return failurePath.takeIf(Files::isRegularFile)?.let(Files::readString) ?: "no sanitized detail"
    }
}
