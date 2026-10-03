package io.github.zyautra.pushbeam.server.db

import java.sql.Connection

/**
 * 번호 붙은 SQL 파일을 순서대로 한 번씩 적용한다.
 * 이미 적용한 파일은 고치지 않고, 바꿀 것이 있으면 새 파일을 추가한다.
 */
object Migrations {
    private val files = listOf(
        "V1__init.sql",
        "V2__delivery_display.sql",
    )

    fun apply(conn: Connection) {
        conn.createStatement().use {
            it.execute("CREATE TABLE IF NOT EXISTS schema_version (version INTEGER PRIMARY KEY, applied_at INTEGER NOT NULL)")
        }
        val current = conn.createStatement().use { s ->
            s.executeQuery("SELECT COALESCE(MAX(version), 0) FROM schema_version").use { it.next(); it.getInt(1) }
        }
        files.forEachIndexed { index, name ->
            val version = index + 1
            if (version <= current) return@forEachIndexed
            val sql = Migrations::class.java.getResource("/db/migration/$name")?.readText()
                ?: error("migration not found: $name")
            conn.inTransaction { c ->
                c.createStatement().use { s ->
                    sql.split(";").map(String::trim).filter(String::isNotEmpty).forEach(s::execute)
                }
                c.prepareStatement("INSERT INTO schema_version (version, applied_at) VALUES (?, ?)").use { ps ->
                    ps.setInt(1, version)
                    ps.setLong(2, System.currentTimeMillis())
                    ps.executeUpdate()
                }
            }
        }
    }

    val latestVersion: Int get() = files.size
}
