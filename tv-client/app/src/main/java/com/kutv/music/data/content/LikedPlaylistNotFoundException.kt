package com.kutv.music.data.content

/** Safe, actionable failure; never suggests that an unrecognized list is empty. */
class LikedPlaylistNotFoundException : Exception("未识别到喜欢歌单，可先从资料库打开对应歌单")
