package com.zyautra.pushbeam.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PushPayloadTest {
    private val payload = PushPayload(
        id = "msg_01J9ZQ7C3V8K2H5N1W4R6T0BXY",
        title = "디스크 경고",
        body = "nas-01 /var 사용량 92%",
        severity = Severity.HIGH,
        channel = "server-alerts",
        quiet = false,
        sentAt = "2026-10-02T12:00:03.120Z",
        data = mapOf("host" to "nas-01", "eventId" to "10234"),
    )

    @Test
    fun `FCM data로 바꿨다가 다시 읽으면 같다`() {
        assertEquals(payload, PushPayload.fromFcmData(payload.toFcmData()))
    }

    @Test
    fun `추가 데이터는 key 순서대로 직렬화한다`() {
        assertEquals("""{"eventId":"10234","host":"nas-01"}""", payload.toFcmData()[PushPayload.Keys.DATA])
    }

    @Test
    fun `모르는 형식 버전은 읽지 않는다`() {
        val fcm = payload.toFcmData() + (PushPayload.Keys.VERSION to "2")
        assertNull(PushPayload.fromFcmData(fcm))
    }

    @Test
    fun `필수 key가 없으면 읽지 않는다`() {
        assertNull(PushPayload.fromFcmData(payload.toFcmData() - PushPayload.Keys.TITLE))
    }

    @Test
    fun `중요도는 낮음부터 높음 순서로 비교된다`() {
        assertTrue(Severity.LOW < Severity.NORMAL)
        assertTrue(Severity.HIGH < Severity.CRITICAL)
        assertEquals(Severity.CRITICAL, Severity.fromWire("critical"))
        assertNull(Severity.fromWire("CRITICAL"))
    }
}
