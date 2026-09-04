package com.harukisolodev.harukistream.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.harukisolodev.harukistream.ui.NovaAdaptiveInfo
import com.harukisolodev.harukistream.data.*
import com.harukisolodev.harukistream.ui.BrowseViewModel
import com.harukisolodev.harukistream.ui.components.LinkifiedText
import com.harukisolodev.harukistream.ui.components.RemoteImage
import com.harukisolodev.harukistream.ui.theme.*

/** Shorts feed that keeps Android status/navigation bars visible, like the regular YouTube app. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShortsScreen(
    state: BrowseState,
    vm: BrowseViewModel,
    initialUrl: String = "",
    savedUrls: Set<String>,
    playbackQualityPreference: String,
    downloadByUrl: Map<String, DownloadQueueItem>,
    onBack: () -> Unit,
    onToggleSave: (BrowseVideo) -> Unit,
    onDownload: (BrowseVideo, AnalyzedMedia) -> Unit,
    bottomBarVisible: Boolean = false,
    adaptive: NovaAdaptiveInfo
) {
    var commentsUrl by rememberSaveable { mutableStateOf("") }

    BackHandler(enabled = commentsUrl.isBlank()) { onBack() }
    // Opening the Shorts section starts a fresh personalized batch so the same clips
    // do not keep appearing every time. A directly-opened Short stays seeded first.
    LaunchedEffect(initialUrl) { vm.loadShorts(force = initialUrl.isBlank()) }

    Box(
        Modifier.fillMaxSize()
            .background(Color.Black)
            .statusBarsPadding()
            .then(
                if (bottomBarVisible) Modifier.navigationBarsPadding().padding(bottom = 80.dp)
                else Modifier.navigationBarsPadding()
            )
    ) {
        when {
            state.youtubeShortsLoading && state.youtubeShorts.isEmpty() -> {
                CircularProgressIndicator(color = HarukiPrimary, modifier = Modifier.align(Alignment.Center))
            }
            state.youtubeShorts.isEmpty() -> {
                Column(
                    Modifier.align(Alignment.Center).padding(28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(state.youtubeShortsError.ifBlank { "No Shorts were returned yet." }, color = HarukiText)
                    Button(
                        onClick = { vm.loadShorts(force = true) },
                        colors = ButtonDefaults.buttonColors(containerColor = HarukiPrimary)
                    ) {
                        Icon(Icons.Rounded.Refresh, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Retry")
                    }
                }
            }
            else -> {
                val items = state.youtubeShorts
                val startIndex = remember(initialUrl, items) {
                    items.indexOfFirst { it.url == initialUrl }.takeIf { it >= 0 } ?: 0
                }
                val pagerState = rememberPagerState(initialPage = startIndex, pageCount = { items.size })

                LaunchedEffect(initialUrl, items.size) {
                    val index = items.indexOfFirst { it.url == initialUrl }
                    if (index >= 0 && index != pagerState.currentPage) pagerState.scrollToPage(index)
                }

                LaunchedEffect(pagerState.currentPage, items.size) {
                    val page = pagerState.currentPage
                    items.getOrNull(page)?.let { current ->
                        vm.ensureShortMedia(current)
                        vm.markShortSeen(current)
                    }
                    items.getOrNull(page + 1)?.let(vm::ensureShortMedia)
                    if (page >= items.lastIndex - 4 && !state.youtubeShortsLoadingMore) vm.loadMoreShorts()
                }

                VerticalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize(),
                    beyondViewportPageCount = 1,
                    key = { index -> items[index].url }
                ) { index ->
                    val item = items[index]
                    val shortComments = state.shortComments[item.url]
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        VerticalNativePlayer(
                            item = item,
                            media = state.verticalMedia[item.url],
                            loading = item.url in state.verticalLoading,
                            error = state.verticalErrors[item.url].orEmpty(),
                            active = index == pagerState.currentPage,
                            saved = SavedVideoStore.canonicalKey(item.url, item.id) in savedUrls,
                            playbackQualityPreference = playbackQualityPreference,
                            commentCount = shortComments?.totalCount ?: 0,
                            downloadState = downloadByUrl[SavedVideoStore.canonicalKey(item.url, item.id)],
                            onToggleSave = onToggleSave,
                            onComments = {
                                commentsUrl = item.url
                                if (shortComments == null || shortComments.items.isEmpty()) vm.loadShortComments(item.url, reset = true)
                            },
                            onDownload = { media -> onDownload(item, media) },
                            onNotInterested = { vm.notInterestedShort(item) },
                            onDontRecommendChannel = { vm.dontRecommendChannel(item) },
                            modifier = if (adaptive.useNavigationRail) {
                                Modifier.fillMaxHeight().widthIn(max = if (adaptive.largeTouchTargets) 430.dp else 540.dp)
                            } else Modifier.fillMaxSize()
                        )
                    }
                }

                if (state.youtubeShortsLoadingMore) {
                    CircularProgressIndicator(
                        color = Color.White,
                        strokeWidth = 2.dp,
                        modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 14.dp).size(22.dp)
                    )
                }
            }
        }

        IconButton(
            onClick = onBack,
            modifier = Modifier.align(Alignment.TopStart).padding(8.dp)
        ) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = Color.White)
        }
    }

    if (commentsUrl.isNotBlank()) {
        val comments = state.shortComments[commentsUrl] ?: ShortCommentsState(loading = true)
        ModalBottomSheet(
            onDismissRequest = { commentsUrl = "" },
            containerColor = HarukiCard,
            contentColor = HarukiText,
            dragHandle = { BottomSheetDefaults.DragHandle(color = HarukiMuted) }
        ) {
            ShortCommentsSheet(
                state = comments,
                onLoadMore = { vm.loadShortComments(commentsUrl, reset = false) }
            )
        }
    }
}

@Composable
private fun ShortCommentsSheet(state: ShortCommentsState, onLoadMore: () -> Unit) {
    Column(Modifier.fillMaxWidth().fillMaxHeight(.72f)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Comments", color = HarukiText, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            if (state.totalCount > 0) {
                Spacer(Modifier.width(8.dp))
                Text(compactShortCommentCount(state.totalCount.toLong()), color = HarukiMuted)
            }
        }
        HorizontalDivider(color = HarukiBorderSoft)

        when {
            state.items.isEmpty() && state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = HarukiPrimary)
            }
            state.items.isEmpty() && state.error.isNotBlank() -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text(state.error, color = HarukiMuted)
            }
            state.items.isEmpty() -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text("No comments were returned for this Short.", color = HarukiMuted)
            }
            else -> LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 28.dp)
            ) {
                items(state.items, key = { it.id }) { comment -> ShortCommentRow(comment) }
                if (state.hasMore) {
                    item(key = "more-comments") {
                        TextButton(
                            onClick = onLoadMore,
                            enabled = !state.loading,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                        ) {
                            if (state.loading) CircularProgressIndicator(color = HarukiPrimary, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                            else Text("Load more comments", color = HarukiPrimary)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ShortCommentRow(comment: VideoComment) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top
    ) {
        if (comment.authorAvatarUrl.isNotBlank()) {
            RemoteImage(comment.authorAvatarUrl, Modifier.size(36.dp).clip(CircleShape))
        } else {
            Box(Modifier.size(36.dp).clip(CircleShape).background(HarukiCard2), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Person, null, tint = HarukiMuted)
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                buildString {
                    append(comment.author.ifBlank { "YouTube user" })
                    if (comment.verified) append(" ✓")
                    if (comment.uploadText.isNotBlank()) append("  •  ${comment.uploadText}")
                },
                color = HarukiMuted,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (comment.pinned) Text("Pinned", color = HarukiPrimary, style = MaterialTheme.typography.labelSmall)
            LinkifiedText(text = comment.text, modifier = Modifier.fillMaxWidth(), color = HarukiText, textSizeSp = 14f)
            if (comment.likeCount >= 0) {
                Text("${compactShortCommentCount(comment.likeCount.toLong())} likes", color = HarukiMuted2, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

private fun compactShortCommentCount(value: Long): String = when {
    value >= 1_000_000_000L -> "%.1fB".format(value / 1_000_000_000.0)
    value >= 1_000_000L -> "%.1fM".format(value / 1_000_000.0)
    value >= 1_000L -> "%.1fK".format(value / 1_000.0)
    else -> value.toString()
}
