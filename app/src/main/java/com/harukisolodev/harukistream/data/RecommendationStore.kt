package com.harukisolodev.harukistream.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.ArrayDeque
import kotlin.math.max
import kotlin.random.Random

/**
 * Small on-device recommendation profile for NovaTube.
 *
 * It never pretends to be YouTube's private account algorithm. Instead it learns from
 * local searches, watches, saves and downloads, plus explicit negative feedback.
 */
class RecommendationStore(context: Context) {
    private val prefs = context.getSharedPreferences("haruki_recommendations", Context.MODE_PRIVATE)

    fun recordSearch(query: String) {
        addPhrase(query, 8.0)
        addTopic(query, 10.0)
    }
    fun recordWatch(item: BrowseVideo) {
        recordVideo(item, 2.0)
        val recentKey = SavedVideoStore.canonicalKey(item.url, item.id)
        val recent = recentWatches().map { SavedVideoStore.canonicalKey(it) }.filter(String::isNotBlank).toMutableList()
        recent.remove(recentKey)
        recent.add(0, recentKey)
        writeStringArray(KEY_RECENT_WATCHES, recent.take(MAX_RECENT_WATCHES))
    }
    fun recordWatchMetadata(item: BrowseVideo, description: String) {
        recordMetadata(item, description, 2.5)
    }
    fun recordSave(item: BrowseVideo, description: String = "") {
        recordVideo(item, 6.5)
        recordMetadata(item, description, 4.5)
    }
    fun recordSaveMetadata(item: BrowseVideo, description: String) {
        recordMetadata(item, description, 4.5)
    }
    fun recordDownload(item: BrowseVideo, description: String = "") {
        recordVideo(item, 7.5)
        recordMetadata(item, description, 5.0)
    }
    fun recordDownloadMetadata(item: BrowseVideo, description: String) {
        recordMetadata(item, description, 5.0)
    }
    fun recordDownload(media: AnalyzedMedia) {
        addPhrase("${media.title} ${media.uploader}", 7.0)
        addTopic(compactTopic(media.title), 7.5)
    }

    fun recordPlaylistSave(item: BrowseVideo) {
        // Playlist additions are an intentional long-term preference signal: stronger than
        // a single watch, but weaker than a download so one playlist cannot dominate Home.
        recordVideo(item, 5.5)
        addTopic(compactTopic(item.title), 5.0)
    }

    fun markShortSeen(item: BrowseVideo) {
        val seen = seenShorts().toMutableList()
        seen.remove(item.url)
        seen.add(0, item.url)
        writeStringArray(KEY_SEEN_SHORTS, seen.take(MAX_SEEN_SHORTS))
        recordWatch(item)
    }

    fun notInterested(item: BrowseVideo) {
        addPhrase("${item.title} ${item.uploader}", -9.0)
        addTopic(compactTopic(item.title), -11.0)
        val hidden = hiddenUrls().toMutableSet().apply { add(item.url) }
        writeStringArray(KEY_HIDDEN_URLS, hidden.take(MAX_HIDDEN).toList())
    }

    fun blockChannel(item: BrowseVideo) {
        val channel = normalizeChannel(item.uploader)
        if (channel.isNotBlank()) {
            val blocked = blockedChannels().toMutableSet().apply { add(channel) }
            writeStringArray(KEY_BLOCKED_CHANNELS, blocked.take(MAX_BLOCKED_CHANNELS).toList())
        }
        notInterested(item)
    }

    fun homeQueries(): List<String> {
        val random = Random(System.nanoTime())
        val topics = topicScores().entries
            .filter { it.value > 1.0 && KID_TERMS.none { kid -> it.key.contains(kid) } }
            .sortedByDescending { it.value }
            .map { it.key }
            .take(14)
        val interests = scores().entries
            .filter { it.value > 1.2 && it.key !in KID_TERMS }
            .sortedByDescending { it.value }
            .map { it.key }
            .take(18)
        val focused = buildList {
            addAll(topics.take(8))
            addAll(interests.chunked(2).take(4).map { it.joinToString(" ") })
            topics.take(5).zip(interests.shuffled(random).take(5)).forEach { (topic, term) -> add("$topic $term") }
        }
        // Exploration is intentionally adult/general-interest and small. The majority of
        // the feed comes from the user's own watch/save/download/playlist topic model.
        val exploration = listOf(
            "technology deep dive", "gaming longplay review", "music live performance",
            "cars engineering review", "science documentary", "engineering explained",
            "film analysis essay", "podcast interview", "travel documentary", "photography tutorial"
        )
        return (focused.shuffled(random).take(14) + exploration.shuffled(random).take(2))
            .filter(String::isNotBlank).distinct().take(16)
    }

