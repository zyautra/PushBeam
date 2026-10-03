package io.github.zyautra.pushbeam.shared

/** 서버와 관리 도구가 같이 쓰는 입력 규칙 (docs/02, docs/03). */
object Validation {
    private val slug = Regex("^[a-z0-9-]{3,40}$")
    private val dataKey = Regex("^[A-Za-z0-9_.-]{1,64}$")

    const val TITLE_MAX = 100
    const val BODY_MAX = 1000
    const val DATA_MAX_ENTRIES = 16
    const val DATA_VALUE_MAX = 256

    fun isChannelSlug(value: String) = slug.matches(value)
    fun isDataKey(value: String) = dataKey.matches(value)
    fun normalizeEmail(value: String) = value.trim().lowercase()
    fun isEmail(value: String) = value.length in 3..254 && value.count { it == '@' } == 1 &&
        value.substringBefore('@').isNotEmpty() && value.substringAfter('@').contains('.')
}
