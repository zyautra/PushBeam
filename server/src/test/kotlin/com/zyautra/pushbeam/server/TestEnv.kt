package com.zyautra.pushbeam.server

import com.zyautra.pushbeam.server.api.Services
import com.zyautra.pushbeam.server.auth.IdTokenVerifier
import com.zyautra.pushbeam.server.auth.VerifiedIdentity
import com.zyautra.pushbeam.server.db.Database
import com.zyautra.pushbeam.server.delivery.Dispatcher
import com.zyautra.pushbeam.server.gateway.DistributionGateway
import com.zyautra.pushbeam.server.gateway.FcmGateway
import com.zyautra.pushbeam.server.gateway.FcmResult
import com.zyautra.pushbeam.server.jobs.DistributionSync
import com.zyautra.pushbeam.server.jobs.Housekeeping
import com.zyautra.pushbeam.server.service.ChannelService
import com.zyautra.pushbeam.server.service.MemberService
import com.zyautra.pushbeam.server.service.MessageService
import com.zyautra.pushbeam.server.service.SenderService
import com.zyautra.pushbeam.shared.PushPayload
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.nio.file.Files
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

class MutableClock(var now: Instant) : Clock() {
    override fun instant() = now
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId) = this
    fun advance(d: Duration) { now = now.plus(d) }
}

class FakeFcm : FcmGateway {
    val sent = mutableListOf<Pair<String, PushPayload>>()
    var result: (String) -> FcmResult = { FcmResult.Sent("projects/p/messages/${sent.size}") }
    override suspend fun send(token: String, payload: PushPayload, highPriority: Boolean): FcmResult =
        result(token).also { if (it is FcmResult.Sent) sent += token to payload }
}

class FakeDistribution : DistributionGateway {
    val members = mutableSetOf<String>()
    override suspend fun groupMembers() = members.toSet()
    override suspend fun join(emails: Set<String>) { members += emails }
    override suspend fun leave(emails: Set<String>) { members -= emails }
}

/** ID Token 대신 이메일을 그대로 토큰으로 쓴다. "unverified:" 접두어면 이메일 미인증. */
class FakeIdTokens : IdTokenVerifier {
    override suspend fun verify(idToken: String): VerifiedIdentity? = when {
        idToken == "invalid" -> null
        idToken.startsWith("unverified:") -> VerifiedIdentity(idToken.removePrefix("unverified:"), false, "google.com")
        else -> VerifiedIdentity(idToken, true, "google.com")
    }
}

class TestEnv {
    val clock = MutableClock(Instant.parse("2026-10-03T03:00:00Z")) // 12:00 KST
    val dir = Files.createTempDirectory("pushbeam-test")
    val db = Database(dir.resolve("pushbeam.db"))
    val fcm = FakeFcm()
    val distributionGateway = FakeDistribution()
    val state = ServerState().apply { ready.set(true) }
    val dispatcher = Dispatcher(db, fcm, clock)
    lateinit var members: MemberService
    val distribution = DistributionSync(distributionGateway, { members.allowedEmails() }, clock)
    val housekeeping = Housekeeping(db, dir.resolve("backup"), clock)

    init {
        members = MemberService(db, clock)
    }

    val services = Services(
        members = members,
        channels = ChannelService(db, clock),
        senders = SenderService(db, clock),
        messages = MessageService(db, clock, ZoneId.of("Asia/Seoul")),
        idTokens = FakeIdTokens(),
        operatorToken = OPERATOR,
        status = StatusService(db, state, distribution, housekeeping)::status,
    )

    companion object {
        const val OPERATOR = "pbo_test-operator-token"
    }
}

val json = Json { ignoreUnknownKeys = true }

fun withEnv(block: suspend ApplicationTestBuilder.(TestEnv) -> Unit) {
    val env = TestEnv()
    try {
        testApplication {
            application { module(env.state, env.services) }
            block(env)
        }
    } finally {
        env.db.close()
    }
}

suspend fun HttpClient.call(method: HttpMethod, path: String, token: String?, body: String? = null): HttpResponse =
    request(path) {
        this.method = method
        token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
        body?.let { contentType(ContentType.Application.Json); setBody(it) }
    }

suspend fun HttpResponse.jsonBody(): JsonElement = json.parseToJsonElement(bodyAsText())
