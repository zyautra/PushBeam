package io.github.zyautra.pushbeam.admin

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 고정폭 표. 한글은 두 칸으로 센다. */
fun table(headers: List<String>, rows: List<List<String?>>): String {
    if (rows.isEmpty()) return "(없음)"
    val cells = rows.map { r -> r.map { it ?: "-" } }
    val widths = headers.indices.map { i -> maxOf(width(headers[i]), cells.maxOf { width(it[i]) }) }
    fun line(values: List<String>) = values.mapIndexed { i, v -> v + " ".repeat(widths[i] - width(v)) }.joinToString("  ").trimEnd()
    return (listOf(line(headers), widths.joinToString("  ") { "-".repeat(it) }) + cells.map(::line)).joinToString("\n")
}

fun keyValues(pairs: List<Pair<String, String?>>): String {
    val w = pairs.maxOf { width(it.first) }
    return pairs.joinToString("\n") { (k, v) -> k + " ".repeat(w - width(k)) + "  " + (v ?: "-") }
}

private fun width(s: String) = s.sumOf { c -> if (c.code in 0x1100..0x11FF || c.code in 0x2E80..0xA4CF || c.code in 0xAC00..0xD7A3 || c.code in 0xF900..0xFAFF || c.code in 0xFF00..0xFF60) 2 else 1 }.toInt()

private val timeFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())

/** 서버의 ISO 시각을 지역 시각으로. */
fun time(iso: String?): String? = iso?.let { runCatching { timeFormat.format(Instant.parse(it)) }.getOrDefault(it) }

/** 서버 오류 코드에 대한 짧은 설명. */
fun explain(code: String): String = when (code) {
    "UNAUTHORIZED" -> "Operator Token이 맞지 않아요"
    "NOT_FOUND" -> "대상을 찾을 수 없어요"
    "ALREADY_EXISTS" -> "이미 있어요"
    "CHANNEL_REQUIRED" -> "필수 채널은 해제할 수 없어요"
    "UNKNOWN_RECIPIENT" -> "허용 목록에 없는 이메일이 있어요"
    "PAYLOAD_TOO_LARGE" -> "알림 내용이 너무 커요"
    "FORBIDDEN" -> "권한이 없어요"
    "INVALID_REQUEST" -> "요청 값이 잘못됐어요"
    "SERVER_NOT_READY" -> "서버가 아직 준비되지 않았어요"
    "NETWORK" -> "네트워크 오류"
    "CONFIG" -> "설정 오류"
    else -> code
}
