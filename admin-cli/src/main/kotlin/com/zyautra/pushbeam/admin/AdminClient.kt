package com.zyautra.pushbeam.admin

import com.zyautra.pushbeam.shared.ApiErrorResponse
import com.zyautra.pushbeam.shared.api.AllowMemberRequest
import com.zyautra.pushbeam.shared.api.ChannelSummary
import com.zyautra.pushbeam.shared.api.CreateChannelRequest
import com.zyautra.pushbeam.shared.api.CreateSenderRequest
import com.zyautra.pushbeam.shared.api.CreateSenderResponse
import com.zyautra.pushbeam.shared.api.MemberDetail
import com.zyautra.pushbeam.shared.api.MemberSummary
import com.zyautra.pushbeam.shared.api.MessageDetail
import com.zyautra.pushbeam.shared.api.MessageSummary
import com.zyautra.pushbeam.shared.api.Page
import com.zyautra.pushbeam.shared.api.SendMessageRequest
import com.zyautra.pushbeam.shared.api.SendMessageResponse
import com.zyautra.pushbeam.shared.api.SenderSummary
import com.zyautra.pushbeam.shared.api.ServerStatus
import com.zyautra.pushbeam.shared.api.UpdateChannelRequest
import com.zyautra.pushbeam.shared.api.UpdateSenderRequest
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.java.Java
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.http.encodeURLPathPart
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import java.io.IOException

/** Admin API (docs/03 5절)를 부른다. 응답 원문(raw)도 함께 돌려줘 --json 출력에 쓴다. */
class AdminClient(private val config: AdminConfig, engine: HttpClientEngine? = null) : AutoCloseable {
    val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val http = (engine?.let { HttpClient(it) } ?: HttpClient(Java)).config {
        install(HttpTimeout) { requestTimeoutMillis = 30_000; connectTimeoutMillis = 10_000 }
        expectSuccess = false
    }

    data class Result<T>(val value: T, val raw: String)

    suspend fun status() = get<ServerStatus>("/api/v1/admin/status")

    suspend fun members(status: String?) =
        get<List<MemberSummary>>("/api/v1/admin/members" + (status?.let { "?status=$it" } ?: ""))
    suspend fun member(email: String) = get<MemberDetail>("/api/v1/admin/members/${email.p()}")
    suspend fun allow(email: String, name: String?) =
        send<MemberSummary>(HttpMethod.Post, "/api/v1/admin/members", json.encodeToString(AllowMemberRequest(email, name)))
    suspend fun revoke(email: String) = send<MemberSummary>(HttpMethod.Post, "/api/v1/admin/members/${email.p()}/revoke")

    suspend fun channels() = get<List<ChannelSummary>>("/api/v1/admin/channels")
    suspend fun createChannel(req: CreateChannelRequest) =
        send<ChannelSummary>(HttpMethod.Post, "/api/v1/admin/channels", json.encodeToString(req))
    suspend fun updateChannel(slug: String, req: UpdateChannelRequest) =
        send<ChannelSummary>(HttpMethod.Patch, "/api/v1/admin/channels/${slug.p()}", json.encodeToString(req))
    suspend fun archiveChannel(slug: String, archived: Boolean) =
        send<ChannelSummary>(HttpMethod.Post, "/api/v1/admin/channels/${slug.p()}/${if (archived) "archive" else "unarchive"}")
    suspend fun subscribe(slug: String, email: String) =
        sendRaw(HttpMethod.Put, "/api/v1/admin/channels/${slug.p()}/members/${email.p()}")

    suspend fun senders() = get<List<SenderSummary>>("/api/v1/admin/senders")
    suspend fun createSender(req: CreateSenderRequest) =
        send<CreateSenderResponse>(HttpMethod.Post, "/api/v1/admin/senders", json.encodeToString(req))
    suspend fun updateSender(id: String, channels: List<String>) =
        send<SenderSummary>(HttpMethod.Patch, "/api/v1/admin/senders/${id.p()}", json.encodeToString(UpdateSenderRequest(channels)))
    suspend fun revokeSender(id: String) = send<SenderSummary>(HttpMethod.Post, "/api/v1/admin/senders/${id.p()}/revoke")

    suspend fun sendMessage(req: SendMessageRequest) =
        send<SendMessageResponse>(HttpMethod.Post, "/api/v1/messages", json.encodeToString(req))
    suspend fun messages(limit: Int, cursor: String?) =
        get<Page<MessageSummary>>("/api/v1/admin/messages?limit=$limit" + (cursor?.let { "&cursor=$it" } ?: ""))
    suspend fun message(id: String) = get<MessageDetail>("/api/v1/admin/messages/${id.p()}")

    suspend fun distributionSync() = sendRaw(HttpMethod.Post, "/api/v1/admin/distribution/sync")

    private suspend inline fun <reified T> get(path: String): Result<T> = send(HttpMethod.Get, path)

    private suspend inline fun <reified T> send(method: HttpMethod, path: String, body: String? = null): Result<T> {
        val raw = sendRaw(method, path, body)
        return Result(json.decodeFromString<T>(raw), raw)
    }

    suspend fun sendRaw(method: HttpMethod, path: String, body: String? = null): String {
        val response = try {
            http.request(config.url + path) {
                this.method = method
                bearerAuth(config.token)
                body?.let { json(it) }
            }
        } catch (e: IOException) {
            throw AdminException("NETWORK", "서버에 연결하지 못했어요: ${e.message}")
        }
        val text = response.bodyAsText()
        if (!response.status.isSuccess()) {
            val error = runCatching { json.decodeFromString<ApiErrorResponse>(text).error }.getOrNull()
            throw AdminException(error?.code ?: "HTTP_${response.status.value}", error?.message ?: text.take(200), response.status.value)
        }
        return text
    }

    private fun HttpRequestBuilder.json(body: String) {
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    private fun String.p() = encodeURLPathPart()

    override fun close() = http.close()
}
