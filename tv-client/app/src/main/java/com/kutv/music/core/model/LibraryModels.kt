package com.kutv.music.core.model

/** Counts only. Never put account identifiers, names, credentials or raw responses here. */
data class LibrarySyncInfo(
    val page: Int,
    val received: Int,
    val playlists: Int,
    val created: Int,
    val collected: Int,
    val albums: Int,
    val missingCreator: Int,
)

data class LibrarySummary(
    val counts: Map<LibrarySection, Int>,
    val featured: List<ContentCard>,
)

data class FavoriteState(
    val target: MutationTarget,
    val favorite: Boolean,
)
