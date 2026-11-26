package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.NewReleases
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.update.AppUpdateState
import com.example.media.MediaUtils
import com.example.ui.theme.LocalUChatColors

@Composable
fun AppUpdateSection(
    currentVersionLabel: String,
    configuredRepo: String,
    updateState: AppUpdateState,
    onCheckForUpdate: () -> Unit,
    onOpenUpdateUrl: (String) -> Unit,
    onSaveRepo: (String) -> Result<String>
) {
    var showRepoDialog by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.SystemUpdate,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "App Updates",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
            if (updateState is AppUpdateState.UpdateAvailable) {
                Spacer(Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                )
            }
        }

        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier
                .fillMaxWidth()
                .border(
                    width = if (updateState is AppUpdateState.UpdateAvailable) 1.5.dp else 1.dp,
                    color = if (updateState is AppUpdateState.UpdateAvailable) {
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                    } else {
                        MaterialTheme.colorScheme.outline
                    },
                    shape = RoundedCornerShape(20.dp)
                )
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "UChat $currentVersionLabel",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Spacer(Modifier.height(2.dp))
                        val statusSubtitle = when (updateState) {
                            is AppUpdateState.Idle -> "Check for the latest APK release directly from Settings"
                            is AppUpdateState.Checking -> "Checking for newer UChat releases..."
                            is AppUpdateState.UpToDate -> "You're on the latest version (Build #${updateState.latestBuild})"
                            is AppUpdateState.UpdateAvailable -> "New update ready: ${updateState.release.title}"
                            is AppUpdateState.NeedsRepoConfig -> updateState.reason
                            is AppUpdateState.Error -> updateState.message
                        }
                        Text(
                            text = statusSubtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = when (updateState) {
                                is AppUpdateState.UpdateAvailable -> MaterialTheme.colorScheme.primary
                                is AppUpdateState.Error -> MaterialTheme.colorScheme.error
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }

                    Spacer(Modifier.width(12.dp))

                    when (updateState) {
                        is AppUpdateState.Checking -> {
                            CircularProgressIndicator(
                                modifier = Modifier.size(22.dp),
                                strokeWidth = 2.5.dp,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        is AppUpdateState.UpToDate -> {
                            Icon(
                                imageVector = Icons.Filled.CheckCircle,
                                contentDescription = "Up to date",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        is AppUpdateState.UpdateAvailable -> {
                            Icon(
                                imageVector = Icons.Filled.NewReleases,
                                contentDescription = "Update available",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(26.dp)
                            )
                        }
                        else -> Unit
                    }
                }

                if (updateState is AppUpdateState.UpdateAvailable) {
                    val release = updateState.release
                    Spacer(Modifier.height(12.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "Build #${release.latestBuildNumber}",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Bold
                                )
                                release.apkSizeBytes?.let { bytes ->
                                    Text(
                                        text = MediaUtils.formatBytes(bytes),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            if (release.notes.isNotBlank()) {
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = release.notes,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = { onOpenUpdateUrl(release.preferredUpdateUrl) },
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = Color.Black
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("settings_download_update_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.CloudDownload,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "Update APK (Build #${release.latestBuildNumber})",
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Spacer(Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(
                            onClick = { onOpenUpdateUrl(release.releasePageUrl) },
                            modifier = Modifier.testTag("settings_release_page_button")
                        ) {
                            Text("View Release Page")
                        }
                        TextButton(
                            onClick = onCheckForUpdate,
                            modifier = Modifier.testTag("settings_check_update_button")
                        ) {
                            Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Recheck")
                        }
                    }
                } else {
                    Spacer(Modifier.height(14.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = onCheckForUpdate,
                            enabled = updateState !is AppUpdateState.Checking,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("settings_check_update_button")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Refresh,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = if (updateState is AppUpdateState.Checking) "Checking..." else "Check for Updates",
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))
                HorizontalDivider(color = LocalUChatColors.current.divider)
                Spacer(Modifier.height(10.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showRepoDialog = true }
                        .padding(vertical = 4.dp)
                        .testTag("settings_update_repo_row"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Update Source (GitHub)",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = configuredRepo.ifBlank { "Not connected — tap to set owner/repo" },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (configuredRepo.isNotBlank()) {
                                MaterialTheme.colorScheme.onBackground
                            } else {
                                MaterialTheme.colorScheme.primary
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Icon(
                        imageVector = Icons.Filled.Edit,
                        contentDescription = "Configure update source",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }

    if (showRepoDialog) {
        var repoInput by remember(configuredRepo) { mutableStateOf(configuredRepo) }
        var repoError by remember { mutableStateOf<String?>(null) }

        AlertDialog(
            onDismissRequest = { showRepoDialog = false },
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            title = { Text("GitHub Update Source") },
            text = {
                Column {
                    Text(
                        text = "Enter your GitHub repository (owner/repo) where UChat releases are published. GitHub-built APKs detect this automatically.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = repoInput,
                        onValueChange = {
                            repoInput = it.take(200)
                            repoError = null
                        },
                        label = { Text("owner/repo") },
                        placeholder = { Text("username/uchat") },
                        singleLine = true,
                        isError = repoError != null,
                        modifier = Modifier.fillMaxWidth().testTag("update_repo_input")
                    )
                    if (repoError != null) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = repoError ?: "",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onSaveRepo(repoInput)
                            .onSuccess {
                                showRepoDialog = false
                                if (it.isNotBlank()) onCheckForUpdate()
                            }
                            .onFailure { err ->
                                repoError = err.message ?: "Invalid repository format"
                            }
                    },
                    modifier = Modifier.testTag("update_repo_save_button")
                ) {
                    Text("Save & Check")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showRepoDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}
