package com.kutv.music.data

import com.kutv.music.core.model.Capability
import com.kutv.music.core.model.HomeContent
import com.kutv.music.core.model.Shelf
import com.kutv.music.direct.KugouContentGateway

class DirectLoginRequiredException : Exception("登录后可使用此功能")

class KugouDirectMusicRepository(
    private val gateway: KugouContentGateway,
) : MusicRepository {
    override suspend fun home(): Result<HomeContent> =
        runCatching {
            val feed = gateway.home()
            val all = (listOfNotNull(feed.hero) + feed.daily + feed.newSongs + feed.history)
                .distinctBy { it.id }
            val hero = feed.hero ?: all.firstOrNull() ?: throw NoSuchElementException("酷狗暂无可用内容")
            val shelves = buildList {
                if (feed.newSongs.isNotEmpty()) add(Shelf("new", "酷狗新歌", feed.newSongs, Capability.AVAILABLE))
                if (feed.daily.isNotEmpty()) add(Shelf("daily", "每日推荐", feed.daily, Capability.AVAILABLE))
                if (feed.history.isNotEmpty()) add(Shelf("history", "最近播放", feed.history, Capability.AVAILABLE))
            }
            HomeContent(
                hero = hero,
                secondary = all.filterNot { it.id == hero.id }.take(2),
                shelves = shelves,
            )
        }

    override suspend fun newReleases() = runCatching { gateway.newReleases() }

    override suspend fun library() = runCatching { gateway.library() }

    override suspend fun search(query: String) = if (query.isBlank()) {
        Result.success(emptyList())
    } else {
        runCatching { gateway.search(query.trim()) }
    }

    override suspend fun lyrics(trackId: String) = runCatching { gateway.lyrics(trackId) }

}
