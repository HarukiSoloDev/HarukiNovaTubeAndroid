package com.harukisolodev.harukistream.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.harukisolodev.harukistream.data.BrowseVideo
import com.harukisolodev.harukistream.data.DownloadQueueItem
import com.harukisolodev.harukistream.data.DownloadQueueStatus
import com.harukisolodev.harukistream.data.LibraryItem
import com.harukisolodev.harukistream.ui.theme.*

@Composable
fun LibraryHubScreen(
    saved: List<BrowseVideo>,
    history: List<BrowseVideo>,
    queue: List<DownloadQueueItem>,
    downloads: List<LibraryItem>,
    playlistCount: Int,
    onSaved: () -> Unit,
    onHistory: () -> Unit,
    onDownloads: () -> Unit,
    onPlaylists: () -> Unit
) {
    val active = queue.count { it.status == DownloadQueueStatus.QUEUED || it.status == DownloadQueueStatus.RUNNING }
    LazyColumn(
        Modifier.fillMaxSize().background(HarukiBg).statusBarsPadding(),
        contentPadding = PaddingValues(14.dp, 10.dp, 14.dp, 118.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text("Library", style = MaterialTheme.typography.headlineMedium, color = HarukiText, fontWeight = FontWeight.ExtraBold)
            Text("Everything you keep in NovaTube, in one place.", color = HarukiMuted)
        }
        item { LibraryEntry(Icons.Rounded.Bookmark, "Saved", "${saved.size} videos", HarukiViolet, onSaved) }
        item { LibraryEntry(Icons.Rounded.Download, "Downloads", if (active > 0) "$active active • ${downloads.size} completed" else "${downloads.size} completed", HarukiCyan, onDownloads) }
        item { LibraryEntry(Icons.Rounded.PlaylistPlay, "Playlists", "$playlistCount local playlists", HarukiPrimary, onPlaylists) }
        item { LibraryEntry(Icons.Rounded.History, "History", "${history.size} recently watched", HarukiPrimary, onHistory) }
        item {
            Surface(color = HarukiCardSoft, shape = RoundedCornerShape(18.dp), border = androidx.compose.foundation.BorderStroke(1.dp, HarukiBorderSoft)) {
                Row(Modifier.fillMaxWidth().padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.AutoAwesome, null, tint = HarukiViolet)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Nova AI remembers locally", color = HarukiText, fontWeight = FontWeight.SemiBold)
                        Text("History, Saved and Downloads help Nova AI find videos you only partly remember.", color = HarukiMuted, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun LibraryEntry(icon: ImageVector, title: String, subtitle: String, tint: Color, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        color = HarukiCard,
        shape = RoundedCornerShape(22.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, HarukiBorderSoft)
    ) {
        Row(Modifier.fillMaxWidth().padding(17.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(46.dp).background(tint.copy(alpha = .12f), RoundedCornerShape(15.dp)), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = tint)
            }
            Spacer(Modifier.width(13.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = HarukiText, style = MaterialTheme.typography.titleMedium)
                Text(subtitle, color = HarukiMuted, style = MaterialTheme.typography.bodySmall)
            }
            Icon(Icons.Rounded.ChevronRight, null, tint = HarukiMuted)
        }
    }
}
