package io.github.zyautra.pushbeam.server

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals

class HealthTest {
    @Test
    fun `준비 전에는 ready가 503이고 준비되면 200이다`() = testApplication {
        val state = ServerState()
        application { module(state) }

        assertEquals(HttpStatusCode.OK, client.get("/health/live").status)
        assertEquals(HttpStatusCode.ServiceUnavailable, client.get("/health/ready").status)

        state.ready.set(true)
        val ready = client.get("/health/ready")
        assertEquals(HttpStatusCode.OK, ready.status)
        assertEquals("""{"status":"READY"}""", ready.bodyAsText())
    }
}
