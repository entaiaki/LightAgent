package com.lightagent.memory

import androidx.room.*
import kotlinx.coroutines.flow.Flow

/**
 * 用户的音乐偏好记忆
 *
 * 天爱星通过分析用户的历史播放记录，
 * 在下次点歌时能推荐符合品味的歌曲。
 */
@Entity(tableName = "music_preferences")
data class MusicPreferenceEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val key: String,              // 偏好类型：favorite_genre / favorite_artist / recent_song
    val value: String,            // 偏好值
    val timestamp: Long = System.currentTimeMillis()
)

@Entity(tableName = "play_history")
data class PlayHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val title: String,
    val artist: String,
    val source: String,            // "LOCAL" / "NERI_PLAYER"
    val playedAt: Long = System.currentTimeMillis()
)

@Dao
interface MusicPreferenceDao {

    // ── 偏好 ────────────────────────────────────────────

    @Query("SELECT * FROM music_preferences WHERE `key` = :key ORDER BY timestamp DESC LIMIT 1")
    suspend fun getPreference(key: String): MusicPreferenceEntity?

    @Query("SELECT * FROM music_preferences WHERE `key` = :key ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getPreferences(key: String, limit: Int = 10): List<MusicPreferenceEntity>

    @Query("SELECT DISTINCT `value` FROM music_preferences WHERE `key` = 'favorite_artist' ORDER BY timestamp DESC LIMIT 5")
    suspend fun getFavoriteArtists(): List<String>

    @Query("SELECT DISTINCT `value` FROM music_preferences WHERE `key` = 'favorite_genre' ORDER BY timestamp DESC LIMIT 5")
    suspend fun getFavoriteGenres(): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun savePreference(pref: MusicPreferenceEntity)

    @Query("DELETE FROM music_preferences WHERE `key` = :key")
    suspend fun clearPreference(key: String)

    // ── 播放历史 ────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun recordPlay(history: PlayHistoryEntity)

    @Query("SELECT * FROM play_history ORDER BY playedAt DESC LIMIT :limit")
    suspend fun getRecentPlays(limit: Int = 50): List<PlayHistoryEntity>

    @Query("SELECT * FROM play_history ORDER BY playedAt DESC LIMIT :limit")
    fun getRecentPlaysFlow(limit: Int = 50): Flow<List<PlayHistoryEntity>>

    @Query("SELECT artist, COUNT(*) as cnt FROM play_history GROUP BY artist ORDER BY cnt DESC LIMIT 10")
    suspend fun getTopArtists(): List<ArtistCount>

    @Query("DELETE FROM play_history WHERE playedAt < :before")
    suspend fun cleanOldHistory(before: Long)

    data class ArtistCount(
        @ColumnInfo(name = "artist") val artist: String,
        @ColumnInfo(name = "cnt") val count: Int
    )
}
