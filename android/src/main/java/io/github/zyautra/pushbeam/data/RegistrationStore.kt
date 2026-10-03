package io.github.zyautra.pushbeam.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID

private val Context.registrationStore by preferencesDataStore(name = "registration")

/** 설치 식별자와 기기 등록 상태 (docs/02 7.2). */
class RegistrationStore(private val context: Context) {
    data class State(
        val installationId: String?,
        val needsRegistration: Boolean,
        val lastRegisteredAt: Long?,
        val lastAppVersion: Int?,
        val lastTimeZone: String?,
        val memberEmail: String?,
    )

    val state: Flow<State> = context.registrationStore.data.map {
        State(
            installationId = it[INSTALLATION_ID],
            needsRegistration = it[NEEDS_REGISTRATION] ?: false,
            lastRegisteredAt = it[LAST_REGISTERED_AT],
            lastAppVersion = it[LAST_APP_VERSION],
            lastTimeZone = it[LAST_TIME_ZONE],
            memberEmail = it[MEMBER_EMAIL],
        )
    }

    suspend fun current(): State = state.first()

    suspend fun installationId(): String {
        current().installationId?.let { return it }
        val id = UUID.randomUUID().toString()
        context.registrationStore.edit { if (it[INSTALLATION_ID] == null) it[INSTALLATION_ID] = id }
        return current().installationId!!
    }

    suspend fun markNeeded() = context.registrationStore.edit { it[NEEDS_REGISTRATION] = true }

    suspend fun markRegistered(at: Long, appVersion: Int, timeZone: String) = context.registrationStore.edit {
        it[NEEDS_REGISTRATION] = false
        it[LAST_REGISTERED_AT] = at
        it[LAST_APP_VERSION] = appVersion
        it[LAST_TIME_ZONE] = timeZone
    }

    /** 로그인한 계정을 기억한다. 다른 계정이면 true를 돌려준다. */
    suspend fun setMember(email: String?): Boolean {
        val previous = current().memberEmail
        context.registrationStore.edit {
            if (email == null) it.remove(MEMBER_EMAIL) else it[MEMBER_EMAIL] = email
            if (email == null) { it.remove(LAST_REGISTERED_AT); it[NEEDS_REGISTRATION] = false }
        }
        return email != null && previous != null && previous != email
    }

    private companion object {
        val INSTALLATION_ID = stringPreferencesKey("installationId")
        val NEEDS_REGISTRATION = booleanPreferencesKey("needsRegistration")
        val LAST_REGISTERED_AT = longPreferencesKey("lastRegisteredAt")
        val LAST_APP_VERSION = intPreferencesKey("lastAppVersion")
        val LAST_TIME_ZONE = stringPreferencesKey("lastTimeZone")
        val MEMBER_EMAIL = stringPreferencesKey("memberEmail")
    }
}
