package io.github.zyautra.pushbeam.server

import io.github.zyautra.pushbeam.server.db.Database
import io.github.zyautra.pushbeam.server.db.count
import io.github.zyautra.pushbeam.server.db.instantOrNull
import io.github.zyautra.pushbeam.server.db.query
import io.github.zyautra.pushbeam.server.db.queryOne
import io.github.zyautra.pushbeam.server.jobs.DistributionSync
import io.github.zyautra.pushbeam.server.jobs.Housekeeping
import io.github.zyautra.pushbeam.shared.api.ServerStatus

/** Admin status (docs/05 4절). */
class StatusService(
    private val db: Database,
    private val state: ServerState,
    private val distribution: DistributionSync?,
    private val housekeeping: Housekeeping,
) {
    suspend fun status(): ServerStatus = db.read { c ->
        ServerStatus(
            ready = state.ready.get(),
            members = c.query("SELECT status, COUNT(*) AS n FROM members GROUP BY status") { it.getString("status") to it.getInt("n") }.toMap(),
            activeDevices = c.count("SELECT COUNT(*) FROM devices WHERE active = 1"),
            pendingDeliveries = c.count("SELECT COUNT(*) FROM deliveries WHERE status = 'PENDING'"),
            oldestPendingAt = c.queryOne(
                "SELECT MIN(m.created_at) AS t FROM deliveries d JOIN messages m ON m.id = d.message_id WHERE d.status = 'PENDING'",
            ) { it.instantOrNull("t") }?.toString(),
            lastDistributionSyncAt = distribution?.lastSuccessAt?.toString(),
            lastDistributionError = distribution?.lastError ?: if (distribution == null) "disabled" else null,
            lastBackupAt = housekeeping.lastBackupAt?.toString(),
        )
    }
}
