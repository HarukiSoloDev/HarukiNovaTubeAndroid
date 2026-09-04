package com.harukisolodev.harukistream.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.harukisolodev.harukistream.data.BrowseVideo
import com.harukisolodev.harukistream.ui.BrowseViewModel
import com.harukisolodev.harukistream.ui.components.RemoteImage
import com.harukisolodev.harukistream.ui.theme.*

@Composable
fun HistoryScreen(
    vm: BrowseViewModel,
    history: List<BrowseVideo>,
    onMenu: () -> Unit,
    onOpen: (BrowseVideo) -> Unit
) {
    Column(Modifier.fillMaxSize().background(HarukiBg)) {
        HeaderRow("History", onMenu)
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            if (history.isNotEmpty()) {
                item {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Recently watched", style = MaterialTheme.typography.titleMedium, color = HarukiText, modifier = Modifier.weight(1f))
                        TextButton(onClick = { vm.clearHistory() }) {
                            Icon(Icons.Rounded.DeleteSweep, null, tint = HarukiDanger)
                            Spacer(Modifier.width(5.dp))
                            Text("Clear", color = HarukiDanger)
                        }
                    }
                }
                items(history, key = { it.url }) { item ->
                    Surface(
                        modifier = Modifier.fillMaxWidth().clickable { onOpen(item) },
                        color = HarukiCard,
                        shape = RoundedCornerShape(18.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, HarukiBorderSoft)
                    ) {
                        Row(Modifier.padding(10.dp), horizontalArrangement = Arrangement.spacedBy(11.dp), verticalAlignment = Alignment.CenterVertically) {
                            RemoteImage(item.thumbnailUrl, Modifier.width(132.dp).aspectRatio(16f / 9f))
                            Column(Modifier.weight(1f)) {
                                Text(item.title, color = HarukiText, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Spacer(Modifier.height(4.dp))
                                Text("${item.service} • ${item.uploader}", color = HarukiMuted, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                            }
                        }
                    }
                }
            } else {
                item {
                    Surface(color = HarukiCard, shape = RoundedCornerShape(18.dp), border = androidx.compose.foundation.BorderStroke(1.dp, HarukiBorderSoft)) {
                        Column(Modifier.fillMaxWidth().padding(26.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Nothing watched yet", style = MaterialTheme.typography.titleMedium, color = HarukiText)
                            Text("Videos you watch in Haruki NovaTube appear here.", color = HarukiMuted)
                        }
                    }
                }
            }
        }
    }
}
