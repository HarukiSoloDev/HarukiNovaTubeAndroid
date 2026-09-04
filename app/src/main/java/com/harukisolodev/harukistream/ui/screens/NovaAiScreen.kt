package com.harukisolodev.harukistream.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.harukisolodev.harukistream.data.*
import com.harukisolodev.harukistream.ui.NovaAdaptiveInfo
import com.harukisolodev.harukistream.ui.components.RemoteImage
import com.harukisolodev.harukistream.ui.components.formatDuration
import com.harukisolodev.harukistream.ui.theme.*

@Composable
fun NovaAiScreen(
    state: NovaAiState,
    onSearch: (String, NovaAiSearchMode) -> Unit,
    onClear: () -> Unit,
    onOpenVideo: (BrowseVideo) -> Unit,
    adaptive: NovaAdaptiveInfo
) {
    var prompt by remember(state.prompt) { mutableStateOf(state.prompt) }
    var selectedMode by remember(state.mode) { mutableStateOf(state.mode) }
    val examples = listOf(
        "A song with a blue thumbnail, male and female singer",
        "Minecraft 100 days video with a dragon",
        "A car review I never watched before, maybe a Honda from around 2023",
        "Funny Short where the cat jumps into a box"
    )

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(HarukiBg).statusBarsPadding().widthIn(max = adaptive.contentMaxWidth),
        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 118.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item(key = "ai-hero") {
            Surface(
                color = Color.Transparent,
                shape = RoundedCornerShape(28.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, HarukiViolet.copy(alpha = .45f))
            ) {
                Column(
                    Modifier.fillMaxWidth().background(
                        Brush.linearGradient(
                            listOf(Color(0xFF211638), Color(0xFF121A2C), Color(0xFF24131B))
                        )
                    ).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(13.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(
                            Modifier.size(52.dp).clip(RoundedCornerShape(17.dp)).background(
                                Brush.linearGradient(listOf(HarukiViolet, HarukiPrimary))
                            ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Rounded.AutoAwesome, null, tint = Color.White, modifier = Modifier.size(29.dp))
                        }
                        Column(Modifier.weight(1f)) {
                            Text("Nova AI", color = HarukiText, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold)
                            Text("Search all of YouTube plus your History, Saved, Downloads and local Playlists from anything you remember.", color = HarukiMuted)
                        }
                    }
                    Surface(color = Color.White.copy(alpha = .055f), shape = RoundedCornerShape(14.dp)) {
                        Row(Modifier.fillMaxWidth().padding(11.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Shield, null, tint = HarukiCyan, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Wide YouTube search • History, Saved & Downloads are only extra clues", color = HarukiMuted, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }

        item(key = "ai-input") {
            Surface(color = HarukiCard, shape = RoundedCornerShape(24.dp), border = androidx.compose.foundation.BorderStroke(1.dp, HarukiBorderSoft)) {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
                    Text("What do you remember?", color = HarukiText, style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(
                        value = prompt,
                        onValueChange = { prompt = it },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 126.dp),
                        placeholder = { Text("Example: I remember a Chinese song, two singers, dark blue thumbnail, maybe I played it a few weeks ago…", color = HarukiMuted2) },
                        leadingIcon = { Icon(Icons.Rounded.Psychology, null, tint = HarukiViolet) },
                        trailingIcon = {
                            if (prompt.isNotBlank()) IconButton(onClick = { prompt = ""; onClear() }) {
                                Icon(Icons.Rounded.Close, "Clear", tint = HarukiMuted)
                            }
                        },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = HarukiCardSoft,
                            unfocusedContainerColor = HarukiCardSoft,
                            focusedBorderColor = HarukiViolet,
                            unfocusedBorderColor = HarukiBorder,
                            focusedTextColor = HarukiText,
                            unfocusedTextColor = HarukiText
                        ),
                        shape = RoundedCornerShape(18.dp),
                        maxLines = 6
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Search mode", color = HarukiMuted, style = MaterialTheme.typography.labelMedium)
                        Row(
                            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            NovaAiSearchMode.entries.forEach { mode ->
                                val selected = selectedMode == mode
                                FilterChip(
                                    selected = selected,
                                    onClick = { selectedMode = mode },
                                    label = { Text(modeLabel(mode)) },
                                    leadingIcon = { Icon(modeIcon(mode), null, modifier = Modifier.size(17.dp)) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = HarukiViolet.copy(alpha = .22f),
                                        selectedLabelColor = HarukiText,
                                        selectedLeadingIconColor = HarukiViolet
                                    )
                                )
                            }
                        }
                        Text(modeDescription(selectedMode), color = HarukiMuted2, style = MaterialTheme.typography.bodySmall)
                    }
                    Button(
                        onClick = { onSearch(prompt, selectedMode) },
                        enabled = prompt.trim().length >= 3 && !state.loading,
                        modifier = Modifier.fillMaxWidth().height(54.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = HarukiViolet),
                        shape = RoundedCornerShape(17.dp)
                    ) {
                        if (state.loading) {
                            CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(21.dp))
                            Spacer(Modifier.width(10.dp))
                            Text("Nova AI is searching…", fontWeight = FontWeight.Bold)
                        } else {
                            Icon(modeIcon(selectedMode), null)
                            Spacer(Modifier.width(9.dp))
                            Text(
                                when (selectedMode) {
                                    NovaAiSearchMode.FAST -> "Fast search"
                                    NovaAiSearchMode.SMART -> "Find my video"
                                    NovaAiSearchMode.DEEP -> "Deep search"
                                },
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }

        if (state.matches.isEmpty() && !state.loading) {
            item(key = "ai-examples-title") {
                Text("Try describing it like this", color = HarukiText, style = MaterialTheme.typography.titleMedium)
            }
            item(key = "ai-examples") {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    examples.forEach { example ->
                        Surface(
                            modifier = Modifier.widthIn(max = 260.dp).clickable { prompt = example },
                            color = HarukiCard,
                            shape = RoundedCornerShape(16.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, HarukiBorderSoft)
                        ) {
                            Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.TipsAndUpdates, null, tint = HarukiCyan, modifier = Modifier.size(17.dp))
                                Spacer(Modifier.width(7.dp))
                                Text(example, color = HarukiText, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }

        if (state.loading || state.phase.isNotBlank()) {
            item(key = "ai-phase") {
                Surface(color = HarukiCardSoft, shape = RoundedCornerShape(16.dp), border = androidx.compose.foundation.BorderStroke(1.dp, HarukiBorderSoft)) {
                    Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (state.loading) CircularProgressIndicator(color = HarukiCyan, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                        else Icon(Icons.Rounded.CheckCircle, null, tint = HarukiSuccess, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(10.dp))
                        Text(state.phase, color = HarukiMuted, modifier = Modifier.weight(1f))
                    }
                }
            }
        }

        if (state.error.isNotBlank()) {
            item(key = "ai-error") {
                Surface(color = HarukiWarning.copy(alpha = .09f), shape = RoundedCornerShape(18.dp), border = androidx.compose.foundation.BorderStroke(1.dp, HarukiWarning.copy(alpha = .28f))) {
                    Row(Modifier.fillMaxWidth().padding(15.dp), verticalAlignment = Alignment.Top) {
                        Icon(Icons.Rounded.Lightbulb, null, tint = HarukiWarning)
                        Spacer(Modifier.width(10.dp))
                        Text(state.error, color = HarukiMuted, modifier = Modifier.weight(1f))
                    }
                }
            }
        }

        if (state.matches.isNotEmpty()) {
            item(key = "ai-results-header") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Best matches", color = HarukiText, style = MaterialTheme.typography.titleLarge)
                        Text("Tap any result to open it directly in NovaTube", color = HarukiMuted, style = MaterialTheme.typography.bodySmall)
                    }
                    Surface(color = HarukiViolet.copy(alpha = .14f), shape = RoundedCornerShape(999.dp)) {
                        Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.AutoAwesome, null, tint = HarukiViolet, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(5.dp))
                            Text("${state.matches.size} found", color = HarukiText, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }

            itemsIndexed(state.matches, key = { _, match -> "ai-${match.video.url}" }) { index, match ->
                NovaAiResultCard(match = match, best = index == 0, onClick = { onOpenVideo(match.video) })
            }

            if (state.queriesTried.isNotEmpty()) {
                item(key = "ai-query-footnote") {
                    Surface(color = HarukiCardSoft, shape = RoundedCornerShape(16.dp)) {
                        Column(Modifier.fillMaxWidth().padding(13.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text("Global search strategies Nova AI tried", color = HarukiText, style = MaterialTheme.typography.labelLarge)
                            Text(state.queriesTried.joinToString("  •  "), color = HarukiMuted2, style = MaterialTheme.typography.bodySmall, maxLines = 4, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

private fun modeLabel(mode: NovaAiSearchMode): String = when (mode) {
    NovaAiSearchMode.FAST -> "Fast"
    NovaAiSearchMode.SMART -> "Smart"
    NovaAiSearchMode.DEEP -> "Deep"
}

private fun modeDescription(mode: NovaAiSearchMode): String = when (mode) {
    NovaAiSearchMode.FAST -> "Fastest • fewer YouTube searches • skips slow metadata checks"
    NovaAiSearchMode.SMART -> "Balanced • broader search with a small amount of deeper checking"
    NovaAiSearchMode.DEEP -> "Most thorough • more pages, channels, playlists and metadata • slower"
}

private fun modeIcon(mode: NovaAiSearchMode): androidx.compose.ui.graphics.vector.ImageVector = when (mode) {
    NovaAiSearchMode.FAST -> Icons.Rounded.Bolt
    NovaAiSearchMode.SMART -> Icons.Rounded.AutoAwesome
    NovaAiSearchMode.DEEP -> Icons.Rounded.TravelExplore
}

@Composable
private fun NovaAiResultCard(match: NovaAiMatch, best: Boolean, onClick: () -> Unit) {
    val border = if (best) HarukiViolet.copy(alpha = .55f) else HarukiBorderSoft
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        color = if (best) Color.Transparent else HarukiCard,
        shape = RoundedCornerShape(22.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, border)
    ) {
        Column(
            Modifier.fillMaxWidth().then(
                if (best) Modifier.background(Brush.linearGradient(listOf(Color(0xFF1E1730), HarukiCard, Color(0xFF1D1217)))) else Modifier
            ).padding(11.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (best) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.AutoAwesome, null, tint = HarukiViolet, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Nova AI's best match", color = HarukiViolet, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.weight(1f))
                    ConfidenceBadge(match.confidence)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(11.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(150.dp).aspectRatio(16f / 9f)) {
                    RemoteImage(match.video.thumbnailUrl, Modifier.fillMaxSize().clip(RoundedCornerShape(13.dp)))
                    if (match.video.durationSeconds > 0) {
                        Surface(Modifier.align(Alignment.BottomEnd).padding(5.dp), color = Color.Black.copy(alpha = .78f), shape = RoundedCornerShape(5.dp)) {
                            Text(formatDuration(match.video.durationSeconds), color = Color.White, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp))
                        }
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (!best) ConfidenceBadge(match.confidence)
                    Text(match.video.title, color = HarukiText, fontWeight = FontWeight.Bold, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    Text(match.video.uploader.ifBlank { sourceLabel(match.source) }, color = HarukiMuted, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                }
            }
            Text(match.reason, color = HarukiMuted, style = MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AiCluePill(sourceLabel(match.source), sourceIcon(match.source), HarukiCyan)
                match.matchedClues.take(4).forEach { clue ->
                    AiCluePill(clue, null, HarukiMuted)
                }
            }
        }
    }
}

@Composable
private fun AiCluePill(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector?, tint: Color) {
    Surface(color = HarukiCardSoft, shape = RoundedCornerShape(999.dp), border = androidx.compose.foundation.BorderStroke(1.dp, HarukiBorderSoft)) {
        Row(Modifier.padding(horizontal = 9.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(icon, null, tint = tint, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(5.dp))
            }
            Text(text, color = if (icon != null) HarukiText else HarukiMuted, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun ConfidenceBadge(confidence: Int) {
    val tint = when {
        confidence >= 80 -> HarukiSuccess
        confidence >= 55 -> HarukiCyan
        else -> HarukiWarning
    }
    Surface(color = tint.copy(alpha = .12f), shape = RoundedCornerShape(999.dp), border = androidx.compose.foundation.BorderStroke(1.dp, tint.copy(alpha = .28f))) {
        Text("$confidence% match", color = tint, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
    }
}

private fun sourceLabel(source: NovaAiMatchSource): String = when (source) {
    NovaAiMatchSource.HISTORY -> "History"
    NovaAiMatchSource.SAVED -> "Saved"
    NovaAiMatchSource.DOWNLOAD -> "Downloaded"
    NovaAiMatchSource.LOCAL_PLAYLIST -> "Your playlist"
    NovaAiMatchSource.YOUTUBE -> "Global YouTube"
    NovaAiMatchSource.YOUTUBE_PLAYLIST -> "Playlist discovery"
    NovaAiMatchSource.YOUTUBE_CHANNEL -> "Channel discovery"
}

private fun sourceIcon(source: NovaAiMatchSource) = when (source) {
    NovaAiMatchSource.HISTORY -> Icons.Rounded.History
    NovaAiMatchSource.SAVED -> Icons.Rounded.Bookmark
    NovaAiMatchSource.DOWNLOAD -> Icons.Rounded.Download
    NovaAiMatchSource.LOCAL_PLAYLIST -> Icons.AutoMirrored.Rounded.PlaylistPlay
    NovaAiMatchSource.YOUTUBE -> Icons.Rounded.Public
    NovaAiMatchSource.YOUTUBE_PLAYLIST -> Icons.AutoMirrored.Rounded.PlaylistPlay
    NovaAiMatchSource.YOUTUBE_CHANNEL -> Icons.Rounded.AccountCircle
}
