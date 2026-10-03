package com.zyautra.pushbeam.server

import com.zyautra.pushbeam.server.api.ApiException
import com.zyautra.pushbeam.server.api.Services
import com.zyautra.pushbeam.server.api.adminRoutes
import com.zyautra.pushbeam.server.api.memberRoutes
import com.zyautra.pushbeam.server.api.messageRoutes
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import com.zyautra.pushbeam.shared.ApiError
import com.zyautra.pushbeam.shared.ApiErrorResponse
import com.zyautra.pushbeam.shared.ErrorCodes
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicBoolean

@Serializable
data class HealthStatus(val status: String)

class ServerState {
    val ready = AtomicBoolean(false)
}

private val log = LoggerFactory.getLogger("pushbeam")

fun Application.module(state: ServerState, services: Services? = null) {
    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true; explicitNulls = false })
    }
    install(StatusPages) {
        exception<ApiException> { call, e ->
            call.respond(e.status, ApiErrorResponse(ApiError(e.code, e.message ?: e.code)))
        }
        exception<BadRequestException> { call, e ->
            call.respond(HttpStatusCode.BadRequest, ApiErrorResponse(ApiError(ErrorCodes.INVALID_REQUEST, e.message ?: "Invalid request")))
        }
        exception<kotlinx.serialization.SerializationException> { call, e ->
            call.respond(HttpStatusCode.BadRequest, ApiErrorResponse(ApiError(ErrorCodes.INVALID_REQUEST, e.message ?: "Invalid request")))
        }
        exception<Throwable> { call, e ->
            log.error("unhandled_error method={} path={}", call.request.httpMethod.value, call.request.path(), e)
            call.respond(HttpStatusCode.InternalServerError, ApiErrorResponse(ApiError("INTERNAL", "Internal server error")))
        }
    }
    routing {
        get("/health/live") { call.respond(HealthStatus("UP")) }
        if (services != null) {
            messageRoutes(services)
            memberRoutes(services)
            adminRoutes(services)
        }
        get("/health/ready") {
            if (state.ready.get()) call.respond(HealthStatus("READY"))
            else call.respond(HttpStatusCode.ServiceUnavailable, HealthStatus("NOT_READY"))
        }
    }
}
