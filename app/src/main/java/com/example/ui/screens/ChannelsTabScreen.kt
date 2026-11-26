package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import coil.compose.AsyncImage
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.model.Channel
import com.example.data.model.ChannelPost
import com.example.data.model.UiState
import com.example.media.MediaUtils
import com.example.ui.components.EmptyStateCard
import com.example.ui.components.UserAvatar
import com.example.ui.components.formatTimestampShort
import com.example.ui.theme.LocalUChatColors
import com.example.viewmodel.UChatViewModel
import kotlinx.coroutines.launch

private val ChannelReactionEmojis = listOf("👍", "❤️", "🔥", "👏", "😮")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChannelsTabScreen(
    viewModel: UChatViewModel,
    onOpenChannel: (String) -> Unit
) {
    val channelsState by viewModel.channelsState.collectAsStateWithLifecycle()
    val followedIds by viewModel.followedChannelIdsState.collectAsStateWithLifecycle()
    val searchQuery by viewModel.channelSearchQuery.collectAsStateWithLifecycle()

    var showCreateSheet by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 12.dp, top = 14.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Channels", style = MaterialTheme.typography.headlineMedium)
                IconButton(
                    onClick = { showCreateSheet = true },
                    modifier = Modifier.testTag("header_create_channel_button")
                ) {
                    Icon(Icons.Filled.Add, contentDescription = "Create channel", tint = MaterialTheme.colorScheme.primary)
                }
            }

            OutlinedTextField(
                value = searchQuery,
                onValueChange = viewModel::setChannelSearchQuery,
                placeholder = { Text("Search public channels") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (searchQuery.isNotBlank()) {
                        IconButton(onClick = { viewModel.setChannelSearchQuery("") }) {
                            Icon(Icons.Filled.Close, contentDescription = "Clear search")
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(50),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = Color.Transparent,
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp)
                    .testTag("channels_search_input")
            )

            when (val state = channelsState) {
                is UiState.Loading -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    }
                }
                is UiState.Error -> {
                    EmptyStateCard(Icons.Filled.Campaign, "Unable to load channels", state.message)
                }
                is UiState.Success -> {
                    val q = searchQuery.trim().lowercase()
                    val filtered = remember(state.data, q) {
                        state.data.filter { ch ->
                            q.isBlank() || ch.name.lowercase().contains(q) || ch.description.lowercase().contains(q)
                        }
                    }
                    val following = filtered.filter { it.id in followedIds }
                    val discover = filtered.filter { it.id !in followedIds }

                    if (filtered.isEmpty()) {
                        EmptyStateCard(
                            icon = Icons.Filled.Campaign,
                            title = "No channels yet",
                            body = "Launch your own broadcast channel to share real-time text and photo updates with followers.",
                            action = {
                                Button(
                                    onClick = { showCreateSheet = true },
                                    shape = RoundedCornerShape(14.dp),
                                    modifier = Modifier.testTag("empty_create_channel_button")
                                ) {
                                    Icon(Icons.Filled.Add, contentDescription = null)
                                    Spacer(Modifier.width(6.dp))
                                    Text("Create Channel")
                                }
                            }
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(bottom = 96.dp)
                        ) {
                            if (following.isNotEmpty()) {
                                item {
                                    Text(
                                        text = "Following",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp)
                                    )
                                }
                                items(following, key = { "f_${it.id}" }) { ch ->
                                    ChannelRow(
                                        channel = ch,
                                        following = true,
                                        isOwner = ch.ownerId == viewModel.currentUserId,
                                        onClick = { onOpenChannel(ch.id) },
                                        onToggleFollow = { viewModel.toggleFollowChannel(ch, true) }
                                    )
                                }
                            }
                            if (discover.isNotEmpty()) {
                                item {
                                    Text(
                                        text = "Discover Public Channels",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
                                    )
                                }
                                items(discover, key = { "d_${it.id}" }) { ch ->
                                    ChannelRow(
                                        channel = ch,
                                        following = false,
                                        isOwner = ch.ownerId == viewModel.currentUserId,
                                        onClick = { onOpenChannel(ch.id) },
                                        onToggleFollow = { viewModel.toggleFollowChannel(ch, false) }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        FloatingActionButton(
            onClick = { showCreateSheet = true },
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(20.dp)
                .testTag("fab_create_channel")
        ) {
            Icon(Icons.Filled.Campaign, contentDescription = "Create broadcast channel")
        }
    }

    if (showCreateSheet) {
        CreateChannelSheet(
            onDismiss = { showCreateSheet = false },
            onCreate = { name, desc, avatar ->
                viewModel.createChannel(name, desc, avatar) {
                    showCreateSheet = false
                }
            }
        )
    }
}

@Composable
private fun ChannelRow(
    channel: Channel,
    following: Boolean,
    isOwner: Boolean,
    onClick: () -> Unit,
    onToggleFollow: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .testTag("channel_row_${channel.id}"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        UserAvatar(
            name = channel.name,
            avatarBase64 = channel.avatarBase64,
            size = 52.dp
        )
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = channel.name,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = channel.lastPostText.ifBlank { channel.description.ifBlank { "${channel.followerCount} followers" } },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "${channel.followerCount} followers • by ${channel.ownerName}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Spacer(Modifier.width(8.dp))
        if (!isOwner) {
            if (following) {
                OutlinedButton(
                    onClick = onToggleFollow,
                    shape = RoundedCornerShape(50),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    Text("Following", style = MaterialTheme.typography.labelMedium)
                }
            } else {
                Button(
                    onClick = onToggleFollow,
                    shape = RoundedCornerShape(50),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)
                ) {
                    Text("Follow", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
    HorizontalDivider(
        color = LocalUChatColors.current.divider.copy(alpha = 0.5f),
        modifier = Modifier.padding(start = 82.dp, end = 16.dp)
    )
}

@Composable
fun ChannelDetailScreen(
    channel: Channel,
    following: Boolean,
    viewModel: UChatViewModel,
    showBackButton: Boolean = true,
    onBack: () -> Unit
) {
    if (showBackButton) {
        BackHandler(onBack = onBack)
    }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val postsState by viewModel.activeChannelPostsState.collectAsStateWithLifecycle()
    val isOwner = channel.ownerId == viewModel.currentUserId

    var postText by remember { mutableStateOf("") }
    var attachedImageBase64 by remember { mutableStateOf<String?>(null) }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            scope.launch {
                attachedImageBase64 = MediaUtils.compressUriToBase64(context, uri, 760, 260_000)
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .imePadding()
    ) {
        // Header
        Surface(color = MaterialTheme.colorScheme.surface) {
            Column(modifier = Modifier.statusBarsPadding()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (showBackButton) {
                        IconButton(onClick = onBack, modifier = Modifier.testTag("channel_back_button")) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to channels")
                        }
                    }
                    UserAvatar(name = channel.name, avatarBase64 = channel.avatarBase64, size = 40.dp)
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(channel.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${channel.followerCount} followers", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    }
                    if (!isOwner) {
                        Button(
                            onClick = { viewModel.toggleFollowChannel(channel, following) },
                            shape = RoundedCornerShape(50),
                            colors = if (following) {
                                ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant, contentColor = Color.White)
                            } else {
                                ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = Color.Black)
                            },
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                        ) {
                            Text(if (following) "Following" else "Follow", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
                HorizontalDivider(color = LocalUChatColors.current.divider)
            }
        }

        // Broadcast Posts Feed
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when (val state = postsState) {
                is UiState.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                is UiState.Error -> Text(state.message, color = MaterialTheme.colorScheme.error, modifier = Modifier.align(Alignment.Center).padding(20.dp))
                is UiState.Success -> {
                    if (state.data.isEmpty()) {
                        EmptyStateCard(
                            icon = Icons.Filled.Campaign,
                            title = channel.name,
                            body = channel.description.ifBlank {
                                if (isOwner) "Publish your first broadcast update below."
                                else "No broadcasts yet. Follow to receive real-time updates."
                            }
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            items(state.data, key = { it.id }) { post ->
                                ChannelPostCard(
                                    post = post,
                                    myUid = viewModel.currentUserId,
                                    onReact = { emoji -> viewModel.reactToChannelPost(channel.id, post, emoji) }
                                )
                            }
                        }
                    }
                }
            }
        }

        // Admin-only broadcast composer
        if (isOwner) {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth().navigationBarsPadding()
            ) {
                Column(modifier = Modifier.padding(10.dp)) {
                    if (attachedImageBase64 != null) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(bottom = 6.dp)
                        ) {
                            Text("Photo attached", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(8.dp))
                            IconButton(onClick = { attachedImageBase64 = null }, modifier = Modifier.size(24.dp)) {
                                Icon(Icons.Filled.Close, contentDescription = "Remove attached photo", modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = {
                                imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                            },
                            modifier = Modifier.testTag("channel_attach_image_button")
                        ) {
                            Icon(Icons.Filled.Image, contentDescription = "Attach broadcast photo", tint = MaterialTheme.colorScheme.primary)
                        }
                        OutlinedTextField(
                            value = postText,
                            onValueChange = { postText = it.take(2000) },
                            placeholder = { Text("Publish broadcast to followers...") },
                            maxLines = 3,
                            shape = RoundedCornerShape(22.dp),
                            modifier = Modifier.weight(1f).testTag("channel_post_input")
                        )
                        Spacer(Modifier.width(8.dp))
                        Surface(
                            color = MaterialTheme.colorScheme.primary,
                            shape = CircleShape,
                            modifier = Modifier
                                .size(46.dp)
                                .clickable {
                                    if (postText.isNotBlank() || attachedImageBase64 != null) {
                                        viewModel.publishChannelPost(channel, postText, attachedImageBase64)
                                        postText = ""
                                        attachedImageBase64 = null
                                    }
                                }
                                .testTag("channel_publish_button")
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Publish broadcast", tint = Color.Black)
                            }
                        }
                    }
                }
            }
        } else {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth().navigationBarsPadding()
            ) {
                Text(
                    text = "Only channel admins can publish updates. Tap an emoji to react.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(14.dp)
                )
            }
        }
    }
}

@Composable
private fun ChannelPostCard(
    post: ChannelPost,
    myUid: String,
    onReact: (String) -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(20.dp))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(post.authorName, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Text(formatTimestampShort(post.createdAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!post.mediaBase64.isNullOrBlank()) {
                val mediaVal = post.mediaBase64
                val isUrl = mediaVal.startsWith("https://") || mediaVal.startsWith("http://")
                if (isUrl) {
                    Spacer(Modifier.height(10.dp))
                    AsyncImage(
                        model = mediaVal,
                        contentDescription = "Channel broadcast photo",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(210.dp)
                            .clip(RoundedCornerShape(14.dp))
                    )
                } else {
                    val bmp = remember(mediaVal) { MediaUtils.decodeBase64ToImageBitmap(mediaVal) }
                    if (bmp != null) {
                        Spacer(Modifier.height(10.dp))
                        Image(
                            bitmap = bmp,
                            contentDescription = "Channel broadcast photo",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(210.dp)
                                .clip(RoundedCornerShape(14.dp))
                        )
                    }
                }
            }
            if (post.text.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                Text(post.text, style = MaterialTheme.typography.bodyLarge, color = Color.White)
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val counts = post.reactions.values.groupingBy { it }.eachCount()
                val myEmoji = post.reactions[myUid]
                ChannelReactionEmojis.forEach { emoji ->
                    val count = counts[emoji] ?: 0
                    val active = myEmoji == emoji
                    Surface(
                        color = if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.22f) else Color.Black.copy(alpha = 0.35f),
                        shape = RoundedCornerShape(50),
                        modifier = Modifier
                            .border(
                                1.dp,
                                if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                RoundedCornerShape(50)
                            )
                            .clickable { onReact(emoji) }
                    ) {
                        Text(
                            text = if (count > 0) "$emoji $count" else emoji,
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CreateChannelSheet(
    onDismiss: () -> Unit,
    onCreate: (String, String, String?) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var avatarBase64 by remember { mutableStateOf<String?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            scope.launch {
                avatarBase64 = MediaUtils.compressUriToBase64(context, uri, 320, 140_000)
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
                .padding(bottom = 28.dp)
        ) {
            Text("Create Broadcast Channel", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier.clickable {
                        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }
                ) {
                    UserAvatar(name = name.ifBlank { "C" }, avatarBase64 = avatarBase64, size = 56.dp)
                }
                Spacer(Modifier.width(12.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(80) },
                    label = { Text("Channel name") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.weight(1f).testTag("create_channel_name_input")
                )
            }
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = description,
                onValueChange = { description = it.take(300) },
                label = { Text("Channel description") },
                maxLines = 3,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth().testTag("create_channel_desc_input")
            )
            Spacer(Modifier.height(18.dp))
            Button(
                onClick = { onCreate(name, description, avatarBase64) },
                enabled = name.isNotBlank(),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth().height(50.dp).testTag("create_channel_submit_button")
            ) {
                Text("Create Channel", fontWeight = FontWeight.Bold)
            }
        }
    }
}
