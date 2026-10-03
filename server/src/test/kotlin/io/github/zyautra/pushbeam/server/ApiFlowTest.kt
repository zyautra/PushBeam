package io.github.zyautra.pushbeam.server

import io.github.zyautra.pushbeam.server.TestEnv.Companion.OPERATOR
import io.github.zyautra.pushbeam.server.db.count
import io.github.zyautra.pushbeam.server.db.queryOne
import io.github.zyautra.pushbeam.server.gateway.FcmResult
import io.ktor.client.HttpClient
import io.ktor.http.HttpMethod.Companion.Delete
import io.ktor.http.HttpMethod.Companion.Get
import io.ktor.http.HttpMethod.Companion.Patch
import io.ktor.http.HttpMethod.Companion.Post
import io.ktor.http.HttpMethod.Companion.Put
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ApiFlowTest {
    private val alice = "alice@gmail.com"
    private val bob = "bob@gmail.com"

    private suspend fun HttpClient.setup(env: TestEnv): String {
        call(Post, "/api/v1/admin/channels", OPERATOR, """{"slug":"server-alerts","name":"서버 경고","required":true}""")
        call(Post, "/api/v1/admin/channels", OPERATOR, """{"slug":"deploy","name":"배포","autoSubscribe":true}""")
        call(Post, "/api/v1/admin/channels", OPERATOR, """{"slug":"backup","name":"백업"}""")
        for (email in listOf(alice, bob)) {
            assertEquals(HttpStatusCode.OK, call(Post, "/api/v1/admin/members", OPERATOR, """{"email":"$email"}""").status)
            assertEquals(HttpStatusCode.OK, call(Get, "/api/v1/me", email).status)
            assertEquals(HttpStatusCode.OK, call(Put, "/api/v1/me/devices/inst-$email", email, """{"fcmToken":"token-$email","timeZone":"Asia/Seoul"}""").status)
        }
        val res = call(Post, "/api/v1/admin/senders", OPERATOR, """{"name":"nas","allowedChannels":["server-alerts","deploy"]}""")
        assertEquals(HttpStatusCode.Created, res.status)
        return res.jsonBody().jsonObject["key"]!!.jsonPrimitive.content
    }

    private suspend fun HttpClient.send(token: String, body: String) = call(Post, "/api/v1/messages", token, body)

    @Test
    fun `Admin API는 Operator Token이 있어야 한다`() = withEnv {
        assertEquals(HttpStatusCode.Unauthorized, client.call(Get, "/api/v1/admin/members", null).status)
        assertEquals(HttpStatusCode.Unauthorized, client.call(Get, "/api/v1/admin/members", "wrong").status)
        assertEquals(HttpStatusCode.Unauthorized, client.call(Get, "/api/v1/admin/members", alice).status)
        assertEquals(HttpStatusCode.OK, client.call(Get, "/api/v1/admin/members", OPERATOR).status)
    }

    @Test
    fun `허용되지 않은 계정과 미인증 이메일은 거부한다`() = withEnv {
        val res = client.call(Get, "/api/v1/me", "stranger@gmail.com")
        assertEquals(HttpStatusCode.Forbidden, res.status)
        assertEquals("NOT_ALLOWLISTED", res.jsonBody().jsonObject["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)
        client.call(Post, "/api/v1/admin/members", OPERATOR, """{"email":"$alice"}""")
        assertEquals(HttpStatusCode.Unauthorized, client.call(Get, "/api/v1/me", "unverified:$alice").status)
        assertEquals(HttpStatusCode.Unauthorized, client.call(Get, "/api/v1/me", "invalid").status)
    }

    @Test
    fun `첫 로그인에 필수와 자동 구독 채널을 구독한다`() = withEnv { env ->
        client.setup(env)
        val channels = client.call(Get, "/api/v1/me/channels", alice).jsonBody().jsonObject["channels"]!!.jsonArray
            .associate { it.jsonObject["slug"]!!.jsonPrimitive.content to it.jsonObject["subscribed"]!!.jsonPrimitive.content.toBoolean() }
        assertEquals(mapOf("server-alerts" to true, "deploy" to true, "backup" to false), channels)
        assertEquals(HttpStatusCode.Conflict, client.call(Delete, "/api/v1/me/channels/server-alerts", alice).status)
        assertEquals(HttpStatusCode.OK, client.call(Delete, "/api/v1/me/channels/deploy", alice).status)
    }

    @Test
    fun `채널 발송부터 FCM 전송까지`() = withEnv { env ->
        val key = client.setup(env)
        val res = client.send(key, """{"target":{"channel":"server-alerts"},"title":"디스크 경고","body":"92%","severity":"high","data":{"host":"nas"}}""")
        assertEquals(HttpStatusCode.Accepted, res.status)
        val body = res.jsonBody().jsonObject
        assertEquals(2, body["deliveries"]!!.jsonPrimitive.int)
        val id = body["messageId"]!!.jsonPrimitive.content

        assertEquals(2, env.dispatcher.runOnce())
        assertEquals(setOf("token-$alice", "token-$bob"), env.fcm.sent.map { it.first }.toSet())
        val payload = env.fcm.sent.first().second
        assertEquals(id, payload.id)
        assertEquals(mapOf("host" to "nas"), payload.data)

        val status = client.call(Get, "/api/v1/messages/$id", key).jsonBody().jsonObject["deliveries"]!!.jsonObject
        assertEquals(2, status["sent"]!!.jsonPrimitive.int)
    }

    @Test
    fun `Sender는 허용된 채널로만 보낼 수 있다`() = withEnv { env ->
        val key = client.setup(env)
        assertEquals(HttpStatusCode.Forbidden, client.send(key, """{"target":{"channel":"backup"},"title":"t","body":"b"}""").status)
        assertEquals(HttpStatusCode.Forbidden, client.send(key, """{"target":{"all":true},"title":"t","body":"b"}""").status)
        assertEquals(HttpStatusCode.Unauthorized, client.send("pbs_" + "x".repeat(40), """{"target":{"channel":"deploy"},"title":"t","body":"b"}""").status)
        assertEquals(HttpStatusCode.Unauthorized, client.send(alice, """{"target":{"channel":"deploy"},"title":"t","body":"b"}""").status)
        assertEquals(HttpStatusCode.Accepted, client.send(OPERATOR, """{"target":{"users":["$alice"]},"title":"t","body":"b"}""").status)
        assertEquals(HttpStatusCode.UnprocessableEntity, client.send(OPERATOR, """{"target":{"users":["nobody@x.com"]},"title":"t","body":"b"}""").status)
        assertEquals(HttpStatusCode.BadRequest, client.send(OPERATOR, """{"target":{},"title":"t","body":"b"}""").status)
    }

    @Test
    fun `음소거와 최소 중요도가 적용되고 필수 채널의 critical은 통과한다`() = withEnv { env ->
        val key = client.setup(env)
        client.call(Patch, "/api/v1/me/channels/server-alerts", alice, """{"muted":true}""")
        client.call(Patch, "/api/v1/me/channels/deploy", bob, """{"minSeverity":"high"}""")

        val muted = client.send(key, """{"target":{"channel":"server-alerts"},"title":"t","body":"b"}""").jsonBody().jsonObject
        assertEquals(1, muted["recipients"]!!.jsonObject["inboxOnly"]!!.jsonPrimitive.int)
        assertEquals(2, muted["deliveries"]!!.jsonPrimitive.int)
        env.dispatcher.runOnce()
        val mutedPayload = env.fcm.sent.single { it.first == "token-$alice" }.second
        assertEquals(io.github.zyautra.pushbeam.shared.Display.INBOX, mutedPayload.display)
        assertEquals("muted", mutedPayload.reason)

        val critical = client.send(key, """{"target":{"channel":"server-alerts"},"title":"t","body":"b","severity":"critical"}""").jsonBody().jsonObject
        assertEquals(2, critical["recipients"]!!.jsonObject["deliver"]!!.jsonPrimitive.int)

        val low = client.send(key, """{"target":{"channel":"deploy"},"title":"t","body":"b","severity":"normal"}""").jsonBody().jsonObject
        val id = low["messageId"]!!.jsonPrimitive.content
        val detail = client.call(Get, "/api/v1/admin/messages/$id", OPERATOR).jsonBody().jsonObject["recipients"]!!.jsonArray
            .associate { it.jsonObject["email"]!!.jsonPrimitive.content to it.jsonObject["result"]!!.jsonPrimitive.content }
        assertEquals(mapOf(alice to "DELIVER", bob to "BELOW_MIN"), detail)
    }

    @Test
    fun `방해 금지 시간에는 무음으로 보낸다`() = withEnv { env ->
        val key = client.setup(env)
        client.call(Put, "/api/v1/me/quiet-hours", alice, """{"enabled":true,"start":"11:00","end":"13:00"}""")
        val res = client.send(key, """{"target":{"channel":"deploy"},"title":"t","body":"b"}""").jsonBody().jsonObject
        assertEquals(1, res["recipients"]!!.jsonObject["quiet"]!!.jsonPrimitive.int)
        env.dispatcher.runOnce()
        assertEquals(true, env.fcm.sent.single { it.first == "token-$alice" }.second.quiet)
    }

    @Test
    fun `허용을 취소하면 대기 전송이 취소되고 더 이상 쓸 수 없다`() = withEnv { env ->
        val key = client.setup(env)
        client.send(key, """{"target":{"channel":"deploy"},"title":"t","body":"b"}""")
        assertEquals(HttpStatusCode.OK, client.call(Post, "/api/v1/admin/members/$alice/revoke", OPERATOR).status)
        env.dispatcher.runOnce()
        assertEquals(listOf("token-$bob"), env.fcm.sent.map { it.first })
        assertEquals(HttpStatusCode.Forbidden, client.call(Get, "/api/v1/me", alice).status)
        assertEquals(1, env.db.read { it.count("SELECT COUNT(*) FROM deliveries WHERE status = 'CANCELLED'") })
    }

    @Test
    fun `토큰 무효면 기기를 비활성화한다`() = withEnv { env ->
        val key = client.setup(env)
        env.fcm.result = { token -> if (token == "token-$alice") FcmResult.InvalidToken("UNREGISTERED") else FcmResult.Sent("ok") }
        client.send(key, """{"target":{"channel":"deploy"},"title":"t","body":"b"}""")
        env.dispatcher.runOnce()
        val active = env.db.read { it.queryOne("SELECT active FROM devices WHERE installation_id = ?", "inst-$alice") { rs -> rs.getInt(1) } }
        assertEquals(0, active)
        val next = client.send(key, """{"target":{"channel":"deploy"},"title":"t","body":"b"}""").jsonBody().jsonObject
        assertEquals(1, next["deliveries"]!!.jsonPrimitive.int)
    }

    @Test
    fun `일시 오류는 정해진 간격으로 재시도하고 다섯 번째 실패면 FAILED`() = withEnv { env ->
        val key = client.setup(env)
        client.call(Post, "/api/v1/admin/members/$bob/revoke", OPERATOR)
        env.fcm.result = { FcmResult.Retryable("UNAVAILABLE") }
        client.send(key, """{"target":{"channel":"deploy"},"title":"t","body":"b"}""")
        val waits = listOf(15L, 60L, 300L, 900L)
        for (w in waits) {
            assertEquals(1, env.dispatcher.runOnce())
            assertEquals(0, env.dispatcher.runOnce(), "should wait before retry")
            env.clock.advance(Duration.ofSeconds(w))
        }
        assertEquals(1, env.dispatcher.runOnce())
        val status = env.db.read { it.queryOne("SELECT status, attempts FROM deliveries") { rs -> rs.getString(1) to rs.getInt(2) } }
        assertEquals("FAILED" to 5, status)
    }

    @Test
    fun `같은 토큰을 다른 기기가 등록하면 이전 기기를 비활성화한다`() = withEnv { env ->
        client.setup(env)
        client.call(Put, "/api/v1/me/devices/inst-new", alice, """{"fcmToken":"token-$bob"}""")
        val bobActive = env.db.read { it.queryOne("SELECT active FROM devices WHERE installation_id = ?", "inst-$bob") { rs -> rs.getInt(1) } }
        assertEquals(0, bobActive)
    }

    @Test
    fun `배포 그룹을 허용 목록에 맞춘다`() = withEnv { env ->
        client.setup(env)
        env.distributionGateway.members += "old@gmail.com"
        assertTrue(env.distribution.runOnce())
        assertEquals(setOf(alice, bob), env.distributionGateway.members)
        client.call(Post, "/api/v1/admin/members/$bob/revoke", OPERATOR)
        env.distribution.runOnce()
        assertEquals(setOf(alice), env.distributionGateway.members)
    }

    @Test
    fun `90일 지난 기록을 지우고 백업을 만든다`() = withEnv { env ->
        val key = client.setup(env)
        client.send(key, """{"target":{"channel":"deploy"},"title":"t","body":"b"}""")
        env.clock.advance(Duration.ofDays(91))
        env.housekeeping.runOnce()
        assertEquals(0, env.db.read { it.count("SELECT COUNT(*) FROM messages") })
        assertEquals(0, env.db.read { it.count("SELECT COUNT(*) FROM deliveries") })
        assertTrue(env.housekeeping.lastBackupAt != null)
        val status = client.call(Get, "/api/v1/admin/status", OPERATOR).jsonBody().jsonObject
        assertEquals(2, status["activeDevices"]!!.jsonPrimitive.int)
    }
}
