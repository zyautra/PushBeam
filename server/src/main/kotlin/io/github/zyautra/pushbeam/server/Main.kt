package io.github.zyautra.pushbeam.server

import io.github.zyautra.pushbeam.server.api.Services
import io.github.zyautra.pushbeam.server.auth.IdTokenVerifier
import io.github.zyautra.pushbeam.server.db.Database
import io.github.zyautra.pushbeam.server.delivery.Dispatcher
import io.github.zyautra.pushbeam.server.gateway.FcmGateway
import io.github.zyautra.pushbeam.server.gateway.FcmResult
import io.github.zyautra.pushbeam.server.gateway.Firebase
import io.github.zyautra.pushbeam.server.gateway.FirebaseDistributionGateway
import io.github.zyautra.pushbeam.server.gateway.FirebaseFcmGateway
import io.github.zyautra.pushbeam.server.gateway.FirebaseIdTokenVerifier
import io.github.zyautra.pushbeam.server.jobs.DistributionSync
import io.github.zyautra.pushbeam.server.jobs.Housekeeping
import io.github.zyautra.pushbeam.server.service.ChannelService
import io.github.zyautra.pushbeam.server.service.MemberService
import io.github.zyautra.pushbeam.server.service.MessageService
import io.github.zyautra.pushbeam.server.service.SenderService
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.time.Clock

fun main() {
    val log = LoggerFactory.getLogger("pushbeam")
    val config = Config.fromEnv()
    val clock = Clock.systemUTC()
    val db = Database(config.databaseFile)
    val state = ServerState()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val firebase = config.firebaseCredentials?.takeIf(Files::exists)?.let(::Firebase)
    if (firebase == null) log.warn("firebase_disabled credentials={}", config.firebaseCredentials)
    val fcm: FcmGateway = firebase?.let(::FirebaseFcmGateway) ?: FcmGateway { _, _, _ -> FcmResult.Retryable("firebase not configured") }
    val idTokens: IdTokenVerifier = firebase?.let(::FirebaseIdTokenVerifier) ?: IdTokenVerifier { null }
    val operatorToken = config.operatorTokenFile.takeIf(Files::exists)?.let { Files.readString(it).trim() }?.takeIf { it.isNotEmpty() }
    if (operatorToken == null) log.warn("operator_token_missing file={}", config.operatorTokenFile)

    val dispatcher = Dispatcher(db, fcm, clock)
    lateinit var members: MemberService
    val distribution = if (firebase != null && config.firebaseProjectNumber != null) {
        DistributionSync(FirebaseDistributionGateway(firebase, config.firebaseProjectNumber, config.distributionGroup), { members.allowedEmails() }, clock)
    } else null
    members = MemberService(db, clock) { distribution?.request() }
    val housekeeping = Housekeeping(db, config.dataDir.resolve("backup"), clock)
    val statusService = StatusService(db, state, distribution, housekeeping)

    val services = Services(
        members = members,
        channels = ChannelService(db, clock),
        senders = SenderService(db, clock),
        messages = MessageService(db, clock, config.defaultTimeZone) { dispatcher.wakeUp() },
        idTokens = idTokens,
        operatorToken = operatorToken,
        status = statusService::status,
        requestDistributionSync = { distribution?.request() },
    )

    val server = embeddedServer(Netty, host = config.host, port = config.port) { module(state, services) }
    Runtime.getRuntime().addShutdownHook(Thread {
        log.info("server_stopping")
        state.ready.set(false)
        server.stop(gracePeriodMillis = 5_000, timeoutMillis = 10_000)
        scope.cancel()
        db.close()
    })
    server.start(wait = false)
    dispatcher.start(scope)
    distribution?.start(scope)
    housekeeping.start(scope)
    state.ready.set(true)
    log.info("server_ready host={} port={}", config.host, config.port)
    Thread.currentThread().join()
}
