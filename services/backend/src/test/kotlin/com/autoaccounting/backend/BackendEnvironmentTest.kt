package com.autoaccounting.backend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class BackendEnvironmentTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun loadsBackendDotEnvAndLetsProcessEnvironmentOverrideIt() {
        val repositoryRoot = temporaryFolder.root
        val backendDirectory = File(repositoryRoot, "services/backend").apply { mkdirs() }
        File(backendDirectory, ".env").writeText(
            """
            # Local backend configuration
            AUTO_ACCOUNTING_DATABASE_URL=jdbc:postgresql://localhost/local
            AUTO_ACCOUNTING_DATABASE_USER='local user'
            AUTO_ACCOUNTING_DATABASE_PASSWORD="local password"
            AUTO_ACCOUNTING_SMS_API_KEY=
            """.trimIndent()
        )

        val environment = BackendEnvironment.load(
            processEnvironment = mapOf(
                "AUTO_ACCOUNTING_DATABASE_URL" to "jdbc:postgresql://production/database"
            ),
            workingDirectory = repositoryRoot.toPath()
        )

        assertEquals(
            "jdbc:postgresql://production/database",
            environment["AUTO_ACCOUNTING_DATABASE_URL"]
        )
        assertEquals("local user", environment["AUTO_ACCOUNTING_DATABASE_USER"])
        assertEquals("local password", environment["AUTO_ACCOUNTING_DATABASE_PASSWORD"])
        assertEquals("", environment["AUTO_ACCOUNTING_SMS_API_KEY"])
    }

    @Test
    fun loadsDotEnvWhenBackendDirectoryIsTheWorkingDirectory() {
        val servicesDirectory = temporaryFolder.newFolder("services")
        val backendDirectory = File(servicesDirectory, "backend").apply { mkdirs() }
        File(backendDirectory, ".env").writeText("AUTO_ACCOUNTING_SMS_PROVIDER=webhook")

        val environment = BackendEnvironment.load(
            processEnvironment = emptyMap(),
            workingDirectory = backendDirectory.toPath()
        )

        assertEquals("webhook", environment["AUTO_ACCOUNTING_SMS_PROVIDER"])
    }

    @Test
    fun rejectsMalformedEntryWithoutIncludingItsContentInTheError() {
        val backendDirectory = File(temporaryFolder.root, "services/backend").apply { mkdirs() }
        File(backendDirectory, ".env").writeText("malformed-secret-value")

        val error = assertThrows(IllegalStateException::class.java) {
            BackendEnvironment.load(emptyMap(), temporaryFolder.root.toPath())
        }

        assertEquals("Invalid .env entry at .env:1.", error.message)
        assertFalse(error.message.orEmpty().contains("malformed-secret-value"))
    }
}
