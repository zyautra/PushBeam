package io.github.zyautra.pushbeam.shared.api

import io.github.zyautra.pushbeam.shared.Severity
import kotlinx.serialization.Serializable

/** docs/03 5절 Admin API. */
@Serializable
data class Page<T>(val items: List<T>, val nextCursor: String? = null)

@Serializable
data class AllowMemberRequest(val email: String, val displayName: String? = null)

@Serializable
data class MemberSummary(
    val id: String,
    val email: String,
    val displayName: String? = null,
    val status: String,
    val allowedAt: String,
    val activatedAt: String? = null,
    val revokedAt: String? = null,
    val lastSeenAt: String? = null,
)

@Serializable
data class DeviceSummary(
    val id: String,
    val active: Boolean,
    val model: String? = null,
    val appVersion: Int? = null,
    val registeredAt: String,
    val lastSeenAt: String,
)

@Serializable
data class SubscriptionSummary(
    val channel: String,
    val minSeverity: Severity,
    val muted: Boolean,
    val mutedUntil: String? = null,
)

@Serializable
data class MemberDetail(
    val member: MemberSummary,
    val devices: List<DeviceSummary>,
    val subscriptions: List<SubscriptionSummary>,
)

@Serializable
data class CreateChannelRequest(
    val slug: String,
    val name: String,
    val description: String? = null,
    val required: Boolean = false,
    val autoSubscribe: Boolean = false,
)

@Serializable
data class UpdateChannelRequest(
    val name: String? = null,
    val description: String? = null,
    val required: Boolean? = null,
    val autoSubscribe: Boolean? = null,
)

@Serializable
data class ChannelSummary(
    val slug: String,
    val name: String,
    val description: String? = null,
    val required: Boolean,
    val autoSubscribe: Boolean,
    val archived: Boolean,
    val subscribers: Int,
)

@Serializable
data class CreateSenderRequest(val name: String, val allowedChannels: List<String>)

@Serializable
data class UpdateSenderRequest(val allowedChannels: List<String>)

@Serializable
data class SenderSummary(
    val id: String,
    val name: String,
    val allowedChannels: List<String>,
    val createdAt: String,
    val lastUsedAt: String? = null,
    val revokedAt: String? = null,
)

@Serializable
data class CreateSenderResponse(val sender: SenderSummary, val key: String)

@Serializable
data class MessageSummary(
    val messageId: String,
    val createdAt: String,
    val sender: String,
    val target: MessageTarget,
    val title: String,
    val severity: Severity,
    val deliveries: DeliveryCounts,
)

@Serializable
data class DeliveryDetail(
    val deviceId: String,
    val deviceModel: String? = null,
    val quiet: Boolean,
    /** normal, quiet, inbox */
    val display: String = if (quiet) "quiet" else "normal",
    val status: String,
    val attempts: Int,
    val lastError: String? = null,
    val updatedAt: String,
)

@Serializable
data class RecipientDetail(val email: String, val result: String, val deliveries: List<DeliveryDetail>)

@Serializable
data class MessageDetail(
    val messageId: String,
    val createdAt: String,
    val sender: String,
    val target: MessageTarget,
    val title: String,
    val body: String,
    val severity: Severity,
    val data: Map<String, String>? = null,
    val recipients: List<RecipientDetail>,
)

@Serializable
data class ServerStatus(
    val ready: Boolean,
    val members: Map<String, Int>,
    val activeDevices: Int,
    val pendingDeliveries: Int,
    val oldestPendingAt: String? = null,
    val lastDistributionSyncAt: String? = null,
    val lastDistributionError: String? = null,
    val lastBackupAt: String? = null,
)
