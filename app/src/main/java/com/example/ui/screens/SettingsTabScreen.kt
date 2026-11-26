package com.example.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material.icons.filled.ReportProblem
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Wallpaper
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.model.UiState
import com.example.data.model.UserProfile
import com.example.media.CallAudioController
import com.example.media.MediaUtils
import com.example.ui.components.UserAvatar
import com.example.ui.theme.LocalUChatColors
import com.example.ui.theme.UChatAccentPresets
import com.example.viewmodel.UChatViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsTabScreen(
    viewModel: UChatViewModel,
    onSignOut: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val profileState by viewModel.myProfileState.collectAsStateWithLifecycle()
    val settings by viewModel.settingsState.collectAsStateWithLifecycle()
    val blockedUsers by viewModel.blockedUsersState.collectAsStateWithLifecycle()

    val profile = (profileState as? UiState.Success)?.data
    var cacheBytes by remember { mutableLongStateOf(MediaUtils.calculateCacheBytes(context)) }

    var showEditProfileSheet by remember { mutableStateOf(false) }
    var showBlockedSheet by remember { mutableStateOf(false) }
    var showReportDialog by remember { mutableStateOf(false) }
    var infoDialogContent by remember { mutableStateOf<Pair<String, String>?>(null) }

    val ringtonePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            val displayName = MediaUtils.queryDisplayName(context, uri) ?: "Custom MP3 Ringtone"
            viewModel.updateSettings(
                settings.copy(
                    ringtoneName = displayName.take(80),
                    ringtoneUri = uri.toString().take(500)
                )
            )
            viewModel.showStatus("Ringtone set to $displayName")
        }
    }

    val notifPermLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        viewModel.showStatus(if (granted) "Android notifications enabled" else "Notification permission denied")
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Text(
                text = "Settings",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
        }

        // Account & Profile Card
        item {
            SettingsCard {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showEditProfileSheet = true }
                        .padding(16.dp)
                        .testTag("settings_edit_profile_card"),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    UserAvatar(
                        name = profile?.displayName ?: "UChat",
                        avatarBase64 = profile?.avatarBase64,
                        size = 62.dp,
                        online = true
                    )
                    Spacer(Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = profile?.displayName ?: "Loading...",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Text(
                            text = "@${profile?.username ?: ""}",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = profile?.about ?: "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                    Icon(Icons.Filled.Edit, contentDescription = "Edit profile", tint = MaterialTheme.colorScheme.primary)
                }
            }
        }

        // Privacy Section
        item {
            SectionTitle(Icons.Filled.Security, "Privacy")
            SettingsCard {
                if (profile != null) {
                    ToggleRow("Last Seen Visibility", "Allow contacts to see your last active time", profile.showLastSeen) {
                        viewModel.updatePrivacy(it, profile.showOnline, profile.showAbout, profile.readReceipts, profile.discoverable)
                    }
                    CardDivider()
                    ToggleRow("Online Status", "Show green dot when you are active", profile.showOnline) {
                        viewModel.updatePrivacy(profile.showLastSeen, it, profile.showAbout, profile.readReceipts, profile.discoverable)
                    }
                    CardDivider()
                    ToggleRow("About Visibility", "Display your status line on profile previews", profile.showAbout) {
                        viewModel.updatePrivacy(profile.showLastSeen, profile.showOnline, it, profile.readReceipts, profile.discoverable)
                    }
                    CardDivider()
                    ToggleRow("Read Receipts", "Show blue double-ticks when messages are read", profile.readReceipts) {
                        viewModel.updatePrivacy(profile.showLastSeen, profile.showOnline, profile.showAbout, it, profile.discoverable)
                    }
                    CardDivider()
                    ActionSettingsRow(
                        icon = Icons.Filled.Block,
                        title = "Blocked Users",
                        subtitle = "${blockedUsers.size} blocked",
                        onClick = { showBlockedSheet = true },
                        testTag = "settings_blocked_users_row"
                    )
                }
            }
        }

        // Notifications & Ringtone Section
        item {
            SectionTitle(Icons.Filled.Notifications, "Notifications & Ringtone")
            SettingsCard {
                ToggleRow("Message Notifications", "In-app & Android alerts for direct messages", settings.msgNotif) {
                    if (it && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                    ) {
                        notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                    viewModel.updateSettings(settings.copy(msgNotif = it))
                }
                CardDivider()
                ToggleRow("Group Notifications", "Alert on new group messages", settings.groupNotif) {
                    viewModel.updateSettings(settings.copy(groupNotif = it))
                }
                CardDivider()
                ToggleRow("Call Notifications", "Incoming ringtone & missed call alerts", settings.callNotif) {
                    viewModel.updateSettings(settings.copy(callNotif = it))
                }
                CardDivider()
                ToggleRow("Sound & Ringtone", "Play ringtone and ringback audio", settings.soundEnabled) {
                    viewModel.updateSettings(settings.copy(soundEnabled = it))
                }
                CardDivider()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { ringtonePicker.launch(arrayOf("audio/*")) }
                        .padding(horizontal = 16.dp, vertical = 14.dp)
                        .testTag("settings_pick_ringtone_row"),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.MusicNote, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Call Ringtone (MP3 / Audio)", style = MaterialTheme.typography.titleSmall)
                        Text(settings.ringtoneName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = {
                        CallAudioController.previewRingtoneOnce(context, settings.ringtoneUri, scope)
                    }) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = "Preview ringtone", tint = MaterialTheme.colorScheme.primary)
                    }
                }
                CardDivider()
                Text(
                    text = "Local Android notifications fire while UChat is active over Supabase Realtime.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                )
            }
        }

        // Appearance Section
        item {
            SectionTitle(Icons.Filled.Palette, "Appearance")
            SettingsCard {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Accent Color", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        UChatAccentPresets.forEach { preset ->
                            val selected = settings.accentHex.equals(preset.hex, ignoreCase = true)
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(preset.primary)
                                    .border(
                                        width = if (selected) 3.dp else 1.dp,
                                        color = if (selected) Color.White else Color.Transparent,
                                        shape = CircleShape
                                    )
                                    .clickable {
                                        viewModel.updateSettings(settings.copy(accentHex = preset.hex))
                                    }
                                    .testTag("accent_${preset.name.lowercase()}")
                            ) {
                                if (selected) {
                                    Icon(Icons.Filled.Check, contentDescription = preset.name, tint = Color.Black, modifier = Modifier.size(20.dp))
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(18.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Wallpaper, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(10.dp))
                            Text("Chat Wallpaper Pattern", style = MaterialTheme.typography.titleSmall)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("doodle" to "Pattern", "obsidian" to "Pure Black").forEach { (id, label) ->
                                val active = settings.wallpaperStyle == id
                                Surface(
                                    color = if (active) MaterialTheme.colorScheme.primary else Color.Black,
                                    shape = RoundedCornerShape(50),
                                    modifier = Modifier
                                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(50))
                                        .clickable { viewModel.updateSettings(settings.copy(wallpaperStyle = id)) }
                                ) {
                                    Text(
                                        text = label,
                                        color = if (active) Color.Black else Color.White,
                                        style = MaterialTheme.typography.labelMedium,
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                    )
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(18.dp))
                    Text(
                        text = "Font Size (${(settings.fontScale * 100).toInt()}%)",
                        style = MaterialTheme.typography.titleSmall
                    )
                    Slider(
                        value = settings.fontScale.toFloat().coerceIn(0.85f, 1.25f),
                        onValueChange = { viewModel.updateSettings(settings.copy(fontScale = it.toDouble())) },
                        valueRange = 0.85f..1.25f,
                        steps = 3,
                        colors = SliderDefaults.colors(
                            thumbColor = MaterialTheme.colorScheme.primary,
                            activeTrackColor = MaterialTheme.colorScheme.primary
                        )
                    )
                }
            }
        }

        // Storage Section
        item {
            SectionTitle(Icons.Filled.Storage, "Storage & Cache")
            SettingsCard {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text("Cached Media & Voice Temp", style = MaterialTheme.typography.titleSmall)
                        Text(
                            text = MediaUtils.formatBytes(cacheBytes),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    OutlinedButton(
                        onClick = {
                            cacheBytes = MediaUtils.clearAppCache(context)
                            viewModel.showStatus("Local media cache cleared")
                        },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.testTag("settings_clear_cache_button")
                    ) {
                        Icon(Icons.Filled.CleaningServices, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Clear Cache")
                    }
                }
            }
        }

        // Help & Legal Section
        item {
            SectionTitle(Icons.AutoMirrored.Filled.HelpOutline, "Help & Legal")
            SettingsCard {
                ActionSettingsRow(
                    icon = Icons.Filled.Info,
                    title = "Help Center",
                    subtitle = "Messaging, voice notes, channels & calling guide",
                    onClick = {
                        infoDialogContent = "UChat Help Center" to
                            "• Search anyone by their unique @username in Chats -> New Chat.\n" +
                            "• Long-press any message to reply, react, star, forward, copy, edit, or delete.\n" +
                            "• Tap the mic icon in chat to record voice notes with live waveform preview.\n" +
                            "• Create public Channels where only admins post and followers react in real time."
                    }
                )
                CardDivider()
                ActionSettingsRow(
                    icon = Icons.Filled.ReportProblem,
                    title = "Report a Problem",
                    subtitle = "Send a bug report or abuse ticket",
                    onClick = { showReportDialog = true },
                    testTag = "settings_report_problem_row"
                )
                CardDivider()
                ActionSettingsRow(
                    icon = Icons.Filled.PrivacyTip,
                    title = "Terms & Privacy Policy",
                    subtitle = "Zero-trust data isolation & user privacy",
                    onClick = {
                        infoDialogContent = "UChat Terms & Privacy" to
                            "UChat secures your @username account with salted bcrypt password hashing and server-verified session tokens — no email or phone number is required. " +
                            "Only your chosen @username, display name, and public profile status are discoverable. " +
                            "Real-time messages, media in Supabase Storage, and WebRTC calls are protected by strict Postgres Row Level Security policies."
                    }
                )
            }
        }

        // Sign Out
        item {
            Button(
                onClick = onSignOut,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.16f),
                    contentColor = MaterialTheme.colorScheme.error
                ),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
                    .testTag("settings_sign_out_button")
            ) {
                Icon(Icons.AutoMirrored.Filled.ExitToApp, contentDescription = null)
                Spacer(Modifier.width(10.dp))
                Text("Sign Out", fontWeight = FontWeight.Bold)
            }
        }
    }

    // Edit Profile Modal Sheet
    if (showEditProfileSheet && profile != null) {
        EditProfileSheet(
            profile = profile,
            viewModel = viewModel,
            onDismiss = { showEditProfileSheet = false }
        )
    }

    // Blocked Users Sheet
    if (showBlockedSheet) {
        ModalBottomSheet(
            onDismissRequest = { showBlockedSheet = false },
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(20.dp).padding(bottom = 24.dp)) {
                Text("Blocked Users", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(12.dp))
                if (blockedUsers.isEmpty()) {
                    Text("You haven't blocked anyone.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    blockedUsers.forEach { b ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text(b.displayName, style = MaterialTheme.typography.titleSmall)
                                Text("@${b.username}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            TextButton(onClick = { viewModel.unblockUser(b.blockedUid) }) {
                                Text("Unblock")
                            }
                        }
                    }
                }
            }
        }
    }

    // Report Problem Dialog
    if (showReportDialog) {
        var details by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showReportDialog = false },
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            title = { Text("Report a Problem") },
            text = {
                OutlinedTextField(
                    value = details,
                    onValueChange = { details = it.take(1000) },
                    label = { Text("Describe the issue") },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.submitReport("app_feedback", details)
                        showReportDialog = false
                    },
                    enabled = details.isNotBlank()
                ) { Text("Submit") }
            },
            dismissButton = {
                TextButton(onClick = { showReportDialog = false }) { Text("Cancel") }
            }
        )
    }

    // Info Dialog
    infoDialogContent?.let { (title, body) ->
        AlertDialog(
            onDismissRequest = { infoDialogContent = null },
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            title = { Text(title) },
            text = { Text(body, style = MaterialTheme.typography.bodyMedium) },
            confirmButton = {
                TextButton(onClick = { infoDialogContent = null }) { Text("OK") }
            }
        )
    }
}

