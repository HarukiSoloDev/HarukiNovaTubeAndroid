package com.harukisolodev.harukistream.data

import com.harukisolodev.harukistream.extractor.BrowseRepository
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Nova AI wide-search video finder.
 *
 * Local History/Saved/Downloads are only extra evidence. The primary search space is the wider
 * YouTube catalogue: Nova AI expands vague memories into multiple queries, follows search
 * suggestions, checks more than one result page for its strongest queries, explores promising
 * channels/playlists, enriches the strongest candidates with description metadata, then reranks
 * everything with fuzzy clue matching and cross-query consensus.
 *
 * This intentionally avoids embedding a cloud AI secret in the APK. It can later be paired with a
 * server-side LLM, but the wide-search engine is useful and private on its own.
 */
class NovaAiSearchEngine(private val repository: BrowseRepository) {
    data class LocalCandidate(val video: BrowseVideo, val source: NovaAiMatchSource)
    data class Result(val matches: List<NovaAiMatch>, val queries: List<String>)

    private data class SearchProfile(
        val maxQueries: Int,
        val suggestionSeeds: Int,
        val suggestionsPerSeed: Int,
        val deepQueryCount: Int,
        val maxCandidates: Int,
        val entityLimit: Int,
        val entityItems: Int,
        val enrichTop: Int,
        val resultLimit: Int
    )

    private fun profile(mode: NovaAiSearchMode): SearchProfile = when (mode) {
        NovaAiSearchMode.FAST -> SearchProfile(4, 1, 2, 0, 120, 0, 0, 0, 10)
        NovaAiSearchMode.SMART -> SearchProfile(8, 1, 2, 2, 240, 1, 16, 5, 16)
        NovaAiSearchMode.DEEP -> SearchProfile(16, 2, 3, 5, 420, 2, 24, 14, 24)
    }

    private data class Candidate(
        var video: BrowseVideo,
        var source: NovaAiMatchSource,
        var queryHits: Int = 0,
        val matchedQueries: MutableSet<String> = linkedSetOf(),
        var extraText: String = ""
    )

    private data class MemoryClues(
        val original: String,
        val normalized: String,
        val tokens: List<String>,
        val expandedTokens: Set<String>,
        val quoted: List<String>,
        val years: Set<Int>,
        val contentHints: Set<String>,
        val creatorHint: String?,
        val durationHintSeconds: Long?
    ) {
        companion object {
            fun from(text: String): MemoryClues {
                val quoted = Regex("[\\\"“”']([^\\\"“”']{3,80})[\\\"“”']")
                    .findAll(text).map { it.groupValues[1].trim() }.filter { it.isNotBlank() }.toList()
                val years = Regex("\\b(?:19|20)\\d{2}\\b").findAll(text).mapNotNull { it.value.toIntOrNull() }.toSet()
                val normalized = normalize(text)
                val tokens = keywords(text)
                val low = text.lowercase(Locale.ROOT)
                val hints = buildSet {
                    if (listOf("song", "music", "singer", "lyrics", "mv", "music video").any(low::contains)) add("music")
                    if (listOf("short", "shorts", "vertical", "reel").any(low::contains)) add("shorts")
                    if (listOf("game", "gaming", "gameplay", "minecraft", "roblox", "valorant", "mobile legends").any(low::contains)) add("gaming")
                    if (listOf("tutorial", "how to", "guide", "fix", "setup").any(low::contains)) add("tutorial")
                    if (listOf("review", "unboxing", "comparison").any(low::contains)) add("review")
                    if (listOf("movie", "trailer", "film", "teaser").any(low::contains)) add("movie")
                    if (listOf("podcast", "interview", "talk show").any(low::contains)) add("talk")
                    if (listOf("documentary", "documentary video").any(low::contains)) add("documentary")
                    if (listOf("live", "livestream", "stream").any(low::contains)) add("live")
                }
                val creator = listOf(
                    Regex("(?i)\\b(?:by|from|creator|channel(?: called| named)?)\\s+([\\p{L}\\p{N}_. -]{2,40})"),
                    Regex("(?i)\\b(?:youtuber|artist|singer)\\s+([\\p{L}\\p{N}_. -]{2,40})")
                ).firstNotNullOfOrNull { regex -> regex.find(text)?.groupValues?.getOrNull(1)?.trim() }
                    ?.substringBefore(',')?.substringBefore('.')?.takeIf { it.length in 2..40 }
                val durationSeconds = parseDurationHint(low)
                return MemoryClues(
                    original = text,
                    normalized = normalized,
                    tokens = tokens,
                    expandedTokens = expandTokens(tokens, hints),
                    quoted = quoted,
                    years = years,
                    contentHints = hints,
                    creatorHint = creator,
                    durationHintSeconds = durationSeconds
                )
            }
        }
    }

