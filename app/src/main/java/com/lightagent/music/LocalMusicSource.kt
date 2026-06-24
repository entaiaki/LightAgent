package com.lightagent.music

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import com.lightagent.music.model.Song
import com.lightagent.music.model.SongFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 本地音乐扫描器
 *
 * 通过 MediaStore 查询本地音频文件，返回 Song 列表。
 * 过滤短音频（< 30s）以排除录音/通知音等。
 */
class LocalMusicSource(private val context: Context) {

    companion object {
        /** 最短时长过滤（毫秒），低于此值视为非音乐 */
        private const val MIN_DURATION_MS = 30_000L

        /** 每次扫描上限，防止列表过长 */
        private const val MAX_RESULTS = 500
    }

    /**
     * 扫描本地所有音频文件
     *
     * @param query 可选的歌名/歌手搜索关键词（null 则获取全部）
     * @return 本地歌曲列表，按标题排序
     */
    suspend fun scan(query: String? = null): List<Song> =
        withContext(Dispatchers.IO) {
            val songs = mutableListOf<Song>()
            val resolver: ContentResolver = context.contentResolver

            val projection = arrayOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ALBUM,
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.DATA,
                MediaStore.Audio.Media.ALBUM_ID
            )

            val selection = buildString {
                append("${MediaStore.Audio.Media.IS_MUSIC} != 0")
                append(" AND ${MediaStore.Audio.Media.DURATION} >= $MIN_DURATION_MS")
                if (!query.isNullOrBlank()) {
                    append(" AND (${MediaStore.Audio.Media.TITLE} LIKE ? OR ${MediaStore.Audio.Media.ARTIST} LIKE ?)")
                }
            }

            val selectionArgs = if (!query.isNullOrBlank()) {
                arrayOf("%$query%", "%$query%")
            } else null

            val sortOrder = "${MediaStore.Audio.Media.TITLE} ASC"

            val cursor = resolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                sortOrder
            )

            cursor?.use { c ->
                val idCol       = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleCol    = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistCol   = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val albumCol    = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val durationCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val dataCol     = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)

                while (c.moveToNext() && songs.size < MAX_RESULTS) {
                    val id       = c.getLong(idCol)
                    val title    = c.getString(titleCol) ?: "未知歌曲"
                    val artist   = c.getString(artistCol) ?: "未知歌手"
                    val album    = c.getString(albumCol) ?: ""
                    val duration = c.getLong(durationCol)
                    val dataPath = c.getString(dataCol) ?: ""

                    val contentUri = Uri.withAppendedPath(
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                        id.toString()
                    )

                    val albumArtUri = Uri.parse(
                        "content://media/external/audio/albumart/$id"
                    ).let { uri ->
                        // 快速检查封面是否存在
                        try {
                            resolver.openInputStream(uri)?.close()
                            uri
                        } catch (_: Exception) { null }
                    }

                    songs.add(
                        SongFactory.fromLocal(
                            id          = id,
                            title       = title,
                            artist      = artist,
                            album       = album,
                            duration    = duration,
                            uri         = contentUri,
                            albumArtUri = albumArtUri
                        )
                    )
                }
            }

            songs
        }

    /**
     * 通过 ID 获取单个歌曲
     */
    suspend fun getSongById(id: Long): Song? {
        val all = scan()
        return all.find { it.id == id }
    }
}
