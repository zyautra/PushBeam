package io.github.zyautra.pushbeam.server.api

import io.github.zyautra.pushbeam.shared.ErrorCodes
import io.ktor.http.HttpStatusCode

/** docs/03 2.1의 오류 응답으로 바뀌는 예외. */
class ApiException(val status: HttpStatusCode, val code: String, message: String) : RuntimeException(message) {
    companion object {
        fun invalid(message: String) = ApiException(HttpStatusCode.BadRequest, ErrorCodes.INVALID_REQUEST, message)
        fun notFound(message: String) = ApiException(HttpStatusCode.NotFound, ErrorCodes.NOT_FOUND, message)
    }
}