    fun find(
        prompt: String,
        locals: List<LocalCandidate>,
        mode: NovaAiSearchMode = NovaAiSearchMode.SMART,
        onPhase: (String) -> Unit = {}
    ): Result {
        val clean = prompt.trim()
        if (clean.length < 3) return Result(emptyList(), emptyList())
        val profile = profile(mode)

        onPhase("Understanding the clues in your memory…")
        val memory = MemoryClues.from(clean)
        val baseQueries = buildQueries(memory)

        onPhase(if (mode == NovaAiSearchMode.FAST) "Building a fast YouTube search…" else "Expanding the search across YouTube…")
        val suggestionQueries = baseQueries.take(profile.suggestionSeeds).flatMap { query ->
            runCatching { repository.suggestions(query) }.getOrDefault(emptyList()).take(profile.suggestionsPerSeed)
        }
        val planned = (baseQueries + suggestionQueries)
            .map { it.replace(Regex("\\s+"), " ").trim() }
            .filter { it.length >= 3 }
            .distinctBy { it.lowercase(Locale.ROOT) }
            .take(profile.maxQueries)

        val candidateMap = LinkedHashMap<String, Candidate>()
        fun upsert(video: BrowseVideo, source: NovaAiMatchSource, query: String? = null) {
            if (video.url.isBlank()) return
            val key = stableKey(video)
            val existing = candidateMap[key]
            if (existing == null) {
                candidateMap[key] = Candidate(
                    video = video,
                    source = source,
                    queryHits = if (query == null) 0 else 1,
                    matchedQueries = linkedSetOf<String>().apply { if (query != null) add(query) }
                )
            } else {
                existing.source = strongerSource(existing.source, source)
                if (video.uploader.isNotBlank() || video.uploadText.isNotBlank() || video.durationSeconds > 0) existing.video = video
                if (query != null && existing.matchedQueries.add(query)) existing.queryHits++
            }
        }

        // Local activity is useful evidence but no longer dominates ranking.
        onPhase("Adding your NovaTube activity as optional clues…")
        locals.forEach { upsert(it.video, it.source) }

        val channels = LinkedHashMap<String, BrowseEntity>()
        val playlists = LinkedHashMap<String, BrowseEntity>()

        onPhase("Searching the wider YouTube catalogue with ${planned.size} strategies…")
        planned.forEachIndexed { index, query ->
            if (candidateMap.size >= profile.maxCandidates) return@forEachIndexed
            val first = runCatching { repository.youtubeSearch(query, reset = true) }.getOrNull() ?: return@forEachIndexed
            first.items.take(32).forEach { upsert(it, NovaAiMatchSource.YOUTUBE, query) }
            first.channels.forEach { channels.putIfAbsent(it.url.ifBlank { it.id }, it) }
            first.playlists.forEach { playlists.putIfAbsent(it.url.ifBlank { it.id }, it) }

            // Go deeper for the strongest query formulations. This is the biggest difference from
            // a normal single-query search and helps find older/less obvious videos.
            if (index < profile.deepQueryCount && first.hasMore && candidateMap.size < profile.maxCandidates) {
                val more = runCatching { repository.youtubeSearchMore(query) }.getOrNull()
                more?.items?.take(28)?.forEach { upsert(it, NovaAiMatchSource.YOUTUBE, query) }
                more?.channels?.forEach { channels.putIfAbsent(it.url.ifBlank { it.id }, it) }
                more?.playlists?.forEach { playlists.putIfAbsent(it.url.ifBlank { it.id }, it) }
            }
        }

        // Entity exploration is intentionally disabled in Fast mode and bounded in Smart mode.
        if (profile.entityLimit > 0) {
            onPhase(if (mode == NovaAiSearchMode.DEEP) "Deep search: checking channels and playlists…" else "Checking the strongest channel or playlist…")
            playlists.values.sortedByDescending { entityRelevance(memory, it) }
                .filter { entityRelevance(memory, it) >= ENTITY_MIN_RELEVANCE }
                .take(profile.entityLimit)
                .forEach { entity ->
                    runCatching { repository.openCollection(entity) }.getOrNull()?.items?.take(profile.entityItems)?.forEach {
                        upsert(it, NovaAiMatchSource.YOUTUBE_PLAYLIST, "playlist:${entity.name}")
                    }
                }
            channels.values.sortedByDescending { entityRelevance(memory, it) }
                .filter { entityRelevance(memory, it) >= ENTITY_MIN_RELEVANCE }
                .take(profile.entityLimit)
                .forEach { entity ->
                    runCatching { repository.openCollection(entity) }.getOrNull()?.items?.take(profile.entityItems)?.forEach {
                        upsert(it, NovaAiMatchSource.YOUTUBE_CHANNEL, "channel:${entity.name}")
                    }
                }
        }

        if (profile.enrichTop > 0) {
            onPhase(if (mode == NovaAiSearchMode.DEEP) "Deep search: reading candidate descriptions…" else "Checking details for the best candidates…")
            candidateMap.values
                .mapNotNull { candidate -> score(memory, candidate, allowWeak = true)?.let { candidate to it.confidence } }
                .sortedByDescending { it.second }
                .take(profile.enrichTop)
                .forEach { (candidate, _) ->
                    candidate.extraText = runCatching { repository.aiSearchMetadata(candidate.video.url) }.getOrDefault("")
                }
        }

        onPhase(if (mode == NovaAiSearchMode.FAST) "Ranking the fastest likely matches…" else "Reranking titles, creators, descriptions, dates and search consensus…")
        val rawMatches = candidateMap.values.mapNotNull { score(memory, it, allowWeak = false) }
            .sortedByDescending { it.confidence }

        // Avoid a vague memory producing a wall of one creator unless the user explicitly named it.
        val perChannelLimit = if (memory.creatorHint != null) 8 else 3
        val channelCounts = mutableMapOf<String, Int>()
        val diversified = rawMatches.filter { match ->
            val channelKey = normalize(match.video.uploader).ifBlank { "unknown:${match.video.id}" }
            val count = channelCounts[channelKey] ?: 0
            if (count >= perChannelLimit) false else {
                channelCounts[channelKey] = count + 1
                true
            }
        }.take(profile.resultLimit)

        return Result(diversified, planned)
    }

