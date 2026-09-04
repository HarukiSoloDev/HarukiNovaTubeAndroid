package com.harukisolodev.harukistream.extractor

import com.harukisolodev.harukistream.data.*
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.comments.CommentsInfo
import org.schabi.newpipe.extractor.comments.CommentsInfoItem
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.channel.ChannelInfo
import org.schabi.newpipe.extractor.channel.ChannelInfoItem
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabInfo
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabs
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** YouTube-only browsing/data layer for Haruki NovaTube. */
class BrowseRepository(
    private val mediaExtractor: HarukiExtractor = HarukiExtractor()
) {
    data class FeedPage(
        val items: List<BrowseVideo>,
        val hasMore: Boolean,
        val channels: List<BrowseEntity> = emptyList(),
        val playlists: List<BrowseEntity> = emptyList(),
        val searchSuggestion: String = ""
    )

    data class CollectionPage(
        val entity: BrowseEntity,
        val title: String,
        val subtitle: String,
        val description: String,
        val thumbnailUrl: String,
        val items: List<BrowseVideo>,
        val playlists: List<BrowseEntity> = emptyList(),
        val hasMore: Boolean
    )
    data class CommentPage(
        val items: List<VideoComment>,
        val totalCount: Int,
        val hasMore: Boolean,
        val disabled: Boolean = false
    )
    data class WatchPayload(
        val media: AnalyzedMedia,
        val related: List<BrowseVideo>,
        val details: WatchDetails
    )

    private data class WatchCacheEntry(
        val payload: WatchPayload,
        val streamInfo: StreamInfo,
        val createdAt: Long = System.currentTimeMillis()
    )

    private data class SearchSession(
        val query: String,
        var nextPage: Page? = null,
        var exhausted: Boolean = false
    )

    private data class CommentSession(
        val info: CommentsInfo,
        var nextPage: Page?
    )

    private data class CollectionSession(
        val entity: BrowseEntity,
        val title: String,
        val subtitle: String,
        val description: String,
        val thumbnailUrl: String,
        val kind: BrowseEntityType,
        val nextPage: Page?,
        val channelTab: ListLinkHandler? = null
    )

    private val watchCache = ConcurrentHashMap<String, WatchCacheEntry>()
    private val searchSessions = ConcurrentHashMap<String, SearchSession>()
    private val commentSessions = ConcurrentHashMap<String, CommentSession>()
    private val collectionSessions = ConcurrentHashMap<String, CollectionSession>()
    private data class VideoSuggestionCacheEntry(val items: List<BrowseVideo>, val createdAt: Long = System.currentTimeMillis())
    private val videoSuggestionCache = ConcurrentHashMap<String, VideoSuggestionCacheEntry>()
    private val cacheLifetimeMs = 7 * 60 * 1000L
    private val suggestionCacheLifetimeMs = 2 * 60 * 1000L

    private val homeQueries = listOf(
        "popular videos",
        "trending videos",
        "technology videos",
        "gaming videos",
        "cars automotive videos",
        "music videos",
        "science documentary videos",
        "movies entertainment videos"
    )
    private var homeQueryIndex = 0
    private var homeGeneration = 0

    private val shortsQueries = listOf(
        "technology shorts",
        "gaming shorts",
        "cars shorts",
        "music shorts",
        "science shorts",
        "engineering shorts",
        "film shorts",
        "travel shorts"
    )
    private var shortsQueryIndex = 0
    private var shortsGeneration = 0
    private val categoryGeneration = ConcurrentHashMap<String, Int>()
    private val customGeneration = ConcurrentHashMap<String, Int>()

    fun youtubeHome(category: String = "For You", reset: Boolean = true): FeedPage {
        if (!category.equals("For You", true) && !category.equals("All", true)) {
            return categoryPage(category, reset)
        }
        if (reset) {
            searchSessions.keys.filter { it.startsWith("home:") }.forEach(searchSessions::remove)
            homeQueryIndex = 0
            homeGeneration = 0
        }
        return nextRotatingPage("home", homeQueries, isShorts = false, reset = reset)
    }

    private fun categoryPage(category: String, reset: Boolean): FeedPage {
        val slug = category.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
        if (reset) {
            searchSessions.keys.filter { it.startsWith("category:$slug:") }.forEach(searchSessions::remove)
            categoryGeneration[slug] = 0
        }
        var generation = categoryGeneration[slug] ?: 0
        var attempts = 0
        val output = mutableListOf<BrowseVideo>()
        while (attempts < 3 && output.size < 16) {
            val base = categoryQuery(category)
            val query = if (generation == 0) base else "$base ${discoverySuffix(generation)}"
            val key = "category:$slug:$generation"
            val page = searchPage(key, query, reset && attempts == 0)
            val accepted = page.items.usableHomeVideos()
            output += accepted.filter { candidate -> output.none { it.url == candidate.url } }
            if (!page.hasMore || accepted.isEmpty()) {
                generation++
                categoryGeneration[slug] = generation
            }
            attempts++
            if (output.size >= 10) break
        }
        // Category feeds also stay open-ended by rotating discovery queries.
        return FeedPage(output.distinctBy { it.url }, true)
    }

    fun youtubeHomeMore(category: String = "For You"): FeedPage = youtubeHome(category, reset = false)

    /** Personalized discovery queries supplied by NovaTube's local recommendation profile. */
    fun youtubePersonalized(queries: List<String>, reset: Boolean = true): FeedPage =
        customDiscoveryPage("personal-home", queries.ifEmpty { homeQueries }, isShorts = false, reset = reset)

    fun youtubePersonalizedMore(queries: List<String>): FeedPage = youtubePersonalized(queries, reset = false)

    fun youtubeSearch(query: String, reset: Boolean = true): FeedPage {
        val clean = query.trim()
        if (clean.isBlank()) return FeedPage(emptyList(), false)
        return searchPage("search:${clean.lowercase()}", clean, reset)
    }

    fun youtubeSearchMore(query: String): FeedPage = youtubeSearch(query, reset = false)

    fun youtubeRelatedDiscovery(item: BrowseVideo, queries: List<String>, reset: Boolean = false): FeedPage {
        val prefix = "watch-related-${item.url.hashCode()}"
        val page = customDiscoveryPage(prefix, queries, isShorts = false, reset = reset)
        return page.copy(items = page.items.filter { it.url != item.url })
    }

    fun youtubeShorts(reset: Boolean = true): FeedPage {
        if (reset) {
            searchSessions.keys.filter { it.startsWith("shorts:") }.forEach(searchSessions::remove)
            shortsQueryIndex = 0
            shortsGeneration = 0
        }
        return nextRotatingPage("shorts", shortsQueries, isShorts = true, reset = reset)
    }

    fun youtubeShortsMore(): FeedPage = youtubeShorts(reset = false)

    fun youtubeShortsPersonalized(queries: List<String>, reset: Boolean = true): FeedPage =
        customDiscoveryPage("personal-shorts", queries.ifEmpty { shortsQueries }, isShorts = true, reset = reset)

    fun youtubeShortsPersonalizedMore(queries: List<String>): FeedPage = youtubeShortsPersonalized(queries, reset = false)

    private fun customDiscoveryPage(
        prefix: String,
        queries: List<String>,
        isShorts: Boolean,
        reset: Boolean
    ): FeedPage {
        val cleanQueries = queries.map(String::trim).filter(String::isNotBlank).distinct().take(10)
        if (cleanQueries.isEmpty()) return FeedPage(emptyList(), false)
        if (reset) {
            searchSessions.keys.filter { it.startsWith("$prefix:") }.forEach(searchSessions::remove)
            customGeneration[prefix] = 0
        }
        var generation = customGeneration[prefix] ?: 0
        var attempts = 0
        while (attempts < 3) {
            val output = mutableListOf<BrowseVideo>()
            var anyMore = false
            cleanQueries.take(if (isShorts) 8 else 6).forEachIndexed { index, baseQuery ->
                val query = if (generation == 0) baseQuery else "$baseQuery ${discoverySuffix(generation)}"
                val key = "$prefix:$generation:$index:${baseQuery.hashCode()}"
                val page = searchPage(key, query, reset && attempts == 0)
                val accepted = if (isShorts) {
                    page.items.mapNotNull { it.asShortCandidate(forceShortContext = true) }
                } else {
                    page.items.usableHomeVideos()
                }
                output += accepted.filter { candidate -> output.none { it.url == candidate.url } }
                anyMore = anyMore || page.hasMore
            }
            if (!anyMore) {
                generation++
                customGeneration[prefix] = generation
            }
            if (output.isNotEmpty()) return FeedPage(output.distinctBy { it.url }.take(30), true)
            generation++
            customGeneration[prefix] = generation
            attempts++
        }
        return FeedPage(emptyList(), true)
    }

    private fun nextRotatingPage(
        prefix: String,
        queries: List<String>,
        isShorts: Boolean,
        reset: Boolean
    ): FeedPage {
        var attempts = 0
        val output = mutableListOf<BrowseVideo>()
        val maxAttempts = queries.size * 2
        while (attempts < maxAttempts && output.size < 20) {
            val index = if (isShorts) shortsQueryIndex else homeQueryIndex
            val generation = if (isShorts) shortsGeneration else homeGeneration
            val baseQuery = queries[index % queries.size]
            val query = if (generation == 0) baseQuery else "$baseQuery ${discoverySuffix(generation)}"
            val key = "$prefix:$generation:${index % queries.size}"
            val page = searchPage(key, query, reset && attempts == 0)
            val accepted = if (isShorts) {
                page.items.mapNotNull { it.asShortCandidate(forceShortContext = true) }
            } else {
                page.items.usableHomeVideos()
            }
            output += accepted.filter { candidate -> output.none { it.url == candidate.url } }

            val session = searchSessions[key]
            if (session?.exhausted == true || accepted.isEmpty()) {
                if (isShorts) {
                    shortsQueryIndex++
                    if (shortsQueryIndex >= queries.size) {
                        shortsQueryIndex = 0
                        shortsGeneration++
                    }
                } else {
                    homeQueryIndex++
                    if (homeQueryIndex >= queries.size) {
                        homeQueryIndex = 0
                        homeGeneration++
                    }
                }
            }
            attempts++
            if (output.size >= 12) break
        }
        // Discovery feeds deliberately stay open-ended. When one search cursor ends,
        // Haruki rotates into another query/generation instead of showing an artificial end.
        return FeedPage(output.distinctBy { it.url }, true)
    }

    private fun searchPage(key: String, query: String, reset: Boolean): FeedPage {
        if (reset) searchSessions.remove(key)
        val existing = searchSessions[key]
        if (existing == null) {
            val extractor = ServiceList.YouTube.getSearchExtractor(query)
            extractor.fetchPage()
            val info = SearchInfo.getInfo(extractor)
            val next = info.nextPage
            searchSessions[key] = SearchSession(query, next, next == null)
            return FeedPage(
                items = info.relatedItems.mapNotNull { it.toBrowseVideo() },
                hasMore = next != null,
                channels = info.relatedItems.mapNotNull { it.toBrowseChannel() },
                playlists = info.relatedItems.mapNotNull { it.toBrowsePlaylist() },
                searchSuggestion = info.searchSuggestion.orEmpty()
            )
        }
        if (existing.exhausted) return FeedPage(emptyList(), false)
        val next = existing.nextPage ?: run {
            existing.exhausted = true
            return FeedPage(emptyList(), false)
        }
        val extractor = ServiceList.YouTube.getSearchExtractor(existing.query)
        val page = extractor.getPage(next)
        existing.nextPage = page.nextPage
        existing.exhausted = page.nextPage == null
        return FeedPage(
            items = page.items.mapNotNull { it.toBrowseVideo() },
            hasMore = !existing.exhausted,
            channels = page.items.mapNotNull { it.toBrowseChannel() },
            playlists = page.items.mapNotNull { it.toBrowsePlaylist() }
        )
    }

    fun openYouTube(url: String): WatchPayload {
        val now = System.currentTimeMillis()
        watchCache[url]?.takeIf { now - it.createdAt < cacheLifetimeMs }?.let { return it.payload }

        val info = StreamInfo.getInfo(url)
        val media = mediaExtractor.fromStreamInfo(info, url)
        val related = info.relatedItems.mapNotNull { it.toBrowseVideo() }
            .filterNot { it.looksLive() }
            .distinctBy { it.url }
        val avatar = info.uploaderAvatars.maxByOrNull { maxOf(it.width, 0) * maxOf(it.height, 0) }?.url.orEmpty()
        val subtitles = info.subtitles
            .filter { it.isUrl && it.content.startsWith("http") }
            .map { track ->
                val playbackUrl = normalizeYoutubeSubtitleUrl(track.content)
                val mime = if (playbackUrl != track.content || track.format?.suffix?.equals("vtt", true) == true) {
                    "text/vtt"
                } else when (track.format?.suffix?.lowercase(Locale.ROOT)) {
                    "srt" -> "application/x-subrip"
                    "ttml" -> "application/ttml+xml"
                    else -> "text/vtt"
                }
                val label = buildString {
                    append(track.displayLanguageName.ifBlank { track.languageTag }.ifBlank { "Captions" })
                    if (track.isAutoGenerated) append(" (auto)")
                }
                SubtitleTrack(
                    id = "${track.languageTag}-${track.isAutoGenerated}-${playbackUrl.hashCode()}",
                    label = label,
                    languageCode = track.languageTag,
                    url = playbackUrl,
                    mimeType = mime,
                    autoGenerated = track.isAutoGenerated
                )
            }
            .distinctBy { it.id }
            .sortedWith(compareBy<SubtitleTrack> { it.languageCode }.thenBy { it.autoGenerated })

        val details = WatchDetails(
            description = info.description?.content.orEmpty().cleanDescription(),
            uploaderAvatarUrl = avatar,
            uploaderUrl = info.uploaderUrl.orEmpty(),
            uploaderVerified = info.isUploaderVerified,
            subscriberCount = info.uploaderSubscriberCount,
            viewCount = info.viewCount,
            likeCount = info.likeCount,
            uploadText = formatUploadText(info.textualUploadDate.orEmpty()),
            subtitles = subtitles
        )
        val payload = WatchPayload(media, related, details)
        watchCache[url] = WatchCacheEntry(payload, info)
        return payload
    }

    /** Lightweight path for Shorts. No related/comments work is performed. */
    fun youtubeMedia(url: String): AnalyzedMedia {
        val now = System.currentTimeMillis()
        watchCache[url]?.takeIf { now - it.createdAt < cacheLifetimeMs }?.payload?.media?.let { return it }
        val info = StreamInfo.getInfo(url)
        val media = mediaExtractor.fromStreamInfo(info, url)
        val details = WatchDetails(
            uploaderAvatarUrl = info.uploaderAvatars.maxByOrNull { maxOf(it.width, 0) * maxOf(it.height, 0) }?.url.orEmpty(),
            uploaderUrl = info.uploaderUrl.orEmpty(),
            uploaderVerified = info.isUploaderVerified,
            viewCount = info.viewCount,
            likeCount = info.likeCount
        )
        watchCache[url] = WatchCacheEntry(WatchPayload(media, emptyList(), details), info)
        return media
    }

    /** Lightweight extra metadata used only by Nova AI reranking. */
    fun aiSearchMetadata(url: String): String {
        val cached = watchCache[url]?.streamInfo
        val info = cached ?: StreamInfo.getInfo(url)
        return listOf(
            info.name,
            info.uploaderName.orEmpty(),
            info.description?.content.orEmpty(),
            info.textualUploadDate.orEmpty()
        ).filter { it.isNotBlank() }.joinToString(" ")
    }

    fun comments(url: String, reset: Boolean = true): CommentPage {
        if (reset) commentSessions.remove(url)
        val existing = commentSessions[url]
        if (existing == null) {
            val info = CommentsInfo.getInfo(url) ?: return CommentPage(emptyList(), 0, false, disabled = true)
            val items = info.relatedItems.map { it.toVideoComment() }
            commentSessions[url] = CommentSession(info, info.nextPage)
            return CommentPage(items, info.commentsCount, info.nextPage != null, info.isCommentsDisabled)
        }
        val next = existing.nextPage ?: return CommentPage(emptyList(), existing.info.commentsCount, false, existing.info.isCommentsDisabled)
        val page = CommentsInfo.getMoreItems(existing.info, next)
        existing.nextPage = page.nextPage
        return CommentPage(page.items.map { it.toVideoComment() }, existing.info.commentsCount, page.nextPage != null, existing.info.isCommentsDisabled)
    }

    fun suggestions(query: String): List<String> {
        val clean = query.trim()
        if (clean.length < 2) return emptyList()
        return ServiceList.YouTube.suggestionExtractor.suggestionList(clean)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .take(10)
    }

    /** Small direct-video preview used under search predictions. Kept separate from the
     * full search session so typing never mutates/paginates the user's real results. */
    fun videoSuggestions(query: String, limit: Int = 3): List<BrowseVideo> {
        val clean = query.trim()
        if (clean.length < 3) return emptyList()
        val key = clean.lowercase(Locale.ROOT)
        val now = System.currentTimeMillis()
        videoSuggestionCache[key]?.takeIf { now - it.createdAt < suggestionCacheLifetimeMs }?.let {
            return it.items.take(limit.coerceIn(1, 5))
        }
        val extractor = ServiceList.YouTube.getSearchExtractor(clean)
        extractor.fetchPage()
        val items = SearchInfo.getInfo(extractor).relatedItems
            .mapNotNull { it.toBrowseVideo() }
            .filterNot { it.looksLive() }
            .distinctBy { it.url }
            .take(5)
        if (videoSuggestionCache.size > 24) {
            videoSuggestionCache.entries.minByOrNull { it.value.createdAt }?.key?.let(videoSuggestionCache::remove)
        }
        videoSuggestionCache[key] = VideoSuggestionCacheEntry(items)
        return items.take(limit.coerceIn(1, 5))
    }

    fun openCollection(entity: BrowseEntity): CollectionPage {
        collectionSessions.remove(entity.url)
        return when (entity.type) {
            BrowseEntityType.PLAYLIST -> openPlaylist(entity)
            BrowseEntityType.CHANNEL -> openChannel(entity)
        }
    }

    fun collectionMore(entity: BrowseEntity): CollectionPage {
        val session = collectionSessions[entity.url]
            ?: return openCollection(entity)
        val next = session.nextPage ?: return CollectionPage(
            entity = session.entity, title = session.title, subtitle = session.subtitle,
            description = session.description, thumbnailUrl = session.thumbnailUrl,
            items = emptyList(), hasMore = false
        )
        return when (session.kind) {
            BrowseEntityType.PLAYLIST -> {
                val page = PlaylistInfo.getMoreItems(ServiceList.YouTube, entity.url, next)
                collectionSessions[entity.url] = session.copy(nextPage = page.nextPage)
                CollectionPage(
                    entity = session.entity, title = session.title, subtitle = session.subtitle,
                    description = session.description, thumbnailUrl = session.thumbnailUrl,
                    items = page.items.mapNotNull { it.toBrowseVideo() }.filterNot { it.looksLive() },
                    hasMore = page.nextPage != null
                )
            }
            BrowseEntityType.CHANNEL -> {
                val tab = session.channelTab ?: return CollectionPage(
                    entity = session.entity, title = session.title, subtitle = session.subtitle,
                    description = session.description, thumbnailUrl = session.thumbnailUrl,
                    items = emptyList(), hasMore = false
                )
                val page = ChannelTabInfo.getMoreItems(ServiceList.YouTube, tab, next)
                collectionSessions[entity.url] = session.copy(nextPage = page.nextPage)
                CollectionPage(
                    entity = session.entity, title = session.title, subtitle = session.subtitle,
                    description = session.description, thumbnailUrl = session.thumbnailUrl,
                    items = page.items.mapNotNull { it.toBrowseVideo() }.filterNot { it.looksLive() },
                    hasMore = page.nextPage != null
                )
            }
        }
    }

    private fun openPlaylist(entity: BrowseEntity): CollectionPage {
        val info = PlaylistInfo.getInfo(ServiceList.YouTube, entity.url)
        val thumbnail = info.thumbnails.maxByOrNull { maxOf(it.width, 0) * maxOf(it.height, 0) }?.url
            .orEmpty().ifBlank { entity.thumbnailUrl }
        val uploader = info.uploaderName.orEmpty()
        val countText = info.streamCount.takeIf { it >= 0 }?.let { "$it videos" }.orEmpty()
        val subtitle = listOf(uploader, countText).filter { it.isNotBlank() }.joinToString(" • ")
        val resolved = entity.copy(
            name = info.name.ifBlank { entity.name },
            thumbnailUrl = thumbnail,
            subtitle = subtitle,
            itemCount = info.streamCount
        )
        collectionSessions[entity.url] = CollectionSession(
            entity = resolved,
            title = resolved.name,
            subtitle = subtitle,
            description = info.description?.content.orEmpty().cleanDescription(),
            thumbnailUrl = thumbnail,
            kind = BrowseEntityType.PLAYLIST,
            nextPage = info.nextPage
        )
        return CollectionPage(
            entity = resolved, title = resolved.name, subtitle = subtitle,
            description = info.description?.content.orEmpty().cleanDescription(),
            thumbnailUrl = thumbnail,
            items = info.relatedItems.mapNotNull { it.toBrowseVideo() }.filterNot { it.looksLive() },
            hasMore = info.nextPage != null
        )
    }

    private fun openChannel(entity: BrowseEntity): CollectionPage {
        val info = ChannelInfo.getInfo(ServiceList.YouTube, entity.url)
        val avatar = info.avatars.maxByOrNull { maxOf(it.width, 0) * maxOf(it.height, 0) }?.url
            .orEmpty().ifBlank { entity.thumbnailUrl }
        val countText = info.subscriberCount.takeIf { it >= 0 }?.let { "$it subscribers" }.orEmpty()
        val videoTab = info.tabs.firstOrNull { handler ->
            handler.contentFilters.any { it.equals(ChannelTabs.VIDEOS, true) }
        } ?: info.tabs.firstOrNull { handler ->
            handler.contentFilters.none { it.equals(ChannelTabs.SHORTS, true) || it.equals(ChannelTabs.PLAYLISTS, true) }
        }
        val playlistTab = info.tabs.firstOrNull { handler ->
            handler.contentFilters.any { it.equals(ChannelTabs.PLAYLISTS, true) }
        }
        val tabInfo = videoTab?.let { ChannelTabInfo.getInfo(ServiceList.YouTube, it) }
        val playlistTabInfo = playlistTab?.let { runCatching { ChannelTabInfo.getInfo(ServiceList.YouTube, it) }.getOrNull() }
        val channelPlaylists = playlistTabInfo?.relatedItems.orEmpty().mapNotNull { it.toBrowsePlaylist() }.distinctBy { it.url }
        val resolved = entity.copy(
            name = info.name.ifBlank { entity.name },
            thumbnailUrl = avatar,
            subtitle = countText,
            description = info.description.orEmpty(),
            verified = info.isVerified,
            itemCount = info.subscriberCount
        )
        collectionSessions[entity.url] = CollectionSession(
            entity = resolved,
            title = resolved.name,
            subtitle = countText,
            description = info.description.orEmpty().cleanDescription(),
            thumbnailUrl = avatar,
            kind = BrowseEntityType.CHANNEL,
            nextPage = tabInfo?.nextPage,
            channelTab = videoTab
        )
        return CollectionPage(
            entity = resolved, title = resolved.name, subtitle = countText,
            description = info.description.orEmpty().cleanDescription(),
            thumbnailUrl = avatar,
            items = tabInfo?.relatedItems.orEmpty().mapNotNull { it.toBrowseVideo() }.filterNot { it.looksLive() },
            playlists = channelPlaylists,
            hasMore = tabInfo?.nextPage != null
        )
    }

    fun prefetchYouTube(url: String) {
        runCatching { openYouTube(url) }
    }

    /**
     * Enrich a feed card with the channel avatar/verification returned by the
     * full stream extractor. The same extraction is cached for Watch, so this
     * doubles as playback prefetch rather than adding duplicate work.
     */
    fun enrichYouTubeCard(item: BrowseVideo): BrowseVideo {
        val payload = openYouTube(item.url)
        return item.copy(
            uploader = payload.media.uploader.ifBlank { item.uploader },
            uploaderAvatarUrl = payload.details.uploaderAvatarUrl.ifBlank { item.uploaderAvatarUrl },
            uploaderVerified = payload.details.uploaderVerified || item.uploaderVerified,
            viewCount = payload.details.viewCount.takeIf { it >= 0 } ?: item.viewCount,
            uploadText = payload.details.uploadText.ifBlank { item.uploadText }
        )
    }

    /** Lightweight enrichment for Shorts after youtubeMedia() populated cache. */
    fun enrichCachedCard(item: BrowseVideo): BrowseVideo {
        val payload = watchCache[item.url]?.payload ?: return item
        return item.copy(
            uploader = payload.media.uploader.ifBlank { item.uploader },
            uploaderAvatarUrl = payload.details.uploaderAvatarUrl.ifBlank { item.uploaderAvatarUrl },
            uploaderVerified = payload.details.uploaderVerified || item.uploaderVerified,
            viewCount = payload.details.viewCount.takeIf { it >= 0 } ?: item.viewCount
        )
    }

    fun analyze(url: String, sessionCookie: String = "") = mediaExtractor.analyze(url, sessionCookie)

    private fun InfoItem.toBrowseVideo(): BrowseVideo? {
        val item = this as? StreamInfoItem ?: return null
        val thumbnail = item.thumbnails.maxByOrNull { maxOf(it.width, 0) * maxOf(it.height, 0) }?.url.orEmpty()
        val avatar = item.uploaderAvatars.maxByOrNull { maxOf(it.width, 0) * maxOf(it.height, 0) }?.url.orEmpty()
        val isShort = item.isShortFormContent || item.url.contains("/shorts/", true)
        val canonicalUrl = if (isShort && item.url.contains("watch?v=", true)) {
            val videoId = item.url.substringAfter("watch?v=").substringBefore('&')
            if (videoId.isNotBlank()) "https://www.youtube.com/shorts/$videoId" else item.url
        } else item.url
        return BrowseVideo(
            id = canonicalUrl.hashCode().toString(),
            url = canonicalUrl,
            title = item.name,
            uploader = item.uploaderName.orEmpty(),
            thumbnailUrl = thumbnail,
            durationSeconds = item.duration.coerceAtLeast(0),
            viewCount = item.viewCount,
            uploadText = formatUploadText(item.textualUploadDate.orEmpty()),
            shortForm = isShort,
            service = "YouTube",
            uploaderAvatarUrl = avatar,
            uploaderVerified = item.isUploaderVerified
        )
    }

    private fun InfoItem.toBrowseChannel(): BrowseEntity? {
        val item = this as? ChannelInfoItem ?: return null
        val thumbnail = item.thumbnails.maxByOrNull { maxOf(it.width, 0) * maxOf(it.height, 0) }?.url.orEmpty()
        val subtitle = buildList {
            if (item.subscriberCount >= 0) add("${item.subscriberCount} subscribers")
            if (item.streamCount >= 0) add("${item.streamCount} videos")
        }.joinToString(" • ")
        return BrowseEntity(
            id = item.url.hashCode().toString(),
            url = item.url,
            name = item.name,
            thumbnailUrl = thumbnail,
            subtitle = subtitle,
            description = item.description.orEmpty(),
            verified = item.isVerified,
            itemCount = item.subscriberCount,
            type = BrowseEntityType.CHANNEL
        )
    }

    private fun InfoItem.toBrowsePlaylist(): BrowseEntity? {
        val item = this as? PlaylistInfoItem ?: return null
        val thumbnail = item.thumbnails.maxByOrNull { maxOf(it.width, 0) * maxOf(it.height, 0) }?.url.orEmpty()
        val subtitle = buildList {
            item.uploaderName.orEmpty().takeIf { it.isNotBlank() }?.let(::add)
            if (item.streamCount >= 0) add("${item.streamCount} videos")
        }.joinToString(" • ")
        return BrowseEntity(
            id = item.url.hashCode().toString(),
            url = item.url,
            name = item.name,
            thumbnailUrl = thumbnail,
            subtitle = subtitle,
            description = item.description?.content.orEmpty().cleanDescription(),
            verified = item.isUploaderVerified,
            itemCount = item.streamCount,
            type = BrowseEntityType.PLAYLIST
        )
    }

    private fun CommentsInfoItem.toVideoComment(): VideoComment {
        val avatar = uploaderAvatars.maxByOrNull { maxOf(it.width, 0) * maxOf(it.height, 0) }?.url.orEmpty()
        return VideoComment(
            id = commentId.orEmpty().ifBlank { url.hashCode().toString() },
            author = uploaderName.orEmpty(),
            authorAvatarUrl = avatar,
            text = commentText.content.cleanDescription(),
            likeCount = likeCount,
            uploadText = formatUploadText(textualUploadDate.orEmpty()),
            verified = isUploaderVerified,
            pinned = isPinned
        )
    }

    private fun List<BrowseVideo>.usableHomeVideos(): List<BrowseVideo> =
        filterNot { it.looksLive() }.distinctBy { it.url }

    private fun BrowseVideo.looksLive(): Boolean {
        val t = title.lowercase()
        val upload = uploadText.lowercase()
        return t.startsWith("live:") || t.contains(" live stream") || upload.contains("upcoming") ||
            (durationSeconds == 0L && !shortForm && !url.contains("/shorts/"))
    }

    private fun BrowseVideo.asShortCandidate(forceShortContext: Boolean): BrowseVideo? {
        if (looksLive()) return null
        val likely = shortForm || url.contains("/shorts/", true) || durationSeconds in 1L..180L ||
            title.contains("#shorts", true) || (forceShortContext && durationSeconds in 0L..180L)
        if (!likely) return null
        val id = when {
            url.contains("/shorts/", true) -> url.substringAfter("/shorts/").substringBefore('?').substringBefore('/')
            url.contains("watch?v=", true) -> url.substringAfter("watch?v=").substringBefore('&')
            else -> ""
        }
        return copy(
            url = if (id.isNotBlank()) "https://www.youtube.com/shorts/$id" else url,
            shortForm = true
        )
    }

    private fun discoverySuffix(generation: Int): String = when (generation % 8) {
        1 -> "new"
        2 -> "recommended"
        3 -> "popular"
        4 -> "recent"
        5 -> "best"
        6 -> "2026"
        7 -> "today"
        else -> "videos"
    }

    private fun categoryQuery(category: String): String = when (category.lowercase()) {
        "gaming" -> "gaming videos"
        "music" -> "music videos"
        "tech" -> "technology videos"
        "cars" -> "cars automotive videos"
        "mixes" -> "music mixes"
        else -> category
    }

    private fun normalizeYoutubeSubtitleUrl(url: String): String {
        if (!url.contains("timedtext", ignoreCase = true)) return url
        val fmt = Regex("([?&])fmt=[^&]*", RegexOption.IGNORE_CASE)
        return if (fmt.containsMatchIn(url)) {
            fmt.replace(url) { match -> "${match.groupValues[1]}fmt=vtt" }
        } else {
            url + if (url.contains('?')) "&fmt=vtt" else "?fmt=vtt"
        }
    }

    private fun formatUploadText(raw: String): String {
        val clean = raw.trim()
        if (clean.isBlank()) return clean
        val instant = runCatching { OffsetDateTime.parse(clean).toInstant() }.getOrNull()
            ?: runCatching { Instant.parse(clean) }.getOrNull()
            ?: runCatching { LocalDate.parse(clean).atStartOfDay(ZoneId.systemDefault()).toInstant() }.getOrNull()
            ?: return clean
        val seconds = Duration.between(instant, Instant.now()).seconds.coerceAtLeast(0L)
        return when {
            seconds < 60L -> "just now"
            seconds < 3_600L -> "${seconds / 60L} minute${if (seconds / 60L == 1L) "" else "s"} ago"
            seconds < 86_400L -> "${seconds / 3_600L} hour${if (seconds / 3_600L == 1L) "" else "s"} ago"
            seconds < 604_800L -> "${seconds / 86_400L} day${if (seconds / 86_400L == 1L) "" else "s"} ago"
            seconds < 2_629_800L -> "${seconds / 604_800L} week${if (seconds / 604_800L == 1L) "" else "s"} ago"
            seconds < 31_557_600L -> "${seconds / 2_629_800L} month${if (seconds / 2_629_800L == 1L) "" else "s"} ago"
            else -> "${seconds / 31_557_600L} year${if (seconds / 31_557_600L == 1L) "" else "s"} ago"
        }
    }

    private fun String.cleanDescription(): String {
        // Preserve the destination of HTML anchors before stripping markup so links
        // in YouTube descriptions/comments remain tappable after Linkify runs.
        val anchorsPreserved = replace(
            Regex("<a\\s+[^>]*href=[\"']([^\"']+)[\"'][^>]*>(.*?)</a>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        ) { match ->
            val href = match.groupValues[1]
                .replace("&amp;", "&")
                .replace("&quot;", "\"")
            val label = match.groupValues[2].replace(Regex("<[^>]+>"), "").trim()
            when {
                href.isBlank() -> label
                label.isBlank() || label.equals(href, true) -> href
                else -> "$label ($href)"
            }
        }
        return anchorsPreserved
            .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("</p\\s*>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("<[^>]+>"), "")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .trim()
    }
}
