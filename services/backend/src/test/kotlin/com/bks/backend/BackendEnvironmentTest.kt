package com.bks.backend

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
            BKS_DATABASE_URL=jdbc:postgresql://localhost/local
            BKS_DATABASE_USER='local user'
            BKS_DATABASE_PASSWORD="local password"
            BKS_SMS_API_KEY=
            """.trimIndent()
        )

        val environment = BackendEnvironment.load(
            processEnvironment = mapOf(
                "BKS_DATABASE_URL" to "jdbc:postgresql://production/database"
            ),
            workingDirectory = repositoryRoot.toPath()
        )

        assertEquals(
            "jdbc:postgresql://production/database",
            environment["BKS_DATABASE_URL"]
        )
        assertEquals("local user", environment["BKS_DATABASE_USER"])
        assertEquals("local password", environment["BKS_DATABASE_PASSWORD"])
        assertEquals("", environment["BKS_SMS_API_KEY"])
    }

    @Test
    fun loadsDotEnvWhenBackendDirectoryIsTheWorkingDirectory() {
        val servicesDirectory = temporaryFolder.newFolder("services")
        val backendDirectory = File(servicesDirectory, "backend").apply { mkdirs() }
        File(backendDirectory, ".env").writeText("BKS_SMS_PROVIDER=webhook")

        val environment = BackendEnvironment.load(
            processEnvironment = emptyMap(),
            workingDirectory = backendDirectory.toPath()
        )

        assertEquals("webhook", environment["BKS_SMS_PROVIDER"])
    }

    @Test
    fun explicitDotEnvPathOverridesRepositoryLookup() {
        val repositoryBackend = File(temporaryFolder.root, "services/backend").apply { mkdirs() }
        File(repositoryBackend, ".env").writeText("BKS_PORT=8080")
        val explicitEnvironment = temporaryFolder.newFile("termux.env").apply {
            writeText("BKS_PORT=18080")
        }

        val environment = BackendEnvironment.load(
            processEnvironment = mapOf(BACKEND_ENV_FILE_KEY to explicitEnvironment.absolutePath),
            workingDirectory = temporaryFolder.root.toPath()
        )

        assertEquals("18080", environment[BACKEND_PORT_KEY])
    }

    @Test
    fun explicitDotEnvPathMustPointToARegularFile() {
        val error = assertThrows(IllegalStateException::class.java) {
            BackendEnvironment.load(
                processEnvironment = mapOf(
                    BACKEND_ENV_FILE_KEY to File(temporaryFolder.root, "missing.env").absolutePath
                ),
                workingDirectory = temporaryFolder.root.toPath()
            )
        }

        assertEquals(
            "$BACKEND_ENV_FILE_KEY must point to a regular file.",
            error.message
        )
    }

    @Test
    fun serverConfigDefaultsAndAcceptsLoopbackProxy() {
        assertEquals(
            BackendServerConfig("0.0.0.0", 8080, false),
            BackendServerConfig.fromEnvironment(emptyMap())
        )
        assertEquals(
            BackendServerConfig("127.0.0.1", 18080, true),
            BackendServerConfig.fromEnvironment(
                mapOf(
                    BACKEND_HOST_KEY to "127.0.0.1",
                    BACKEND_PORT_KEY to "18080",
                    BACKEND_TRUST_PROXY_HEADERS_KEY to "true"
                )
            )
        )
    }

    @Test
    fun serverConfigRejectsInvalidPortAndPublicProxyTrust() {
        val invalidPort = assertThrows(IllegalStateException::class.java) {
            BackendServerConfig.fromEnvironment(mapOf(BACKEND_PORT_KEY to "70000"))
        }
        assertEquals(
            "$BACKEND_PORT_KEY must be an integer between 1 and 65535.",
            invalidPort.message
        )

        val unsafeProxy = assertThrows(IllegalStateException::class.java) {
            BackendServerConfig.fromEnvironment(
                mapOf(BACKEND_TRUST_PROXY_HEADERS_KEY to "true")
            )
        }
        assertEquals(
            "$BACKEND_TRUST_PROXY_HEADERS_KEY requires $BACKEND_HOST_KEY to use a loopback host.",
            unsafeProxy.message
        )
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