    private fun buildQueries(memory: MemoryClues): List<String> {
        val out = mutableListOf<String>()
        val stripped = memory.original
            .replace(Regex("(?i)\\b(i|im|i'm|think|maybe|probably|please|can you|find|finding|looking for|remember|remembered|video|youtube|title|something|the|a|an|that|where|which|was|were|it|had|has|about)\\b"), " ")
            .replace(Regex("\\s+"), " ").trim()
        val core = memory.tokens.take(10).joinToString(" ")
        val expandedCore = memory.expandedTokens.take(11).joinToString(" ")

        memory.quoted.forEach(out::add)
        if (memory.creatorHint != null) {
            out += "${memory.creatorHint} $core"
            out += "\"${memory.creatorHint}\" ${memory.tokens.take(7).joinToString(" ")}"
        }
        if (stripped.length >= 3) out += stripped
        if (core.isNotBlank()) out += core
        if (expandedCore.isNotBlank() && !expandedCore.equals(core, true)) out += expandedCore

        when {
            "music" in memory.contentHints -> {
                out += "$core official music video"
                out += "$core lyrics audio"
            }
            "gaming" in memory.contentHints -> {
                out += "$core gameplay"
                out += "$core walkthrough challenge"
            }
            "tutorial" in memory.contentHints -> out += "$core tutorial guide"
            "review" in memory.contentHints -> out += "$core review comparison"
            "movie" in memory.contentHints -> out += "$core trailer clip"
            "talk" in memory.contentHints -> out += "$core interview podcast"
            "documentary" in memory.contentHints -> out += "$core documentary"
        }
        if ("shorts" in memory.contentHints) out += "$core #shorts"

        memory.years.firstOrNull()?.let { year ->
            out += "$core $year"
            // Memories of the year are often off by one, so search a small era window.
            out += "$core ${year - 1} ${year + 1}"
        }
        out += memory.original
        return out.map { it.replace(Regex("\\s+"), " ").trim() }
            .filter { it.length >= 3 }
            .distinctBy { it.lowercase(Locale.ROOT) }
            .take(10)
    }

