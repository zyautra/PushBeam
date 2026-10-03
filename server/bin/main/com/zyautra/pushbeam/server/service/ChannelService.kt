package com.zyautra.pushbeam.server.service

import com.zyautra.pushbeam.server.api.ApiException
import com.zyautra.pushbeam.server.auth.Principal
import com.zyautra.pushbeam.server.db.Database
import com.zyautra.pushbeam.server.db.bool
import com.zyautra.pushbeam.server.db.instantOrNull
import com.zyautra.pushbeam.server.db.query
import com.zyautra.pushbeam.server.db.queryOne
import com.zyautra.pushbeam.server.db.str
import com.zyautra.pushbeam.server.db.strOrNull
import com.zyautra.pushbeam.server.db.update
import com.zyautra.pushbeam.shared.ErrorCodes
import com.zyautra.pushbeam.shared.Severity
import com.zyautra.pushbeam.shared.Validation
import com.zyautra.pushbeam.shared.api.ChannelSummary
import com.zyautra.pushbeam.shared.api.CreateChannelRequest
import com.zyautra.pushbeam.shared.api.MemberChannel
import com.zyautra.pushbeam.shared.api.SubscriptionPatch
import com.zyautra.pushbeam.shared.api.UpdateChannelRequest
import io.ktor.http.HttpStatusCode
import java.sql.Connection
import java.time.Clock
import java.time.Duration
import java.time.Instant

class ChannelService(private val db: Database, private val clock: Clock) {

    // ---- Operator ----

    suspend fun create(req: CreateChannelRequest): ChannelSummary {
        if (!Validation.isChannelSlug(req.slug)) throw ApiException.invalid("Invalid channel slug: ${req.slug}")
        if (req.name.isBlank()) throw ApiException.invalid("name is required")
        val now = clock.instant()
        db.write { c ->
            if (c.queryOne("SELECT 1 FROM channels WHERE slug = ?", req.slug) { 1 } != null) {
                throw ApiException(HttpStatusCode.Conflict, ErrorCodes.ALREADY_EXISTS, "Channel already exists: ${req.slug}")
            }
            c.update(
                "INSERT INTO channels (slug, name, description, required, auto_subscribe, created_at) VALUES (?, ?, ?, ?, ?, ?)",
                req.slug, req.name, req.description, req.required, req.autoSubscribe, now,
            )
            if (req.required) c.subscribeAllActive(req.slug, now)
        }
        return summary(req.slug)
    }

    suspend fun update(slug: String, req: UpdateChannelRequest): ChannelSummary {
        val now = clock.instant()
        db.write { c ->
            c.requireChannel(slug)
            req.name?.let { if (it.isBlank()) throw ApiException.invalid("name is required"); c.update("UPDATE channels SET name = ? WHERE slug = ?", it, slug) }
            req.description?.let { c.update("UPDATE channels SET description = ? WHERE slug = ?", it, slug) }
            req.autoSubscribe?.let { c.update("UPDATE channels SET auto_subscribe = ? WHERE slug = ?", it, slug) }
            req.required?.let {
                c.update("UPDATE channels SET required = ? WHERE slug = ?", it, slug)
                if (it) c.subscribeAllActive(slug, now)
            }
        }
        return summary(slug)
    }

    suspend fun setArchived(slug: String, archived: Boolean): ChannelSummary {
        db.write { c ->
            c.requireChannel(slug)
            c.update("UPDATE channels SET archived_at = ? WHERE slug = ?", if (archived) clock.instant() else null, slug)
        }
        return summary(slug)
    }

    suspend fun list(): List<ChannelSummary> = db.read { c ->
        c.query(SUMMARY_SQL + " ORDER BY c.slug") { it.toSummary() }
    }

    suspend fun summary(slug: String): ChannelSummary = db.read { c ->
        c.queryOne("$SUMMARY_SQL WHERE c.slug = ?", slug) { it.toSummary() }
            ?: throw ApiException.notFound("Channel not found: $slug")
    }

    /** Operator가 Member를 구독시킨다. */
    suspend fun subscribeMember(slug: String, rawEmail: String) {
        val email = Validation.normalizeEmail(rawEmail)
        db.write { c ->
            c.requireOpenChannel(slug)
            val memberId = c.queryOne("SELECT id FROM members WHERE email = ? AND status = 'ACTIVE'", email) { it.str("id") }
                ?: throw ApiException.notFound("Active member not found: $email")
            c.update("INSERT OR IGNORE INTO subscriptions (member_id, channel, created_at) VALUES (?, ?, ?)", memberId, slug, clock.instant())
        }
    }

    // ---- Member ----

