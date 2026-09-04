package com.harukisolodev.harukistream.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PlaylistPlay
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.harukisolodev.harukistream.data.LocalPlaylist
import com.harukisolodev.harukistream.ui.components.RemoteImage
import com.harukisolodev.harukistream.ui.components.formatDuration
import com.harukisolodev.harukistream.ui.theme.*

@Composable
fun LocalPlaylistsScreen(
    playlists: List<LocalPlaylist>,
    onBack: () -> Unit,
    onPlay: (String, Int) -> Unit,
    onDelete: (String) -> Unit
) {
    var expandedId by remember { mutableStateOf(playlists.firstOrNull()?.id.orEmpty()) }
    LazyColumn(
        Modifier.fillMaxSize().background(HarukiBg).statusBarsPadding(),
        contentPadding = PaddingValues(bottom = 110.dp)
    ) {
        item {
            Row(Modifier.fillMaxWidth().height(58.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = HarukiText) }
                Text("Playlists", color = HarukiText, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
        }
        if (playlists.isEmpty()) {
            item {
                Surface(Modifier.fillMaxWidth().padding(16.dp), color = HarukiCard, shape = RoundedCornerShape(20.dp)) {
                    Column(Modifier.padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Rounded.PlaylistPlay, null, tint = HarukiPrimary, modifier = Modifier.size(44.dp))
                        Spacer(Modifier.height(10.dp))
                        Text("No playlists yet", color = HarukiText, fontWeight = FontWeight.Bold)
                        Text("Open a video and tap Playlist to create one.", color = HarukiMuted)
                    }
                }
            }
        }
        playlists.forEach { playlist ->
            item(key = "pl-${playlist.id}") {
                Surface(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).clickable {
                        expandedId = if (expandedId == playlist.id) "" else playlist.id
                    },
                    color = HarukiCard, shape = RoundedCornerShape(20.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, HarukiBorderSoft)
                ) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(54.dp).background(HarukiPrimary.copy(alpha = .13f), RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.PlaylistPlay, null, tint = HarukiPrimary)
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(playlist.name, color = HarukiText, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${playlist.videos.size} videos", color = HarukiMuted, style = MaterialTheme.typography.bodySmall)
                        }
                        if (playlist.videos.isNotEmpty()) IconButton(onClick = { onPlay(playlist.id, 0) }) { Icon(Icons.Rounded.PlayArrow, "Play", tint = HarukiPrimary) }
                        IconButton(onClick = { onDelete(playlist.id) }) { Icon(Icons.Rounded.DeleteOutline, "Delete", tint = HarukiMuted) }
                    }
                }
            }
            if (expandedId == playlist.id) {
                itemsIndexed(playlist.videos, key = { index, video -> "${playlist.id}-$index-${video.url}" }) { index, video ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onPlay(playlist.id, index) }.padding(horizontal = 18.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(Modifier.width(124.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(10.dp))) {
                            RemoteImage(video.thumbnailUrl, Modifier.fillMaxSize())
                            if (video.durationSeconds > 0) {
                                Surface(Modifier.align(Alignment.BottomEnd).padding(4.dp), color = androidx.compose.ui.graphics.Color.Black.copy(alpha = .78f), shape = RoundedCornerShape(5.dp)) {
                                    Text(formatDuration(video.durationSeconds), color = androidx.compose.ui.graphics.Color.White, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp))
                                }
                            }
                        }
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(video.title, color = HarukiText, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                            Text(video.uploader, color = HarukiMuted, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}
