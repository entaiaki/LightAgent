package com.lightagent.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lightagent.memory.AgentDatabase
import com.lightagent.memory.ReminderEntity
import com.lightagent.memory.ReminderRepository
import com.lightagent.notification.ReminderScheduler
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class ReminderViewModel(application: Application) : AndroidViewModel(application) {

    private val repo = ReminderRepository(
        AgentDatabase.getInstance(application).reminderDao()
    )

    val reminders: StateFlow<List<ReminderEntity>> = repo.allReminders
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun add(title: String, timeText: String) = viewModelScope.launch {
        val triggerAt = parseTimeInput(timeText)
        repo.addReminder(title = title, triggerAt = triggerAt)
    }

    fun toggleDone(entity: ReminderEntity) = viewModelScope.launch {
        repo.markDone(id = entity.id, done = !entity.isCompleted)
    }

    fun delete(entity: ReminderEntity) = viewModelScope.launch {
        repo.deleteReminder(entity.id)
        ReminderScheduler.cancel(getApplication(), entity.id)
    }

    // ── 简易时间解析：用户输入自由文本 → 毫秒时间戳 ────────────────────────
    private fun parseTimeInput(input: String): Long {
        if (input.isBlank()) return System.currentTimeMillis() + 3600_000
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        // 尝试标准格式
        sdf.parse(input)?.let { return it.time }
        // 尝试 "MM-dd HH:mm"
        val year = SimpleDateFormat("yyyy", Locale.getDefault()).format(Date())
        sdf.parse("$year-$input")?.let { return it.time }
        // 兜底
        return System.currentTimeMillis() + 3600_000
    }
}
