package io.github.zyautra.pushbeam

import android.app.Application
import com.google.firebase.messaging.FirebaseMessaging
import io.github.zyautra.pushbeam.data.ApiClient
import io.github.zyautra.pushbeam.data.RegistrationStore
import io.github.zyautra.pushbeam.data.SessionRepository
import io.github.zyautra.pushbeam.data.inbox.InboxDatabase
import io.github.zyautra.pushbeam.push.NotificationPresenter
import io.github.zyautra.pushbeam.push.RegistrationWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import java.time.Duration
import java.time.ZoneId

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        graph.notifications.createChannels()
    }

    companion object {
        lateinit var graph: AppGraph
            private set
    }
}

/** 앱 전체에서 하나만 있는 객체들. */
class AppGraph(private val app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val session = SessionRepository(app)
    val api = ApiClient(BuildConfig.PUSHBEAM_URL, session)
    val registration = RegistrationStore(app)
    private val database = InboxDatabase.open(app)
    val inbox = database.inbox()
    val notificationPrefs = io.github.zyautra.pushbeam.data.NotificationPrefs(app)
    val notifications = NotificationPresenter(app, notificationPrefs)

    /** 서버가 접근을 거부한 이유 (NOT_ALLOWLISTED, MEMBER_REVOKED). 화면이 보고 안내한다. */
    private val _accessDenied = MutableStateFlow<String?>(null)
    val accessDenied: StateFlow<String?> = _accessDenied

    fun requestRegistration() {
        runBlocking { registration.markNeeded() }
        RegistrationWorker.enqueue(app)
    }

    /** 앱이 화면에 나올 때: 업데이트·시간대 변경·7일 경과면 다시 등록하고 오래된 Inbox를 지운다. */
    fun onForeground() = scope.launch {
        inbox.deleteOlderThan(System.currentTimeMillis() - Duration.ofDays(90).toMillis())
        if (!session.isSignedIn) return@launch
        val s = registration.current()
        val stale = s.lastRegisteredAt == null ||
            System.currentTimeMillis() - s.lastRegisteredAt > Duration.ofDays(7).toMillis()
        if (s.needsRegistration || stale || s.lastAppVersion != BuildConfig.VERSION_CODE ||
            s.lastTimeZone != ZoneId.systemDefault().id
        ) requestRegistration()
    }

    /** 로그인 직후. 다른 계정이면 이전 계정의 Inbox를 비운다. */
    suspend fun onSignedIn(email: String) {
        _accessDenied.value = null
        if (registration.setMember(email)) inbox.deleteAll()
        requestRegistration()
    }

    suspend fun signOut(unregister: Boolean = true) {
        if (unregister) runCatching { api.unregisterDevice(registration.installationId()) }
        runCatching { FirebaseMessaging.getInstance().deleteToken().await() }
        session.signOut()
        registration.setMember(null)
    }

    suspend fun onAccessDenied(code: String) {
        _accessDenied.value = code
        signOut(unregister = false)
    }

}
