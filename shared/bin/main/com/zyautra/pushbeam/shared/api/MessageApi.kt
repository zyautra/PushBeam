package com.zyautra.pushbeam.shared.api

import com.zyautra.pushbeam.shared.Severity
import kotlinx.serialization.Serializable

/** docs/03 3절 발송 API. */
@Serializable
data class MessageTarget(
    val channel: String? = null,
    val users: List<String>? = null,
    val all: Boolean? = null,
)

@Serializable
data class SendMessageRequest(
    val target: MessageTarget,
    val title: String,
    val body: String,
    val severity: Severity = Severity.NORMAL,
    val data: Map<String, String>? = null,
)

@Serializable
data class RecipientCounts(val deliver: Int, val quiet: Int, val skipped: Int)

@Serializable
data class SendMessageResponse(val messageId: String, val recipients: RecipientCounts, val deliveries: Int)

@Serializable
data class DeliveryCounts(
    val pending: Int,
    val sent: Int,
    val failed: Int,
    val invalidToken: Int,
    val cancelled: Int,
)

@Serializable
data class MessageStatusResponse(val messageId: String, val createdAt: String, val deliveries: DeliveryCounts)
