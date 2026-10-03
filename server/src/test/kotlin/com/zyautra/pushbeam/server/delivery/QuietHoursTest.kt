package com.zyautra.pushbeam.server.delivery

import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class QuietHoursTest {
    private val seoul = ZoneId.of("Asia/Seoul")
    private fun at(h: Int, m: Int, zone: ZoneId = seoul) = LocalDateTime.of(2026, 10, 2, h, m).atZone(zone).toInstant()
    private val night = QuietHours(true, 23 * 60, 7 * 60)
    private val lunch = QuietHours(true, 13 * 60, 14 * 60)

    @Test fun `자정을 넘는 구간`() {
        assertTrue(night.isQuietAt(at(23, 0), seoul))
        assertTrue(night.isQuietAt(at(6, 59), seoul))
        assertFalse(night.isQuietAt(at(7, 0), seoul))
        assertFalse(night.isQuietAt(at(22, 59), seoul))
    }

    @Test fun `같은 날 안의 구간`() {
        assertTrue(lunch.isQuietAt(at(13, 30), seoul))
        assertFalse(lunch.isQuietAt(at(14, 0), seoul))
    }

    @Test fun `시작과 종료가 같거나 꺼져 있으면 방해 금지가 아니다`() {
        assertFalse(QuietHours(true, 540, 540).isQuietAt(at(9, 0), seoul))
        assertFalse(night.copy(enabled = false).isQuietAt(at(23, 30), seoul))
    }

    @Test fun `Member의 시간대 기준으로 판단한다`() {
        val instant = at(23, 30, seoul)               // 서울 23:30
        assertTrue(night.isQuietAt(instant, seoul))
        assertFalse(night.isQuietAt(instant, ZoneId.of("Europe/Berlin"))) // 베를린 16:30
    }
}
