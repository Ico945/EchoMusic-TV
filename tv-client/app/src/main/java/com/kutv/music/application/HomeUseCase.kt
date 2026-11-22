package com.kutv.music.application

import com.kutv.music.core.model.HomeContent
import com.kutv.music.core.model.Shelf
import com.kutv.music.core.model.Track
import com.kutv.music.data.MusicRepository
import org.json.JSONArray
import org.json.JSONObject

class HomeUseCase(private val repository: MusicRepository) {
    suspend fun bootstrapHome(): JSONObject? = repository.bootstrapHome()?.let(::toDto)

    suspend fun home(): JSONObject = toDto(repository.home().getOrThrow())

    private fun toDto(content: HomeContent): JSONObject = JSONObject()
        .put("greeting", "晚上好")
        .put("hero", content.hero.toDto())
        .put("secondary", JSONArray(content.secondary.map { it.toDto() }))
        .put("shelves", JSONArray(content.shelves.map { it.toDto() }))

    private fun Shelf.toDto(): JSONObject = JSONObject()
        .put("id", id)
        .put("title", title)
        .put("capability", capability.name.lowercase())
        .put("tracks", JSONArray(tracks.map { it.toDto() }))

    private fun Track.toDto(): JSONObject = JSONObject()
        .put("id", id)
        .put("title", title)
        .put("artist", artists.joinToString(" / "))
        .put("album", album)
        .put("artwork", artworkUri)
        .put("durationMs", durationMs)
        .apply {
            mvId?.let { nativeMvId ->
                put(
                    "mv",
                    JSONObject()
                        .put("id", "mv:${nativeMvId.removePrefix("mv:")}")
                        .put("title", title)
                        .put("artist", artists.joinToString(" / "))
                        .put("quality", "hd")
                        .put("qualities", JSONArray(listOf("hd"))),
                )
            }
        }
}
