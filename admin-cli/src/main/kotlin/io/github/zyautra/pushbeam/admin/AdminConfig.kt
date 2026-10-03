package io.github.zyautra.pushbeam.admin

import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties

/**
 * 서버 주소와 Operator Token.
 * 우선순위: 환경 변수(PUSHBEAM_URL, PUSHBEAM_OPERATOR_TOKEN) > 설정 파일(~/.config/pushbeam/admin.properties).
 */
data class AdminConfig(val url: String, val token: String) {
    companion object {
        val defaultFile: Path = Path.of(System.getProperty("user.home"), ".config", "pushbeam", "admin.properties")

        fun load(file: Path = defaultFile, env: Map<String, String> = System.getenv()): AdminConfig {
            val props = Properties()
            if (Files.exists(file)) Files.newBufferedReader(file).use(props::load)

            val url = env["PUSHBEAM_URL"] ?: props.getProperty("url")
                ?: throw AdminException("CONFIG", "서버 주소가 없어요. $file 에 url=... 을 넣거나 PUSHBEAM_URL을 설정하세요.")
            val token = env["PUSHBEAM_OPERATOR_TOKEN"]
                ?: props.getProperty("token-file")?.let { readTokenFile(expandHome(it)) }
                ?: props.getProperty("token")
                ?: throw AdminException("CONFIG", "Operator Token이 없어요. $file 에 token-file=... 을 넣거나 PUSHBEAM_OPERATOR_TOKEN을 설정하세요.")
            if (!url.startsWith("https://")) throw AdminException("CONFIG", "서버 주소는 https:// 여야 해요: $url")
            return AdminConfig(url.trimEnd('/'), token.trim())
        }

        private fun expandHome(path: String): Path =
            if (path.startsWith("~/")) Path.of(System.getProperty("user.home"), path.substring(2)) else Path.of(path)

        private fun readTokenFile(path: Path): String {
            if (!Files.exists(path)) throw AdminException("CONFIG", "Operator Token 파일이 없어요: $path")
            return Files.readString(path).trim()
        }
    }
}

/** 설정 오류나 서버 오류. code는 서버 오류 코드(docs/03 2.1) 또는 CONFIG, NETWORK. */
class AdminException(val code: String, message: String, val status: Int? = null) : Exception(message)
