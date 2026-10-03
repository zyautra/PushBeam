package com.zyautra.pushbeam.server.service

import com.zyautra.pushbeam.server.Ids
import com.zyautra.pushbeam.server.api.ApiException
import com.zyautra.pushbeam.server.auth.Principal
import com.zyautra.pushbeam.server.auth.forbidden
import com.zyautra.pushbeam.server.db.Database
import com.zyautra.pushbeam.server.db.bool
import com.zyautra.pushbeam.server.db.instant
import com.zyautra.pushbeam.server.db.instantOrNull
import com.zyautra.pushbeam.server.db.intOrNull
import com.zyautra.pushbeam.server.db.query
import com.zyautra.pushbeam.server.db.queryOne
import com.zyautra.pushbeam.server.db.str
import com.zyautra.pushbeam.server.db.strOrNull
import com.zyautra.pushbeam.server.db.update
import com.zyautra.pushbeam.shared.ErrorCodes
import com.zyautra.pushbeam.shared.Severity
import com.zyautra.pushbeam.shared.Validation
import com.zyautra.pushbeam.shared.api.DeviceSummary
import com.zyautra.pushbeam.shared.api.MeResponse
import com.zyautra.pushbeam.shared.api.MemberDetail
import com.zyautra.pushbeam.shared.api.MemberSummary
import com.zyautra.pushbeam.shared.api.QuietHoursDto
import com.zyautra.pushbeam.shared.api.RegisterDeviceRequest
import com.zyautra.pushbeam.shared.api.SubscriptionSummary
import java.sql.Connection
import java.sql.ResultSet
import java.time.Clock
import java.time.Duration
import java.time.LocalTime
import java.time.ZoneId

enum class MemberStatus { INVITED, ACTIVE, REVOKED }

