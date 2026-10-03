package com.zyautra.pushbeam.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.mutableStateOf
import com.zyautra.pushbeam.App
import com.zyautra.pushbeam.ui.theme.PushbeamTheme

class MainActivity : ComponentActivity() {
    private val viewModel: AppViewModel by viewModels()

    /** 알림을 눌러 들어온 경우 열 Message ID. */
    private val openMessageId = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        openMessageId.value = intent.getStringExtra(EXTRA_MESSAGE_ID)
        if (com.zyautra.pushbeam.BuildConfig.DEBUG && intent.getBooleanExtra("demo", false)) viewModel.enterDemo()
        setContent {
            PushbeamTheme {
                PushBeamApp(viewModel, openMessageId.value, onMessageOpened = { openMessageId.value = null })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        openMessageId.value = intent.getStringExtra(EXTRA_MESSAGE_ID)
    }

    override fun onStart() {
        super.onStart()
        App.graph.onForeground()
        viewModel.refresh()
    }

    companion object {
        const val EXTRA_MESSAGE_ID = "messageId"
    }
}
