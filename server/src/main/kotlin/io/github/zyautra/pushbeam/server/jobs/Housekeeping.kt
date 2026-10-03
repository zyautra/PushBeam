package io.github.zyautra.pushbeam.server.jobs

import io.github.zyautra.pushbeam.server.db.Database
import io.github.zyautra.pushbeam.server.db.update
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

/** 90일 지난 기록 삭제와 하루 한 번 DB 백업 (docs/02 6절, docs/05 6절). */
class Housekeeping(
    private val db: Database,
    private val backupDir: Path,
    private val clock: Clock,
    private val retention: Duration = Duration.ofDays(90),
    private val keepBackups: Int = 14,
) {
    private val log = LoggerFactory.getLogger(Housekeeping::class.java)

    @Volatile var lastBackupAt: Instant? = null; private set

    fun start(scope: CoroutineScope): Job = scope.launch {
        while (isActive) {
            runOnce()
            delay(Duration.ofHours(24).toMillis())
        }
    }

    suspend fun runOnce() {
        try {
            val deleted = deleteOldMessages()
            if (deleted > 0) log.info("retention_completed deletedMessages={}", deleted)
        } catch (e: Exception) {
            log.error("retention_failed", e)
        }
        try {
            backup()
        } catch (e: Exception) {
            log.error("backup_failed", e)
        }
    }

    suspend fun deleteOldMessages(): Int {
        val cutoff = clock.instant().minus(retention)
        var total = 0
        while (true) {
            // 오래 잠그지 않도록 나눠서 지운다. 전송과 결과 기록은 ON DELETE CASCADE로 함께 지워진다.
            val n = db.write { c ->
                c.update("DELETE FROM messages WHERE id IN (SELECT id FROM messages WHERE created_at < ? LIMIT 500)", cutoff)
            }
            total += n
            if (n == 0) return total
        }
    }

    suspend fun backup(): Path {
        Files.createDirectories(backupDir)
        val day = DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC).format(clock.instant())
        val target = backupDir.resolve("pushbeam-$day.db")
        Files.deleteIfExists(target)
        db.read { c -> c.createStatement().use { it.execute("VACUUM INTO '${target.toAbsolutePath().toString().replace("'", "''")}'") } }
        backupDir.listDirectoryEntries("pushbeam-*.db").sortedBy { it.name }.dropLast(keepBackups).forEach(Files::delete)
        lastBackupAt = clock.instant()
        log.info("backup_completed file={}", target.name)
        return target
    }
}