    fun relatedQueries(
        item: BrowseVideo,
        description: String = "",
        playlistName: String = "",
        playlistItems: List<BrowseVideo> = emptyList()
    ): List<String> {
        val random = Random(System.nanoTime())
        val titleTerms = keywords(item.title).filterNot { it in KID_TERMS }.take(7)
        val tags = extractHashtags(description).filterNot { it in KID_TERMS }.take(6)
        val playlistTerms = keywords(playlistName + " " + playlistItems.takeLast(8).joinToString(" ") { it.title })
            .filterNot { it in KID_TERMS }.take(7)
        val profileTopics = topicScores().entries.filter { it.value > 1.0 }.sortedByDescending { it.value }.map { it.key }.take(6)
        val queries = mutableListOf<String>()
        if (item.uploader.isNotBlank()) queries += "${item.uploader} ${titleTerms.take(4).joinToString(" ")}".trim()
        if (titleTerms.isNotEmpty()) queries += titleTerms.joinToString(" ")
        if (tags.isNotEmpty()) queries += (titleTerms.take(3) + tags.take(4)).distinct().joinToString(" ")
        if (playlistTerms.isNotEmpty()) {
            queries += playlistTerms.take(6).joinToString(" ")
            queries += (playlistTerms.take(4) + titleTerms.take(3)).distinct().joinToString(" ")
        }
        profileTopics.shuffled(random).take(3).forEach { queries += "$it ${titleTerms.take(2).joinToString(" ")}".trim() }
        return queries.filter(String::isNotBlank).distinct().take(10)
    }

    fun shortsQueries(): List<String> {
        val random = Random(System.nanoTime())
        val interests = scores().entries
            .filter { it.value > 0.6 }
            .sortedByDescending { it.value }
            .map { it.key }
            .take(12)
            .shuffled(random)

        // Keep personalization, but always reserve exploration slots so one interest/channel
        // cannot monopolize Shorts. Query order changes on every refresh/load generation.
        val exploration = listOf(
            "travel shorts", "food shorts", "sports shorts", "science shorts",
            "creative filmmaking shorts", "diy shorts", "cars shorts", "technology shorts",
            "gaming shorts", "music performance shorts", "movie analysis shorts",
            "photography shorts", "nature documentary shorts", "engineering shorts"
        ).shuffled(random)
        val personalized = interests.take(6).map { "$it #shorts" }
        return (personalized + exploration.take(7))
            .distinct()
            .shuffled(random)
            .take(12)
    }

    fun filterFeed(items: List<BrowseVideo>, shorts: Boolean): List<BrowseVideo> {
        val blocked = blockedChannels()
        val hidden = hiddenUrls()
        val seen = if (shorts) seenShorts().toSet() else emptySet()
        val recentWatchKeys = if (shorts) emptySet() else recentWatches().take(24).map { SavedVideoStore.canonicalKey(it) }.toSet()
        val scoreMap = scores()
        val candidates = items.asSequence()
            .filterNot { it.url in hidden }
            .filterNot { normalizeChannel(it.uploader) in blocked }
            .filterNot { shorts && it.url in seen }
            .filterNot { !shorts && SavedVideoStore.canonicalKey(it.url, it.id) in recentWatchKeys }
            .filterNot { looksKidFocused(it) }
            .map { item -> item to (affinity(item, scoreMap) - qualityPenalty(item)) }
            .filterNot { (_, affinity) -> affinity < -3.5 }
            .distinctBy { SavedVideoStore.canonicalKey(it.first.url, it.first.id) }
            .toList()

        if (!shorts) return diversifyLongForm(candidates)

        return diversifyShorts(candidates)
    }


