package com.autoaccounting.backend.account

import com.autoaccounting.backend.jdbcConnection
import com.autoaccounting.backend.runBackendMigrations
import java.sql.Connection

internal class JdbcAccountStoreContext(
    private val jdbcUrl: String,
    private val username: String,
    private val password: String
) {
    init {
        runBackendMigrations(jdbcUrl, username, password)
    }

    fun connection(): Connection = jdbcConnection(jdbcUrl, username, password)
}
