package com.kutv.music.direct.content

import com.kutv.music.core.model.CommentTarget
import com.kutv.music.core.model.CommentTargetKind
import com.kutv.music.direct.KugouRequest
import com.kutv.music.direct.KugouRequestExecutor
import org.json.JSONObject

/**
 * Direct KuGou comment-list endpoints. The reference implementation sends these
 * "params" as the URL query string (not the POST body), so every field below is
 * placed in [KugouRequest.query].
 */
class KugouCommentApi(private val executor: KugouRequestExecutor) {
    suspend fun comments(target: CommentTarget, page: Int): JSONObject {
        val query = linkedMapOf<String, Any?>(
            "need_show_image" to 1,
            "p" to page,
            "pagesize" to PAGE_SIZE,
            "show_classify" to 1,
            "show_hotword_list" to 1,
            "extdata" to "0",
        )
        val path = when (target.kind) {
            CommentTargetKind.SONG -> {
                query["mixsongid"] = target.id
                query["code"] = CODE_SONG
                "/mcomment/v1/cmtlist"
            }
            CommentTargetKind.PLAYLIST -> {
                query["childrenid"] = target.id
                query["content_type"] = 0
                query["tag"] = 5
                query["code"] = CODE_PLAYLIST
                "/m.comment.service/v1/cmtlist"
            }
            CommentTargetKind.ALBUM -> {
                query["childrenid"] = target.id
                query["content_type"] = 0
                query["tag"] = 5
                query["code"] = CODE_ALBUM
                "/m.comment.service/v1/cmtlist"
            }
        }
        return executor.execute(
            KugouRequest(
                method = "POST",
                baseUrl = GATEWAY,
                path = path,
                query = query,
            ),
        ).body
    }

    private companion object {
        const val GATEWAY = "https://gateway.kugou.com"
        const val PAGE_SIZE = 30
        const val CODE_SONG = "fc4be23b4e972707f36b8a828a93ba8a"
        const val CODE_PLAYLIST = "ca53b96fe5a1d9c22d71c8f522ef7c4f"
        const val CODE_ALBUM = "94f1792ced1df89aa68a7939eaf2efca"
    }
}
