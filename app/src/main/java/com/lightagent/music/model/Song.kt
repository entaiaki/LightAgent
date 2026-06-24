package com.lightagent.music.model

import android.net.Uri
import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * 歌曲数据模型
 *
 * 来源可以是本地（MediaStore）或 NeriPlayer 远程音源
 */
@Parcelize
data class Song(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String = "",
    val duration: Long = 0L,         // 毫秒
    val uri: Uri = Uri.EMPTY,
    val albumArtUri: Uri? = null,
    val source: SongSource = SongSource.LOCAL
) : Parcelable

/**
 * 音源类型
 * LOCAL — 本地媒体库
 * NERI_PLAYER — 通过 NeriPlayer 远程获取
 */
enum class SongSource {
    LOCAL,
    NERI_PLAYER
}

/**
 * 将本地 MediaStore 相关字段聚合为一个 Song
 */
object SongFactory {
    fun fromLocal(
        id: Long,
        title: String,
        artist: String,
        album: String = "",
        duration: Long = 0L,
        uri: Uri = Uri.EMPTY,
        albumArtUri: Uri? = null
    ): Song = Song(
        id          = id,
        title       = title,
        artist      = artist,
        album       = album,
        duration    = duration,
        uri         = uri,
        albumArtUri = albumArtUri,
        source      = SongSource.LOCAL
    )

    fun fromNeriPlayer(
        title: String,
        artist: String,
        album: String = "",
        duration: Long = 0L,
        uri: Uri = Uri.EMPTY,
        albumArtUri: Uri? = null
    ): Song = Song(
        id          = title.hashCode().toLong(),
        title       = title,
        artist      = artist,
        album       = album,
        duration    = duration,
        uri         = uri,
        albumArtUri = albumArtUri,
        source      = SongSource.NERI_PLAYER
    )
}
