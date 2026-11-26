package com.example

import android.graphics.Color as AndroidColor
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.Campaign
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.data.model.MainTab
import com.example.data.model.UiState
import com.example.data.repository.CallRepository
import com.example.data.repository.ChannelRepository
import com.example.data.repository.ChatRepository
import com.example.data.repository.ProfileRepository
import com.example.data.supabase.SupabaseClient
import com.example.data.supabase.UChatAuthManager
import com.example.media.NotificationHelper
import com.example.ui.components.EmptyStateCard
import com.example.ui.screens.ActiveCallOverlay
import com.example.ui.screens.AuthScreen
import com.example.ui.screens.CallsTabScreen
import com.example.ui.screens.ChannelDetailScreen
import com.example.ui.screens.ChannelsTabScreen
import com.example.ui.screens.ChatDetailScreen
import com.example.ui.screens.ChatsTabScreen
import com.example.ui.screens.ProfileSetupScreen
import com.example.ui.screens.SettingsTabScreen
import com.example.ui.theme.LocalUChatColors
import com.example.ui.theme.UChatTheme
import com.example.viewmodel.UChatViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AndroidColor.BLACK),
            navigationBarStyle = SystemBarStyle.dark(AndroidColor.BLACK)
        )
        NotificationHelper.ensureChannels(this)
        setContent {
            UChatAppRoot()
        }
    }
}

