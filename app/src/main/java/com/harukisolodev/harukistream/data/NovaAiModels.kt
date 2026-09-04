package com.harukisolodev.harukistream.data

enum class NovaAiSearchMode {
    FAST, SMART, DEEP
}

enum class NovaAiMatchSource {
    HISTORY, SAVED, DOWNLOAD, LOCAL_PLAYLIST, YOUTUBE, YOUTUBE_PLAYLIST, YOUTUBE_CHANNEL
}

data class NovaAiMatch(
    val video: BrowseVideo,
    val confidence: Int,
    val reason: String,
    val source: NovaAiMatchSource,
    val matchedClues: List<String> = emptyList()
)

data class NovaAiState(
    val prompt: String = "",
    val mode: NovaAiSearchMode = NovaAiSearchMode.SMART,
    val loading: Boolean = false,
    val phase: String = "",
    val matches: List<NovaAiMatch> = emptyList(),
    val queriesTried: List<String> = emptyList(),
    val error: String = ""
)
