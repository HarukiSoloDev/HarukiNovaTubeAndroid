package com.harukisolodev.harukistream.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.harukisolodev.harukistream.data.*
import com.harukisolodev.harukistream.ui.BrowseViewModel
import com.harukisolodev.harukistream.ui.NovaAdaptiveInfo
import com.harukisolodev.harukistream.ui.components.RemoteImage
import com.harukisolodev.harukistream.ui.components.formatDuration
import com.harukisolodev.harukistream.ui.theme.*

@Composable
fun YouTubeScreen(
    state: BrowseState,
    history: List<BrowseVideo>,
    vm: BrowseViewModel,
    onOpenVideo: (BrowseVideo) -> Unit,
    onOpenShorts: (BrowseVideo?) -> Unit,
    adaptive: NovaAdaptiveInfo,
    downloadedKeys: Set<String> = emptySet()
) {
    if (state.collection.entity != null) {
        CollectionScreen(
            collection = state.collection,
            vm = vm,
            onBack = vm::closeCollection,
            onOpenVideo = onOpenVideo,
            onOpenShorts = onOpenShorts,
            adaptive = adaptive,
            downloadedKeys = downloadedKeys
        )
        return
    }

    var searchMode by remember { mutableStateOf(false) }
    val query = state.youtubeSearchDraft
    val videos = if (state.youtubeQuery.isNotBlank()) state.youtubeSearch else state.youtubeHome
    val shorts = videos.filter { it.shortForm || it.url.contains("/shorts/", true) }.take(18)
    val regular = videos.filterNot { it.shortForm || it.url.contains("/shorts/", true) }
    val listState = rememberLazyListState()

    LaunchedEffect(state.youtubeQuery, state.youtubeCategory) {
        listState.scrollToItem(0)
    }
    LaunchedEffect(state.youtubeHomeNavigationToken) {
        if (state.youtubeHomeNavigationToken > 0L) {
            searchMode = false
            listState.scrollToItem(0)
        }
    }
    LaunchedEffect(searchMode) {
        if (searchMode) listState.scrollToItem(0)
    }

    BackHandler(enabled = searchMode || state.youtubeQuery.isNotBlank()) {
        if (state.youtubeQuery.isNotBlank()) vm.clearSearch()
        searchMode = false
    }

    val nearEnd by remember {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            val total = listState.layoutInfo.totalItemsCount
            total > 0 && last >= total - 5
        }
    }
    LaunchedEffect(nearEnd, state.youtubeLoadingMore, state.youtubeHasMore) {
        if (nearEnd && !state.youtubeLoadingMore && state.youtubeHasMore) vm.loadMoreYouTube()
    }

    Column(Modifier.fillMaxSize().background(HarukiBg).statusBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().height(if (adaptive.largeTouchTargets) 66.dp else 52.dp).padding(horizontal = if (adaptive.isLarge) 16.dp else 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text("NovaTube", style = if (adaptive.isLarge) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f), color = HarukiText)
            IconButton(onClick = {
                searchMode = !searchMode
                if (!searchMode) vm.requestSearchSuggestions("")
            }, modifier = Modifier.size(42.dp)) {
                Icon(Icons.Rounded.Search, "Search", tint = HarukiText)
            }
            IconButton(onClick = { onOpenShorts(null) }, modifier = Modifier.size(42.dp)) {
                Icon(Icons.Rounded.PlayCircle, "Shorts", tint = HarukiPrimary)
            }
        }

        if (searchMode) {
            Column(Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = query,
                    onValueChange = {
                        vm.updateSearchDraft(it)
                        vm.requestSearchSuggestions(it)
                    },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp),
                    singleLine = true,
                    placeholder = { Text("Search YouTube", color = HarukiMuted2) },
                    leadingIcon = { Icon(Icons.Rounded.Search, null, tint = HarukiMuted) },
                    trailingIcon = {
                        if (query.isNotBlank()) IconButton(onClick = { vm.updateSearchDraft(""); vm.clearSearch(); vm.requestSearchSuggestions("") }) {
                            Icon(Icons.Rounded.Close, "Clear")
                        }
                    },
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { vm.searchYouTube(query) }),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = HarukiCardSoft,
                        unfocusedContainerColor = HarukiCardSoft,
                        focusedBorderColor = HarukiPrimary,
                        unfocusedBorderColor = HarukiBorder,
                        focusedTextColor = HarukiText,
                        unfocusedTextColor = HarukiText
                    ),
                    shape = RoundedCornerShape(14.dp)
                )

                val showSuggestions = query.trim().length >= 2 &&
                    query.trim() != state.youtubeQuery && state.youtubeSuggestions.isNotEmpty()
                if (showSuggestions) {
                    Surface(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                        color = HarukiCard,
                        shape = RoundedCornerShape(14.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, HarukiBorderSoft)
                    ) {
                        Column {
                            state.youtubeSuggestions.take(8).forEach { suggestion ->
                                Row(
                                    Modifier.fillMaxWidth().clickable {
                                        vm.updateSearchDraft(suggestion)
                                        vm.searchYouTube(suggestion)
                                    }.padding(horizontal = 13.dp, vertical = 11.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Rounded.Search, null, tint = HarukiMuted, modifier = Modifier.size(20.dp))
                                    Spacer(Modifier.width(11.dp))
                                    Text(suggestion, color = HarukiText, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Icon(Icons.Rounded.NorthWest, null, tint = HarukiMuted2, modifier = Modifier.size(17.dp))
                                }
                            }
                        }
                    }
                }
            }
        } else {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 3.dp),
                horizontalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                listOf("For You", "Gaming", "Music", "Tech", "Cars", "Mixes").forEach { category ->
                    FilterChip(
                        selected = state.youtubeCategory == category && state.youtubeQuery.isBlank(),
                        onClick = { vm.loadYouTube(category) },
                        label = { Text(category) },
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = HarukiCard,
                            labelColor = HarukiText,
                            selectedContainerColor = HarukiPrimary,
                            selectedLabelColor = Color.White
                        )
                    )
                }
            }
        }

        Box(Modifier.weight(1f)) {
            when {
                state.youtubeLoading && videos.isEmpty() && state.youtubeSearchChannels.isEmpty() && state.youtubeSearchPlaylists.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = HarukiPrimary)
                }
                state.youtubeError.isNotBlank() && videos.isEmpty() && state.youtubeSearchChannels.isEmpty() && state.youtubeSearchPlaylists.isEmpty() -> ErrorPane(state.youtubeError) {
                    if (query.isNotBlank()) vm.searchYouTube(query) else vm.loadYouTube()
                }
                else -> LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (state.youtubeQuery.isNotBlank() && state.youtubeSearchSuggestion.isNotBlank() &&
                        !state.youtubeSearchSuggestion.equals(state.youtubeQuery, true)) {
                        item(key = "did-you-mean") {
                            Surface(
                                onClick = {
                                    vm.updateSearchDraft(state.youtubeSearchSuggestion)
                                    vm.searchYouTube(state.youtubeSearchSuggestion)
                                },
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                                color = HarukiCardSoft,
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text(
                                    "Did you mean: ${state.youtubeSearchSuggestion}",
                                    color = HarukiPrimary,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }

                    if (state.youtubeQuery.isNotBlank() && state.youtubeSearchChannels.isNotEmpty()) {
                        item(key = "channels-heading") { SearchSectionTitle("Channels") }
                        items(state.youtubeSearchChannels, key = { "channel-${it.url}" }) { entity ->
                            ChannelSearchCard(entity) { vm.openCollection(entity) }
                        }
                    }

                    if (state.youtubeQuery.isNotBlank() && state.youtubeSearchPlaylists.isNotEmpty()) {
                        item(key = "playlists-heading") { SearchSectionTitle("Playlists") }
                        items(state.youtubeSearchPlaylists, key = { "playlist-${it.url}" }) { entity ->
                            PlaylistSearchCard(entity) { vm.openCollection(entity) }
                        }
                    }

                    val recent = history.filter { it.service.equals("YouTube", true) && !it.shortForm }.take(12)
                    if (recent.isNotEmpty() && state.youtubeQuery.isBlank() && state.youtubeCategory == "For You") {
                        item(key = "continue") {
                            Column(Modifier.padding(top = 4.dp, bottom = 7.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Continue watching", style = MaterialTheme.typography.titleMedium, color = HarukiText, modifier = Modifier.padding(horizontal = 14.dp))
                                LazyRow(contentPadding = PaddingValues(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                                    items(recent, key = { "recent-${it.url}" }) { video ->
                                        Column(Modifier.width(190.dp).clickable { onOpenVideo(video) }, verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                            Box {
                                                RemoteImage(video.thumbnailUrl, Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp)))
                                                if (video.durationSeconds > 0) DurationBadge(formatDuration(video.durationSeconds), Modifier.align(Alignment.BottomEnd).padding(5.dp))
                                            }
                                            Text(video.title, color = HarukiText, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (shorts.isNotEmpty() && state.youtubeQuery.isBlank()) {
                        item(key = "shorts-strip") {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                                Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Rounded.PlayCircle, null, tint = HarukiPrimary, modifier = Modifier.size(22.dp))
                                    Spacer(Modifier.width(7.dp))
                                    Text("Shorts", style = MaterialTheme.typography.titleMedium, color = HarukiText, modifier = Modifier.weight(1f))
                                    TextButton(onClick = { onOpenShorts(null) }) { Text("View all", color = HarukiPrimary) }
                                }
                                LazyRow(contentPadding = PaddingValues(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                                    items(shorts, key = { "short-${it.url}" }) { short ->
                                        Column(
                                            Modifier.width(140.dp).clickable { onOpenShorts(short) },
                                            verticalArrangement = Arrangement.spacedBy(5.dp)
                                        ) {
                                            Box {
                                                RemoteImage(short.thumbnailUrl, Modifier.fillMaxWidth().aspectRatio(9f / 16f).clip(RoundedCornerShape(14.dp)))
                                                if (short.durationSeconds > 0) DurationBadge(formatDuration(short.durationSeconds), Modifier.align(Alignment.BottomEnd).padding(5.dp))
                                            }
                                            Text(short.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = HarukiText)
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (state.youtubeQuery.isNotBlank() && regular.isNotEmpty()) {
                        item(key = "videos-heading") { SearchSectionTitle("Videos") }
                    }
                    val feedColumns = adaptive.feedColumns.coerceAtLeast(1)
                    if (feedColumns == 1) {
                        items(regular, key = { it.url }) { item ->
                            YouTubeVideoCard(
                                item = item,
                                downloaded = SavedVideoStore.canonicalKey(item.url, item.id) in downloadedKeys,
                                onClick = { onOpenVideo(item) },
                                onNotInterested = { vm.notInterestedVideo(item) },
                                onDontRecommendChannel = { vm.dontRecommendChannel(item) }
                            )
                        }
                    } else {
                        items(regular.chunked(feedColumns), key = { row -> row.joinToString("|") { it.url } }) { row ->
                            Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                row.forEach { item ->
                                    YouTubeVideoCard(
                                        item = item, modifier = Modifier.weight(1f),
                                        downloaded = SavedVideoStore.canonicalKey(item.url, item.id) in downloadedKeys,
                                        onClick = { onOpenVideo(item) },
                                        onNotInterested = { vm.notInterestedVideo(item) },
                                        onDontRecommendChannel = { vm.dontRecommendChannel(item) }
                                    )
                                }
                                repeat(feedColumns - row.size) { Spacer(Modifier.weight(1f)) }
                            }
                        }
                    }
                    if (state.youtubeLoadingMore) {
                        item(key = "loading-more") {
                            Box(Modifier.fillMaxWidth().padding(22.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(color = HarukiPrimary, modifier = Modifier.size(28.dp))
                            }
                        }
                    } else if (!state.youtubeHasMore && regular.isNotEmpty()) {
                        item(key = "end") {
                            Text(
                                "You're all caught up for this source. Search or change a category for more.",
                                color = HarukiMuted2,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.fillMaxWidth().padding(24.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CollectionScreen(
    collection: BrowseCollectionState,
    vm: BrowseViewModel,
    onBack: () -> Unit,
    onOpenVideo: (BrowseVideo) -> Unit,
    onOpenShorts: (BrowseVideo?) -> Unit,
    adaptive: NovaAdaptiveInfo,
    downloadedKeys: Set<String>
) {
    val listState = rememberLazyListState()
    val nearEnd by remember {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            val total = listState.layoutInfo.totalItemsCount
            total > 0 && last >= total - 4
        }
    }
    LaunchedEffect(collection.entity?.url, collection.selectedTab) {
        listState.scrollToItem(0)
    }
    LaunchedEffect(nearEnd, collection.loadingMore, collection.hasMore, collection.selectedTab) {
        if (collection.selectedTab == CollectionTab.VIDEOS && nearEnd && !collection.loadingMore && collection.hasMore) vm.loadMoreCollection()
    }
    BackHandler(onBack = onBack)

    Column(Modifier.fillMaxSize().background(HarukiBg).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(if (adaptive.largeTouchTargets) 66.dp else 52.dp).padding(horizontal = if (adaptive.isLarge) 12.dp else 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = HarukiText) }
            Text(
                if (collection.entity?.type == BrowseEntityType.PLAYLIST) "Playlist" else "Channel",
                color = HarukiText,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f)
            )
        }

        if (collection.loading && collection.videos.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = HarukiPrimary) }
            return@Column
        }

        LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 96.dp)) {
            item(key = "collection-header") {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(13.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (collection.thumbnailUrl.isNotBlank()) {
                            RemoteImage(
                                collection.thumbnailUrl,
                                Modifier.size(if (collection.entity?.type == BrowseEntityType.CHANNEL) 86.dp else 112.dp)
                                    .then(if (collection.entity?.type == BrowseEntityType.CHANNEL) Modifier.clip(CircleShape) else Modifier.clip(RoundedCornerShape(13.dp)))
                            )
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text(collection.title, color = HarukiText, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            if (collection.subtitle.isNotBlank()) Text(collection.subtitle, color = HarukiMuted, style = MaterialTheme.typography.bodySmall)
                            if (collection.entity?.verified == true) Text("Verified", color = HarukiPrimary, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                    if (collection.description.isNotBlank()) {
                        Text(collection.description, color = HarukiMuted, maxLines = 3, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                    }
                    if (collection.entity?.type == BrowseEntityType.CHANNEL) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = collection.selectedTab == CollectionTab.VIDEOS,
                                onClick = { vm.selectCollectionTab(CollectionTab.VIDEOS) },
                                label = { Text("Videos") },
                                leadingIcon = { Icon(Icons.Rounded.VideoLibrary, null, modifier = Modifier.size(17.dp)) },
                                modifier = Modifier.weight(1f),
                                colors = FilterChipDefaults.filterChipColors(
                                    containerColor = HarukiCardSoft, labelColor = HarukiText,
                                    selectedContainerColor = HarukiPrimary, selectedLabelColor = Color.White
                                )
                            )
                            FilterChip(
                                selected = collection.selectedTab == CollectionTab.PLAYLISTS,
                                onClick = { vm.selectCollectionTab(CollectionTab.PLAYLISTS) },
                                label = { Text("Playlists") },
                                leadingIcon = { Icon(Icons.AutoMirrored.Rounded.PlaylistPlay, null, modifier = Modifier.size(17.dp)) },
                                modifier = Modifier.weight(1f),
                                colors = FilterChipDefaults.filterChipColors(
                                    containerColor = HarukiCardSoft, labelColor = HarukiText,
                                    selectedContainerColor = HarukiPrimary, selectedLabelColor = Color.White
                                )
                            )
                        }
                    }
                    if (collection.selectedTab == CollectionTab.VIDEOS && collection.videos.isNotEmpty()) {
                        Button(
                            onClick = {
                                vm.prepareCollectionPlayback(0)
                                val first = collection.videos.first()
                                if (first.shortForm || first.url.contains("/shorts/", true)) onOpenShorts(first) else onOpenVideo(first)
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = HarukiPrimary),
                            shape = RoundedCornerShape(13.dp)
                        ) {
                            Icon(Icons.Rounded.PlayArrow, null)
                            Spacer(Modifier.width(7.dp))
                            Text(if (collection.entity?.type == BrowseEntityType.PLAYLIST) "Play all" else "Play latest")
                        }
                    }
                    HorizontalDivider(color = HarukiBorderSoft)
                }
            }

            if (collection.selectedTab == CollectionTab.PLAYLISTS) {
                if (collection.playlists.isEmpty()) {
                    item(key = "collection-playlists-empty") {
                        Text("No public playlists were returned for this channel.", color = HarukiMuted, modifier = Modifier.padding(18.dp))
                    }
                } else {
                    items(collection.playlists, key = { "channel-playlist-${it.url}" }) { playlist ->
                        PlaylistSearchCard(playlist) { vm.openCollection(playlist) }
                    }
                }
            } else {
                if (collection.error.isNotBlank() && collection.videos.isEmpty()) {
                    item(key = "collection-error") {
                        Text(collection.error, color = HarukiMuted, modifier = Modifier.padding(18.dp))
                    }
                }

                val collectionColumns = adaptive.feedColumns.coerceAtLeast(1)
                if (collectionColumns == 1) {
                    itemsIndexed(collection.videos, key = { _, video -> "collection-${video.url}" }) { index, video ->
                        YouTubeVideoCard(
                            item = video,
                            downloaded = SavedVideoStore.canonicalKey(video.url, video.id) in downloadedKeys,
                            onClick = {
                                vm.prepareCollectionPlayback(index)
                                if (video.shortForm || video.url.contains("/shorts/", true)) onOpenShorts(video) else onOpenVideo(video)
                            }
                        )
                    }
                } else {
                    itemsIndexed(collection.videos.chunked(collectionColumns), key = { rowIndex, row -> "collection-row-$rowIndex-${row.joinToString("|") { it.url }}" }) { rowIndex, row ->
                        Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            row.forEachIndexed { offset, video ->
                                val index = rowIndex * collectionColumns + offset
                                YouTubeVideoCard(
                                    item = video,
                                    modifier = Modifier.weight(1f),
                                    downloaded = SavedVideoStore.canonicalKey(video.url, video.id) in downloadedKeys,
                                    onClick = {
                                        vm.prepareCollectionPlayback(index)
                                        if (video.shortForm || video.url.contains("/shorts/", true)) onOpenShorts(video) else onOpenVideo(video)
                                    }
                                )
                            }
                            repeat(collectionColumns - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
                if (collection.loadingMore) {
                    item(key = "collection-more") {
                        Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = HarukiPrimary, modifier = Modifier.size(26.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchSectionTitle(title: String) {
    Text(title, color = HarukiText, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp))
}

@Composable
private fun ChannelSearchCard(entity: BrowseEntity, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(13.dp)
    ) {
        if (entity.thumbnailUrl.isNotBlank()) RemoteImage(entity.thumbnailUrl, Modifier.size(72.dp).clip(CircleShape))
        else Box(Modifier.size(72.dp).clip(CircleShape).background(HarukiCard2), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Person, null, tint = HarukiMuted) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(buildString { append(entity.name); if (entity.verified) append(" ✓") }, color = HarukiText, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (entity.subtitle.isNotBlank()) Text(entity.subtitle, color = HarukiMuted, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (entity.description.isNotBlank()) Text(entity.description, color = HarukiMuted2, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Icon(Icons.Rounded.ChevronRight, null, tint = HarukiMuted2)
    }
}

@Composable
private fun PlaylistSearchCard(entity: BrowseEntity, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.width(132.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(11.dp)).background(HarukiCard2)) {
            if (entity.thumbnailUrl.isNotBlank()) RemoteImage(entity.thumbnailUrl, Modifier.fillMaxSize())
            Box(Modifier.align(Alignment.BottomEnd).fillMaxHeight().width(42.dp).background(Color.Black.copy(alpha = .68f)), contentAlignment = Alignment.Center) {
                Icon(Icons.AutoMirrored.Rounded.PlaylistPlay, null, tint = Color.White)
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(entity.name, color = HarukiText, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (entity.subtitle.isNotBlank()) Text(entity.subtitle, color = HarukiMuted, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("View full playlist", color = HarukiPrimary, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun YouTubeVideoCard(
    item: BrowseVideo,
    modifier: Modifier = Modifier,
    downloaded: Boolean = false,
    onClick: () -> Unit,
    onNotInterested: () -> Unit = {},
    onDontRecommendChannel: () -> Unit = {}
) {
    var menuOpen by remember { mutableStateOf(false) }
    Column(modifier.fillMaxWidth().clickable(onClick = onClick).padding(bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Box(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
            RemoteImage(item.thumbnailUrl, Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(13.dp)))
            if (item.durationSeconds > 0) DurationBadge(formatDuration(item.durationSeconds), Modifier.align(Alignment.BottomEnd).padding(6.dp))
            if (downloaded) {
                Surface(Modifier.align(Alignment.TopStart).padding(6.dp), color = HarukiSuccess.copy(alpha = .92f), shape = RoundedCornerShape(7.dp)) {
                    Row(Modifier.padding(horizontal = 7.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.DownloadDone, null, tint = Color.White, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp)); Text("Downloaded", color = Color.White, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
            if (item.uploaderAvatarUrl.isNotBlank()) {
                RemoteImage(item.uploaderAvatarUrl, Modifier.size(38.dp).clip(CircleShape))
            } else {
                Box(Modifier.size(38.dp).clip(CircleShape).background(HarukiCard2), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Person, null, tint = HarukiMuted, modifier = Modifier.size(21.dp))
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(item.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium, color = HarukiText)
                val channel = buildString {
                    append(item.uploader)
                    if (item.uploaderVerified) append(" ✓")
                }
                val meta = buildList {
                    if (channel.isNotBlank()) add(channel)
                    if (item.viewCount >= 0) add(compactViews(item.viewCount))
                    if (item.uploadText.isNotBlank()) add(item.uploadText)
                }.joinToString(" • ")
                if (meta.isNotBlank()) Text(meta, color = HarukiMuted, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
            }
            Box {
                IconButton(
                    onClick = { menuOpen = true },
                    modifier = Modifier.size(36.dp)
                ) { Icon(Icons.Rounded.MoreVert, "Recommendation options", tint = HarukiMuted2, modifier = Modifier.size(20.dp)) }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }, containerColor = HarukiCard2) {
                    DropdownMenuItem(
                        text = { Text("Not interested", color = HarukiText) },
                        leadingIcon = { Icon(Icons.Rounded.VisibilityOff, null, tint = HarukiMuted) },
                        onClick = { menuOpen = false; onNotInterested() }
                    )
                    DropdownMenuItem(
                        text = { Text("Don't recommend this channel", color = HarukiText) },
                        leadingIcon = { Icon(Icons.Rounded.PersonOff, null, tint = HarukiMuted) },
                        onClick = { menuOpen = false; onDontRecommendChannel() }
                    )
                }
            }
        }
    }
}

@Composable
private fun DurationBadge(text: String, modifier: Modifier = Modifier) {
    Box(modifier.clip(RoundedCornerShape(5.dp)).background(Color.Black.copy(alpha = .84f)).padding(horizontal = 5.dp, vertical = 2.dp)) {
        Text(text, color = Color.White, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun ErrorPane(message: String, retry: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(message, color = HarukiMuted, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(12.dp))
        Button(onClick = retry, colors = ButtonDefaults.buttonColors(containerColor = HarukiPrimary)) {
            Icon(Icons.Rounded.Refresh, null)
            Spacer(Modifier.width(8.dp))
            Text("Retry")
        }
    }
}

private fun compactViews(value: Long): String = when {
    value >= 1_000_000_000 -> "%.1fB views".format(value / 1_000_000_000.0)
    value >= 1_000_000 -> "%.1fM views".format(value / 1_000_000.0)
    value >= 1_000 -> "%.1fK views".format(value / 1_000.0)
    value >= 0 -> "$value views"
    else -> ""
}
