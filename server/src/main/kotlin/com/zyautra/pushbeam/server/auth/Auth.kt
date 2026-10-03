package com.zyautra.pushbeam.server.auth

import com.zyautra.pushbeam.server.api.ApiException
import com.zyautra.pushbeam.shared.ErrorCodes
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import java.security.MessageDigest
import java.security.SecureRandom

/** 요청을 보낸 주체 (docs/04 3절). */
sealed interface Principal {
    data object Operator : Principal
    data class Sender(val id: String, val allowedChannels: Set<String>) : Principal
    data class Member(val id: String, val email: String) : Principal
}

/** Firebase ID Token을 검증한 결과. */
data class VerifiedIdentity(val email: String, val emailVerified: Boolean, val provider: String?)

fun interface IdTokenVerifier {
    /** 유효하지 않으면 null. */
    suspend fun verify(idToken: String): VerifiedIdentity?
}

object Secrets {
    private val random = SecureRandom()
    private const val ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"

    /** base62 40자 (약 238 bit). */
    fun newToken(prefix: String): String =
        prefix + String(CharArray(40) { ALPHABET[random.nextInt(ALPHABET.length)] })

    fun sha256Hex(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

    fun constantTimeEquals(a: String, b: String): Boolean =
        MessageDigest.isEqual(a.toByteArray(), b.toByteArray())
}

const val SENDER_KEY_PREFIX = "pbs_"

fun ApplicationCall.bearerToken(): String? =
    request.headers[HttpHeaders.Authorization]
        ?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }
        ?.substring(7)?.trim()
        ?.takeIf { it.isNotEmpty() }

fun unauthorized(message: String = "Authentication required") =
    ApiException(HttpStatusCode.Unauthorized, ErrorCodes.UNAUTHORIZED, message)

fun forbidden(code: String, message: String) = ApiException(HttpStatusCode.Forbidden, code, message)