    private fun score(memory: MemoryClues, candidate: Candidate, allowWeak: Boolean): NovaAiMatch? {
        val video = candidate.video
        val titleN = normalize(video.title)
        val uploaderN = normalize(video.uploader)
        val uploadN = normalize(video.uploadText)
        val extraN = normalize(candidate.extraText)
        val allN = "$titleN $uploaderN $uploadN $extraN"
        val titleTokens = keywords(video.title).toSet()
        val uploaderTokens = keywords(video.uploader).toSet()
        val allTokens = (titleTokens + uploaderTokens + keywords(video.uploadText) + keywords(candidate.extraText)).toSet()

        val matched = memory.expandedTokens.filter { it in allTokens }
        val directMatched = memory.tokens.filter { it in allTokens }
        val titleMatched = memory.tokens.filter { it in titleTokens }
        val uploaderMatched = memory.tokens.filter { it in uploaderTokens }
        val quotedMatch = memory.quoted.firstOrNull { normalize(it) in allN }
        val exactYear = memory.years.firstOrNull { it.toString() in video.uploadText || it.toString() in extraN }
        val nearYear = if (exactYear == null) memory.years.firstOrNull { year ->
            Regex("\\b(?:19|20)\\d{2}\\b").findAll("${video.uploadText} $extraN")
                .mapNotNull { it.value.toIntOrNull() }.any { abs(it - year) <= 1 }
        } else null

        val tokenCoverage = if (memory.expandedTokens.isEmpty()) 0.0 else matched.size.toDouble() / memory.expandedTokens.size
        val directCoverage = if (memory.tokens.isEmpty()) 0.0 else directMatched.size.toDouble() / memory.tokens.size
        val titleCoverage = if (memory.tokens.isEmpty()) 0.0 else titleMatched.size.toDouble() / memory.tokens.size
        val fuzzy = max(dice(memory.normalized, titleN), dice(memory.tokens.take(9).joinToString(" "), titleN))
        val consensusBonus = min(15.0, candidate.queryHits * 2.75)
        val sourceBonus = when (candidate.source) {
            NovaAiMatchSource.DOWNLOAD -> 3.0
            NovaAiMatchSource.SAVED -> 2.5
            NovaAiMatchSource.HISTORY -> 1.5
            NovaAiMatchSource.LOCAL_PLAYLIST -> 2.0
            NovaAiMatchSource.YOUTUBE, NovaAiMatchSource.YOUTUBE_PLAYLIST, NovaAiMatchSource.YOUTUBE_CHANNEL -> 0.0
        }

        var raw = directCoverage * 38.0 + tokenCoverage * 18.0 + titleCoverage * 18.0 + fuzzy * 19.0 + consensusBonus + sourceBonus
        if (quotedMatch != null) raw += 18.0
        if (uploaderMatched.isNotEmpty()) raw += min(13.0, uploaderMatched.size * 5.0)
        if (memory.creatorHint != null && normalize(memory.creatorHint) in uploaderN) raw += 15.0
        if (exactYear != null) raw += 8.0 else if (nearYear != null) raw += 4.0
        if ("shorts" in memory.contentHints && (video.shortForm || video.url.contains("/shorts/", true))) raw += 8.0
        if ("music" in memory.contentHints && listOf("music", "official", "lyrics", "song", "mv", "audio").any { it in titleN || it in extraN }) raw += 6.0
        if ("gaming" in memory.contentHints && listOf("gameplay", "minecraft", "roblox", "gaming", "walkthrough").any { it in titleN || it in extraN }) raw += 5.0

        memory.durationHintSeconds?.let { expected ->
            if (video.durationSeconds > 0L) {
                val ratio = abs(video.durationSeconds - expected).toDouble() / max(1L, expected)
                raw += when {
                    ratio <= .20 -> 10.0
                    ratio <= .45 -> 5.0
                    ratio > 1.5 -> -4.0
                    else -> 0.0
                }
            }
        }

        if (!allowWeak && candidate.source in REMOTE_SOURCES && raw < 10.5) return null
        val confidence = raw.coerceIn(1.0, 98.0).toInt()

        val clues = buildList {
            if (quotedMatch != null) add("remembered phrase")
            if (titleMatched.isNotEmpty()) add(titleMatched.take(3).joinToString(", "))
            if (uploaderMatched.isNotEmpty() || (memory.creatorHint != null && normalize(memory.creatorHint) in uploaderN)) add("creator")
            if (exactYear != null) add(exactYear.toString()) else if (nearYear != null) add("around $nearYear")
            if (candidate.queryHits >= 2) add("matched ${candidate.queryHits} searches")
            if (candidate.extraText.isNotBlank() && directMatched.any { it in extraN }) add("description clue")
            when (candidate.source) {
                NovaAiMatchSource.HISTORY -> add("your history")
                NovaAiMatchSource.SAVED -> add("your saved videos")
                NovaAiMatchSource.DOWNLOAD -> add("your downloads")
                NovaAiMatchSource.LOCAL_PLAYLIST -> add("your playlists")
                NovaAiMatchSource.YOUTUBE -> add("global YouTube")
                NovaAiMatchSource.YOUTUBE_PLAYLIST -> add("playlist discovery")
                NovaAiMatchSource.YOUTUBE_CHANNEL -> add("channel discovery")
            }
        }.distinct()

        val reason = when {
            candidate.queryHits >= 3 -> "This video kept appearing across several different searches built from your memory, which makes it a strong global match."
            quotedMatch != null -> "A phrase you remembered closely matches this video's title or metadata."
            memory.creatorHint != null && normalize(memory.creatorHint) in uploaderN -> "The creator you remembered matches, and the video's topic also lines up with your clues."
            candidate.source == NovaAiMatchSource.YOUTUBE_PLAYLIST -> "Nova AI found this inside a playlist that matched your memory, even though the video itself was not an obvious first-page result."
            candidate.source == NovaAiMatchSource.YOUTUBE_CHANNEL -> "Nova AI found a likely creator first, then searched that channel's videos for your clues."
            candidate.source == NovaAiMatchSource.DOWNLOAD -> "You downloaded this before, but it is ranked mainly because its title and metadata match your clues."
            candidate.source == NovaAiMatchSource.SAVED -> "This is in Saved, but it is ranked mainly because its details match what you described."
            candidate.source == NovaAiMatchSource.HISTORY -> "You watched this before, but Nova AI only uses that as extra evidence; the title/creator still match your memory."
            candidate.source == NovaAiMatchSource.LOCAL_PLAYLIST -> "This appears in one of your local playlists and also matches the title/topic clues you described."
            titleMatched.size >= 3 -> "Several remembered words appear directly in the title, and the wider YouTube search supports the match."
            candidate.extraText.isNotBlank() && directMatched.any { it in extraN } -> "Your clues appear in the video's additional metadata/description even though the title is less obvious."
            else -> "This is one of the closest matches found across the wider YouTube catalogue using title, creator, date, topic and fuzzy wording."
        }
        return NovaAiMatch(video, confidence, reason, candidate.source, clues)
    }

