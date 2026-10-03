package io.github.zyautra.pushbeam.server.service

import io.github.zyautra.pushbeam.server.Ids
import io.github.zyautra.pushbeam.server.api.ApiException
import io.github.zyautra.pushbeam.server.auth.Principal
import io.github.zyautra.pushbeam.server.auth.SENDER_KEY_PREFIX
import io.github.zyautra.pushbeam.server.auth.Secrets
import io.github.zyautra.pushbeam.server.db.Database
import io.github.zyautra.pushbeam.server.db.instant
import io.github.zyautra.pushbeam.server.db.instantOrNull
import io.github.zyautra.pushbeam.server.db.query
import io.github.zyautra.pushbeam.server.db.queryOne
import io.github.zyautra.pushbeam.server.db.str
import io.github.zyautra.pushbeam.server.db.update
import io.github.zyautra.pushbeam.shared.ErrorCodes
import io.github.zyautra.pushbeam.shared.api.CreateSenderRequest
import io.github.zyautra.pushbeam.shared.api.CreateSenderResponse
import io.github.zyautra.pushbeam.shared.api.SenderSummary
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json
import java.sql.Connection
import java.sql.ResultSet
import java.time.Clock
import java.time.Duration

class SenderService(private val db: Database, private val clock: Clock) {

    suspend fun create(req: CreateSenderRequest): CreateSenderResponse {
        if (req.name.isBlank()) throw ApiException.invalid("name is required")
        val key = Secrets.newToken(SENDER_KEY_PREFIX)
        val id = Ids.sender()
        db.write { c ->
            if (c.queryOne("SELECT 1 FROM senders WHERE name = ?", req.name) { 1 } != null) {
                throw ApiException(HttpStatusCode.Conflict, ErrorCodes.ALREADY_EXISTS, "Sender already exists: ${req.name}")
            }
            c.requireChannels(req.allowedChannels)
            c.update(
                "INSERT INTO senders (id, name, key_hash, allowed_channels, created_at) VALUES (?, ?, ?, ?, ?)",
                id, req.name, Secrets.sha256Hex(key), Json.encodeToString(req.allowedChannels.distinct()), clock.instant(),
            )
        }
        return CreateSenderResponse(get(id), key)
    }

    suspend fun updateChannels(id: String, channels: List<String>): SenderSummary {
        db.write { c ->
            c.requireChannels(channels)
            if (c.update("UPDATE senders SET allowed_channels = ? WHERE id = ?", Json.encodeToString(channels.distinct()), id) == 0) {
                throw ApiException.notFound("Sender not found: $id")
            }
        }
        return get(id)
    }

    suspend fun revoke(id: String): SenderSummary {
        db.write { c ->
            if (c.update("UPDATE senders SET revoked_at = COALESCE(revoked_at, ?) WHERE id = ?", clock.instant(), id) == 0) {
                throw ApiException.notFound("Sender not found: $id")
            }
        }
        return get(id)
    }

    suspend fun list(): List<SenderSummary> = db.read { c -> c.query("SELECT * FROM senders ORDER BY name", map = ::row) }

    suspend fun get(id: String): SenderSummary = db.read { c ->
        c.queryOne("SELECT * FROM senders WHERE id = ?", id, map = ::row) ?: throw ApiException.notFound("Sender not found: $id")
    }

    /** Sender Key로 Sender를 찾는다. 없거나 폐기됐으면 null. */
    suspend fun authenticate(key: String): Principal.Sender? {
        if (!key.startsWith(SENDER_KEY_PREFIX) || key.length != SENDER_KEY_PREFIX.length + 40) return null
        val hash = Secrets.sha256Hex(key)
        val found = db.read { c ->
            c.queryOne("SELECT id, allowed_channels, last_used_at FROM senders WHERE key_hash = ? AND revoked_at IS NULL", hash) {
                Triple(it.str("id"), Json.decodeFromString<List<String>>(it.str("allowed_channels")).toSet(), it.instantOrNull("last_used_at"))
            }
        } ?: return null
        val now = clock.instant()
        if (found.third == null || Duration.between(found.third, now).toMinutes() >= 1) {
            db.write { it.update("UPDATE senders SET last_used_at = ? WHERE id = ?", now, found.first) }
        }
        return Principal.Sender(found.first, found.second)
    }

    private fun Connection.requireChannels(channels: List<String>) {
        if (channels.isEmpty()) throw ApiException.invalid("allowedChannels must not be empty")
        val missing = channels.filter { queryOne("SELECT 1 FROM channels WHERE slug = ?", it) { 1 } == null }
        if (missing.isNotEmpty()) throw ApiException.invalid("Unknown channels: ${missing.joinToString()}")
    }

    private fun row(rs: ResultSet) = SenderSummary(
        id = rs.str("id"), name = rs.str("name"),
        allowedChannels = Json.decodeFromString(rs.str("allowed_channels")),
        createdAt = rs.instant("created_at").toString(),
        lastUsedAt = rs.instantOrNull("last_used_at")?.toString(),
        revokedAt = rs.instantOrNull("revoked_at")?.toString(),
    )
}