    /**
     * Long-form ranking keeps relevance without allowing one creator/topic or recently
     * watched item to dominate the entire Home/related list.
     */
    private fun diversifyLongForm(scored: List<Pair<BrowseVideo, Double>>): List<BrowseVideo> {
        if (scored.isEmpty()) return emptyList()
        val remaining = scored
            .distinctBy { SavedVideoStore.canonicalKey(it.first.url, it.first.id) }
            .toMutableList()
        val out = mutableListOf<BrowseVideo>()
        val channelCounts = mutableMapOf<String, Int>()
        val recentChannels = ArrayDeque<String>()
        val recentTopics = ArrayDeque<String>()

        while (remaining.isNotEmpty()) {
            val earlyFeed = out.size < 24
            val candidate = remaining
                .asSequence()
                .filter { (video, _) ->
                    val channel = video.uploader.trim().lowercase()
                    !earlyFeed || channel.isBlank() || (channelCounts[channel] ?: 0) < 2
                }
                .maxByOrNull { (video, baseScore) ->
                    val channel = video.uploader.trim().lowercase()
                    val topic = topicSignature(video)
                    var adjusted = baseScore
                    val channelCount = channelCounts[channel] ?: 0
                    adjusted -= channelCount * 7.5
                    if (channel.isNotBlank() && recentChannels.contains(channel)) adjusted -= 10.0
                    if (topic.isNotBlank() && recentTopics.contains(topic)) adjusted -= 8.0
                    adjusted
                } ?: remaining.maxBy { it.second }

            remaining.remove(candidate)
            val video = candidate.first
            out += video
            val channel = video.uploader.trim().lowercase()
            if (channel.isNotBlank()) {
                channelCounts[channel] = (channelCounts[channel] ?: 0) + 1
                recentChannels.addLast(channel)
                while (recentChannels.size > 6) recentChannels.removeFirst()
            }
            val topic = topicSignature(video)
            if (topic.isNotBlank()) {
                recentTopics.addLast(topic)
                while (recentTopics.size > 5) recentTopics.removeFirst()
            }
        }
        return out
    }
    /**
     * Shorts should feel personalized without becoming a loop of one creator/topic.
     * Affinity remains a signal, but random exploration and a channel-repeat penalty
     * deliberately break up clusters from the same uploader.
     */
    private fun diversifyShorts(candidates: List<Pair<BrowseVideo, Double>>): List<BrowseVideo> {
        if (candidates.isEmpty()) return emptyList()
        val random = Random(System.nanoTime())
        val pool = candidates
            .map { (item, affinity) ->
                // Cap the personalized advantage and add a meaningful exploration jitter.
                val score = affinity.coerceIn(-3.5, 8.0) + random.nextDouble(-4.25, 4.25)
                Triple(item, affinity, score)
            }
            .shuffled(random)
            .toMutableList()

        val result = mutableListOf<BrowseVideo>()
        val channelCounts = mutableMapOf<String, Int>()
        val recentChannels = ArrayDeque<String>()

        while (pool.isNotEmpty()) {
            val bestIndex = pool.indices.maxByOrNull { index ->
                val (item, _, baseScore) = pool[index]
                val channel = normalizeChannel(item.uploader)
                val repeats = channelCounts[channel] ?: 0
                val recentPenalty = if (channel.isNotBlank() && channel in recentChannels) 5.5 else 0.0
                val repeatPenalty = repeats * 6.0
                baseScore - recentPenalty - repeatPenalty + random.nextDouble(-1.0, 1.0)
            } ?: 0

            val (item, _, _) = pool.removeAt(bestIndex)
            val channel = normalizeChannel(item.uploader)
            result += item
            if (channel.isNotBlank()) {
                channelCounts[channel] = (channelCounts[channel] ?: 0) + 1
                recentChannels.addLast(channel)
                while (recentChannels.size > 5) recentChannels.removeFirst()
            }
        }
        return result
    }

    private fun recordVideo(item: BrowseVideo, weight: Double) {
        addPhrase(item.title, weight)
        if (item.uploader.isNotBlank()) addPhrase(item.uploader, weight * 0.55)
        addTopic(compactTopic(item.title), weight * 1.15)
    }

