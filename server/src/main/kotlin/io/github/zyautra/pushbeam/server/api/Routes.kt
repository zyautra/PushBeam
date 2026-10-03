package io.github.zyautra.pushbeam.server.api

import io.github.zyautra.pushbeam.server.auth.IdTokenVerifier
import io.github.zyautra.pushbeam.server.auth.Principal
import io.github.zyautra.pushbeam.server.auth.SENDER_KEY_PREFIX
import io.github.zyautra.pushbeam.server.auth.Secrets
import io.github.zyautra.pushbeam.server.auth.bearerToken
import io.github.zyautra.pushbeam.server.auth.unauthorized
import io.github.zyautra.pushbeam.server.service.ChannelService
import io.github.zyautra.pushbeam.server.service.MemberService
import io.github.zyautra.pushbeam.server.service.MemberStatus
import io.github.zyautra.pushbeam.server.service.MessageService
import io.github.zyautra.pushbeam.server.service.SenderService
import io.github.zyautra.pushbeam.shared.Severity
import io.github.zyautra.pushbeam.shared.api.AllowMemberRequest
import io.github.zyautra.pushbeam.shared.api.CreateChannelRequest
import io.github.zyautra.pushbeam.shared.api.CreateSenderRequest
import io.github.zyautra.pushbeam.shared.api.MemberChannelsResponse
import io.github.zyautra.pushbeam.shared.api.QuietHoursDto
import io.github.zyautra.pushbeam.shared.api.RegisterDeviceRequest
import io.github.zyautra.pushbeam.shared.api.RegisterDeviceResponse
import io.github.zyautra.pushbeam.shared.api.SendMessageRequest
import io.github.zyautra.pushbeam.shared.api.ServerStatus
import io.github.zyautra.pushbeam.shared.api.SubscriptionPatch
import io.github.zyautra.pushbeam.shared.api.UpdateChannelRequest
import io.github.zyautra.pushbeam.shared.api.UpdateSenderRequest
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class Services(
    val members: MemberService,
    val channels: ChannelService,
    val senders: SenderService,
    val messages: MessageService,
    val idTokens: IdTokenVerifier,
    /** 없으면 Admin API를 쓸 수 없다. */
    val operatorToken: String?,
    val status: suspend () -> ServerStatus,
    val requestDistributionSync: () -> Unit = {},
)

// ---- 인증 (docs/04 3절) ----

suspend fun ApplicationCall.requireOperator(s: Services): Principal.Operator {
    val token = bearerToken() ?: throw unauthorized()
    val expected = s.operatorToken ?: throw unauthorized("Operator access is not configured")
    if (!Secrets.constantTimeEquals(token, expected)) throw unauthorized("Invalid operator token")
    return Principal.Operator
}

suspend fun ApplicationCall.requireSender(s: Services): Principal {
    val token = bearerToken() ?: throw unauthorized()
    if (token.startsWith(SENDER_KEY_PREFIX)) return s.senders.authenticate(token) ?: throw unauthorized("Invalid sender key")
    return requireOperator(s)
}

suspend fun ApplicationCall.requireMember(s: Services): Principal.Member {
    val token = bearerToken() ?: throw unauthorized()
    val identity = s.idTokens.verify(token) ?: throw unauthorized("Invalid ID token")
    if (!identity.emailVerified || identity.provider != "google.com") throw unauthorized("A verified Google account is required")
    return s.members.authenticate(identity.email)
}

// ---- 발송 API ----

fun Route.messageRoutes(s: Services) {
    post("/api/v1/messages") {
        val sender = call.requireSender(s)
        val req = call.receive<SendMessageRequest>()
        call.respond(HttpStatusCode.Accepted, s.messages.send(sender, req))
    }
    get("/api/v1/messages/{id}") {
        val sender = call.requireSender(s)
        call.respond(s.messages.status(sender, call.parameters["id"]!!))
    }
}

// ---- Member API ----

@Serializable
private data class SubscribeRequest(val minSeverity: Severity? = null)

