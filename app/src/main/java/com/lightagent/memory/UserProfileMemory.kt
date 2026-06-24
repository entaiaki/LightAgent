package com.lightagent.memory

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// ═══════════════════════════════════════
// Entity
// ═══════════════════════════════════════

@Entity(tableName = "user_facts")
data class UserFact(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val key: String,
    val value: String,
    val timestamp: Long = System.currentTimeMillis()
)

// ═══════════════════════════════════════
// DAO
// ═══════════════════════════════════════

@Dao
interface UserFactDao {

    @Query("SELECT * FROM user_facts ORDER BY timestamp DESC")
    suspend fun getAll(): List<UserFact>

    @Query("SELECT * FROM user_facts WHERE `key` = :key LIMIT 1")
    suspend fun getByKey(key: String): UserFact?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(fact: UserFact)

    @Query("DELETE FROM user_facts WHERE `key` = :key")
    suspend fun deleteByKey(key: String)

    @Query("DELETE FROM user_facts")
    suspend fun clearAll()
}

// ═══════════════════════════════════════
// Database
// ═══════════════════════════════════════

@Database(
    entities = [
        UserFact::class,
        ConversationEntity::class,
        MessageEntity::class,
        ReminderEntity::class,
        MusicPreferenceEntity::class,
        PlayHistoryEntity::class
    ],
    version = 3
)
abstract class AgentDatabase : RoomDatabase() {
    abstract fun userFactDao(): UserFactDao
    abstract fun conversationDao(): ConversationDao
    abstract fun reminderDao(): ReminderDao
    abstract fun musicPreferenceDao(): MusicPreferenceDao

    companion object {
        @Volatile
        private var INSTANCE: AgentDatabase? = null

        fun getInstance(context: Context): AgentDatabase {
            return INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(
                    context.applicationContext,
                    AgentDatabase::class.java,
                    "agent_db"
                )
                    .fallbackToDestructiveMigration()
                    .build().also { INSTANCE = it }
            }
        }
    }
}

// ═══════════════════════════════════════
// Repository (对外接口)
// ═══════════════════════════════════════

class UserProfileMemory(context: Context) {

    private val dao = AgentDatabase.getInstance(context).userFactDao()

    suspend fun set(key: String, value: String) = withContext(Dispatchers.IO) {
        dao.insert(UserFact(key = key, value = value))
    }

    suspend fun get(key: String): String? = withContext(Dispatchers.IO) {
        dao.getByKey(key)?.value
    }

    suspend fun getAll(): List<UserFact> = withContext(Dispatchers.IO) {
        dao.getAll()
    }

    /**
     * 生成注入 prompt 的用户画像摘要
     */
    suspend fun buildProfileSummary(): String = withContext(Dispatchers.IO) {
        val facts = dao.getAll()
        if (facts.isEmpty()) return@withContext ""
        "用户信息：\n" + facts.joinToString("\n") { "- ${it.key}：${it.value}" }
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        dao.clearAll()
    }
}