    private fun entityRelevance(memory: MemoryClues, entity: BrowseEntity): Double {
        val text = normalize("${entity.name} ${entity.subtitle} ${entity.description}")
        if (text.isBlank()) return 0.0
        val entityTokens = keywords(text).toSet()
        val coverage = if (memory.tokens.isEmpty()) 0.0 else memory.tokens.count { it in entityTokens }.toDouble() / memory.tokens.size
        val fuzzy = max(dice(memory.tokens.take(7).joinToString(" "), text), dice(memory.normalized, normalize(entity.name)))
        val creator = memory.creatorHint?.let { if (normalize(it) in normalize(entity.name)) .55 else 0.0 } ?: 0.0
        return coverage * .55 + fuzzy * .35 + creator
    }

    private fun strongerSource(a: NovaAiMatchSource, b: NovaAiMatchSource): NovaAiMatchSource {
        val rank = mapOf(
            NovaAiMatchSource.YOUTUBE to 0,
            NovaAiMatchSource.YOUTUBE_PLAYLIST to 1,
            NovaAiMatchSource.YOUTUBE_CHANNEL to 1,
            NovaAiMatchSource.HISTORY to 2,
            NovaAiMatchSource.LOCAL_PLAYLIST to 2,
            NovaAiMatchSource.SAVED to 3,
            NovaAiMatchSource.DOWNLOAD to 4
        )
        return if (rank.getValue(b) > rank.getValue(a)) b else a
    }