fun Route.memberRoutes(s: Services) = route("/api/v1/me") {
    get {
        call.respond(s.members.me(call.requireMember(s)))
    }
    put("/devices/{installationId}") {
        val m = call.requireMember(s)
        val id = s.members.registerDevice(m, call.parameters["installationId"]!!, call.receive<RegisterDeviceRequest>())
        call.respond(RegisterDeviceResponse(id))
    }
    delete("/devices/{installationId}") {
        s.members.unregisterDevice(call.requireMember(s), call.parameters["installationId"]!!)
        call.respond(HttpStatusCode.NoContent)
    }
    get("/channels") {
        call.respond(MemberChannelsResponse(s.channels.memberChannels(call.requireMember(s))))
    }
    put("/channels/{slug}") {
        val m = call.requireMember(s)
        val body = call.receiveText().takeIf { it.isNotBlank() }?.let { Json.decodeFromString<SubscribeRequest>(it) }
        call.respond(s.channels.subscribe(m, call.parameters["slug"]!!, body?.minSeverity))
    }
    delete("/channels/{slug}") {
        call.respond(s.channels.unsubscribe(call.requireMember(s), call.parameters["slug"]!!))
    }
    patch("/channels/{slug}") {
        val m = call.requireMember(s)
        call.respond(s.channels.patch(m, call.parameters["slug"]!!, call.receive<SubscriptionPatch>()))
    }
    put("/quiet-hours") {
        val m = call.requireMember(s)
        call.respond(s.members.setQuietHours(m, call.receive<QuietHoursDto>()))
    }
}

// ---- Admin API ----

fun Route.adminRoutes(s: Services) = route("/api/v1/admin") {
    get("/status") {
        call.requireOperator(s)
        call.respond(s.status())
    }
    route("/members") {
        get {
            call.requireOperator(s)
            val status = call.request.queryParameters["status"]?.let {
                runCatching { MemberStatus.valueOf(it.uppercase()) }.getOrNull() ?: throw ApiException.invalid("Unknown status: $it")
            }
            call.respond(s.members.list(status))
        }
        post {
            call.requireOperator(s)
            val req = call.receive<AllowMemberRequest>()
            call.respond(s.members.allow(req.email, req.displayName))
        }
        get("/{email}") {
            call.requireOperator(s)
            call.respond(s.members.detail(call.parameters["email"]!!))
        }
        post("/{email}/revoke") {
            call.requireOperator(s)
            call.respond(s.members.revoke(call.parameters["email"]!!))
        }
    }
    route("/channels") {
        get {
            call.requireOperator(s)
            call.respond(s.channels.list())
        }
        post {
            call.requireOperator(s)
            call.respond(HttpStatusCode.Created, s.channels.create(call.receive<CreateChannelRequest>()))
        }
        patch("/{slug}") {
            call.requireOperator(s)
            call.respond(s.channels.update(call.parameters["slug"]!!, call.receive<UpdateChannelRequest>()))
        }
        post("/{slug}/archive") {
            call.requireOperator(s)
            call.respond(s.channels.setArchived(call.parameters["slug"]!!, true))
        }
        post("/{slug}/unarchive") {
            call.requireOperator(s)
            call.respond(s.channels.setArchived(call.parameters["slug"]!!, false))
        }
        put("/{slug}/members/{email}") {
            call.requireOperator(s)
            s.channels.subscribeMember(call.parameters["slug"]!!, call.parameters["email"]!!)
            call.respond(HttpStatusCode.NoContent)
        }
    }
    route("/senders") {
        get {
            call.requireOperator(s)
            call.respond(s.senders.list())
        }
        post {
            call.requireOperator(s)
            call.respond(HttpStatusCode.Created, s.senders.create(call.receive<CreateSenderRequest>()))
        }
        patch("/{id}") {
            call.requireOperator(s)
            call.respond(s.senders.updateChannels(call.parameters["id"]!!, call.receive<UpdateSenderRequest>().allowedChannels))
        }
        post("/{id}/revoke") {
            call.requireOperator(s)
            call.respond(s.senders.revoke(call.parameters["id"]!!))
        }
    }
    post("/distribution/sync") {
        call.requireOperator(s)
        s.requestDistributionSync()
        call.respond(HttpStatusCode.Accepted)
    }
    route("/messages") {
        get {
            call.requireOperator(s)
            val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 50
            call.respond(s.messages.list(limit, call.request.queryParameters["cursor"]))
        }
        get("/{id}") {
            call.requireOperator(s)
            call.respond(s.messages.detail(call.parameters["id"]!!))
        }
    }
}
