package com.zyautra.pushbeam.server

import java.nio.file.Path
import java.time.ZoneId

/** 서버 설정. 모두 환경 변수에서 읽는다 (docs/01, docs/04). */
data class Config(
    val host: String,
    val port: Int,
    val dataDir: Path,
    val defaultTimeZone: ZoneId,
    /** Firebase 서비스 계정 키. 없으면 FCM·로그인·배포 동기화가 꺼진 채로 뜬다 (로컬 개발용). */
    val firebaseCredentials: Path?,
    val firebaseProjectNumber: String?,
    val distributionGroup: String,
    val operatorTokenFile: Path,
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
                firebaseCredentials = (env["PUSHBEAM_FIREBASE_CREDENTIALS"] ?: "/run/secrets/firebase-service-account.json")
                    .takeIf { it.isNotBlank() }?.let(Path::of),
                firebaseProjectNumber = env["PUSHBEAM_FIREBASE_PROJECT_NUMBER"]?.takeIf { it.isNotBlank() },
                distributionGroup = env["PUSHBEAM_DISTRIBUTION_GROUP"] ?: "pushbeam-members",
                operatorTokenFile = Path.of(env["PUSHBEAM_OPERATOR_TOKEN_FILE"] ?: "/run/secrets/operator-token"),
            )
        }
    }
}
