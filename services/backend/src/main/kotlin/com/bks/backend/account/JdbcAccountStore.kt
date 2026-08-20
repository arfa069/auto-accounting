package com.bks.backend.account

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
            val url = env["BKS_DATABASE_URL"].orEmpty()
            if (url.isBlank()) return null
            return Config(
                jdbcUrl = url,
                username = env["BKS_DATABASE_USER"].orEmpty(),
                password = env["BKS_DATABASE_PASSWORD"].orEmpty()
            )
        }
    }
}
