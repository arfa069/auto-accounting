package com.autoaccounting.backend

import java.sql.Connection
import java.sql.DriverManager

data class Migration(
    val version: Int,
    val statements: List<String>
)

fun runMigrations(
    jdbcUrl: String,
    username: String = "",
    password: String = "",
    migrations: List<Migration>
) {
    jdbcConnection(jdbcUrl, username, password).use { connection ->
        connection.createStatement().use { statement ->
            statement.execute(
                """
                CREATE TABLE IF NOT EXISTS schema_migrations (
                    version INTEGER PRIMARY KEY,
                    applied_at_millis BIGINT NOT NULL
                )
                """.trimIndent()
            )
        }
        val applied = connection.createStatement().use { statement ->
            statement.executeQuery("SELECT version FROM schema_migrations").use { rs ->
                buildSet {
                    while (rs.next()) add(rs.getInt("version"))
                }
            }
        }
        migrations.filter { it.version !in applied }.forEach { migration ->
            connection.autoCommit = false
            try {
                connection.createStatement().use { statement ->
                    migration.statements.forEach(statement::execute)
                }
                connection.prepareStatement(
                    "INSERT INTO schema_migrations (version, applied_at_millis) VALUES (?, ?)"
                ).use { statement ->
                    statement.setInt(1, migration.version)
                    statement.setLong(2, System.currentTimeMillis())
                    statement.executeUpdate()
                }
                connection.commit()
            } catch (error: java.sql.SQLException) {
                connection.rollback()
                throw error
            } finally {
                connection.autoCommit = true
            }
        }
    }
}

fun jdbcConnection(
    jdbcUrl: String,
    username: String = "",
    password: String = ""
): Connection {
    return if (username.isBlank()) {
        DriverManager.getConnection(jdbcUrl)
    } else {
        DriverManager.getConnection(jdbcUrl, username, password)
    }
}