    private fun recordMetadata(item: BrowseVideo, description: String, weight: Double) {
        if (description.isBlank()) return
        val tags = extractHashtags(description).filterNot { it in KID_TERMS }.take(8)
        if (tags.isNotEmpty()) {
            addPhrase(tags.joinToString(" "), weight * 1.35)
            addTopic(tags.take(4).joinToString(" "), weight * 1.5)
        }
        // A small amount of description context helps when creators do not use hashtags.
        val contextTerms = keywords(description).filterNot { it in KID_TERMS }.take(10)
        if (contextTerms.isNotEmpty()) addPhrase(contextTerms.joinToString(" "), weight * 0.35)
        if (item.uploader.isNotBlank()) addTopic("${item.uploader} ${compactTopic(item.title)}", weight * 0.55)
    }

    private fun addTopic(topic: String, weight: Double) {
        val clean = topic.trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")
        if (clean.length < 3) return
        val current = topicScores().toMutableMap()
        current[clean] = ((current[clean] ?: 0.0) * 0.985 + weight).coerceIn(-30.0, 80.0)
        val compact = current.entries
            .filter { kotlin.math.abs(it.value) >= 0.2 }
            .sortedByDescending { kotlin.math.abs(it.value) }
            .take(MAX_TOPICS)
            .associate { it.key to it.value }
        val json = JSONObject()
        compact.forEach { (key, value) -> json.put(key, value) }
        prefs.edit().putString(KEY_TOPIC_SCORES, json.toString()).apply()
    }