@Composable
private fun SectionTitle(icon: ImageVector, title: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(20.dp))
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            content()
        }
    }
}

@Composable
private fun CardDivider() {
    HorizontalDivider(
        color = LocalUChatColors.current.divider,
        modifier = Modifier.padding(horizontal = 16.dp)
    )
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onBackground)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun ActionSettingsRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    testTag: String = ""
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp)
            .then(if (testTag.isNotBlank()) Modifier.testTag(testTag) else Modifier),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onBackground)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditProfileSheet(
    profile: UserProfile,
    viewModel: UChatViewModel,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var username by remember { mutableStateOf(profile.username) }
    var displayName by remember { mutableStateOf(profile.displayName) }
    var about by remember { mutableStateOf(profile.about) }
    var avatarBase64 by remember { mutableStateOf(profile.avatarBase64) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            scope.launch {
                val compressed = MediaUtils.compressUriToBase64(context, uri, 320, 140_000)
                if (compressed != null) avatarBase64 = compressed
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Edit Profile", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(14.dp))
            Box(
                modifier = Modifier.clickable {
                    picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }
            ) {
                UserAvatar(name = displayName.ifBlank { username }, avatarBase64 = avatarBase64, size = 82.dp)
                Surface(
                    color = MaterialTheme.colorScheme.primary,
                    shape = CircleShape,
                    modifier = Modifier.align(Alignment.BottomEnd).size(28.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.CameraAlt, contentDescription = "Change photo", tint = Color.Black, modifier = Modifier.size(14.dp))
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = username,
                onValueChange = { username = it.lowercase().filter { ch -> ch.isLetterOrDigit() || ch == '_' }.take(24) },
                label = { Text("Username") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = displayName,
                onValueChange = { displayName = it.take(60) },
                label = { Text("Display name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = about,
                onValueChange = { about = it.take(160) },
                label = { Text("About / Status") },
                maxLines = 2,
                modifier = Modifier.fillMaxWidth()
            )
            if (errorMsg != null) {
                Spacer(Modifier.height(8.dp))
                Text(errorMsg ?: "", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(18.dp))
            Button(
                onClick = {
                    errorMsg = null
                    saving = true
                    viewModel.updateMyProfile(profile.username, username, displayName, about, avatarBase64) { res ->
                        saving = false
                        res.onSuccess {
                            viewModel.showStatus("Profile updated")
                            onDismiss()
                        }.onFailure { ex ->
                            errorMsg = ex.message ?: "Failed to update profile"
                        }
                    }
                },
                enabled = !saving && username.length >= 3 && displayName.isNotBlank(),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth().height(50.dp)
            ) {
                Text(if (saving) "Saving..." else "Save Profile", fontWeight = FontWeight.Bold)
            }
        }
    }
}
