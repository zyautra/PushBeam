package com.zyautra.pushbeam.admin

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.core.findOrSetObject
import com.github.ajalt.clikt.core.requireObject
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.options.split
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.path
import com.zyautra.pushbeam.shared.Severity
import com.zyautra.pushbeam.shared.api.CreateChannelRequest
import com.zyautra.pushbeam.shared.api.CreateSenderRequest
import com.zyautra.pushbeam.shared.api.MessageTarget
import com.zyautra.pushbeam.shared.api.SendMessageRequest
import com.zyautra.pushbeam.shared.api.UpdateChannelRequest
import io.ktor.client.engine.HttpClientEngine
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom

/** 명령들이 함께 쓰는 상태. 테스트에서는 engine과 config를 바꿔 넣는다. */
class Session(
    var json: Boolean = false,
    var configFile: Path = AdminConfig.defaultFile,
    val engine: HttpClientEngine? = null,
    val configOverride: AdminConfig? = null,
) {
    fun <T> call(block: suspend (AdminClient) -> T): T {
        val config = configOverride ?: AdminConfig.load(configFile)
        return AdminClient(config, engine).use { runBlocking { block(it) } }
    }
}

abstract class AdminCommand(name: String, private val helpText: String) : CliktCommand(name) {
    protected val session by requireObject<Session>()
    override fun help(context: Context) = helpText

    /** --json이면 서버 응답 원문을, 아니면 사람이 읽는 형태를 출력한다. */
    protected fun <T> show(result: AdminClient.Result<T>, human: (T) -> String) =
        echo(if (session.json) result.raw else human(result.value))

    override fun run() = try {
        execute()
    } catch (e: AdminException) {
        echo("오류: ${explain(e.code)} (${e.code}) ${e.message ?: ""}".trimEnd(), err = true)
        throw ProgramResult(1)
    }

    abstract fun execute()
}

class Group(name: String, private val helpText: String) : CliktCommand(name) {
    override fun help(context: Context) = helpText
    override fun run() = Unit
}

class PushBeamAdmin(private val base: Session = Session()) : CliktCommand("pushbeam-admin") {
    private val json by option("--json", help = "서버 응답 JSON을 그대로 출력").flag()
    private val config by option("--config", help = "설정 파일 (기본 ~/.config/pushbeam/admin.properties)").path()
    private val session by findOrSetObject { base }

    override fun help(context: Context) = "PushBeam 운영 도구"

    override fun run() {
        session.json = json
        config?.let { session.configFile = it }
    }
}

// ---- status ----

class StatusCmd : AdminCommand("status", "서버 상태") {
    override fun execute() = show(session.call { it.status() }) { s ->
        keyValues(
            listOf(
                "상태" to if (s.ready) "READY" else "NOT READY",
                "Member" to s.members.entries.joinToString(", ") { "${it.key} ${it.value}" }.ifEmpty { "0" },
                "활성 기기" to s.activeDevices.toString(),
                "대기 전송" to s.pendingDeliveries.toString(),
                "가장 오래된 대기" to time(s.oldestPendingAt),
                "배포 그룹 동기화" to time(s.lastDistributionSyncAt),
                "배포 그룹 오류" to s.lastDistributionError,
                "마지막 백업" to time(s.lastBackupAt),
            ),
        )
    }
}

// ---- members ----

class MembersList : AdminCommand("list", "Member 목록") {
    private val status by option("--status", help = "INVITED, ACTIVE, REVOKED").choice("INVITED", "ACTIVE", "REVOKED", ignoreCase = true)
    override fun execute() = show(session.call { it.members(status?.uppercase()) }) { list ->
        table(listOf("EMAIL", "이름", "상태", "허용", "마지막 접속"), list.map { listOf(it.email, it.displayName, it.status, time(it.allowedAt), time(it.lastSeenAt)) })
    }
}

class MembersShow : AdminCommand("show", "Member 상세 (기기, 구독)") {
    private val email by argument()
    override fun execute() = show(session.call { it.member(email) }) { d ->
        val m = d.member
        keyValues(listOf("EMAIL" to m.email, "이름" to m.displayName, "상태" to m.status, "허용" to time(m.allowedAt),
            "로그인" to time(m.activatedAt), "취소" to time(m.revokedAt), "마지막 접속" to time(m.lastSeenAt))) +
            "\n\n기기\n" + table(listOf("ID", "활성", "모델", "앱 버전", "마지막 등록"), d.devices.map { listOf(it.id, if (it.active) "예" else "아니오", it.model, it.appVersion?.toString(), time(it.lastSeenAt)) }) +
            "\n\n구독\n" + table(listOf("채널", "최소 중요도", "음소거"), d.subscriptions.map { listOf(it.channel, it.minSeverity.wire, if (!it.muted) "아니오" else time(it.mutedUntil)?.let { t -> "$t 까지" } ?: "직접 끌 때까지") })
    }
}