    private fun topicScores(): Map<String, Double> = runCatching {
        val json = JSONObject(prefs.getString(KEY_TOPIC_SCORES, "{}").orEmpty())
        buildMap {
            val keys = json.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                put(key, json.optDouble(key, 0.0))
            }
        }
    }.getOrDefault(emptyMap())

    private fun compactTopic(text: String): String = keywords(text)
        .filterNot { it in KID_TERMS }
        .take(5)
        .joinToString(" ")

    /**
     * Stable lightweight topic bucket used only for feed diversification.
     * Keep it independent from uploader because creator repetition is penalized separately.
     */
    private fun topicSignature(item: BrowseVideo): String = keywords(item.title)
        .filterNot { it in KID_TERMS }
        .take(3)
        .sorted()
        .joinToString("|")

    private fun extractHashtags(text: String): List<String> = Regex("#[\\p{L}\\p{N}_]{2,}")
        .findAll(text)
        .map { it.value.removePrefix("#").lowercase(Locale.ROOT).replace('_', ' ') }
        .filter { it.isNotBlank() }
        .distinct()
        .toList()

    private fun looksKidFocused(item: BrowseVideo): Boolean {
        val text = "${item.title} ${item.uploader}".lowercase(Locale.ROOT)
        return KID_PHRASES.any { text.contains(it) }
    }

    private fun qualityPenalty(item: BrowseVideo): Double {
        val title = item.title.trim()
        var penalty = 0.0
        if (item.uploader.isBlank()) penalty += 1.5
        val letters = title.count(Char::isLetter)
        val upper = title.count(Char::isUpperCase)
        if (letters >= 12 && upper.toDouble() / letters.coerceAtLeast(1) > 0.72) penalty += 1.8
        if (Regex("[!?]{3,}").containsMatchIn(title)) penalty += 1.2
        if (LOW_QUALITY_PHRASES.any { title.lowercase(Locale.ROOT).contains(it) }) penalty += 2.5
        return penalty
    }

    private fun addPhrase(phrase: String, weight: Double) {
        val tokens = keywords(phrase)
        if (tokens.isEmpty()) return
        val current = scores().toMutableMap()
        tokens.take(8).forEachIndexed { index, token ->
            val adjusted = weight * (1.0 - index.coerceAtMost(5) * 0.07)
            current[token] = (current[token] ?: 0.0) * 0.985 + adjusted
        }
        val compact = current.entries
            .filter { kotlin.math.abs(it.value) >= 0.15 }
            .sortedByDescending { kotlin.math.abs(it.value) }
            .take(MAX_TERMS)
            .associate { it.key to it.value.coerceIn(-40.0, 60.0) }
        writeScores(compact)
    }

    private fun affinity(item: BrowseVideo, scoreMap: Map<String, Double>): Double {
        val terms = keywords("${item.title} ${item.uploader}")
        if (terms.isEmpty()) return 0.0
        val tokenScore = terms.sumOf { scoreMap[it] ?: 0.0 } / max(terms.size, 1)
        val normalized = terms.toSet()
        val topicBonus = topicScores().entries
            .filter { it.value > 0.5 }
            .take(30)
            .sumOf { (topic, value) ->
                val overlap = keywords(topic).count { it in normalized }
                if (overlap >= 2) value.coerceAtMost(18.0) * (overlap / 6.0) else 0.0
            }
        return tokenScore + topicBonus.coerceAtMost(8.0)
    }

    private fun keywords(text: String): List<String> = text
        .lowercase(Locale.ROOT)
        .replace(Regex("https?://\\S+"), " ")
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .split(Regex("\\s+"))
        .map { it.trim() }
        .filter { it.length >= 3 && it !in STOP_WORDS && it.none(Char::isWhitespace) }
        .distinct()

    private fun scores(): Map<String, Double> = runCatching {
        val raw = prefs.getString(KEY_SCORES, "{}").orEmpty()
        val json = JSONObject(raw)
        buildMap {
            val keys = json.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                put(key, json.optDouble(key, 0.0))
            }
        }
    }.getOrDefault(emptyMap())

    private fun writeScores(map: Map<String, Double>) {
        val json = JSONObject()
        map.forEach { (key, value) -> json.put(key, value) }
        prefs.edit().putString(KEY_SCORES, json.toString()).apply()
    }

    private fun seenShorts(): List<String> = readStringArray(KEY_SEEN_SHORTS)
    private fun recentWatches(): List<String> = readStringArray(KEY_RECENT_WATCHES)
    private fun hiddenUrls(): Set<String> = readStringArray(KEY_HIDDEN_URLS).toSet()
    private fun blockedChannels(): Set<String> = readStringArray(KEY_BLOCKED_CHANNELS).map(::normalizeChannel).filter(String::isNotBlank).toSet()

    private fun readStringArray(key: String): List<String> = runCatching {
        val array = JSONArray(prefs.getString(key, "[]").orEmpty())
        (0 until array.length()).mapNotNull { array.optString(it).takeIf(String::isNotBlank) }
    }.getOrDefault(emptyList())

    private fun writeStringArray(key: String, values: List<String>) {
        prefs.edit().putString(key, JSONArray(values).toString()).apply()
    }

    private fun normalizeChannel(value: String): String = value.trim().lowercase(Locale.ROOT)

    companion object {
        private const val KEY_SCORES = "interest_scores"
        private const val KEY_TOPIC_SCORES = "topic_scores"
        private const val KEY_SEEN_SHORTS = "seen_shorts"
        private const val KEY_RECENT_WATCHES = "recent_watches"
        private const val KEY_HIDDEN_URLS = "hidden_urls"
        private const val KEY_BLOCKED_CHANNELS = "blocked_channels"
        private const val MAX_TERMS = 160
        private const val MAX_TOPICS = 80
        private const val MAX_SEEN_SHORTS = 600
        private const val MAX_RECENT_WATCHES = 120
        private const val MAX_HIDDEN = 300
        private const val MAX_BLOCKED_CHANNELS = 200

        private val KID_TERMS = setOf("kids", "kid", "baby", "nursery", "toddler", "children", "toys", "cocomelon")
        private val KID_PHRASES = setOf(
            "for kids", "kids video", "kids songs", "nursery rhyme", "nursery rhymes",
            "baby songs", "learn colors", "learning colors", "toy unboxing", "toys for kids",
            "toddler", "cocomelon", "pinkfong", "baby shark", "children songs",
            "kids diana show", "ryan's world", "vlad and niki", "like nastya",
            "chuchu tv", "little angel", "super simple songs", "babybus"
        )
        private val LOW_QUALITY_PHRASES = setOf(
            "brainrot compilation", "skibidi compilation", "elsagate", "clickbait"
        )

        private val STOP_WORDS = setOf(
            "the", "and", "for", "with", "from", "this", "that", "you", "your", "are", "was", "were",
            "have", "has", "had", "video", "videos", "short", "shorts", "youtube", "official", "new", "full",
            "into", "about", "how", "why", "what", "when", "where", "who", "its", "our", "their", "not"
        )
    }
}
