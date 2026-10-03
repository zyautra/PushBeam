package com.zyautra.pushbeam.server.db

import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DatabaseTest {
    @Test
    fun `Migration을 한 번만 적용하고 다시 열어도 그대로다`() = runBlocking {
        val file = Files.createTempDirectory("pushbeam").resolve("pushbeam.db")
        Database(file).use { db ->
            assertEquals(Migrations.latestVersion, db.read { c -> c.version() })
        }
        Database(file).use { db ->
            assertEquals(Migrations.latestVersion, db.read { c -> c.version() })
            val tables = db.read { c ->
                c.createStatement().executeQuery("SELECT name FROM sqlite_master WHERE type = 'table' ORDER BY name")
                    .use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
            }
            assertEquals(
                listOf("channels", "deliveries", "devices", "members", "message_recipients",
                    "messages", "quiet_hours", "schema_version", "senders", "subscriptions"),
                tables,
            )
        }
    }

    @Test
    fun `transaction 중 예외가 나면 rollback한다`() = runBlocking {
        val file = Files.createTempDirectory("pushbeam").resolve("pushbeam.db")
        Database(file).use { db ->
            assertFailsWith<IllegalStateException> {
                db.write { c ->
                    c.createStatement().executeUpdate(
                        "INSERT INTO members (id, email, status, allowed_at) VALUES ('mbr_1', 'a@b.c', 'INVITED', 0)"
                    )
                    error("boom")
                }
            }
            assertEquals(0, db.read { c -> c.createStatement().executeQuery("SELECT COUNT(*) FROM members").use { it.next(); it.getInt(1) } })
        }
    }

    private fun java.sql.Connection.version() =
        createStatement().executeQuery("SELECT MAX(version) FROM schema_version").use { it.next(); it.getInt(1) }
}
