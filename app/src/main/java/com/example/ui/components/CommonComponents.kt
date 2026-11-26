package com.example.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Group
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import coil.compose.AsyncImage
import com.example.data.model.AppTimestamp
import com.example.media.MediaUtils
import com.example.ui.theme.LocalUChatColors
import org.webrtc.EglBase
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs

private val AvatarGradients = listOf(
    listOf(Color(0xFF1A5E44), Color(0xFF0D3326)),
    listOf(Color(0xFF14526E), Color(0xFF0B2C3D)),
    listOf(Color(0xFF4A2B7A), Color(0xFF251442)),
    listOf(Color(0xFF704214), Color(0xFF3A2108)),
    listOf(Color(0xFF6B2446), Color(0xFF381023))
)

@Composable
fun UserAvatar(
    name: String,
    avatarBase64: String? = null,
    size: Dp = 48.dp,
    online: Boolean = false,
    showOfflineDot: Boolean = false,
    isGroup: Boolean = false,
    isSelfChat: Boolean = false,
    modifier: Modifier = Modifier
) {
    val isRemoteUrl = remember(avatarBase64) {
        avatarBase64 != null && (avatarBase64.startsWith("https://") || avatarBase64.startsWith("http://"))
    }
    val bitmap = remember(avatarBase64, isRemoteUrl) {
        if (isRemoteUrl) null else MediaUtils.decodeBase64ToImageBitmap(avatarBase64)
    }
    val gradient = remember(name) {
        AvatarGradients[abs(name.hashCode()) % AvatarGradients.size]
    }
    val initials = remember(name) {
        name.trim().split(" ").filter { it.isNotBlank() }
            .take(2)
            .joinToString("") { it.first().uppercaseChar().toString() }
            .ifBlank { "U" }
    }

    Box(modifier = modifier.size(size)) {
        if (isSelfChat) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(CircleShape)
                    .background(
                        Brush.linearGradient(
                            listOf(
                                MaterialTheme.colorScheme.primary,
                                MaterialTheme.colorScheme.secondary
                            )
                        )
                    )
                    .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.6f), CircleShape)
            ) {
                Icon(
                    imageVector = Icons.Filled.Bookmark,
                    contentDescription = "Saved Messages",
                    tint = Color.Black,
                    modifier = Modifier.size(size * 0.5f)
                )
            }
        } else if (isRemoteUrl && !avatarBase64.isNullOrBlank()) {
            AsyncImage(
                model = avatarBase64,
                contentDescription = "$name avatar",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(CircleShape)
                    .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
            )
        } else if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = "$name avatar",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(CircleShape)
                    .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
            )
        } else {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(CircleShape)
                    .background(Brush.linearGradient(gradient))
                    .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.7f), CircleShape)
            ) {
                if (isGroup) {
                    Icon(
                        imageVector = Icons.Filled.Group,
                        contentDescription = "$name group icon",
                        tint = Color.White,
                        modifier = Modifier.size(size * 0.5f)
                    )
                } else {
                    Text(
                        text = initials,
                        color = Color.White,
                        style = if (size >= 52.dp) MaterialTheme.typography.titleMedium else MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        if ((online || showOfflineDot) && !isSelfChat && !isGroup) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(size * 0.28f)
                    .clip(CircleShape)
                    .background(
                        if (online) MaterialTheme.colorScheme.primary
                        else Color(0xFF5A5A60)
                    )
                    .border(2.dp, MaterialTheme.colorScheme.background, CircleShape)
            )
        }
    }
}

@Composable
fun PresenceStatusPill(
    online: Boolean,
    lastSeenText: String? = null,
    modifier: Modifier = Modifier
) {
    val dotColor = if (online) MaterialTheme.colorScheme.primary else Color(0xFF6E6E76)
    val label = when {
        online -> "Online"
        !lastSeenText.isNullOrBlank() -> "Offline • $lastSeenText"
        else -> "Offline"
    }
    Surface(
        color = if (online) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f) else Color(0xFF222226),
        shape = RoundedCornerShape(50),
        modifier = modifier
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(dotColor)
            )
            Spacer(Modifier.width(5.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = if (online) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = if (online) FontWeight.SemiBold else FontWeight.Normal
            )
        }
    }
}

