package com.harukisolodev.harukistream.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.harukisolodev.harukistream.data.BrowseVideo
import com.harukisolodev.harukistream.ui.components.RemoteImage
import com.harukisolodev.harukistream.ui.theme.*

@Composable
fun SavedScreen(
    items: List<BrowseVideo>,
    onMenu: () -> Unit,
    onOpen: (BrowseVideo) -> Unit,
    onRemove: (BrowseVideo) -> Unit,
    onClear: () -> Unit
) {
    Column(Modifier.fillMaxSize().background(HarukiBg)) {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().height(52.dp).padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onMenu, modifier = Modifier.size(42.dp)) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = HarukiText)
            }
            Text("Saved", style = MaterialTheme.typography.titleLarge, color = HarukiText, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            if (items.isNotEmpty()) TextButton(onClick = onClear) { Text("Clear", color = HarukiPrimary) }
        }

        if (items.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Rounded.Bookmark, null, tint = HarukiMuted, modifier = Modifier.size(44.dp))
                    Text("Nothing saved yet", color = HarukiText, style = MaterialTheme.typography.titleMedium)
                    Text("Tap Save under a YouTube video or Short.", color = HarukiMuted)
                }
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(items, key = { it.url }) { video ->
                    Surface(
                        modifier = Modifier.fillMaxWidth().clickable { onOpen(video) },
                        color = HarukiCard,
                        shape = RoundedCornerShape(16.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, HarukiBorderSoft)
                    ) {
                        Row(Modifier.padding(9.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            RemoteImage(video.thumbnailUrl, Modifier.width(128.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(10.dp)))
                            Column(Modifier.weight(1f)) {
                                Text(video.title, color = HarukiText, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Spacer(Modifier.height(3.dp))
                                Text(video.uploader.ifBlank { video.service }, color = HarukiMuted, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                            }
                            IconButton(onClick = { onRemove(video) }) {
                                Icon(Icons.Rounded.DeleteOutline, "Remove saved video", tint = HarukiMuted)
                            }
                        }
                    }
                }
            }
        }
    }
}
