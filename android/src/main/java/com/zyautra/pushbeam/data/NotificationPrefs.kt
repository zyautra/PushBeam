package com.zyautra.pushbeam.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.notificationPrefs by preferencesDataStore(name = "notification_prefs")

/** 설정 화면의 진동·소리 스위치. 긴급 알림에는 적용하지 않는다. */
class NotificationPrefs(private val context: Context) {
    data class Value(val sound: Boolean = true, val vibrate: Boolean = true)

    val value: Flow<Value> = context.notificationPrefs.data.map {
        Value(sound = it[SOUND] ?: true, vibrate = it[VIBRATE] ?: true)
    }

    suspend fun current(): Value = value.first()

    suspend fun setSound(on: Boolean) = context.notificationPrefs.edit { it[SOUND] = on }
    suspend fun setVibrate(on: Boolean) = context.notificationPrefs.edit { it[VIBRATE] = on }

    private companion object {
        val SOUND = booleanPreferencesKey("sound")
        val VIBRATE = booleanPreferencesKey("vibrate")
    }
}
