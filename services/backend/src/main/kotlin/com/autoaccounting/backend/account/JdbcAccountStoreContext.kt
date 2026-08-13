package com.autoaccounting.backend.account

import com.autoaccounting.backend.runBackendMigrations
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.sql.Connection

internal class JdbcAccountStoreContext(
    private val jdbcUrl: String,
    private val username: String,
    private val password: String
) {
    private val dataSource: HikariDataSource

    init {
        runBackendMigrations(jdbcUrl, username, password)
        dataSource = HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = this@JdbcAccountStoreContext.jdbcUrl
                this.username = this@JdbcAccountStoreContext.username
                this.password = this@JdbcAccountStoreContext.password
                maximumPoolSize = 10
                minimumIdle = 1
                poolName = "account-store"
            }
        )
    }

    fun connection(): Connection = dataSource.connection
}
