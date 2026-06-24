package com.lightagent.tools

import android.content.Context
import com.lightagent.memory.ReminderRepository
import com.lightagent.notification.ReminderScheduler
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

class ReminderTool(
    private val context: Context,
    private val repository: ReminderRepository
) : Tool {

    override val name = "add_reminder"
    override val description = """
        Add a reminder that will trigger a system notification at a specified time.
        Use this when the user asks to be reminded about something.
        Parameters:
          - title (string, required): short reminder title
          - note (string, optional): extra detail
          - datetime (string, required): date and time in format "yyyy-MM-dd HH:mm"
    """.trimIndent()

    override suspend fun execute(params: JSONObject): String {
        val title    = params.optString("title", "").ifBlank { return "Error: title is required" }
        val note     = params.optString("note", "")
        val datetime = params.optString("datetime", "").ifBlank { return "Error: datetime is required" }

        val triggerAt = try {
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
            sdf.parse(datetime)?.time ?: return "Error: invalid datetime format, use yyyy-MM-dd HH:mm"
        } catch (e: Exception) {
            return "Error: invalid datetime format, use yyyy-MM-dd HH:mm"
        }

        if (triggerAt <= System.currentTimeMillis()) {
            return "Error: reminder time must be in the future"
        }

        val reminder = repository.addReminder(title, note, triggerAt)
        ReminderScheduler.schedule(context, reminder)

        return "Reminder set: \"$title\" at $datetime"
    }
}
