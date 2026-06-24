package com.lightagent.memory

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import java.util.UUID

// ─── Entity ───────────────────────────────────────────────────────────────────

@Entity(tableName = "reminders")
data class ReminderEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val title: String,
    val note: String = "",
    val triggerAt: Long,        // 触发时间（毫秒时间戳）
    val isCompleted: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)

// ─── DAO ──────────────────────────────────────────────────────────────────────

@Dao
interface ReminderDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertReminder(reminder: ReminderEntity)

    @Query("UPDATE reminders SET isCompleted = :done WHERE id = :id")
    suspend fun markDone(id: String, done: Boolean = true)

    @Query("DELETE FROM reminders WHERE id = :id")
    suspend fun deleteReminder(id: String)

    @Query("SELECT * FROM reminders ORDER BY triggerAt ASC")
    fun getAllReminders(): Flow<List<ReminderEntity>>

    @Query("SELECT * FROM reminders WHERE id = :id LIMIT 1")
    suspend fun getReminderById(id: String): ReminderEntity?
}

// ─── Repository ───────────────────────────────────────────────────────────────

class ReminderRepository(private val dao: ReminderDao) {

    val allReminders: Flow<List<ReminderEntity>> = dao.getAllReminders()

    suspend fun addReminder(title: String, note: String = "", triggerAt: Long): ReminderEntity {
        val reminder = ReminderEntity(
            title = title,
            note = note,
            triggerAt = triggerAt
        )
        dao.insertReminder(reminder)
        return reminder
    }

    suspend fun markDone(id: String) = dao.markDone(id)
    suspend fun markDone(id: String, done: Boolean) = dao.markDone(id, done)

    suspend fun deleteReminder(id: String) = dao.deleteReminder(id)

    suspend fun getReminderById(id: String) = dao.getReminderById(id)
}