class MemberService(
    private val db: Database,
    private val clock: Clock,
    private val onMembershipChanged: () -> Unit = {},
) {
    // ---- Operator ----

    suspend fun allow(rawEmail: String, displayName: String?): MemberSummary {
        val email = Validation.normalizeEmail(rawEmail)
        if (!Validation.isEmail(email)) throw ApiException.invalid("Invalid email: $rawEmail")
        val now = clock.instant()
        val member = db.write { c ->
            val existing = c.findByEmail(email)
            when (existing?.status) {
                null -> c.update(
                    "INSERT INTO members (id, email, display_name, status, allowed_at) VALUES (?, ?, ?, ?, ?)",
                    Ids.member(), email, displayName, MemberStatus.INVITED, now,
                )
                MemberStatus.REVOKED -> c.update(
                    """UPDATE members SET status = ?, allowed_at = ?, activated_at = NULL, revoked_at = NULL,
                       display_name = COALESCE(?, display_name) WHERE id = ?""",
                    MemberStatus.INVITED, now, displayName, existing.id,
                )
                else -> if (displayName != null) c.update("UPDATE members SET display_name = ? WHERE id = ?", displayName, existing.id)
            }
            c.findByEmail(email)!!
        }
        onMembershipChanged()
        return member.toSummary()
    }

    /** docs/01 5.5: 기기 비활성화, 대기 전송 취소, 구독 삭제를 하나의 transaction으로. */
    suspend fun revoke(rawEmail: String): MemberSummary {
        val email = Validation.normalizeEmail(rawEmail)
        val now = clock.instant()
        val member = db.write { c ->
            val m = c.findByEmail(email) ?: throw ApiException.notFound("Member not found: $email")
            if (m.status != MemberStatus.REVOKED) {
                c.update("UPDATE members SET status = ?, revoked_at = ? WHERE id = ?", MemberStatus.REVOKED, now, m.id)
                c.update(
                    """UPDATE deliveries SET status = 'CANCELLED', last_error = 'member revoked', updated_at = ?
                       WHERE status = 'PENDING' AND device_id IN (SELECT id FROM devices WHERE member_id = ?)""",
                    now, m.id,
                )
                c.update("UPDATE devices SET active = 0 WHERE member_id = ?", m.id)
                c.update("DELETE FROM subscriptions WHERE member_id = ?", m.id)
            }
            c.findByEmail(email)!!
        }
        onMembershipChanged()
        return member.toSummary()
    }

    suspend fun list(status: MemberStatus?): List<MemberSummary> = db.read { c ->
        if (status == null) c.query("SELECT * FROM members ORDER BY email", map = ::memberRow)
        else c.query("SELECT * FROM members WHERE status = ? ORDER BY email", status, map = ::memberRow)
    }.map { it.toSummary() }

    suspend fun detail(rawEmail: String): MemberDetail = db.read { c ->
        val m = c.findByEmail(Validation.normalizeEmail(rawEmail)) ?: throw ApiException.notFound("Member not found: $rawEmail")
        val devices = c.query("SELECT * FROM devices WHERE member_id = ? ORDER BY registered_at", m.id) {
            DeviceSummary(
                id = it.str("id"), active = it.bool("active"), model = it.strOrNull("model"),
                appVersion = it.intOrNull("app_version"), registeredAt = it.instant("registered_at").toString(),
                lastSeenAt = it.instant("last_seen_at").toString(),
            )
        }
        val subs = c.query("SELECT * FROM subscriptions WHERE member_id = ? ORDER BY channel", m.id) {
            SubscriptionSummary(
                channel = it.str("channel"), minSeverity = Severity.valueOf(it.str("min_severity")),
                muted = it.bool("muted"), mutedUntil = it.instantOrNull("muted_until")?.toString(),
            )
        }
        MemberDetail(m.toSummary(), devices, subs)
    }

    /** 허용된 이메일(ACTIVE, INVITED)은 배포 그룹에 있어야 한다. */
    suspend fun allowedEmails(): Set<String> = db.read { c ->
        c.query("SELECT email FROM members WHERE status IN ('INVITED', 'ACTIVE')") { it.str("email") }.toSet()
    }

    // ---- Member ----

    /** 인증된 Google 계정을 허용 목록과 대조한다. 첫 로그인이면 활성화하고 필수·자동 구독을 만든다. */
    suspend fun authenticate(rawEmail: String): Principal.Member {
        val email = Validation.normalizeEmail(rawEmail)
        val now = clock.instant()
        val existing = db.read { it.findByEmail(email) }
            ?: throw forbidden(ErrorCodes.NOT_ALLOWLISTED, "Email is not on the allowlist")
        when (existing.status) {
            MemberStatus.REVOKED -> throw forbidden(ErrorCodes.MEMBER_REVOKED, "Access has been revoked")
            MemberStatus.INVITED -> db.write { c ->
                val changed = c.update(
                    "UPDATE members SET status = ?, activated_at = ?, last_seen_at = ? WHERE id = ? AND status = ?",
                    MemberStatus.ACTIVE, now, now, existing.id, MemberStatus.INVITED,
                )
                if (changed == 1) {
                    c.update(
                        """INSERT OR IGNORE INTO subscriptions (member_id, channel, created_at)
                           SELECT ?, slug, ? FROM channels
                           WHERE archived_at IS NULL AND (required = 1 OR auto_subscribe = 1)""",
                        existing.id, now,
                    )
                }
            }
            MemberStatus.ACTIVE -> if (existing.lastSeenAt == null || Duration.between(existing.lastSeenAt, now).toMinutes() >= 1) {
                db.write { it.update("UPDATE members SET last_seen_at = ? WHERE id = ?", now, existing.id) }
            }
        }
        return Principal.Member(existing.id, email)
    }

    suspend fun me(member: Principal.Member): MeResponse = db.read { c ->
        val m = c.findByEmail(member.email)!!
        val qh = c.queryOne("SELECT * FROM quiet_hours WHERE member_id = ?", m.id) {
            QuietHoursDto(it.bool("enabled"), formatMinutes(it.getInt("start_min")), formatMinutes(it.getInt("end_min")))
        }
        MeResponse(email = m.email, displayName = m.displayName, status = m.status.name, quietHours = qh)
    }

    suspend fun setQuietHours(member: Principal.Member, req: QuietHoursDto): QuietHoursDto {
        val start = parseMinutes(req.start)
        val end = parseMinutes(req.end)
        if (req.enabled && start == end) throw ApiException.invalid("start and end must differ")
        db.write { c ->
            c.update(
                """INSERT INTO quiet_hours (member_id, enabled, start_min, end_min) VALUES (?, ?, ?, ?)
                   ON CONFLICT(member_id) DO UPDATE SET enabled = excluded.enabled,
                   start_min = excluded.start_min, end_min = excluded.end_min""",
                member.id, req.enabled, start, end,
            )
        }
        return QuietHoursDto(req.enabled, formatMinutes(start), formatMinutes(end))
    }

    /** docs/02 4.2 기기 등록 규칙. */
    suspend fun registerDevice(member: Principal.Member, installationId: String, req: RegisterDeviceRequest): String {
        if (installationId.isBlank() || installationId.length > 64) throw ApiException.invalid("Invalid installation id")
        if (req.fcmToken.isBlank() || req.fcmToken.length > 4096) throw ApiException.invalid("Invalid fcmToken")
        val zone = req.timeZone?.let { runCatching { ZoneId.of(it) }.getOrNull()?.id }
        val now = clock.instant()
        return db.write { c ->
            // 같은 토큰을 가진 다른 기기를 먼저 비활성화해야 활성 토큰 고유 index를 지킬 수 있다.
            c.update(
                "UPDATE devices SET active = 0 WHERE fcm_token = ? AND installation_id <> ? AND active = 1",
                req.fcmToken, installationId,
            )
            val existingId = c.queryOne("SELECT id FROM devices WHERE installation_id = ?", installationId) { it.str("id") }
            val id = existingId ?: Ids.device()
            if (existingId == null) {
                c.update(
                    """INSERT INTO devices (id, member_id, installation_id, fcm_token, active, model, app_version,
                       registered_at, last_seen_at) VALUES (?, ?, ?, ?, 1, ?, ?, ?, ?)""",
                    id, member.id, installationId, req.fcmToken, req.model, req.appVersion, now, now,
                )
            } else {
                c.update(
                    """UPDATE devices SET member_id = ?, fcm_token = ?, active = 1, model = ?, app_version = ?,
                       last_seen_at = ? WHERE id = ?""",
                    member.id, req.fcmToken, req.model, req.appVersion, now, id,
                )
            }
            if (zone != null) c.update("UPDATE members SET time_zone = ? WHERE id = ?", zone, member.id)
            id
        }
    }

    suspend fun unregisterDevice(member: Principal.Member, installationId: String) {
        val now = clock.instant()
        db.write { c ->
            val id = c.queryOne(
                "SELECT id FROM devices WHERE installation_id = ? AND member_id = ?", installationId, member.id,
            ) { it.str("id") } ?: return@write
            c.update("UPDATE devices SET active = 0 WHERE id = ?", id)
            c.update(
                "UPDATE deliveries SET status = 'CANCELLED', last_error = 'device unregistered', updated_at = ? WHERE device_id = ? AND status = 'PENDING'",
                now, id,
            )
        }
    }

    private data class MemberRow(
        val id: String,
        val email: String,
        val displayName: String?,
        val status: MemberStatus,
        val allowedAt: java.time.Instant,
        val activatedAt: java.time.Instant?,
        val revokedAt: java.time.Instant?,
        val lastSeenAt: java.time.Instant?,
    ) {
        fun toSummary() = MemberSummary(
            id = id, email = email, displayName = displayName, status = status.name,
            allowedAt = allowedAt.toString(), activatedAt = activatedAt?.toString(),
            revokedAt = revokedAt?.toString(), lastSeenAt = lastSeenAt?.toString(),
        )
    }

    private fun memberRow(rs: ResultSet) = MemberRow(
        id = rs.str("id"), email = rs.str("email"), displayName = rs.strOrNull("display_name"),
        status = MemberStatus.valueOf(rs.str("status")), allowedAt = rs.instant("allowed_at"),
        activatedAt = rs.instantOrNull("activated_at"), revokedAt = rs.instantOrNull("revoked_at"),
        lastSeenAt = rs.instantOrNull("last_seen_at"),
    )

    private fun Connection.findByEmail(email: String) =
        queryOne("SELECT * FROM members WHERE email = ?", email, map = ::memberRow)

    companion object {
        fun parseMinutes(value: String): Int =
            runCatching { LocalTime.parse(value) }.getOrNull()?.let { it.hour * 60 + it.minute }
                ?: throw ApiException.invalid("Time must be HH:mm: $value")

        fun formatMinutes(minutes: Int): String = "%02d:%02d".format(minutes / 60, minutes % 60)
    }
}
