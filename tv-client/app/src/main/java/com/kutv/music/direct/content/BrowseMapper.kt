package com.kutv.music.direct.content

import com.kutv.music.core.model.ContentCard
import com.kutv.music.core.model.ContentKind
import com.kutv.music.core.model.PagedResult
import com.kutv.music.core.model.RankingSummary
import com.kutv.music.core.model.Track
import com.kutv.music.direct.KugouTrackMapper
import org.json.JSONArray
import org.json.JSONObject

/**
 * Pure JSONObject -> DTO mapping for the browse surface. Every parser fails
 * closed: malformed or unexpected payloads collapse to empty lists rather
 * than leaking sensitive fields or throwing into the UI layer.
 */
object BrowseMapper {
    private const val PAGE_SIZE = 30

    fun cardsFromTracks(value: Any?): List<Track> = when (value) {
        is JSONObject -> KugouTrackMapper.extractTracks(value)
        is JSONArray -> buildList {
            repeat(value.length()) { index ->
                value.optJSONObject(index)?.let(KugouTrackMapper::map)?.let(::add)
            }
        }
        else -> emptyList()
    }

    fun trackPage(json: JSONObject, requestedPage: Int? = null): PagedResult<Track> {
        val data = json.optJSONObject("data") ?: json
        val items = cardsFromTracks(json)
        // The live rank/audio response omits page. Carry the request cursor
        // through instead of advertising page 2 again on every full page.
        val page = requestedPage?.coerceAtLeast(1) ?: optIntOrNull(data, "page")?.coerceAtLeast(1) ?: 1
        val total = optIntOrNull(data, "total")
        return PagedResult(
            items = items,
            nextCursor = nextCursor(items.size, page, total),
            total = total,
        )
    }

    fun cardPage(json: JSONObject, kind: ContentKind, idPrefix: String): PagedResult<ContentCard> {
        // newsong_publish returns data as an array, unlike search responses.
        val data = json.optJSONObject("data")
            ?: json.optJSONArray("data")?.let { JSONObject().put("list", it) }
            ?: json
        val items = when (kind) {
            ContentKind.SONG -> songCards(data, idPrefix)
            ContentKind.PLAYLIST -> playlistCards(data, idPrefix)
            ContentKind.ALBUM -> albumCards(data, idPrefix)
            ContentKind.ARTIST -> artistCards(data, idPrefix)
            ContentKind.MV -> mvCards(data, idPrefix)
            ContentKind.USER -> emptyList()
        }
        val page = optIntOrNull(data, "page")?.coerceAtLeast(1) ?: 1
        val total = optIntOrNull(data, "total")
        return PagedResult(
            items = items,
            nextCursor = nextCursor(items.size, page, total),
            total = total,
        )
    }

    fun rankings(json: JSONObject): List<RankingSummary> {
        val data = json.optJSONObject("data")
        val arrays = buildList {
            data?.let { parent ->
                listOf("list", "rank", "ranks", "info").forEach { key ->
                    parent.optJSONArray(key)?.let(::add)
                }
            }
            json.optJSONArray("data")?.let(::add)
        }
        val candidates = arrays.firstOrNull { it.length() > 0 } ?: return emptyList()
        return buildList {
            repeat(candidates.length()) { index ->
                candidates.optJSONObject(index)?.let(::mapRanking)?.let(::add)
            }
        }
    }

    fun hotWords(json: JSONObject): List<String> {
        val data = json.optJSONObject("data") ?: return emptyList()
        val result = mutableListOf<String>()
        val tabs = data.optJSONArray("hot_tab") ?: data.optJSONArray("tab") ?: data.optJSONArray("list")
        tabs?.let { array ->
            repeat(array.length()) { index ->
                val tab = array.optJSONObject(index) ?: return@repeat
                val words = tab.optJSONArray("words") ?: tab.optJSONArray("word") ?: tab.optJSONArray("keywords")
                words?.let { wordArray ->
                    repeat(wordArray.length()) { wordIndex ->
                        wordArray.optJSONObject(wordIndex)?.let { item ->
                            firstString(item, "word", "name", "keyword")?.let(result::add)
                        }
                    }
                }
            }
        }
        val directWords = data.optJSONArray("words") ?: data.optJSONArray("hot_words")
        directWords?.let { array ->
            repeat(array.length()) { index ->
                val item = array.optJSONObject(index)
                if (item != null) {
                    firstString(item, "word", "name", "keyword")?.let(result::add)
                } else {
                    array.optString(index).takeIf(String::isNotBlank)?.let(result::add)
                }
            }
        }
        return result
    }