@Composable
fun UChatAppRoot() {
    val context = LocalContext.current
    val authManager = remember(context) { UChatAuthManager(context.applicationContext) }
    val supabase = remember(authManager) { SupabaseClient(authManager) }

    val session by authManager.session.collectAsStateWithLifecycle()
    val initializing by authManager.initializing.collectAsStateWithLifecycle()

    if (initializing) {
        UChatTheme {
            Box(
                modifier = Modifier.fillMaxSize().background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
        }
    } else if (session == null) {
        UChatTheme {
            AuthScreen(
                authManager = authManager,
                onAuthSuccess = {}
            )
        }
    } else {
        val activeSession = session!!
        key(activeSession.userId) {
            AuthenticatedUChatApp(
                currentUserId = activeSession.userId,
                suggestedDisplayName = activeSession.displayName,
                suggestedUsername = activeSession.username,
                supabase = supabase
            )
        }
    }
}

private data class NavTabSpec(
    val tab: MainTab,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
)

private val UChatTabs = listOf(
    NavTabSpec(MainTab.CHATS, "Chats", Icons.AutoMirrored.Filled.Chat, Icons.AutoMirrored.Outlined.Chat),
    NavTabSpec(MainTab.CHANNELS, "Channels", Icons.Filled.Campaign, Icons.Outlined.Campaign),
    NavTabSpec(MainTab.CALLS, "Calls", Icons.Filled.Call, Icons.Outlined.Call),
    NavTabSpec(MainTab.SETTINGS, "Settings", Icons.Filled.Settings, Icons.Outlined.Settings)
)

@Composable
private fun AuthenticatedUChatApp(
    currentUserId: String,
    suggestedDisplayName: String,
    suggestedUsername: String,
    supabase: SupabaseClient
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val viewModel: UChatViewModel = viewModel(
        key = currentUserId,
        factory = viewModelFactory {
            initializer {
                UChatViewModel(
                    currentUserId = currentUserId,
                    profileRepo = ProfileRepository(supabase),
                    chatRepo = ChatRepository(supabase),
                    channelRepo = ChannelRepository(supabase),
                    callRepo = CallRepository(supabase)
                )
            }
        }
    )

    val myProfileState by viewModel.myProfileState.collectAsStateWithLifecycle()
    val settings by viewModel.settingsState.collectAsStateWithLifecycle()
    val selectedTab by viewModel.selectedTab.collectAsStateWithLifecycle()
    val activeConversationId by viewModel.activeConversationId.collectAsStateWithLifecycle()
    val activeChannelId by viewModel.activeChannelId.collectAsStateWithLifecycle()
    val conversationsState by viewModel.conversationsState.collectAsStateWithLifecycle()
    val channelsState by viewModel.channelsState.collectAsStateWithLifecycle()
    val followedChannelIds by viewModel.followedChannelIdsState.collectAsStateWithLifecycle()
    val callsState by viewModel.callsState.collectAsStateWithLifecycle()
    val statusBanner by viewModel.statusBanner.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    val lifecycleOwner = LocalLifecycleOwner.current

    fun performSignOut(markOffline: Boolean) {
        scope.launch {
            supabase.disconnectRealtime()
            supabase.auth.signOut(
                beforeSignOut = {
                    if (markOffline) {
                        viewModel.markOfflineBeforeSignOut()
                    }
                }
            )
        }
    }

    // Real-time Supabase presence: online when foregrounded, offline when backgrounded
    DisposableEffect(lifecycleOwner, currentUserId) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> viewModel.setPresenceOnline(true)
                Lifecycle.Event.ON_STOP -> viewModel.setPresenceOnline(false)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // Periodic foreground heartbeat so stale sessions flip to offline automatically
    LaunchedEffect(lifecycleOwner, currentUserId) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                viewModel.setPresenceOnline(true)
                delay(45_000L)
            }
        }
    }

    LaunchedEffect(statusBanner) {
        statusBanner?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            viewModel.clearStatus()
        }
    }

    // Real-time incoming call notification trigger
    val allCalls = (callsState as? UiState.Success)?.data ?: emptyList()
    val activeCall = remember(allCalls) { allCalls.firstOrNull { it.activeCall() } }
    var lastNotifiedCallId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(activeCall?.id, activeCall?.status) {
        if (activeCall != null &&
            activeCall.status == "ringing" &&
            !activeCall.outgoing(currentUserId) &&
            settings.callNotif &&
            lastNotifiedCallId != activeCall.id
        ) {
            lastNotifiedCallId = activeCall.id
            NotificationHelper.notifyCall(
                context = context,
                title = "Incoming ${activeCall.callType} call",
                body = "${activeCall.callerName} (@${activeCall.callerUsername}) is calling...",
                notificationId = activeCall.id.hashCode()
            )
        }
    }

    UChatTheme(
        accentHex = settings.accentHex,
        fontScale = settings.fontScale.toFloat()
    ) {
        when (val pState = myProfileState) {
            is UiState.Loading -> {
                Box(
                    modifier = Modifier.fillMaxSize().background(Color.Black),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
            }
            is UiState.Error -> {
                Box(
                    modifier = Modifier.fillMaxSize().background(Color.Black),
                    contentAlignment = Alignment.Center
                ) {
                    EmptyStateCard(
                        icon = Icons.AutoMirrored.Filled.Chat,
                        title = "Connection issue",
                        body = pState.message,
                        action = {
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Button(
                                    onClick = { viewModel.retryProfileSync() },
                                    modifier = Modifier.testTag("profile_error_retry_button")
                                ) {
                                    Text("Retry")
                                }
                                OutlinedButton(
                                    onClick = { performSignOut(markOffline = false) },
                                    modifier = Modifier.testTag("profile_error_sign_out_button")
                                ) {
                                    Text("Sign Out")
                                }
                            }
                        }
                    )
                }
            }
            is UiState.Success -> {
                if (pState.data == null) {
                    ProfileSetupScreen(
                        viewModel = viewModel,
                        suggestedDisplayName = suggestedDisplayName,
                        suggestedUsername = suggestedUsername,
                        onSignOut = { performSignOut(markOffline = false) }
                    )
                } else {
                    val conversations = (conversationsState as? UiState.Success)?.data ?: emptyList()
                    val activeConversation = conversations.firstOrNull { it.id == activeConversationId }
                    val channels = (channelsState as? UiState.Success)?.data ?: emptyList()
                    val activeChannel = channels.firstOrNull { it.id == activeChannelId }

                    BoxWithConstraints(modifier = Modifier.fillMaxSize().background(Color.Black)) {
                        val isExpanded = maxWidth >= 700.dp

                        if (!isExpanded && activeConversation != null) {
                            // Full-screen Chat Detail on compact mobile
                            ChatDetailScreen(
                                conversation = activeConversation,
                                viewModel = viewModel,
                                showBackButton = true,
                                onBack = { viewModel.openConversation(null) }
                            )
                        } else if (!isExpanded && activeChannel != null) {
                            // Full-screen Channel Detail on compact mobile
                            ChannelDetailScreen(
                                channel = activeChannel,
                                following = activeChannel.id in followedChannelIds,
                                viewModel = viewModel,
                                showBackButton = true,
                                onBack = { viewModel.openChannel(null) }
                            )
                        } else if (isExpanded) {
                            // Tablet / Foldable Expanded Adaptive Layout: NavigationRail + List-Detail
                            Scaffold(
                                containerColor = Color.Black,
                                snackbarHost = { SnackbarHost(snackbarHostState) }
                            ) { innerPadding ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(innerPadding)
                                        .statusBarsPadding()
                                ) {
                                    NavigationRail(
                                        containerColor = Color.Black,
                                        contentColor = Color.White
                                    ) {
                                        UChatTabs.forEach { spec ->
                                            val selected = selectedTab == spec.tab
                                            NavigationRailItem(
                                                selected = selected,
                                                onClick = { viewModel.selectTab(spec.tab) },
                                                icon = {
                                                    Icon(
                                                        imageVector = if (selected) spec.selectedIcon else spec.unselectedIcon,
                                                        contentDescription = spec.label
                                                    )
                                                },
                                                label = { Text(spec.label) },
                                                colors = NavigationRailItemDefaults.colors(
                                                    selectedIconColor = MaterialTheme.colorScheme.primary,
                                                    selectedTextColor = MaterialTheme.colorScheme.primary,
                                                    indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                                                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                                                ),
                                                modifier = Modifier.testTag("nav_rail_${spec.tab.name.lowercase()}")
                                            )
                                        }
                                    }
                                    VerticalDivider(color = LocalUChatColors.current.divider)
                                    Box(modifier = Modifier.weight(0.42f).fillMaxHeight()) {
                                        TabMasterContent(
                                            selectedTab = selectedTab,
                                            viewModel = viewModel,
                                            onSignOut = { performSignOut(markOffline = true) }
                                        )
                                    }
                                    VerticalDivider(color = LocalUChatColors.current.divider)
                                    Box(modifier = Modifier.weight(0.58f).fillMaxHeight()) {
                                        when {
                                            selectedTab == MainTab.CHATS && activeConversation != null -> {
                                                ChatDetailScreen(
                                                    conversation = activeConversation,
                                                    viewModel = viewModel,
                                                    showBackButton = false,
                                                    onBack = { viewModel.openConversation(null) }
                                                )
                                            }
                                            selectedTab == MainTab.CHANNELS && activeChannel != null -> {
                                                ChannelDetailScreen(
                                                    channel = activeChannel,
                                                    following = activeChannel.id in followedChannelIds,
                                                    viewModel = viewModel,
                                                    showBackButton = false,
                                                    onBack = { viewModel.openChannel(null) }
                                                )
                                            }
                                            else -> {
                                                EmptyStateCard(
                                                    icon = Icons.AutoMirrored.Filled.Chat,
                                                    title = "UChat Workspace",
                                                    body = "Select a conversation or channel on the left to start messaging in real time."
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        } else {
                            // Standard Mobile Layout: Fixed Pure Black Bottom Navigation Bar
                            Scaffold(
                                containerColor = Color.Black,
                                snackbarHost = { SnackbarHost(snackbarHostState) },
                                contentWindowInsets = WindowInsets(0, 0, 0, 0),
                                bottomBar = {
                                    NavigationBar(
                                        containerColor = Color.Black,
                                        tonalElevation = 0.dp,
                                        windowInsets = WindowInsets.navigationBars
                                    ) {
                                        UChatTabs.forEach { spec ->
                                            val selected = selectedTab == spec.tab
                                            NavigationBarItem(
                                                selected = selected,
                                                onClick = { viewModel.selectTab(spec.tab) },
                                                icon = {
                                                    Icon(
                                                        imageVector = if (selected) spec.selectedIcon else spec.unselectedIcon,
                                                        contentDescription = spec.label
                                                    )
                                                },
                                                label = { Text(spec.label, style = MaterialTheme.typography.labelSmall) },
                                                colors = NavigationBarItemDefaults.colors(
                                                    selectedIconColor = MaterialTheme.colorScheme.primary,
                                                    selectedTextColor = MaterialTheme.colorScheme.primary,
                                                    indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
                                                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                                                ),
                                                modifier = Modifier.testTag("bottom_nav_${spec.tab.name.lowercase()}")
                                            )
                                        }
                                    }
                                }
                            ) { innerPadding ->
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(innerPadding)
                                        .statusBarsPadding()
                                ) {
                                    TabMasterContent(
                                        selectedTab = selectedTab,
                                        viewModel = viewModel,
                                        onSignOut = { performSignOut(markOffline = true) }
                                    )
                                }
                            }
                        }

                        // Real-time Incoming / Outgoing / Connected WebRTC Call Overlay
                        if (activeCall != null) {
                            val candidatesFlow = remember(activeCall.id) {
                                viewModel.observeCallCandidates(activeCall.id)
                            }
                            ActiveCallOverlay(
                                call = activeCall,
                                myUid = currentUserId,
                                customRingtoneUri = settings.ringtoneUri,
                                soundEnabled = settings.soundEnabled,
                                candidatesFlow = candidatesFlow,
                                onPublishOffer = { offerSdp ->
                                    viewModel.publishSdpOffer(activeCall.id, offerSdp)
                                },
                                onAcceptWithAnswer = { answerSdp ->
                                    viewModel.acceptCall(activeCall, answerSdp)
                                },
                                onMarkConnected = {
                                    viewModel.markCallConnected(activeCall.id)
                                },
                                onSendIceCandidate = { sdp, sdpMid, sdpMLineIndex ->
                                    viewModel.sendIceCandidate(activeCall, sdp, sdpMid, sdpMLineIndex)
                                },
                                onReject = { viewModel.finishCall(activeCall.id, "rejected", 0L) },
                                onEnd = { dur ->
                                    val terminal = if (activeCall.status == "ringing") "cancelled" else "ended"
                                    viewModel.finishCall(activeCall.id, terminal, dur)
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TabMasterContent(
    selectedTab: MainTab,
    viewModel: UChatViewModel,
    onSignOut: () -> Unit
) {
    Crossfade(targetState = selectedTab, label = "uchat_tabs") { tab ->
        when (tab) {
            MainTab.CHATS -> ChatsTabScreen(
                viewModel = viewModel,
                onOpenConversation = { viewModel.openConversation(it) }
            )
            MainTab.CHANNELS -> ChannelsTabScreen(
                viewModel = viewModel,
                onOpenChannel = { viewModel.openChannel(it) }
            )
            MainTab.CALLS -> CallsTabScreen(viewModel = viewModel)
            MainTab.SETTINGS -> SettingsTabScreen(
                viewModel = viewModel,
                onSignOut = onSignOut
            )
        }
    }
}
