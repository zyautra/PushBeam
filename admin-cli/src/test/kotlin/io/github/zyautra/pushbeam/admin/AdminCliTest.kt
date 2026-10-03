package io.github.zyautra.pushbeam.admin

import com.github.ajalt.clikt.testing.test
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AdminCliTest {
    private data class Seen(val method: HttpMethod, val path: String, val body: String?, val auth: String?)

    private val seen = mutableListOf<Seen>()

    private fun cli(status: HttpStatusCode = HttpStatusCode.OK, response: String): com.github.ajalt.clikt.core.CliktCommand {
        val engine = MockEngine { req ->
            seen += Seen(req.method, req.url.encodedPath + (req.url.encodedQuery.takeIf { it.isNotEmpty() }?.let { "?$it" } ?: ""),
                (req.body as? TextContent)?.text, req.headers[HttpHeaders.Authorization])
            respond(response, status, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        return buildCli(Session(engine = engine, configOverride = AdminConfig("https://pb.example.com", "pbo_test")))
    }

    @Test
    fun `status는 Operator Token으로 status API를 부른다`() {
        val r = cli(response = """{"ready":true,"members":{"ACTIVE":2},"activeDevices":3,"pendingDeliveries":0}""").test("status")
        assertEquals(0, r.statusCode)
        assertEquals(Seen(HttpMethod.Get, "/api/v1/admin/status", null, "Bearer pbo_test"), seen.single())
        assertContains(r.stdout, "READY")
        assertContains(r.stdout, "ACTIVE 2")
    }

    @Test
    fun `members allow는 이메일과 이름을 보낸다`() {
        cli(response = """{"id":"mbr_1","email":"a@b.co","status":"INVITED","allowedAt":"2026-10-03T00:00:00Z"}""")
            .test("members allow a@b.co --name 앨리스")
        assertEquals(HttpMethod.Post, seen.single().method)
        assertEquals("/api/v1/admin/members", seen.single().path)
        assertEquals("""{"email":"a@b.co","displayName":"앨리스"}""", seen.single().body)
    }

    @Test
    fun `send는 채널 대상과 추가 데이터를 보낸다`() {
        val r = cli(response = """{"messageId":"msg_1","recipients":{"deliver":1,"quiet":0,"skipped":0},"deliveries":1}""")
            .test(listOf("send", "--channel", "test", "--title", "제목", "--body", "본문", "--severity", "high", "--data", "host=nas", "--data", "k=a=b"))
        assertEquals(0, r.statusCode)
        val body = seen.single().body!!
        assertContains(body, """"target":{"channel":"test"}""")
        assertContains(body, """"severity":"high"""")
        assertContains(body, """"data":{"host":"nas","k":"a=b"}""")
        assertContains(r.stdout, "msg_1")
    }

    @Test
    fun `send는 대상을 정확히 하나만 받는다`() {
        val r = cli(response = "{}").test(listOf("send", "--channel", "a", "--all", "--title", "t", "--body", "b"))
        assertTrue(r.statusCode != 0)
        assertTrue(seen.isEmpty())
    }

    @Test
    fun `서버 오류는 설명과 함께 종료 코드 1로 끝난다`() {
        val r = cli(HttpStatusCode.Conflict, """{"error":{"code":"ALREADY_EXISTS","message":"Channel already exists: test"}}""")
            .test("channels create test --name 테스트")
        assertEquals(1, r.statusCode)
        assertContains(r.stderr, "이미 있어요")
        assertContains(r.stderr, "ALREADY_EXISTS")
    }

    @Test
    fun `--json이면 응답 원문을 출력한다`() {
        val raw = """[{"slug":"test","name":"테스트","required":true,"autoSubscribe":false,"archived":false,"subscribers":1}]"""
        val r = cli(response = raw).test("--json channels list")
        assertEquals(raw, r.stdout.trim())
    }

    @Test
    fun `이메일은 경로에 넣을 때 인코딩한다`() {
        cli(response = """{"id":"mbr_1","email":"a+b@c.co","status":"REVOKED","allowedAt":"2026-10-03T00:00:00Z"}""")
            .test("members revoke a+b@c.co")
        assertTrue(seen.single().path.startsWith("/api/v1/admin/members/a"))
        assertTrue(seen.single().path.endsWith("/revoke"))
    }

    @Test
    fun `설정은 https 주소만 받는다`() {
        val dir = Files.createTempDirectory("pb-admin")
        val file = dir.resolve("admin.properties")
        Files.writeString(file, "url=http://pb.example.com\ntoken=pbo_x\n")
        val e = assertFailsWith<AdminException> { AdminConfig.load(file, env = emptyMap()) }
        assertEquals("CONFIG", e.code)
    }

    @Test
    fun `설정 파일의 token-file을 읽고 환경 변수가 우선한다`() {
        val dir = Files.createTempDirectory("pb-admin")
        Files.writeString(dir.resolve("token"), "pbo_from_file\n")
        val file = dir.resolve("admin.properties")
        Files.writeString(file, "url=https://pb.example.com/\ntoken-file=${dir.resolve("token")}\n")
        assertEquals(AdminConfig("https://pb.example.com", "pbo_from_file"), AdminConfig.load(file, env = emptyMap()))
        assertEquals("pbo_env", AdminConfig.load(file, env = mapOf("PUSHBEAM_OPERATOR_TOKEN" to "pbo_env")).token)
    }

    @Test
    fun `token generate는 0600 파일에 저장한다`() {
        val out = Files.createTempDirectory("pb-admin").resolve("operator-token")
        val r = buildCli().test(listOf("token", "generate", "--output", out.toString()))
        assertEquals(0, r.statusCode)
        assertTrue(Files.readString(out).matches(Regex("pbo_[0-9A-Za-z]{40}")))
        assertEquals("rw-------", java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(out)))
    }
}