class MembersAllow : AdminCommand("allow", "허용 (취소된 사람이면 다시 허용)") {
    private val email by argument()
    private val name by option("--name", help = "표시 이름")
    override fun execute() = show(session.call { it.allow(email, name) }) { "${it.email}: ${it.status}" }
}

class MembersRevoke : AdminCommand("revoke", "허용 취소 (기기 해제, 대기 전송 취소, 구독 삭제)") {
    private val email by argument()
    override fun execute() = show(session.call { it.revoke(email) }) { "${it.email}: ${it.status}" }
}

// ---- channels ----

class ChannelsList : AdminCommand("list", "채널 목록") {
    override fun execute() = show(session.call { it.channels() }) { list ->
        table(listOf("SLUG", "이름", "필수", "자동 구독", "보관", "구독자"), list.map {
            listOf(it.slug, it.name, yn(it.required), yn(it.autoSubscribe), yn(it.archived), it.subscribers.toString())
        })
    }
}

class ChannelsCreate : AdminCommand("create", "채널 만들기") {
    private val slug by argument()
    private val name by option("--name").required()
    private val description by option("--description")
    private val required by option("--required", help = "모든 Member가 구독하고 해제할 수 없음").flag()
    private val auto by option("--auto", help = "새 Member가 처음에 구독").flag()
    override fun execute() = show(session.call { it.createChannel(CreateChannelRequest(slug, name, description, required, auto)) }) { "${it.slug} 만들었어요 (구독자 ${it.subscribers})" }
}

class ChannelsSet : AdminCommand("set", "채널 변경") {
    private val slug by argument()
    private val name by option("--name")
    private val description by option("--description")
    private val required by option("--required").choice("yes" to true, "no" to false)
    private val auto by option("--auto").choice("yes" to true, "no" to false)
    override fun execute() = show(session.call { it.updateChannel(slug, UpdateChannelRequest(name, description, required, auto)) }) {
        "${it.slug}: 필수 ${yn(it.required)}, 자동 구독 ${yn(it.autoSubscribe)}"
    }
}

class ChannelsArchive : AdminCommand("archive", "채널 보관 (보이지 않고 발송 불가)") {
    private val slug by argument()
    private val undo by option("--undo", help = "보관 해제").flag()
    override fun execute() = show(session.call { it.archiveChannel(slug, !undo) }) { "${it.slug}: 보관 ${yn(it.archived)}" }
}

class ChannelsSubscribe : AdminCommand("subscribe", "Member를 채널에 구독시키기") {
    private val slug by argument()
    private val email by argument()
    override fun execute() {
        session.call { it.subscribe(slug, email) }
        echo("$email → $slug 구독")
    }
}

// ---- senders ----

class SendersList : AdminCommand("list", "Sender 목록") {
    override fun execute() = show(session.call { it.senders() }) { list ->
        table(listOf("ID", "이름", "채널", "마지막 사용", "폐기"), list.map {
            listOf(it.id, it.name, it.allowedChannels.joinToString(","), time(it.lastUsedAt), time(it.revokedAt))
        })
    }
}

class SendersCreate : AdminCommand("create", "Sender 발급. 키는 이때 한 번만 보인다") {
    private val name by argument()
    private val channels by option("--channels", help = "보낼 수 있는 채널 (쉼표로 구분)").split(",").required()
    override fun execute() = show(session.call { it.createSender(CreateSenderRequest(name, channels)) }) {
        "${it.sender.name} (${it.sender.id})\n\nSender Key: ${it.key}\n\n이 키는 다시 볼 수 없어요. Sender 쪽 비밀 설정에 바로 저장하세요."
    }
}

class SendersSet : AdminCommand("set", "Sender의 채널 바꾸기") {
    private val id by argument()
    private val channels by option("--channels").split(",").required()
    override fun execute() = show(session.call { it.updateSender(id, channels) }) { "${it.name}: ${it.allowedChannels.joinToString(",")}" }
}

class SendersRevoke : AdminCommand("revoke", "Sender 폐기") {
    private val id by argument()
    override fun execute() = show(session.call { it.revokeSender(id) }) { "${it.name}: 폐기 ${time(it.revokedAt)}" }
}

// ---- send ----

class SendCmd : AdminCommand("send", "알림 보내기") {
    private val channel by option("--channel")
    private val users by option("--users", help = "이메일 (쉼표로 구분)").split(",")
    private val all by option("--all", help = "모든 ACTIVE Member").flag()
    private val title by option("--title").required()
    private val body by option("--body").required()
    private val severity by option("--severity").choice("critical", "high", "normal", "low").default("normal")
    private val data by option("--data", help = "추가 데이터 key=value (여러 번)").multiple()