@Composable
fun MessageStatusTicks(
    deliveredCount: Int,
    readCount: Int,
    showBlueRead: Boolean,
    isSelfChat: Boolean = false,
    modifier: Modifier = Modifier
) {
    val extended = LocalUChatColors.current
    val readByOther = isSelfChat || readCount > 1
    val deliveredToOther = isSelfChat || deliveredCount > 1
    val icon = if (readByOther || deliveredToOther) Icons.Filled.DoneAll else Icons.Filled.Done
    val tint = if (readByOther && (isSelfChat || showBlueRead)) {
        extended.readTickBlue
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Icon(
        imageVector = icon,
        contentDescription = when {
            isSelfChat -> "Saved"
            readByOther && showBlueRead -> "Read"
            deliveredToOther -> "Delivered"
            else -> "Sent"
        },
        tint = tint,
        modifier = modifier.size(16.dp)
    )
}

@Composable
fun AudioWaveformBars(
    amplitudes: List<Int>,
    progress: Float,
    activeColor: Color,
    inactiveColor: Color,
    modifier: Modifier = Modifier
) {
    val bars = remember(amplitudes) {
        val raw = if (amplitudes.isEmpty()) List(28) { 25 + ((it * 19) % 65) } else amplitudes
        if (raw.size <= 32) raw else {
            val step = raw.size.toFloat() / 32f
            List(32) { idx -> raw[(idx * step).toInt().coerceIn(0, raw.lastIndex)] }
        }
    }
    Canvas(modifier = modifier) {
        val count = bars.size.coerceAtLeast(1)
        val gap = 3.dp.toPx()
        val barWidth = ((size.width - gap * (count - 1)) / count).coerceAtLeast(2.dp.toPx())
        bars.forEachIndexed { index, amp ->
            val frac = (amp.coerceIn(10, 100) / 100f)
            val h = (size.height * frac).coerceAtLeast(4.dp.toPx())
            val x = index * (barWidth + gap)
            val y = (size.height - h) / 2f
            val barProg = index.toFloat() / count.toFloat()
            drawRoundRect(
                color = if (barProg <= progress) activeColor else inactiveColor,
                topLeft = Offset(x, y),
                size = Size(barWidth, h),
                cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f)
            )
        }
    }
}

@Composable
fun EmptyStateCard(
    icon: ImageVector,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 28.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = CircleShape,
            modifier = Modifier.size(72.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(34.dp)
                )
            }
        }
        Spacer(Modifier.height(18.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        if (action != null) {
            Spacer(Modifier.height(20.dp))
            action()
        }
    }
}

@Composable
fun WebRtcVideoRenderer(
    videoTrack: VideoTrack?,
    eglBaseContext: EglBase.Context,
    mirror: Boolean,
    zOrderMediaOverlay: Boolean = false,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val renderer = remember(eglBaseContext, zOrderMediaOverlay) {
        SurfaceViewRenderer(context).apply {
            init(eglBaseContext, null)
            setEnableHardwareScaler(true)
            setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
            setZOrderOnTop(false)
            setZOrderMediaOverlay(zOrderMediaOverlay)
            setMirror(mirror)
            if (zOrderMediaOverlay) {
                outlineProvider = object : android.view.ViewOutlineProvider() {
                    override fun getOutline(view: android.view.View, outline: android.graphics.Outline) {
                        val radiusPx = 18f * view.resources.displayMetrics.density
                        outline.setRoundRect(0, 0, view.width, view.height, radiusPx)
                    }
                }
                clipToOutline = true
            }
        }
    }

    DisposableEffect(renderer, mirror) {
        runCatching { renderer.setMirror(mirror) }
        onDispose { }
    }

    DisposableEffect(renderer, videoTrack) {
        if (videoTrack != null) {
            runCatching { videoTrack.addSink(renderer) }
        }
        onDispose {
            if (videoTrack != null) {
                runCatching { videoTrack.removeSink(renderer) }
            }
        }
    }

    DisposableEffect(renderer) {
        onDispose {
            runCatching { renderer.release() }
        }
    }

    AndroidView(
        factory = { renderer },
        modifier = modifier
    )
}

private val URL_REGEX = Regex("(https?://[\\w\\-._~:/?#\\[\\]@!$&'()*+,;=%]+)")

fun extractFirstUrl(text: String): String? = URL_REGEX.find(text)?.value

fun formatTimestampShort(timestamp: AppTimestamp?): String {
    val date = timestamp?.toDate() ?: return ""
    val now = Calendar.getInstance()
    val target = Calendar.getInstance().apply { time = date }
    return if (now.get(Calendar.YEAR) == target.get(Calendar.YEAR) &&
        now.get(Calendar.DAY_OF_YEAR) == target.get(Calendar.DAY_OF_YEAR)
    ) {
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(date)
    } else {
        SimpleDateFormat("MMM d", Locale.getDefault()).format(date)
    }
}

fun formatTimeOnly(timestamp: AppTimestamp?): String {
    val date = timestamp?.toDate() ?: Date()
    return SimpleDateFormat("HH:mm", Locale.getDefault()).format(date)
}

fun formatDateSeparator(timestamp: AppTimestamp?): String {
    val date = timestamp?.toDate() ?: return "Today"
    val now = Calendar.getInstance()
    val target = Calendar.getInstance().apply { time = date }
    val sameYear = now.get(Calendar.YEAR) == target.get(Calendar.YEAR)
    return when {
        sameYear && now.get(Calendar.DAY_OF_YEAR) == target.get(Calendar.DAY_OF_YEAR) -> "Today"
        sameYear && now.get(Calendar.DAY_OF_YEAR) - target.get(Calendar.DAY_OF_YEAR) == 1 -> "Yesterday"
        else -> SimpleDateFormat("MMMM d, yyyy", Locale.getDefault()).format(date)
    }
}

fun formatDurationSec(seconds: Long): String {
    val safe = seconds.coerceAtLeast(0L)
    val mins = safe / 60L
    val secs = safe % 60L
    return String.format(Locale.getDefault(), "%02d:%02d", mins, secs)
}
