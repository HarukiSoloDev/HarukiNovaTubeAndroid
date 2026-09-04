package com.harukisolodev.harukistream.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.harukisolodev.harukistream.data.*
import com.harukisolodev.harukistream.extractor.BrowseRepository
import com.harukisolodev.harukistream.player.PlaybackAutoplayBridge
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap

class BrowseViewModel(app: Application) : AndroidViewModel(app) {
    private val repository = BrowseRepository()
    private val historyStore = WatchHistoryStore(app)
    private val savedStore = SavedVideoStore(app)
    private val recommendationStore = RecommendationStore(app)
    private val localPlaylistStore = LocalPlaylistStore(app)
    private val novaAiEngine = NovaAiSearchEngine(repository)

    private val _state = MutableStateFlow(BrowseState())
    val state: StateFlow<BrowseState> = _state.asStateFlow()

    private val _history = MutableStateFlow(historyStore.all())
    val history: StateFlow<List<BrowseVideo>> = _history.asStateFlow()

    private val _saved = MutableStateFlow(savedStore.all())
    val saved: StateFlow<List<BrowseVideo>> = _saved.asStateFlow()

    private val _playlists = MutableStateFlow(localPlaylistStore.all())
    val playlists: StateFlow<List<LocalPlaylist>> = _playlists.asStateFlow()

    private var youtubeJob: Job? = null
    private var suggestionJob: Job? = null
    private var shortsJob: Job? = null
    private var watchJob: Job? = null
    private var commentsJob: Job? = null
    private var relatedJob: Job? = null
    private var collectionJob: Job? = null
    private var novaAiJob: Job? = null
    private val verticalJobs = ConcurrentHashMap<String, Job>()
    private val shortCommentJobs = ConcurrentHashMap<String, Job>()
    private val cardPrefetchJobs = ConcurrentHashMap<String, Job>()
    private val cardPrefetchSemaphore = Semaphore(1)

    // Keeps playlist/channel Play all playback continuous across Watch transitions.
    private var playbackCollection: List<BrowseVideo> = emptyList()
    private var playbackCollectionName: String = ""
    private var playbackCollectionIndex: Int = -1
    private var preparedCollectionUrl: String = ""

    init {
        loadYouTube("For You")
        viewModelScope.launch {
            PlaybackAutoplayBridge.advances.collect { advance ->
                applyServiceAutoplayAdvance(advance)
            }
        }
    }

    fun loadYouTube(category: String = _state.value.youtubeCategory) {
        youtubeJob?.cancel()
        youtubeJob = viewModelScope.launch {
            _state.value = _state.value.copy(
                youtubeLoading = true,
                youtubeLoadingMore = false,
                youtubeHasMore = true,
                youtubeError = "",
                youtubeCategory = category,
                youtubeSearch = emptyList(),
                youtubeSearchChannels = emptyList(),
                youtubeSearchPlaylists = emptyList(),
                youtubeSearchSuggestion = "",
                youtubeSuggestions = emptyList(),
                youtubeQuery = "",
                collection = BrowseCollectionState()
            )
            runCatching { withContext(Dispatchers.IO) {
                if (category.equals("For You", true) || category.equals("All", true)) {
                    repository.youtubePersonalized(recommendationStore.homeQueries(), reset = true)
                } else repository.youtubeHome(category, reset = true)
            } }
                .onSuccess { page ->
                    val ranked = if (category.equals("For You", true) || category.equals("All", true))
                        recommendationStore.filterFeed(page.items, shorts = false) else page.items
                    _state.value = _state.value.copy(
                        youtubeLoading = false,
                        youtubeHome = ranked,
                        youtubeHasMore = page.hasMore,
                        youtubeError = if (ranked.isEmpty()) "No videos were returned. Pull to refresh or search." else ""
                    )
                    ranked.take(2).forEach(::prefetchYouTube)
                }
                .onFailure { e ->
                    if (e !is CancellationException) _state.value = _state.value.copy(
                        youtubeLoading = false,
                        youtubeError = e.message ?: "Could not load YouTube."
                    )
                }
        }
    }

