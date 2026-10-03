package com.zyautra.pushbeam.server

import java.nio.file.Path
import java.time.ZoneId

/** 서버 설정. 모두 환경 변수에서 읽는다 (docs/01, docs/04). */
data class Config(
    val host: String,
    val port: Int,
    val dataDir: Path,
    val defaultTimeZone: ZoneId,
) {
    val databaseFile: Path get() = dataDir.resolve("pushbeam.db")

    companion object {
        fun fromEnv(env: Map<String, String> = System.getenv()): Config {
            val listen = env["PUSHBEAM_LISTEN"] ?: "0.0.0.0:8080"
            return Config(
                host = listen.substringBeforeLast(':'),
                port = listen.substringAfterLast(':').toInt(),
                dataDir = Path.of(env["PUSHBEAM_DATA_DIR"] ?: "/data"),
                defaultTimeZone = ZoneId.of(env["PUSHBEAM_DEFAULT_TIME_ZONE"] ?: "Asia/Seoul"),
            )
        }
    }
}
