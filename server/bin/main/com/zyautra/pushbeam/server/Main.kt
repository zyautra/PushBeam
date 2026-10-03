package com.zyautra.pushbeam.server

import com.zyautra.pushbeam.server.db.Database
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import org.slf4j.LoggerFactory

fun main() {
    val log = LoggerFactory.getLogger("pushbeam")
    val config = Config.fromEnv()
    val db = Database(config.databaseFile)
    val state = ServerState()

    val server = embeddedServer(Netty, host = config.host, port = config.port) { module(state) }
    Runtime.getRuntime().addShutdownHook(Thread {
        state.ready.set(false)
        server.stop(gracePeriodMillis = 5_000, timeoutMillis = 10_000)
        db.close()
    })
    server.start(wait = false)
    state.ready.set(true)
    log.info("server_ready host={} port={}", config.host, config.port)
    Thread.currentThread().join()
}
