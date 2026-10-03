package io.github.zyautra.pushbeam.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.zyautra.pushbeam.shared.ErrorCodes

@Composable
fun PushBeamApp(viewModel: AppViewModel, openMessageId: String?, onMessageOpened: () -> Unit) {
    val gate by viewModel.gate.collectAsState()
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        when (val g = gate) {
            Gate.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            is Gate.SignedOut -> SignInScreen(g.error, onSignIn = viewModel::signIn)
            is Gate.Denied -> DeniedScreen(g, viewModel, openMessageId, onMessageOpened)
            is Gate.Ready -> MainScreen(viewModel, g, openMessageId, onMessageOpened)
        }
    }
}

@Composable
private fun SignInScreen(error: String?, onSignIn: (android.content.Context) -> Unit) {
    val activity = LocalActivity.current
    CenteredColumn {
        RoundIcon(Icons.Filled.Notifications, MaterialTheme.colorScheme.primary, size = 80.dp)
        Spacer(Modifier.height(20.dp))
        Text("PushBeam", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            "운영자가 허용한 사람에게\n서버 알림을 전해 주는 앱이에요.",
            textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(36.dp))
        Button(
            onClick = { activity?.let(onSignIn) },
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(14.dp),
        ) { Text("Google 계정으로 로그인") }
        if (error != null) {
            Spacer(Modifier.height(16.dp))
            Text(error, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun DeniedScreen(g: Gate.Denied, viewModel: AppViewModel, openMessageId: String?, onMessageOpened: () -> Unit) {
    var showInbox by rememberSaveable { mutableStateOf(false) }
    var detailId by rememberSaveable { mutableStateOf<String?>(null) }
    if (showInbox) {
        // 사용이 중지돼도 받은 알림 기록은 읽을 수 있다 (docs/06 4절).
        BackHandler { if (detailId != null) detailId = null else showInbox = false }
        Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
            Box(Modifier.padding(padding)) {
                val id = detailId
                if (id != null) DetailScreen(viewModel, id, onBack = { detailId = null }, onOpenChannel = null)
                else InboxScreen(viewModel, openMessageId, onMessageOpened, onOpen = { detailId = it }, readOnlyNotice = "사용이 중지된 계정이에요. 기록만 볼 수 있어요.")
            }
        }
        return
    }
    CenteredColumn {
        if (g.code == ErrorCodes.NOT_ALLOWLISTED) {
            Text("아직 PushBeam을 쓸 수 없어요", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            Text("${g.email ?: "이"} 계정은 허용 목록에 없어요.\n운영자에게 이 이메일을 알려 주세요.", textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Text("사용이 중지되었어요", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            Text("운영자가 이 계정의 사용을 중지했어요.\n받은 알림 기록은 이 기기에 남아 있어요.", textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(32.dp))
        if (g.code == ErrorCodes.MEMBER_REVOKED) {
            OutlinedButton(onClick = { showInbox = true }, Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(14.dp)) { Text("받은 알림 보기") }
            Spacer(Modifier.height(12.dp))
        }
        Button(onClick = viewModel::backToSignIn, Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(14.dp)) { Text("다른 계정으로 로그인") }
    }
}

private enum class Tab(val label: String, val icon: ImageVector, val selectedIcon: ImageVector) {
    Inbox("받은 알림", Icons.Outlined.Notifications, Icons.Filled.Notifications),
    Channels("채널", Icons.Outlined.Layers, Icons.Filled.Layers),
    Settings("설정", Icons.Outlined.Settings, Icons.Filled.Settings),
}

@Composable
private fun MainScreen(viewModel: AppViewModel, gate: Gate.Ready, openMessageId: String?, onMessageOpened: () -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var detailId by rememberSaveable { mutableStateOf<String?>(null) }
    var channelToOpen by rememberSaveable { mutableStateOf<String?>(null) }

    if (detailId != null) {
        BackHandler { detailId = null }
        DetailScreen(viewModel, detailId!!, onBack = { detailId = null }, onOpenChannel = { slug ->
            detailId = null; channelToOpen = slug; tab = Tab.Channels.ordinal
        })
        return
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
                Tab.entries.forEach { t ->
                    val selected = tab == t.ordinal
                    NavigationBarItem(
                        selected = selected,
                        onClick = { tab = t.ordinal },
                        icon = { Icon(if (selected) t.selectedIcon else t.icon, contentDescription = null) },
                        label = { Text(t.label) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.primary,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                        ),
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding)) {
            when (Tab.entries[tab]) {
                Tab.Inbox -> InboxScreen(viewModel, openMessageId, onMessageOpened, onOpen = { detailId = it }, offline = gate.offline)
                Tab.Channels -> ChannelsScreen(viewModel, openSlug = channelToOpen, onOpened = { channelToOpen = null })
                Tab.Settings -> SettingsScreen(viewModel, gate)
            }
        }
    }
}

@Composable
fun CenteredColumn(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) { content() }
}

@Composable
fun Banner(text: String, action: String? = null, onAction: () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
            .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
        if (action != null) TextButton(onClick = onAction) { Text(action) }
    }
}
