package com.zyautra.pushbeam.shared

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** 알림 중요도. 선언 순서가 낮음 → 높음이므로 비교 연산을 그대로 쓸 수 있다. */
@Serializable
enum class Severity(val wire: String) {
    @SerialName("low") LOW("low"),
    @SerialName("normal") NORMAL("normal"),
    @SerialName("high") HIGH("high"),
    @SerialName("critical") CRITICAL("critical");

    companion object {
        fun fromWire(value: String): Severity? = entries.firstOrNull { it.wire == value }
    }
}