    override fun execute() {
        if (listOf(channel != null, users != null, all).count { it } != 1) throw UsageError("--channel, --users, --all 중 하나만 지정하세요")
        val extra = data.associate { kv ->
            val i = kv.indexOf('=')
            if (i <= 0) throw UsageError("--data는 key=value 형식이에요: $kv")
            kv.substring(0, i) to kv.substring(i + 1)
        }
        val req = SendMessageRequest(
            target = MessageTarget(channel = channel, users = users, all = if (all) true else null),
            title = title, body = body, severity = Severity.fromWire(severity)!!, data = extra.ifEmpty { null },
        )
        show(session.call { it.sendMessage(req) }) {
            "${it.messageId}\n보냄 ${it.recipients.deliver}, 무음 ${it.recipients.quiet}, 목록만 ${it.recipients.inboxOnly}, 안 보냄 ${it.recipients.skipped} (기기 ${it.deliveries}대)"
        }
    }
}

// ---- messages ----

class MessagesList : AdminCommand("list", "보낸 알림 목록 (최신 순)") {
    private val limit by option("--limit").int().default(20)
    private val cursor by option("--cursor", help = "이전 출력의 다음 페이지 값")
    override fun execute() = show(session.call { it.messages(limit, cursor) }) { page ->
        table(listOf("ID", "시각", "보낸 곳", "대상", "중요도", "제목", "전송"), page.items.map { m ->
            val target = m.target.channel ?: m.target.users?.joinToString(",") ?: "전체"
            val d = m.deliveries
            listOf(m.messageId, time(m.createdAt), m.sender, target, m.severity.wire, m.title,
                "성공 ${d.sent} 대기 ${d.pending} 실패 ${d.failed + d.invalidToken} 취소 ${d.cancelled}")
        }) + (page.nextCursor?.let { "\n\n다음 페이지: --cursor $it" } ?: "")
    }
}

class MessagesShow : AdminCommand("show", "알림 상세 (Member별 결과, 기기별 전송)") {
    private val id by argument()
    override fun execute() = show(session.call { it.message(id) }) { m ->
        keyValues(listOf("ID" to m.messageId, "시각" to time(m.createdAt), "보낸 곳" to m.sender,
            "대상" to (m.target.channel ?: m.target.users?.joinToString(",") ?: "전체"),
            "중요도" to m.severity.wire, "제목" to m.title, "본문" to m.body,
            "추가 데이터" to m.data?.entries?.joinToString(", ") { "${it.key}=${it.value}" })) +
            "\n\n" + table(listOf("EMAIL", "결과", "기기", "전송", "시도", "오류"), m.recipients.flatMap { r ->
                if (r.deliveries.isEmpty()) listOf(listOf(r.email, r.result, null, null, null, null))
                else r.deliveries.map { d -> listOf(r.email, r.result, d.deviceModel ?: d.deviceId, d.status + when (d.display) { "quiet" -> " (무음)"; "inbox" -> " (목록만)"; else -> "" }, d.attempts.toString(), d.lastError) }
            })
    }
}

// ---- distribution, token ----

class DistributionSync : AdminCommand("sync", "App Distribution 그룹을 허용 목록에 맞추기") {
    override fun execute() {
        session.call { it.distributionSync() }
        echo("동기화를 요청했어요. 결과는 status에서 확인하세요.")
    }
}

class TokenGenerate : CliktCommand("generate") {
    private val output by option("--output", help = "토큰을 저장할 파일 (권한 0600)").path()
    override fun help(context: Context) = "Operator Token 만들기 (서버에 연결하지 않음)"
    override fun run() {
        val alphabet = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
        val random = SecureRandom()
        val token = "pbo_" + String(CharArray(40) { alphabet[random.nextInt(alphabet.length)] })
        val out = output
        if (out == null) {
            echo(token)
        } else {
            if (Files.exists(out)) throw UsageError("이미 있는 파일이에요: $out")
            out.parent?.let(Files::createDirectories)
            Files.createFile(out, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
            Files.writeString(out, token)
            echo("$out 에 저장했어요. 서버 Secret의 operator-token에 같은 값을 넣으세요.")
        }
    }
}

private fun yn(b: Boolean) = if (b) "예" else "아니오"

fun buildCli(session: Session = Session()): CliktCommand = PushBeamAdmin(session).subcommands(
    StatusCmd(),
    Group("members", "Member (허용 목록)").subcommands(MembersList(), MembersShow(), MembersAllow(), MembersRevoke()),
    Group("channels", "채널").subcommands(ChannelsList(), ChannelsCreate(), ChannelsSet(), ChannelsArchive(), ChannelsSubscribe()),
    Group("senders", "Sender (발송 키)").subcommands(SendersList(), SendersCreate(), SendersSet(), SendersRevoke()),
    SendCmd(),
    Group("messages", "보낸 알림 기록").subcommands(MessagesList(), MessagesShow()),
    Group("distribution", "App Distribution 그룹").subcommands(DistributionSync()),
    Group("token", "Operator Token").subcommands(TokenGenerate()),
)