    private fun stableKey(video: BrowseVideo): String = SavedVideoStore.canonicalKey(video.url, video.id).ifBlank { video.url.trim().removeSuffix("/").ifBlank { video.id } }

    private fun dice(a: String, b: String): Double {
        if (a.isBlank() || b.isBlank()) return 0.0
        if (a == b) return 1.0
        val aa = bigrams(a)
        val bb = bigrams(b)
        if (aa.isEmpty() || bb.isEmpty()) return 0.0
        return aa.intersect(bb).size * 2.0 / (aa.size + bb.size)
    }

    private fun bigrams(text: String): Set<String> {
        val compact = normalize(text).replace(" ", "_")
        if (compact.length < 2) return setOf(compact)
        return (0 until compact.length - 1).map { compact.substring(it, it + 2) }.toSet()
    }

    companion object {
        private const val ENTITY_MIN_RELEVANCE = .18
        private val REMOTE_SOURCES = setOf(NovaAiMatchSource.YOUTUBE, NovaAiMatchSource.YOUTUBE_PLAYLIST, NovaAiMatchSource.YOUTUBE_CHANNEL)

        private val STOP = setOf(
            "the", "and", "for", "that", "this", "with", "from", "where", "when", "what", "which",
            "video", "youtube", "remember", "think", "maybe", "probably", "about", "there", "something", "like",
            "have", "has", "was", "were", "been", "into", "some", "just", "please", "find", "looking", "watched"
        )

        private val SYNONYMS = mapOf(
            "song" to setOf("music", "audio", "lyrics", "mv"),
            "music" to setOf("song", "audio", "official"),
            "car" to setOf("automotive", "vehicle"),
            "phone" to setOf("smartphone", "mobile"),
            "game" to setOf("gaming", "gameplay"),
            "funny" to setOf("comedy", "meme"),
            "tutorial" to setOf("guide", "howto"),
            "review" to setOf("comparison", "test"),
            "trailer" to setOf("teaser", "official"),
            "short" to setOf("shorts", "vertical")
        )

        private fun normalize(text: String): String = text.lowercase(Locale.ROOT)
            .replace(Regex("https?://\\S+"), " ")
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

        private fun keywords(text: String): List<String> = normalize(text)
            .split(' ')
            .map(String::trim)
            .filter { it.length >= 2 && it !in STOP }
            .distinct()

        private fun expandTokens(tokens: List<String>, hints: Set<String>): Set<String> = linkedSetOf<String>().apply {
            addAll(tokens)
            tokens.forEach { token -> SYNONYMS[token]?.let(::addAll) }
            if ("music" in hints) addAll(listOf("music", "song", "lyrics", "official"))
            if ("gaming" in hints) addAll(listOf("gaming", "gameplay"))
        }

        private fun parseDurationHint(low: String): Long? {
            Regex("\\b(\\d{1,3})\\s*(?:hours?|hrs?|hr)\\b").find(low)?.groupValues?.getOrNull(1)?.toLongOrNull()?.let { return it * 3600L }
            Regex("\\b(\\d{1,3})\\s*(?:minutes?|mins?|min)\\b").find(low)?.groupValues?.getOrNull(1)?.toLongOrNull()?.let { return it * 60L }
            return null
        }
    }
}
