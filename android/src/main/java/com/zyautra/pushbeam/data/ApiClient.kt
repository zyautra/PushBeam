package com.zyautra.pushbeam.data

import com.zyautra.pushbeam.shared.ApiErrorResponse
import com.zyautra.pushbeam.shared.ErrorCodes
import com.zyautra.pushbeam.shared.Severity
import com.zyautra.pushbeam.shared.api.MeResponse
import com.zyautra.pushbeam.shared.api.MemberChannel
import com.zyautra.pushbeam.shared.api.MemberChannelsResponse
import com.zyautra.pushbeam.shared.api.QuietHoursDto
import com.zyautra.pushbeam.shared.api.RegisterDeviceRequest
import com.zyautra.pushbeam.shared.api.RegisterDeviceResponse
import com.zyautra.pushbeam.shared.api.SubscriptionPatch
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** 서버가 오류 응답을 준 경우. code는 docs/03 2.1의 오류 코드. */
class ApiException(val status: Int, val code: String, message: String) : Exception(message) {
    val isAccessDenied get() = code == ErrorCodes.NOT_ALLOWLISTED || code == ErrorCodes.MEMBER_REVOKED
}

/** Member API (docs/03 4절). 모든 요청에 Firebase ID Token을 붙인다. */
class ApiClient(private val baseUrl: String, private val session: SessionRepository) {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val http = HttpClient(OkHttp) {
        install(ContentNegotiation) { json(json) }
        install(HttpTimeout) { requestTimeoutMillis = 15_000; connectTimeoutMillis = 10_000 }
        expectSuccess = false
    }

    suspend fun me(): MeResponse = call(HttpMethod.Get, "/api/v1/me").body()

    suspend fun registerDevice(installationId: String, req: RegisterDeviceRequest): RegisterDeviceResponse =
        call(HttpMethod.Put, "/api/v1/me/devices/$installationId") { jsonBody(req) }.body()

    suspend fun unregisterDevice(installationId: String) {
        call(HttpMethod.Delete, "/api/v1/me/devices/$installationId")
    }

    suspend fun channels(): List<MemberChannel> = call(HttpMethod.Get, "/api/v1/me/channels").body<MemberChannelsResponse>().channels

    suspend fun subscribe(slug: String, minSeverity: Severity? = null): MemberChannel =
        call(HttpMethod.Put, "/api/v1/me/channels/$slug") { jsonBody(SubscribeBody(minSeverity)) }.body()

    suspend fun unsubscribe(slug: String): MemberChannel = call(HttpMethod.Delete, "/api/v1/me/channels/$slug").body()

    suspend fun patch(slug: String, patch: SubscriptionPatch): MemberChannel =
        call(HttpMethod.Patch, "/api/v1/me/channels/$slug") { jsonBody(patch) }.body()

    suspend fun setQuietHours(q: QuietHoursDto): QuietHoursDto =
        call(HttpMethod.Put, "/api/v1/me/quiet-hours") { jsonBody(q) }.body()

    @Serializable
    private data class SubscribeBody(val minSeverity: Severity? = null)

    private inline fun <reified T> HttpRequestBuilder.jsonBody(value: T) {
        contentType(ContentType.Application.Json)
        setBody(value)
    }

    /** 401이면 토큰을 새로 받아 한 번만 다시 시도한다. */
    private suspend fun call(
        method: HttpMethod,
        path: String,
        block: HttpRequestBuilder.() -> Unit = {},
    ): HttpResponse {
        var response = send(method, path, forceRefresh = false, block)
        if (response.status.value == 401) response = send(method, path, forceRefresh = true, block)
        if (!response.status.isSuccess()) {
            val text = response.bodyAsText()
            val error = runCatching { json.decodeFromString<ApiErrorResponse>(text).error }.getOrNull()
            throw ApiException(response.status.value, error?.code ?: "HTTP_${response.status.value}", error?.message ?: text.take(200))
        }
        return response
    }

    private suspend fun send(method: HttpMethod, path: String, forceRefresh: Boolean, block: HttpRequestBuilder.() -> Unit): HttpResponse {
        val token = session.idToken(forceRefresh) ?: throw ApiException(401, ErrorCodes.UNAUTHORIZED, "Not signed in")
        return http.request(baseUrl.trimEnd('/') + path) {
            this.method = method
            bearerAuth(token)
            block()
        }
    }
}
