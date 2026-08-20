package com.bks.feature.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpLedgerSyncRepositoryTest {
    @Test
    fun httpsIsAvailableWithoutTestOverride() {
        val repository = HttpLedgerSyncRepository("https://sync.example.com", allowHttp = false)

        assertTrue(repository.available)
        assertFalse(repository.insecureHttpTestMode)
    }

    @Test
    fun httpIsDisabledByDefault() {
        val repository = HttpLedgerSyncRepository("http://192.168.1.3:8080", allowHttp = false)

        assertFalse(repository.available)
        assertFalse(repository.insecureHttpTestMode)
    }

    @Test
    fun explicitHttpOverrideAllowsOnlyLoopbackAndRfc1918Hosts() {
        val allowed = listOf(
            "http://localhost:8080",
            "http://127.0.0.1:8080",
            "http://[::1]:8080",
            "http://10.0.0.2:8080",
            "http://172.16.0.2:8080",
            "http://172.31.255.254:8080",
            "http://192.168.1.3:8080"
        )
        val denied = listOf(
            "http://example.com",
            "http://10.example.com",
            "http://127.evil.test",
            "http://192.168.example.com",
            "http://192.168.1.256:8080",
            "http://192.168.1:8080",
            "http://192.168.x.3:8080",
            "http://192.168.1.3.4:8080",
            "http://172.15.0.2:8080",
            "http://172.32.0.2:8080",
            "ftp://192.168.1.3"
        )

        allowed.forEach { url ->
            val repository = HttpLedgerSyncRepository(url, allowHttp = true)
            assertTrue(url, repository.available)
            assertTrue(url, repository.insecureHttpTestMode)
        }
        denied.forEach { url ->
            val repository = HttpLedgerSyncRepository(url, allowHttp = true)
            assertFalse(url, repository.available)
            assertFalse(url, repository.insecureHttpTestMode)
        }
    }
}
