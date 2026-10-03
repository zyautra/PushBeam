package com.zyautra.pushbeam.server.gateway

import com.google.auth.oauth2.GoogleCredentials
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.messaging.AndroidConfig
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingException
import com.google.firebase.messaging.Message
import com.google.firebase.messaging.MessagingErrorCode
import com.zyautra.pushbeam.server.auth.IdTokenVerifier
import com.zyautra.pushbeam.server.auth.VerifiedIdentity
import com.zyautra.pushbeam.shared.PushPayload
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.add
import org.slf4j.LoggerFactory
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

/** 서버 계정 키 하나로 FCM, ID Token 검증, App Distribution을 쓴다 (docs/04 4절). */
class Firebase(credentialsFile: Path) {
    val credentials: GoogleCredentials = Files.newInputStream(credentialsFile).use {
        GoogleCredentials.fromStream(it).createScoped("https://www.googleapis.com/auth/cloud-platform")
    }
    val app: FirebaseApp = FirebaseApp.initializeApp(FirebaseOptions.builder().setCredentials(credentials).build(), "pushbeam")
}

class FirebaseFcmGateway(firebase: Firebase) : FcmGateway {
    private val messaging = FirebaseMessaging.getInstance(firebase.app)

    override suspend fun send(token: String, payload: PushPayload, highPriority: Boolean): FcmResult = withContext(Dispatchers.IO) {
        val message = Message.builder()
            .setToken(token)
            .putAllData(payload.toFcmData())
            .setAndroidConfig(
                AndroidConfig.builder()
                    .setPriority(if (highPriority) AndroidConfig.Priority.HIGH else AndroidConfig.Priority.NORMAL)
                    .build(),
            )
            .build()
        try {
            FcmResult.Sent(messaging.send(message))
        } catch (e: FirebaseMessagingException) {
            classify(e)
        } catch (e: IOException) {
            FcmResult.Retryable("network: ${e.message}")
        }
    }

    private fun classify(e: FirebaseMessagingException): FcmResult {
        val code = e.messagingErrorCode
        val reason = "${code ?: e.errorCode}: ${e.message}"
        return when (code) {
            MessagingErrorCode.UNREGISTERED, MessagingErrorCode.SENDER_ID_MISMATCH -> FcmResult.InvalidToken(reason)
            MessagingErrorCode.INVALID_ARGUMENT ->
                if (e.message?.contains("registration token", ignoreCase = true) == true) FcmResult.InvalidToken(reason)
                else FcmResult.Failed(reason)
            MessagingErrorCode.UNAVAILABLE, MessagingErrorCode.INTERNAL, MessagingErrorCode.QUOTA_EXCEEDED ->
                FcmResult.Retryable(reason, retryAfter(e))
            else -> FcmResult.Failed(reason)
        }
    }

    private fun retryAfter(e: FirebaseMessagingException): Duration? =
        e.httpResponse?.headers?.get("retry-after")?.toString()?.trim('[', ']')?.toLongOrNull()?.let(Duration::ofSeconds)
}

class FirebaseIdTokenVerifier(firebase: Firebase) : IdTokenVerifier {
    private val auth = FirebaseAuth.getInstance(firebase.app)

    override suspend fun verify(idToken: String): VerifiedIdentity? = withContext(Dispatchers.IO) {
        try {
            val token = auth.verifyIdToken(idToken)
            @Suppress("UNCHECKED_CAST")
            val provider = (token.claims["firebase"] as? Map<String, Any?>)?.get("sign_in_provider") as? String
            token.email?.let { VerifiedIdentity(it, token.isEmailVerified, provider) }
        } catch (e: FirebaseAuthException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }
    }
}

/** App Distribution REST API v1. */
class FirebaseDistributionGateway(
    private val firebase: Firebase,
    private val projectNumber: String,
    private val groupAlias: String,
) : DistributionGateway {
    private val log = LoggerFactory.getLogger(FirebaseDistributionGateway::class.java)
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
    private val base = "https://firebaseappdistribution.googleapis.com/v1/projects/$projectNumber"
    private val group = "projects/$projectNumber/groups/$groupAlias"

    override suspend fun groupMembers(): Set<String> {
        ensureGroup()
        val emails = mutableSetOf<String>()
        var pageToken: String? = null
        do {
            val filter = URLEncoder.encode("groups=$group", Charsets.UTF_8)
            val url = "$base/testers?pageSize=1000&filter=$filter" + (pageToken?.let { "&pageToken=$it" } ?: "")
            val body = call("GET", url, null)
            body["testers"]?.jsonArray?.forEach { t ->
                t.jsonObject["name"]?.jsonPrimitive?.content?.substringAfterLast("/testers/")?.let { emails += it.lowercase() }
            }
            pageToken = body["nextPageToken"]?.jsonPrimitive?.content
        } while (pageToken != null)
        return emails
    }

    override suspend fun join(emails: Set<String>) {
        if (emails.isEmpty()) return
        ensureGroup()
        call("POST", "$base/groups/$groupAlias:batchJoin", buildJsonObject {
            putJsonArray("emails") { emails.forEach { add(it) } }
            put("createMissingTesters", true)
        })
    }

    override suspend fun leave(emails: Set<String>) {
        if (emails.isEmpty()) return
        call("POST", "$base/groups/$groupAlias:batchLeave", buildJsonObject {
            putJsonArray("emails") { emails.forEach { add(it) } }
        })
    }

    private var groupChecked = false

    private suspend fun ensureGroup() {
        if (groupChecked) return
        val status = rawCall("GET", "$base/groups/$groupAlias", null).statusCode()
        if (status == 404) {
            call("POST", "$base/groups?groupId=$groupAlias", buildJsonObject { put("displayName", "PushBeam Members") })
            log.info("distribution_group_created group={}", groupAlias)
        }
        groupChecked = true
    }

    private suspend fun call(method: String, url: String, body: JsonObject?): JsonObject {
        val res = rawCall(method, url, body)
        if (res.statusCode() !in 200..299) throw IOException("App Distribution $method $url -> ${res.statusCode()}: ${res.body().take(300)}")
        return if (res.body().isBlank()) JsonObject(emptyMap()) else Json.parseToJsonElement(res.body()).jsonObject
    }

    private suspend fun rawCall(method: String, url: String, body: JsonObject?): HttpResponse<String> = withContext(Dispatchers.IO) {
        firebase.credentials.refreshIfExpired()
        val req = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(30))
            .header("Authorization", "Bearer ${firebase.credentials.accessToken!!.tokenValue}")
            .header("Content-Type", "application/json")
            .method(method, body?.let { HttpRequest.BodyPublishers.ofString(it.toString()) } ?: HttpRequest.BodyPublishers.noBody())
            .build()
        http.send(req, HttpResponse.BodyHandlers.ofString())
    }
}
