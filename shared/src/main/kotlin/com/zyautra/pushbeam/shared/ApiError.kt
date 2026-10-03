package com.zyautra.pushbeam.shared

import kotlinx.serialization.Serializable

/** 모든 API 오류 응답의 형식 (docs/03 2.1절). */
@Serializable
data class ApiErrorResponse(val error: ApiError)

@Serializable
data class ApiError(val code: String, val message: String)

object ErrorCodes {
    const val INVALID_REQUEST = "INVALID_REQUEST"
    const val UNAUTHORIZED = "UNAUTHORIZED"
    const val NOT_ALLOWLISTED = "NOT_ALLOWLISTED"
    const val MEMBER_REVOKED = "MEMBER_REVOKED"
    const val FORBIDDEN = "FORBIDDEN"
    const val NOT_FOUND = "NOT_FOUND"
    const val ALREADY_EXISTS = "ALREADY_EXISTS"
    const val CHANNEL_REQUIRED = "CHANNEL_REQUIRED"
    const val PAYLOAD_TOO_LARGE = "PAYLOAD_TOO_LARGE"
    const val UNKNOWN_RECIPIENT = "UNKNOWN_RECIPIENT"
    const val SERVER_NOT_READY = "SERVER_NOT_READY"
}