    fun loadMoreYouTube() {
        val current = _state.value
        if (current.youtubeLoading || current.youtubeLoadingMore || !current.youtubeHasMore) return
        youtubeJob = viewModelScope.launch {
            _state.value = _state.value.copy(youtubeLoadingMore = true)
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    if (current.youtubeQuery.isNotBlank()) repository.youtubeSearchMore(current.youtubeQuery)
                    else if (current.youtubeCategory.equals("For You", true) || current.youtubeCategory.equals("All", true))
                        repository.youtubePersonalizedMore(recommendationStore.homeQueries())
                    else repository.youtubeHomeMore(current.youtubeCategory)
                }
            }
            result.onSuccess { page ->
                val existing = if (current.youtubeQuery.isNotBlank()) _state.value.youtubeSearch else _state.value.youtubeHome
                val incoming = if (current.youtubeQuery.isBlank() && (current.youtubeCategory.equals("For You", true) || current.youtubeCategory.equals("All", true)))
                    recommendationStore.filterFeed(page.items, shorts = false) else page.items
                val merged = (existing + incoming).distinctBy { it.url }
                _state.value = if (current.youtubeQuery.isNotBlank()) {
                    _state.value.copy(
                        youtubeSearch = merged,
                        youtubeSearchChannels = (_state.value.youtubeSearchChannels + page.channels).distinctBy { it.url },
                        youtubeSearchPlaylists = (_state.value.youtubeSearchPlaylists + page.playlists).distinctBy { it.url },
                        youtubeLoadingMore = false,
                        youtubeHasMore = page.hasMore
                    )
                } else {
                    _state.value.copy(youtubeHome = merged, youtubeLoadingMore = false, youtubeHasMore = page.hasMore)
                }
                page.items.take(2).forEach(::prefetchYouTube)
            }.onFailure { e ->
                if (e !is CancellationException) _state.value = _state.value.copy(
                    youtubeLoadingMore = false,
                    youtubeError = e.message ?: "Could not load more videos."
                )
            }
        }
    }

    fun searchYouTube(query: String) {
        val clean = query.trim()
        if (clean.isBlank()) return
        recommendationStore.recordSearch(clean)
        youtubeJob?.cancel()
        suggestionJob?.cancel()
        youtubeJob = viewModelScope.launch {
            _state.value = _state.value.copy(
                youtubeLoading = true,
                youtubeLoadingMore = false,
                youtubeHasMore = true,
                youtubeError = "",
                youtubeQuery = clean,
                youtubeSearchDraft = clean,
                youtubeSearch = emptyList(),
                youtubeSearchChannels = emptyList(),
                youtubeSearchPlaylists = emptyList(),
                youtubeSearchSuggestion = "",
                youtubeSuggestions = emptyList(),
                collection = BrowseCollectionState()
            )
            runCatching { withContext(Dispatchers.IO) { repository.youtubeSearch(clean, reset = true) } }
                .onSuccess { page ->
                    val items = page.items.filterNot { it.durationSeconds == 0L && !it.shortForm }
                    val hasAny = items.isNotEmpty() || page.channels.isNotEmpty() || page.playlists.isNotEmpty()
                    _state.value = _state.value.copy(
                        youtubeLoading = false,
                        youtubeSearch = items,
                        youtubeSearchChannels = page.channels.distinctBy { it.url },
                        youtubeSearchPlaylists = page.playlists.distinctBy { it.url },
                        youtubeSearchSuggestion = page.searchSuggestion,
                        youtubeHasMore = page.hasMore,
                        youtubeError = if (!hasAny) "No search results." else ""
                    )
                    items.take(2).forEach(::prefetchYouTube)
                }
                .onFailure { e -> if (e !is CancellationException) _state.value = _state.value.copy(
                    youtubeLoading = false,
                    youtubeError = e.message ?: "Search failed."
                ) }
        }
    }

    fun updateSearchDraft(query: String) {
        _state.value = _state.value.copy(youtubeSearchDraft = query)
    }

    /**
     * Return to the real Home feed without erasing what the user last typed/searched.
     * A second Home tap refreshes For You and always asks the UI to scroll to the top.
     */
    fun goHome(refresh: Boolean = false) {
        suggestionJob?.cancel()
        val current = _state.value
        val token = current.youtubeHomeNavigationToken + 1L
        val needsReload = refresh || current.youtubeHome.isEmpty() || !current.youtubeCategory.equals("For You", true)
        _state.value = current.copy(
            youtubeQuery = "",
            youtubeSearch = emptyList(),
            youtubeSearchChannels = emptyList(),
            youtubeSearchPlaylists = emptyList(),
            youtubeSearchSuggestion = "",
            youtubeSuggestions = emptyList(),
            youtubeError = "",
            youtubeCategory = "For You",
            youtubeHomeNavigationToken = token,
            collection = BrowseCollectionState()
        )
        if (needsReload) loadYouTube("For You")
    }

    fun requestSearchSuggestions(query: String) {
        suggestionJob?.cancel()
        val clean = query.trim()
        if (clean.length < 2) {
            _state.value = _state.value.copy(youtubeSuggestions = emptyList())
            return
        }
        suggestionJob = viewModelScope.launch {
            delay(180)
            val suggestions = runCatching { withContext(Dispatchers.IO) { repository.suggestions(clean) } }
                .getOrDefault(emptyList())
            if (_state.value.youtubeQuery != clean || _state.value.youtubeQuery.isBlank()) {
                _state.value = _state.value.copy(youtubeSuggestions = suggestions)
            }
        }
    }

    fun clearSearch() {
        suggestionJob?.cancel()
        _state.value = _state.value.copy(
            youtubeQuery = "",
            youtubeSearch = emptyList(),
            youtubeSearchChannels = emptyList(),
            youtubeSearchPlaylists = emptyList(),
            youtubeSearchSuggestion = "",
            youtubeSuggestions = emptyList(),
            youtubeError = ""
        )
    }


    fun searchWithNovaAi(
        prompt: String,
        downloads: List<LibraryItem> = emptyList(),
        mode: NovaAiSearchMode = NovaAiSearchMode.SMART
    ) {
        val clean = prompt.trim()
        if (clean.length < 3) {
            _state.value = _state.value.copy(novaAi = NovaAiState(prompt = clean, mode = mode, error = "Tell Nova AI a little more about what you remember."))
            return
        }
        novaAiJob?.cancel()
        novaAiJob = viewModelScope.launch {
            _state.value = _state.value.copy(novaAi = NovaAiState(prompt = clean, mode = mode, loading = true, phase = "Understanding the clues in your memory…"))
            val localCandidates = buildList {
                historyStore.all().forEach { add(NovaAiSearchEngine.LocalCandidate(it, NovaAiMatchSource.HISTORY)) }
                savedStore.all().forEach { add(NovaAiSearchEngine.LocalCandidate(it, NovaAiMatchSource.SAVED)) }
                downloads.filter { it.sourceUrl.isNotBlank() }.forEach { item ->
                    add(NovaAiSearchEngine.LocalCandidate(
                        BrowseVideo(
                            id = item.mediaId.ifBlank { item.sourceUrl.hashCode().toString() },
                            url = item.sourceUrl,
                            title = item.title,
                            uploader = "",
                            thumbnailUrl = item.thumbnailUrl,
                            shortForm = item.sourceUrl.contains("/shorts/", true)
                        ),
                        NovaAiMatchSource.DOWNLOAD
                    ))
                }
                localPlaylistStore.all().forEach { playlist ->
                    playlist.videos.forEach { video ->
                        add(NovaAiSearchEngine.LocalCandidate(video, NovaAiMatchSource.LOCAL_PLAYLIST))
                    }
                }
            }
            runCatching {
                withContext(Dispatchers.IO) {
                    novaAiEngine.find(clean, localCandidates, mode) { phase ->
                        _state.value = _state.value.copy(novaAi = _state.value.novaAi.copy(phase = phase))
                    }
                }
            }.onSuccess { result ->
                result.queries.firstOrNull()?.let(recommendationStore::recordSearch)
                _state.value = _state.value.copy(novaAi = NovaAiState(
                    prompt = clean,
                    mode = mode,
                    loading = false,
                    phase = if (result.matches.isEmpty()) "No confident match yet" else "Best matches ready",
                    matches = result.matches,
                    queriesTried = result.queries,
                    error = if (result.matches.isEmpty()) "I searched broadly across YouTube but could not find a strong match yet. Add another clue like the creator, approximate year, language, duration, lyric, game, or what happened in the video." else ""
                ))
            }.onFailure { e ->
                if (e !is CancellationException) {
                    _state.value = _state.value.copy(novaAi = _state.value.novaAi.copy(
                        loading = false,
                        phase = "",
                        error = e.message ?: "Nova AI search failed. Try again with one more clue."
                    ))
                }
            }
        }
    }

    fun clearNovaAi() {
        novaAiJob?.cancel()
        _state.value = _state.value.copy(novaAi = NovaAiState())
    }

    fun openCollection(entity: BrowseEntity) {
        collectionJob?.cancel()
        collectionJob = viewModelScope.launch {
            _state.value = _state.value.copy(collection = BrowseCollectionState(entity = entity, title = entity.name, thumbnailUrl = entity.thumbnailUrl, loading = true))
            runCatching { withContext(Dispatchers.IO) { repository.openCollection(entity) } }
                .onSuccess { page ->
                    _state.value = _state.value.copy(collection = BrowseCollectionState(
                        entity = page.entity,
                        title = page.title,
                        subtitle = page.subtitle,
                        description = page.description,
                        thumbnailUrl = page.thumbnailUrl,
                        videos = page.items.distinctBy { it.url },
                        playlists = page.playlists.distinctBy { it.url },
                        selectedTab = CollectionTab.VIDEOS,
                        loading = false,
                        hasMore = page.hasMore,
                        error = if (page.items.isEmpty()) "No videos were returned for this ${entity.type.name.lowercase()}." else ""
                    ))
                    page.items.take(2).forEach(::prefetchYouTube)
                }
                .onFailure { e -> if (e !is CancellationException) {
                    val current = _state.value.collection
                    _state.value = _state.value.copy(collection = current.copy(
                        loading = false,
                        error = e.message ?: "Could not open ${entity.type.name.lowercase()}."
                    ))
                } }
        }
    }

    fun openChannel(url: String, name: String = "", thumbnailUrl: String = "") {
        if (url.isBlank()) return
        openCollection(BrowseEntity(
            id = url.hashCode().toString(),
            url = url,
            name = name.ifBlank { "Channel" },
            thumbnailUrl = thumbnailUrl,
            type = BrowseEntityType.CHANNEL
        ))
    }

    fun loadMoreCollection() {
        val current = _state.value.collection
        val entity = current.entity ?: return
        if (current.selectedTab != CollectionTab.VIDEOS) return
        if (current.loading || current.loadingMore || !current.hasMore) return
        collectionJob = viewModelScope.launch {
            _state.value = _state.value.copy(collection = current.copy(loadingMore = true))
            runCatching { withContext(Dispatchers.IO) { repository.collectionMore(entity) } }
                .onSuccess { page ->
                    val latest = _state.value.collection
                    _state.value = _state.value.copy(collection = latest.copy(
                        entity = page.entity,
                        videos = (latest.videos + page.items).distinctBy { it.url },
                        loadingMore = false,
                        hasMore = page.hasMore
                    ))
                    page.items.take(2).forEach(::prefetchYouTube)
                }
                .onFailure { e -> if (e !is CancellationException) {
                    val latest = _state.value.collection
                    _state.value = _state.value.copy(collection = latest.copy(
                        loadingMore = false,
                        error = e.message ?: "Could not load more videos."
                    ))
                } }
        }
    }

    fun selectCollectionTab(tab: CollectionTab) {
        val current = _state.value.collection
        if (current.selectedTab == tab) return
        _state.value = _state.value.copy(collection = current.copy(selectedTab = tab))
    }

    fun closeCollection() {
        collectionJob?.cancel()
        _state.value = _state.value.copy(collection = BrowseCollectionState())
    }

    fun prepareCollectionPlayback(index: Int) {
        val collection = _state.value.collection
        if (index !in collection.videos.indices) return
        playbackCollection = collection.videos
        playbackCollectionName = collection.title
        playbackCollectionIndex = index
        preparedCollectionUrl = collection.videos[index].url
    }

    fun createLocalPlaylist(name: String): LocalPlaylist {
        val playlist = localPlaylistStore.create(name)
        _playlists.value = localPlaylistStore.all()
        return playlist
    }

    fun addToLocalPlaylist(playlistId: String, video: BrowseVideo) {
        localPlaylistStore.addVideo(playlistId, video)
        _playlists.value = localPlaylistStore.all()
        recommendationStore.recordPlaylistSave(video)
    }

    fun removeFromLocalPlaylist(playlistId: String, video: BrowseVideo) {
        localPlaylistStore.removeVideo(playlistId, video)
        _playlists.value = localPlaylistStore.all()
    }

    fun deleteLocalPlaylist(playlistId: String) {
        localPlaylistStore.delete(playlistId)
        _playlists.value = localPlaylistStore.all()
    }

    fun prepareLocalPlaylistPlayback(playlistId: String, index: Int) {
        val playlist = localPlaylistStore.all().firstOrNull { it.id == playlistId } ?: return
        if (index !in playlist.videos.indices) return
        playbackCollection = playlist.videos
        playbackCollectionName = playlist.name
        playbackCollectionIndex = index
        preparedCollectionUrl = playlist.videos[index].url
    }

    fun loadShorts(force: Boolean = false) {
        if (!force && shortsJob?.isActive == true) return
        if (!force && _state.value.youtubeShorts.size >= 8) return
        shortsJob?.cancel()
        shortsJob = viewModelScope.launch {
            _state.value = _state.value.copy(
                youtubeShortsLoading = true,
                youtubeShortsLoadingMore = false,
                youtubeShortsHasMore = true,
                youtubeShortsError = ""
            )
            runCatching { withContext(Dispatchers.IO) { repository.youtubeShortsPersonalized(recommendationStore.shortsQueries(), reset = true) } }
                .onSuccess { page ->
                    val homeShorts = (_state.value.youtubeHome + _state.value.youtubeSearch)
                        .filter { it.shortForm || it.url.contains("/shorts/", true) }
                    val merged = recommendationStore.filterFeed((homeShorts + page.items).distinctBy { it.url }, shorts = true)
                    _state.value = _state.value.copy(
                        youtubeShortsLoading = false,
                        youtubeShorts = merged,
                        youtubeShortsHasMore = page.hasMore,
                        youtubeShortsError = if (merged.isEmpty()) "YouTube did not return Shorts right now. Swipe down to retry." else ""
                    )
                    merged.take(3).forEach(::ensureShortMedia)
                }
                .onFailure { e -> if (e !is CancellationException) _state.value = _state.value.copy(
                    youtubeShortsLoading = false,
                    youtubeShortsError = e.message ?: "Could not load Shorts."
                ) }
        }
    }

    fun loadMoreShorts() {
        val current = _state.value
        if (current.youtubeShortsLoading || current.youtubeShortsLoadingMore || !current.youtubeShortsHasMore) return
        shortsJob = viewModelScope.launch {
            _state.value = _state.value.copy(youtubeShortsLoadingMore = true)
            runCatching { withContext(Dispatchers.IO) { repository.youtubeShortsPersonalizedMore(recommendationStore.shortsQueries()) } }
                .onSuccess { page ->
                    val incoming = recommendationStore.filterFeed(page.items, shorts = true)
                    val merged = (_state.value.youtubeShorts + incoming).distinctBy { it.url }
                    _state.value = _state.value.copy(
                        youtubeShorts = merged,
                        youtubeShortsLoadingMore = false,
                        youtubeShortsHasMore = page.hasMore
                    )
                    page.items.take(2).forEach(::ensureShortMedia)
                }
                .onFailure { e -> if (e !is CancellationException) _state.value = _state.value.copy(
                    youtubeShortsLoadingMore = false,
                    youtubeShortsError = e.message ?: "Could not load more Shorts."
                ) }
        }
    }

    fun seedShort(item: BrowseVideo) {
        val short = item.copy(shortForm = true)
        _state.value = _state.value.copy(
            youtubeShorts = (listOf(short) + _state.value.youtubeShorts).distinctBy { it.url },
            youtubeShortsError = ""
        )
    }

    fun ensureShortMedia(item: BrowseVideo) {
        val short = item.copy(shortForm = true)
        val key = short.url
        if (_state.value.verticalMedia.containsKey(key) || verticalJobs[key]?.isActive == true) return
        _state.value = _state.value.copy(
            verticalLoading = _state.value.verticalLoading + key,
            verticalErrors = _state.value.verticalErrors - key
        )
        val job = viewModelScope.launch {
            try {
                val media = withContext(Dispatchers.IO) { repository.youtubeMedia(short.url) }
                val enriched = withContext(Dispatchers.IO) { repository.enrichCachedCard(short) }
                val current = _state.value
                _state.value = current.copy(
                    youtubeShorts = current.youtubeShorts.map { if (it.url == key) enriched else it },
                    verticalMedia = current.verticalMedia + (key to media),
                    verticalLoading = current.verticalLoading - key,
                    verticalErrors = current.verticalErrors - key
                )
            } catch (_: CancellationException) {
                _state.value = _state.value.copy(verticalLoading = _state.value.verticalLoading - key)
            } catch (t: Throwable) {
                _state.value = _state.value.copy(
                    verticalLoading = _state.value.verticalLoading - key,
                    verticalErrors = _state.value.verticalErrors + (key to (t.message ?: "Could not load Short."))
                )
            } finally {
                verticalJobs.remove(key)
            }
        }
        verticalJobs[key] = job
    }

    fun loadShortComments(url: String, reset: Boolean = false) {
        if (url.isBlank()) return
        val existing = _state.value.shortComments[url] ?: ShortCommentsState()
        if (existing.loading && !reset) return
        shortCommentJobs[url]?.cancel()
        _state.value = _state.value.copy(shortComments = _state.value.shortComments + (url to existing.copy(loading = true, error = "")))
        val job = viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { repository.comments(url, reset) } }
                .onSuccess { page ->
                    val previous = _state.value.shortComments[url] ?: ShortCommentsState()
                    val merged = if (reset) page.items else (previous.items + page.items).distinctBy { it.id }
                    val next = ShortCommentsState(
                        items = merged,
                        totalCount = page.totalCount,
                        loading = false,
                        hasMore = page.hasMore,
                        error = if (page.disabled) "Comments are disabled for this Short." else ""
                    )
                    _state.value = _state.value.copy(shortComments = _state.value.shortComments + (url to next))
                }
                .onFailure { e -> if (e !is CancellationException) {
                    val previous = _state.value.shortComments[url] ?: ShortCommentsState()
                    _state.value = _state.value.copy(shortComments = _state.value.shortComments + (
                        url to previous.copy(loading = false, error = e.message ?: "Comments are unavailable.")
                    ))
                } }
            shortCommentJobs.remove(url)
        }
        shortCommentJobs[url] = job
    }

    fun prefetchYouTube(item: BrowseVideo) {
        if (item.shortForm || item.url.contains("/shorts/", true)) return
        if (cardPrefetchJobs[item.url]?.isActive == true) return
        val job = viewModelScope.launch {
            try {
                cardPrefetchSemaphore.withPermit {
                    val enriched = runCatching {
                        withContext(Dispatchers.IO) { repository.enrichYouTubeCard(item) }
                    }.getOrNull() ?: return@withPermit
                    val current = _state.value
                    fun enrich(list: List<BrowseVideo>) = list.map { if (it.url == enriched.url) enriched else it }
                    _state.value = current.copy(
                        youtubeHome = enrich(current.youtubeHome),
                        youtubeSearch = enrich(current.youtubeSearch),
                        collection = current.collection.copy(videos = enrich(current.collection.videos))
                    )
                }
            } finally {
                cardPrefetchJobs.remove(item.url)
            }
        }
        cardPrefetchJobs[item.url] = job
    }

    fun openOffline(item: BrowseVideo, libraryItem: LibraryItem) {
        val prepared = preparedCollectionUrl == item.url
        val collectionIndex = playbackCollection.indexOfFirst { it.url == item.url }
        val expectedNext = playbackCollection.getOrNull(playbackCollectionIndex + 1)?.url == item.url
        val expectedPrevious = playbackCollection.getOrNull(playbackCollectionIndex - 1)?.url == item.url
        when {
            prepared -> { preparedCollectionUrl = ""; playbackCollectionIndex = collectionIndex.coerceAtLeast(0) }
            expectedNext -> playbackCollectionIndex += 1
            expectedPrevious -> playbackCollectionIndex -= 1
            collectionIndex >= 0 -> playbackCollectionIndex = collectionIndex
            else -> clearPlaybackCollection()
        }
        watchJob?.cancel(); commentsJob?.cancel(); relatedJob?.cancel()
        cardPrefetchJobs.values.forEach { it.cancel() }; cardPrefetchJobs.clear()
        rememberHistory(item)
        val queueName = playbackCollectionName
        val queuePosition = playbackCollectionIndex
        val queueSize = playbackCollection.size
        val mime = libraryItem.mimeType.ifBlank { "video/mp4" }
        val height = Regex("(\\d{3,4})p").find(libraryItem.quality)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
        val ext = when {
            mime.contains("mp3", true) || mime.contains("mpeg", true) -> "mp3"
            mime.contains("webm", true) -> "webm"
            mime.contains("audio", true) -> "m4a"
            else -> "mp4"
        }
        val media = AnalyzedMedia(
            mediaId = libraryItem.mediaId.ifBlank { item.id },
            sourceUrl = item.url,
            title = libraryItem.title.ifBlank { item.title },
            uploader = item.uploader,
            durationSeconds = item.durationSeconds,
            thumbnailUrl = libraryItem.thumbnailUrl.ifBlank { item.thumbnailUrl },
            serviceName = "Downloaded",
            videoVariants = if (mime.startsWith("video/")) listOf(MediaVariant(
                id = "offline:${libraryItem.id}", label = "Downloaded • ${libraryItem.quality}",
                qualityHeight = height, videoUrl = libraryItem.uri, mimeType = mime, extension = ext,
                codecNote = "Offline • no streaming"
            )) else emptyList(),
            audioVariants = if (mime.startsWith("audio/")) listOf(MediaVariant(
                id = "offline-audio:${libraryItem.id}", label = "Downloaded audio",
                qualityHeight = 0, videoUrl = libraryItem.uri, mimeType = mime, extension = ext,
                codecNote = "Offline • no streaming"
            )) else emptyList()
        )
        _state.value = _state.value.copy(watch = WatchState(
            item = item, media = media,
            details = WatchDetails(description = "Playing the downloaded copy from this device."),
            collectionName = queueName, collectionPosition = queuePosition, collectionSize = queueSize,
            collectionPrevious = playbackCollection.getOrNull(queuePosition - 1),
            collectionNext = playbackCollection.getOrNull(queuePosition + 1), collectionItems = playbackCollection,
            loading = false
        ))
        relatedJob = viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) {
                repository.youtubeRelatedDiscovery(
                    item, recommendationStore.relatedQueries(item, "", queueName, playbackCollection), reset = true
                )
            } }.onSuccess { page ->
                val current = _state.value.watch
                if (current.item?.url == item.url) {
                    val playlistUrls = current.collectionItems.map { it.url }.toSet()
                    val related = recommendationStore.filterFeed(page.items, shorts = false)
                        .filter { it.url != item.url && it.url !in playlistUrls && !it.shortForm }
                    _state.value = _state.value.copy(watch = current.copy(related = related, relatedHasMore = true))
                }
            }
        }
    }

    fun openYouTube(item: BrowseVideo) {
        // Preserve Play all only when this is the prepared/current-next collection item.
        val prepared = preparedCollectionUrl == item.url
        val collectionIndex = playbackCollection.indexOfFirst { it.url == item.url }
        val expectedNext = playbackCollection.getOrNull(playbackCollectionIndex + 1)?.url == item.url
        val expectedPrevious = playbackCollection.getOrNull(playbackCollectionIndex - 1)?.url == item.url
        when {
            prepared -> {
                preparedCollectionUrl = ""
                playbackCollectionIndex = collectionIndex.coerceAtLeast(0)
            }
            expectedNext -> playbackCollectionIndex += 1
            expectedPrevious -> playbackCollectionIndex -= 1
            collectionIndex >= 0 -> playbackCollectionIndex = collectionIndex
            else -> clearPlaybackCollection()
        }

        watchJob?.cancel()
        commentsJob?.cancel()
        relatedJob?.cancel()
        cardPrefetchJobs.values.forEach { it.cancel() }
        cardPrefetchJobs.clear()
        rememberHistory(item)
        val queueName = playbackCollectionName
        val queuePosition = playbackCollectionIndex
        val queueSize = playbackCollection.size
        val queuePrevious = playbackCollection.getOrNull(queuePosition - 1)
        val queueNext = playbackCollection.getOrNull(queuePosition + 1)
        val queueItems = playbackCollection

        watchJob = viewModelScope.launch {
            _state.value = _state.value.copy(watch = WatchState(
                item = item,
                collectionName = queueName,
                collectionPosition = queuePosition,
                collectionSize = queueSize,
                collectionPrevious = queuePrevious,
                collectionNext = queueNext,
                collectionItems = queueItems,
                loading = true
            ))
            runCatching { withContext(Dispatchers.IO) { repository.openYouTube(item.url) } }
                .onSuccess { payload ->
                    val enrichedItem = item.copy(
                        uploader = payload.media.uploader.ifBlank { item.uploader },
                        uploaderAvatarUrl = payload.details.uploaderAvatarUrl.ifBlank { item.uploaderAvatarUrl },
                        uploaderVerified = payload.details.uploaderVerified
                    )
                    recommendationStore.recordWatchMetadata(enrichedItem, payload.details.description)
                    val rankedRelated = recommendationStore.filterFeed(payload.related, shorts = false)
                    _state.value = _state.value.copy(
                        watch = WatchState(
                            item = enrichedItem,
                            media = payload.media,
                            related = rankedRelated,
                            details = payload.details,
                            collectionName = queueName,
                            collectionPosition = queuePosition,
                            collectionSize = queueSize,
                            collectionPrevious = queuePrevious,
                            collectionNext = queueNext,
                            collectionItems = queueItems,
                            loading = false,
                            commentsLoading = false
                        )
                    )
                    rankedRelated.take(2).forEach(::prefetchYouTube)
        if (queueItems.isNotEmpty() && queuePosition == queueItems.lastIndex) loadMoreRelated()
                }
                .onFailure { e -> if (e !is CancellationException) _state.value = _state.value.copy(
                    watch = WatchState(
                        item = item,
                        collectionName = queueName,
                        collectionPosition = queuePosition,
                        collectionSize = queueSize,
                        collectionPrevious = queuePrevious,
                        collectionNext = queueNext,
                        collectionItems = queueItems,
                        loading = false,
                        error = e.message ?: "Could not open video."
                    )
                ) }
        }
    }


    private fun applyServiceAutoplayAdvance(advance: PlaybackAutoplayBridge.Advance) {
        watchJob?.cancel()
        commentsJob?.cancel()
        relatedJob?.cancel()
        cardPrefetchJobs.values.forEach { it.cancel() }
        cardPrefetchJobs.clear()
        val item = advance.item
        val payload = advance.payload

        val collectionIndex = playbackCollection.indexOfFirst { it.url == item.url }
        val expectedNext = playbackCollection.getOrNull(playbackCollectionIndex + 1)?.url == item.url
        when {
            expectedNext -> playbackCollectionIndex += 1
            collectionIndex >= 0 -> playbackCollectionIndex = collectionIndex
            else -> clearPlaybackCollection()
        }

        rememberHistory(item)
        val queueName = playbackCollectionName
        val queuePosition = playbackCollectionIndex
        val queueSize = playbackCollection.size
        val queuePrevious = playbackCollection.getOrNull(queuePosition - 1)
        val queueNext = playbackCollection.getOrNull(queuePosition + 1)
        val queueItems = playbackCollection
        val enrichedItem = item.copy(
            uploader = payload.media.uploader.ifBlank { item.uploader },
            uploaderAvatarUrl = payload.details.uploaderAvatarUrl.ifBlank { item.uploaderAvatarUrl },
            uploaderVerified = payload.details.uploaderVerified
        )

        recommendationStore.recordWatchMetadata(enrichedItem, payload.details.description)
        val rankedRelated = recommendationStore.filterFeed(payload.related, shorts = false)
        _state.value = _state.value.copy(
            watch = WatchState(
                item = enrichedItem,
                media = payload.media,
                related = rankedRelated,
                details = payload.details,
                collectionName = queueName,
                collectionPosition = queuePosition,
                collectionSize = queueSize,
                collectionPrevious = queuePrevious,
                collectionNext = queueNext,
                collectionItems = queueItems,
                loading = false,
                commentsLoading = false
            )
        )
        rankedRelated.take(2).forEach(::prefetchYouTube)
    }

    fun loadMoreRelated() {
        val current = _state.value.watch
        val item = current.item ?: return
        if (current.relatedLoadingMore || !current.relatedHasMore) return
        relatedJob?.cancel()
        relatedJob = viewModelScope.launch {
            _state.value = _state.value.copy(watch = _state.value.watch.copy(relatedLoadingMore = true, relatedError = ""))
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    repository.youtubeRelatedDiscovery(item, recommendationStore.relatedQueries(item, current.details.description, current.collectionName, current.collectionItems), reset = false)
                }
            }
            result.onSuccess { page ->
                val latest = _state.value.watch
                if (latest.item?.url != item.url) return@onSuccess
                val playlistUrls = latest.collectionItems.map { it.url }.toSet()
                val incoming = recommendationStore.filterFeed(page.items, shorts = false)
                    .filter { it.url != item.url && it.url !in playlistUrls && !it.shortForm && !it.url.contains("/shorts/", true) }
                val merged = (latest.related + incoming).distinctBy { it.url }
                _state.value = _state.value.copy(watch = latest.copy(
                    related = merged,
                    relatedLoadingMore = false,
                    // Discovery generations deliberately remain open-ended.
                    relatedHasMore = true,
                    relatedError = ""
                ))
                incoming.take(1).forEach(::prefetchYouTube)
            }.onFailure { e ->
                if (e !is CancellationException) {
                    val latest = _state.value.watch
                    if (latest.item?.url == item.url) _state.value = _state.value.copy(watch = latest.copy(
                        relatedLoadingMore = false,
                        relatedHasMore = true,
                        relatedError = e.message ?: "Could not load more recommendations."
                    ))
                }
            }
        }
    }

    fun loadComments(reset: Boolean = false) {
        val url = _state.value.watch.item?.url ?: return
        if (_state.value.watch.commentsLoading && !reset) return
        commentsJob?.cancel()
        commentsJob = viewModelScope.launch {
            _state.value = _state.value.copy(watch = _state.value.watch.copy(commentsLoading = true, commentsError = ""))
            runCatching { withContext(Dispatchers.IO) { repository.comments(url, reset) } }
                .onSuccess { page ->
                    val current = _state.value.watch
                    val merged = if (reset) page.items else (current.comments + page.items).distinctBy { it.id }
                    _state.value = _state.value.copy(watch = current.copy(
                        comments = merged,
                        commentsCount = page.totalCount,
                        commentsLoading = false,
                        commentsHasMore = page.hasMore,
                        commentsError = if (page.disabled) "Comments are disabled for this video." else ""
                    ))
                }
                .onFailure { e -> if (e !is CancellationException) {
                    val current = _state.value.watch
                    _state.value = _state.value.copy(watch = current.copy(
                        commentsLoading = false,
                        commentsError = e.message ?: "Comments are unavailable."
                    ))
                } }
        }
    }

    fun openSharedUrl(url: String) {
        val clean = url.trim()
        if (!clean.contains("youtube.com", true) && !clean.contains("youtu.be", true)) {
            _state.value = _state.value.copy(youtubeError = "Haruki NovaTube focuses on YouTube links.")
            return
        }
        val isShort = clean.contains("/shorts/", true)
        val item = BrowseVideo(
            id = clean.hashCode().toString(),
            url = clean,
            title = if (isShort) "YouTube Short" else "Shared video",
            uploader = "",
            thumbnailUrl = "",
            shortForm = isShort,
            service = "YouTube"
        )
        if (isShort) seedShort(item) else openYouTube(item)
    }

    fun closeWatch() {
        watchJob?.cancel()
        commentsJob?.cancel()
        clearPlaybackCollection()
        _state.value = _state.value.copy(watch = WatchState())
    }

    fun isSaved(url: String): Boolean {
        val key = SavedVideoStore.canonicalKey(url)
        return key.isNotBlank() && _saved.value.any { SavedVideoStore.canonicalKey(it.url, it.id) == key }
    }

    fun toggleSaved(item: BrowseVideo): Boolean {
        // SavedVideoStore is the source of truth. It normalizes all common YouTube URL
        // forms and commits synchronously, then StateFlow mirrors the durable result.
        val nowSaved = savedStore.toggle(item)
        _saved.value = savedStore.all()
        if (nowSaved) {
            val metadata = _state.value.watch.takeIf { it.item?.url == item.url }?.details?.description.orEmpty()
            recommendationStore.recordSave(item, metadata)
            if (metadata.isBlank()) {
                // Saved directly from a feed: enrich it in the background so hashtags and
                // description topics still influence For You without delaying the save UI.
                viewModelScope.launch(Dispatchers.IO) {
                    val extra = runCatching { repository.aiSearchMetadata(item.url) }.getOrDefault("")
                    if (extra.isNotBlank()) recommendationStore.recordSaveMetadata(item, extra)
                }
            }
        }
        return nowSaved
    }

    fun removeSaved(item: BrowseVideo) {
        savedStore.remove(item.url, item.id)
        _saved.value = savedStore.all()
    }

    fun clearSaved() {
        savedStore.clear()
        _saved.value = emptyList()
    }

    fun clearHistory() { historyStore.clear(); _history.value = emptyList() }

    private fun clearPlaybackCollection() {
        playbackCollection = emptyList()
        playbackCollectionName = ""
        playbackCollectionIndex = -1
        preparedCollectionUrl = ""
    }

    fun recordDownload(item: BrowseVideo) {
        val metadata = _state.value.watch.takeIf { it.item?.url == item.url }?.details?.description.orEmpty()
        recommendationStore.recordDownload(item, metadata)
        if (metadata.isBlank()) {
            // Downloads started from a feed should teach the same topic/hashtag model as
            // downloads started from the Watch page, but metadata extraction stays off-UI.
            viewModelScope.launch(Dispatchers.IO) {
                val extra = runCatching { repository.aiSearchMetadata(item.url) }.getOrDefault("")
                if (extra.isNotBlank()) recommendationStore.recordDownloadMetadata(item, extra)
            }
        }
    }
    fun recordDownload(media: AnalyzedMedia) { recommendationStore.recordDownload(media) }

    fun markShortSeen(item: BrowseVideo) { recommendationStore.markShortSeen(item) }

    fun notInterestedVideo(item: BrowseVideo) {
        recommendationStore.notInterested(item)
        val current = _state.value
        _state.value = current.copy(
            youtubeHome = current.youtubeHome.filterNot { it.url == item.url },
            youtubeSearch = current.youtubeSearch.filterNot { it.url == item.url },
            watch = current.watch.copy(related = current.watch.related.filterNot { it.url == item.url })
        )
    }

    fun notInterestedShort(item: BrowseVideo) {
        recommendationStore.notInterested(item)
        _state.value = _state.value.copy(youtubeShorts = _state.value.youtubeShorts.filterNot { it.url == item.url })
    }

    fun dontRecommendChannel(item: BrowseVideo) {
        recommendationStore.blockChannel(item)
        val uploader = item.uploader
        _state.value = _state.value.copy(
            youtubeShorts = _state.value.youtubeShorts.filterNot { it.uploader.equals(uploader, true) },
            youtubeHome = _state.value.youtubeHome.filterNot { it.uploader.equals(uploader, true) }
        )
    }

    private fun rememberHistory(item: BrowseVideo) {
        recommendationStore.recordWatch(item)
        historyStore.add(item)
        _history.value = historyStore.all()
    }
}