    suspend fun memberChannels(member: Principal.Member): List<MemberChannel> = db.read { c ->
        c.query(
            """SELECT c.slug, c.name, c.description, c.required, s.member_id IS NOT NULL AS subscribed,
                      s.min_severity, s.muted, s.muted_until
               FROM channels c LEFT JOIN subscriptions s ON s.channel = c.slug AND s.member_id = ?
               WHERE c.archived_at IS NULL ORDER BY c.required DESC, c.name""",
            member.id,
        ) { rs ->
            val subscribed = rs.bool("subscribed")
            MemberChannel(
                slug = rs.str("slug"), name = rs.str("name"), description = rs.strOrNull("description"),
                required = rs.bool("required"), subscribed = subscribed,
                minSeverity = if (subscribed) Severity.valueOf(rs.str("min_severity")) else null,
                muted = subscribed && rs.bool("muted"),
                mutedUntil = if (subscribed) rs.instantOrNull("muted_until")?.toString() else null,
            )
        }
    }

    suspend fun subscribe(member: Principal.Member, slug: String, minSeverity: Severity?): MemberChannel {
        db.write { c ->
            c.requireOpenChannel(slug)
            c.update(
                "INSERT OR IGNORE INTO subscriptions (member_id, channel, min_severity, created_at) VALUES (?, ?, ?, ?)",
                member.id, slug, minSeverity ?: Severity.LOW, clock.instant(),
            )
        }
        return memberChannel(member, slug)
    }

    suspend fun unsubscribe(member: Principal.Member, slug: String): MemberChannel {
        db.write { c ->
            val required = c.requireOpenChannel(slug)
            if (required) throw ApiException(HttpStatusCode.Conflict, ErrorCodes.CHANNEL_REQUIRED, "Required channels cannot be unsubscribed")
            c.update("DELETE FROM subscriptions WHERE member_id = ? AND channel = ?", member.id, slug)
        }
        return memberChannel(member, slug)
    }

    suspend fun patch(member: Principal.Member, slug: String, patch: SubscriptionPatch): MemberChannel {
        val now = clock.instant()
        val until = patch.mutedUntil?.let {
            runCatching { Instant.parse(it) }.getOrNull() ?: throw ApiException.invalid("mutedUntil must be ISO 8601")
        }
        if (patch.muted == true && until != null && (until <= now || Duration.between(now, until) > Duration.ofDays(30))) {
            throw ApiException.invalid("mutedUntil must be within the next 30 days")
        }
        db.write { c ->
            c.requireOpenChannel(slug)
            if (c.queryOne("SELECT 1 FROM subscriptions WHERE member_id = ? AND channel = ?", member.id, slug) { 1 } == null) {
                throw ApiException.notFound("Not subscribed: $slug")
            }
            patch.minSeverity?.let {
                c.update("UPDATE subscriptions SET min_severity = ? WHERE member_id = ? AND channel = ?", it, member.id, slug)
            }
            when (patch.muted) {
                true -> c.update("UPDATE subscriptions SET muted = 1, muted_until = ? WHERE member_id = ? AND channel = ?", until, member.id, slug)
                false -> c.update("UPDATE subscriptions SET muted = 0, muted_until = NULL WHERE member_id = ? AND channel = ?", member.id, slug)
                null -> {}
            }
        }
        return memberChannel(member, slug)
    }

    private suspend fun memberChannel(member: Principal.Member, slug: String) =
        memberChannels(member).first { it.slug == slug }

    // ---- helpers ----

    private fun Connection.subscribeAllActive(slug: String, now: Instant) {
        update(
            "INSERT OR IGNORE INTO subscriptions (member_id, channel, created_at) SELECT id, ?, ? FROM members WHERE status = 'ACTIVE'",
            slug, now,
        )
    }

    private fun Connection.requireChannel(slug: String) {
        queryOne("SELECT 1 FROM channels WHERE slug = ?", slug) { 1 } ?: throw ApiException.notFound("Channel not found: $slug")
    }

    /** 보관되지 않은 채널인지 확인하고 필수 여부를 돌려준다. */
    private fun Connection.requireOpenChannel(slug: String): Boolean =
        queryOne("SELECT required FROM channels WHERE slug = ? AND archived_at IS NULL", slug) { it.bool("required") }
            ?: throw ApiException.notFound("Channel not found: $slug")

    private fun java.sql.ResultSet.toSummary() = ChannelSummary(
        slug = str("slug"), name = str("name"), description = strOrNull("description"),
        required = bool("required"), autoSubscribe = bool("auto_subscribe"),
        archived = instantOrNull("archived_at") != null, subscribers = getInt("subscribers"),
    )

    private companion object {
        const val SUMMARY_SQL = """SELECT c.*, (SELECT COUNT(*) FROM subscriptions s WHERE s.channel = c.slug) AS subscribers
                                   FROM channels c"""
    }
}
