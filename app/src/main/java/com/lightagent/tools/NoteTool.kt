package com.lightagent.tools

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class NoteTool(private val context: Context) : Tool {

    override val name = "save_note"
    override val description = "Save a text note. Parameters: content (string)"

    override suspend fun execute(params: JSONObject): String {
        val content = params.optString("content", "").ifBlank {
            return "❌ 缺少 content 参数"
        }

        val timestamp = SimpleDateFormat(
            "yyyy-MM-dd HH:mm",
            Locale.getDefault()
        ).format(Date())

        val file = File(context.filesDir, "notes.txt")
        file.appendText("[$timestamp] $content\n")

        return "✅ 已保存笔记：$content"
    }
}
