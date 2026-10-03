package com.zyautra.pushbeam.shared.api

import com.zyautra.pushbeam.shared.Severity
import kotlinx.serialization.Serializable

/** docs/03 4절 Member API. */
@Serializable
data class QuietHoursDto(val enabled: Boolean, val start: String, val end: String)

@Serializable
data class MeResponse(
    val email: String,
    val displayName: String? = null,
    val status: String,
    val quietHours: QuietHoursDto? = null,
)

@Serializable
data class RegisterDeviceRequest(
    val fcmToken: String,
    val model: String? = null,
    val appVersion: Int? = null,
    val timeZone: String? = null,
)

@Serializable
data class RegisterDeviceResponse(val deviceId: String)

@Serializable
data class MemberChannel(
    val slug: String,
    val name: String,
    val description: String? = null,
    val required: Boolean,
    val subscribed: Boolean,
    val minSeverity: Severity? = null,
    val muted: Boolean = false,
    val mutedUntil: String? = null,
    /** 이 채널을 구독한 Member 수 */
    val subscribers: Int = 0,
)

@Serializable
data class MemberChannelsResponse(val channels: List<MemberChannel>)

/** 바꿀 필드만 보낸다. muted가 true이고 mutedUntil이 없으면 직접 끌 때까지 음소거한다. */
@Serializable
data class SubscriptionPatch(
    val minSeverity: Severity? = null,
    val muted: Boolean? = null,
    val mutedUntil: String? = null,
)