    private fun songCards(data: JSONObject, idPrefix: String): List<ContentCard> =
        cardsFromContainers(containersOf(data, "song_list", "songs", "info", "list", "lists", "audios")) {
            mapSongCard(it, idPrefix)
        }

    private fun playlistCards(data: JSONObject, idPrefix: String): List<ContentCard> =
        cardsFromContainers(containersOf(data, "special_list", "lists", "items", "list")) { mapPlaylistCard(it, idPrefix) }

    private fun albumCards(data: JSONObject, idPrefix: String): List<ContentCard> {
        // Echo extractAlbumGroups: language keys (or numeric aliases), possibly
        // wrapping list/info/data. Combine groups rather than taking only one.
        val grouped = listOf(listOf("chn", "1"), listOf("eur", "2"), listOf("jpn", "3"), listOf("kor", "4"))
            .flatMap { aliases ->
                val array = aliases.asSequence().mapNotNull { key ->
                    data.optJSONArray(key) ?: data.optJSONObject(key)?.let { group ->
                        group.optJSONArray("list") ?: group.optJSONArray("info") ?: group.optJSONArray("data")
                    }
                }.firstOrNull { it.length() > 0 }
                cardsFromContainers(listOfNotNull(array)) { mapAlbumCard(it, idPrefix) }
            }
        return grouped.ifEmpty {
            cardsFromContainers(containersOf(data, "list", "lists", "albums")) { mapAlbumCard(it, idPrefix) }
        }
    }

    private fun artistCards(data: JSONObject, idPrefix: String): List<ContentCard> {
        val groups = data.optJSONArray("info")
        val grouped = buildList {
            if (groups != null) repeat(groups.length()) { index ->
                val singers = groups.optJSONObject(index)?.optJSONArray("singer") ?: return@repeat
                addAll(cardsFromContainers(listOf(singers)) { mapArtistCard(it, idPrefix) })
            }
        }
        return grouped.ifEmpty {
            cardsFromContainers(containersOf(data, "lists", "authors", "info", "singers")) { mapArtistCard(it, idPrefix) }
        }.distinctBy { it.id }
    }

    private fun mvCards(data: JSONObject, idPrefix: String): List<ContentCard> =
        cardsFromContainers(containersOf(data, "lists", "videos", "mv")) { mapMvCard(it, idPrefix) }

    private fun mapSongCard(item: JSONObject, idPrefix: String): ContentCard? {
        val track = KugouTrackMapper.map(item)
        val rawId = track?.id ?: firstString(item, "album_audio_id", "mixsongid", "song_id", "audio_id", "id")
            ?: firstString(item, "hash")
            ?: return null
        return ContentCard(
            id = idPrefix + rawId,
            title = track?.title ?: firstString(item, "songname", "title", "filename", "name").orEmpty(),
            kind = ContentKind.SONG,
            subtitle = track?.artists?.joinToString(" / ") ?: firstString(item, "singername", "author_name", "singer").orEmpty(),
            artworkUrl = track?.artworkUri ?: normalizeImage(firstString(item, "img", "album_img", "pic", "cover")),
            badge = songBadge(item),
        )
    }

    private fun mapPlaylistCard(item: JSONObject, idPrefix: String): ContentCard? {
        // Public recommendation cards carry both a legacy numeric specialid and
        // the collection id required by the public detail/track endpoints.
        val rawId = firstString(item, "global_collection_id", "gid", "specialid", "listid", "special_id", "id")
            ?: return null
        return ContentCard(
            id = idPrefix + rawId,
            title = firstString(item, "name", "name_desc", "specialname", "title").orEmpty(),
            kind = ContentKind.PLAYLIST,
            subtitle = firstString(item, "intro", "desc", "author").orEmpty(),
            // The same live rows offer an imge static pic alongside a flexible
            // c1.kgimg cover. Prefer the static TV-safe variant, not an empty
            // projection caused by selecting a CDN outside the image policy.
            artworkUrl = normalizeImage(firstString(item, "pic", "img", "imgurl", "cover", "flexible_cover", "image_url")),
        )
    }

