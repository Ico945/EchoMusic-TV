package com.kutv.music.direct.content

/** Fixed diagnostic codes; no upstream body or account data crosses the bridge. */
class LibraryReadException(val stage: Stage) : Exception(stage.name) {
    enum class Stage { RESPONSE_SHAPE, ROW_ID, UPSTREAM_STATUS }
}
