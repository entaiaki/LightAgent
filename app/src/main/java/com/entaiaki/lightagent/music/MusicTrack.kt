package com.entaiaki.lightagent.music

data class MusicTrack(
    val id: Long,
    val title: String,
    val artist: String,
    val uri: String,
    val duration: Long   // 毫秒
)
