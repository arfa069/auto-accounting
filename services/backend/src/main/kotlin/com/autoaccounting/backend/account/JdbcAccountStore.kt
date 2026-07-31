package com.autoaccounting.backend.account

class JdbcAccountStore(
    jdbcUrl: String,
    username: String = "",
    password: String = ""
) : AccountStore by JdbcAccountStoreDelegate(jdbcUrl, username, password) {
    data class Config(
        val jdbcUrl: String,
        val username: String = "",
        val password: String = ""
    )

    companion object {
        fun configFromEnvironment(env: Map<String, String> = System.getenv()): Config? {
            val url = env["AUTO_ACCOUNTING_DATABASE_URL"].orEmpty()
            if (url.isBlank()) return null
            return Config(
                jdbcUrl = url,
                username = env["AUTO_ACCOUNTING_DATABASE_USER"].orEmpty(),
                password = env["AUTO_ACCOUNTING_DATABASE_PASSWORD"].orEmpty()
            )
        }
    }
}