    private fun mapAlbumCard(item: JSONObject, idPrefix: String): ContentCard? {
        val rawId = firstString(item, "album_id", "albumid", "AlbumId", "AlbumID", "id") ?: return null
        return ContentCard(
            id = idPrefix + rawId,
            title = firstString(item, "album_name", "albumname", "AlbumName", "album", "name").orEmpty(),
            kind = ContentKind.ALBUM,
            subtitle = firstString(item, "author_name", "singer", "singername", "SingerName").orEmpty(),
            artworkUrl = normalizeImage(firstString(item, "sizable_cover", "img", "imgurl", "pic", "cover", "Image")),
        )
    }

    private fun mapArtistCard(item: JSONObject, idPrefix: String): ContentCard? {
        val rawId = firstString(item, "author_id", "AuthorId", "singer_id", "singerid", "id") ?: return null
        return ContentCard(
            id = idPrefix + rawId,
            title = firstString(item, "author_name", "AuthorName", "singername", "name").orEmpty(),
            kind = ContentKind.ARTIST,
            subtitle = "",
            artworkUrl = normalizeImage(firstString(item, "Avatar", "imgurl", "sizable_avatar", "pic", "img", "avatar", "image", "singer_img", "cover", "singerimg")),
        )
    }

    private fun mapMvCard(item: JSONObject, idPrefix: String): ContentCard? {
        val rawId = firstString(item, "video_id", "mv_id", "id") ?: return null
        return ContentCard(
            id = idPrefix + rawId,
            title = firstString(item, "name", "title", "filename").orEmpty(),
            kind = ContentKind.MV,
            subtitle = firstString(item, "author", "singername", "singer").orEmpty(),
            artworkUrl = normalizeImage(firstString(item, "img", "pic", "cover")),
        )
    }

    private fun mapRanking(item: JSONObject): RankingSummary? {
        val id = firstString(item, "rankid", "rank_id", "id") ?: return null
        return RankingSummary(
            id = id,
            title = firstString(item, "rankname", "rank_name", "name").orEmpty(),
            artworkUrl = normalizeImage(firstString(item, "imgurl", "img", "img_cover", "banner_9", "banner", "pic", "bannerurl")),
        )
    }

    private fun songBadge(item: JSONObject): String? {
        val qualities = item.optJSONArray("qualities")?.stringValues().orEmpty()
        val lossless = qualities.any { it == "flac" || it == "hires" }
        return when {
            isVip(item) -> "VIP"
            lossless -> "无损"
            else -> null
        }
    }

    private fun containersOf(data: JSONObject, vararg keys: String): List<JSONArray> = buildList {
        keys.forEach { key -> data.optJSONArray(key)?.let(::add) }
    }

    private fun cardsFromContainers(
        containers: List<JSONArray>,
        map: (JSONObject) -> ContentCard?,
    ): List<ContentCard> {
        for (array in containers) {
            val cards = buildList {
                repeat(array.length()) { index ->
                    array.optJSONObject(index)?.let(map)?.let(::add)
                }
            }
            if (cards.isNotEmpty()) return cards
        }
        return emptyList()
    }

    private fun nextCursor(count: Int, page: Int, total: Int?): String? =
        if (count >= PAGE_SIZE && (total == null || page * PAGE_SIZE < total)) (page + 1).toString() else null

    private fun optIntOrNull(obj: JSONObject, vararg keys: String): Int? {
        for (key in keys) {
            when (val value = obj.opt(key)) {
                is Number -> return value.toInt()
                is String -> value.toIntOrNull()?.let { return it }
            }
        }
        return null
    }

    private fun firstString(obj: JSONObject, vararg keys: String): String? {
        for (key in keys) {
            val value = obj.opt(key)
            if (value is String && value.isNotBlank()) return value
            if (value is Number) return value.toString()
        }
        return null
    }

    private fun normalizeImage(url: String?): String = url.orEmpty()
        .replace("{size}", "400")
        .let { if (it.startsWith("http://")) "https://${it.removePrefix("http://")}" else it }

    private fun isVip(item: JSONObject): Boolean {
        if (item.optInt("vip_type", 0) > 0) return true
        return when (val value = item.opt("vip") ?: item.opt("is_vip")) {
            is Boolean -> value
            is Number -> value.toInt() != 0
            is String -> value == "1" || value.equals("true", ignoreCase = true)
            else -> false
        }
    }

    private fun JSONArray.stringValues(): List<String> = buildList {
        repeat(length()) { index -> optString(index).takeIf(String::isNotBlank)?.let(::add) }
    }
}
