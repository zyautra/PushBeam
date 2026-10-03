package com.zyautra.pushbeam.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zyautra.pushbeam.App
import com.zyautra.pushbeam.data.ApiException
import com.zyautra.pushbeam.data.inbox.InboxEntry
import com.zyautra.pushbeam.shared.ErrorCodes
import com.zyautra.pushbeam.shared.Severity
import com.zyautra.pushbeam.shared.api.MeResponse
import com.zyautra.pushbeam.shared.api.MemberChannel
import com.zyautra.pushbeam.shared.api.QuietHoursDto
import com.zyautra.pushbeam.shared.api.SubscriptionPatch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException

sealed interface Gate {
    data object Loading : Gate
    data class SignedOut(val error: String? = null) : Gate
    /** NOT_ALLOWLISTED 또는 MEMBER_REVOKED. */
    data class Denied(val code: String, val email: String?) : Gate
    data class Ready(val me: MeResponse?, val offline: Boolean = false) : Gate
}

data class ChannelsState(
    val channels: List<MemberChannel> = emptyList(),
    val loading: Boolean = false,
    val offline: Boolean = false,
    val updating: String? = null,
    val error: String? = null,
)

class AppViewModel : ViewModel() {
    private val graph = App.graph

    private val _gate = MutableStateFlow<Gate>(Gate.Loading)
    val gate: StateFlow<Gate> = _gate.asStateFlow()

    private val _channels = MutableStateFlow(ChannelsState())
    val channels: StateFlow<ChannelsState> = _channels.asStateFlow()

    val inbox: StateFlow<List<InboxEntry>> =
        graph.inbox.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        viewModelScope.launch {
            graph.accessDenied.collect { code -> if (code != null) _gate.value = Gate.Denied(code, lastEmail) }
        }
        refresh()
    }

    private var lastEmail: String? = graph.session.email

    private var demo = false

    /** debug 빌드 화면 확인용: 로그인 없이 예시 데이터로 연다. */
    fun enterDemo() {
        demo = true
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) { DemoData.install(graph.inbox) }
        _channels.value = ChannelsState(channels = DemoData.channels)
        _gate.value = Gate.Ready(DemoData.me)
    }

    fun refresh() = viewModelScope.launch {
        if (demo) return@launch
        if (!graph.session.isSignedIn) {
            if (_gate.value !is Gate.Denied) _gate.value = Gate.SignedOut()
            return@launch
        }
        lastEmail = graph.session.email
        _gate.value = try {
            Gate.Ready(graph.api.me())
        } catch (e: ApiException) {
            if (e.isAccessDenied) { graph.onAccessDenied(e.code); Gate.Denied(e.code, lastEmail) }
            else Gate.Ready((_gate.value as? Gate.Ready)?.me, offline = true)
        } catch (e: IOException) {
            Gate.Ready((_gate.value as? Gate.Ready)?.me, offline = true)
        }
    }

    fun signIn(activity: Context) = viewModelScope.launch {
        _gate.value = Gate.Loading
        try {
            if (!graph.session.signIn(activity)) { _gate.value = Gate.SignedOut(); return@launch }
            lastEmail = graph.session.email
            val me = graph.api.me()
            graph.onSignedIn(me.email)
            _gate.value = Gate.Ready(me)
        } catch (e: ApiException) {
            if (e.isAccessDenied) { graph.onAccessDenied(e.code); _gate.value = Gate.Denied(e.code, lastEmail) }
            else { graph.signOut(unregister = false); _gate.value = Gate.SignedOut("로그인하지 못했어요 (${e.code})") }
        } catch (e: Exception) {
            graph.session.signOut()
            _gate.value = Gate.SignedOut("로그인하지 못했어요. ${e.message ?: ""}".trim())
        }
    }

    fun signOut() = viewModelScope.launch {
        graph.signOut()
        _channels.value = ChannelsState()
        _gate.value = Gate.SignedOut()
    }

    /** 접근 거부 화면에서 "다른 계정으로 로그인". */
    fun backToSignIn() { _gate.value = Gate.SignedOut() }

    // ---- 채널 ----

    fun loadChannels() = viewModelScope.launch {
        if (demo) return@launch
        _channels.update { it.copy(loading = true, error = null) }
        try {
            _channels.update { it.copy(channels = graph.api.channels(), loading = false, offline = false) }
        } catch (e: Exception) {
            handle(e)
            _channels.update { it.copy(loading = false, offline = e is IOException) }
        }
    }

    fun setSubscribed(slug: String, subscribed: Boolean) =
        change(slug) { if (subscribed) graph.api.subscribe(slug) else graph.api.unsubscribe(slug) }

    fun setMinSeverity(slug: String, severity: Severity) = change(slug) { graph.api.patch(slug, SubscriptionPatch(minSeverity = severity)) }

    /** until이 null이면 직접 끌 때까지. */
    fun mute(slug: String, until: java.time.Instant?) =
        change(slug) { graph.api.patch(slug, SubscriptionPatch(muted = true, mutedUntil = until?.toString())) }

    fun unmute(slug: String) = change(slug) { graph.api.patch(slug, SubscriptionPatch(muted = false)) }

    /** 서버가 받아들인 결과만 화면에 반영한다 (docs/01 6.5). */
    private fun change(slug: String, request: suspend () -> MemberChannel) = viewModelScope.launch {
        _channels.update { it.copy(updating = slug, error = null) }
        try {
            val updated = request()
            _channels.update { s -> s.copy(channels = s.channels.map { if (it.slug == slug) updated else it }, updating = null) }
        } catch (e: Exception) {
            handle(e)
            val message = if (e is ApiException && e.code == ErrorCodes.CHANNEL_REQUIRED) "필수 채널은 해제할 수 없어요" else "변경하지 못했어요. 다시 시도해 주세요"
            _channels.update { it.copy(updating = null, error = message) }
        }
    }

    // ---- 방해 금지 ----

    fun setQuietHours(q: QuietHoursDto, onDone: (Boolean) -> Unit) = viewModelScope.launch {
        try {
            val saved = graph.api.setQuietHours(q)
            _gate.update { g -> if (g is Gate.Ready && g.me != null) g.copy(me = g.me.copy(quietHours = saved)) else g }
            onDone(true)
        } catch (e: Exception) {
            handle(e)
            onDone(false)
        }
    }

    // ---- 받은 알림 ----

    fun markRead(id: String) = viewModelScope.launch {
        graph.inbox.markRead(id, System.currentTimeMillis())
        graph.notifications.cancel(id)
    }

    fun markAllRead() = viewModelScope.launch { graph.inbox.markAllRead(System.currentTimeMillis()) }
    fun delete(id: String) = viewModelScope.launch { graph.inbox.delete(id) }
    fun deleteAll() = viewModelScope.launch { graph.inbox.deleteAll() }

    private suspend fun handle(e: Exception) {
        if (e is ApiException && e.isAccessDenied) {
            graph.onAccessDenied(e.code)
            _gate.value = Gate.Denied(e.code, lastEmail)
        }
    }
}
