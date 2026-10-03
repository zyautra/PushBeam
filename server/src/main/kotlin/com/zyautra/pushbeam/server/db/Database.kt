package com.zyautra.pushbeam.server.db

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.util.concurrent.Executors

/**
 * SQLite 접근.
 * 쓰기는 하나의 connection, 하나의 thread에서 순서대로 실행하고 (docs/01 5.7),
 * 읽기는 요청마다 짧게 connection을 연다. WAL 모드라 읽기가 쓰기를 막지 않는다.
 */
class Database(private val file: Path) : AutoCloseable {
    private val writeExecutor = Executors.newSingleThreadExecutor { Thread(it, "db-writer") }
    private val writeDispatcher = writeExecutor.asCoroutineDispatcher()
    private val writer: Connection

    init {
        file.parent?.let(Files::createDirectories)
        writer = open()
        Migrations.apply(writer)
    }

    /** 하나의 transaction으로 실행한다. 예외가 나면 rollback한다. */
    suspend fun <T> write(block: (Connection) -> T): T = withContext(writeDispatcher) {
        writer.inTransaction(block)
    }

    suspend fun <T> read(block: (Connection) -> T): T = withContext(Dispatchers.IO) {
        open().use(block)
    }

    private fun open(): Connection =
        DriverManager.getConnection("jdbc:sqlite:$file").apply {
            createStatement().use { s ->
                s.execute("PRAGMA journal_mode = WAL")
                s.execute("PRAGMA synchronous = FULL")
                s.execute("PRAGMA foreign_keys = ON")
                s.execute("PRAGMA busy_timeout = 5000")
            }
        }

    override fun close() {
        writeExecutor.submit { writer.close() }.get()
        writeExecutor.shutdown()
    }
}

fun <T> Connection.inTransaction(block: (Connection) -> T): T {
    autoCommit = false
    try {
        val result = block(this)
        commit()
        return result
    } catch (e: Throwable) {
        rollback()
        throw e
    } finally {
        autoCommit = true
    }
}
