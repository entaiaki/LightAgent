package com.lightagent.tools

import android.content.Context
import android.content.Intent
import org.json.JSONObject

class OpenAppTool(private val context: Context) : Tool {

    override val name = "open_app"
    override val description = "Open a mobile app by its package name. Parameters: app_name (string)"

    override suspend fun execute(params: JSONObject): String {
        val appName = params.optString("app_name", "").ifBlank {
            return "❌ 缺少 app_name 参数"
        }

        val intent = context.packageManager
            .getLaunchIntentForPackage(appName)

        return if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            "✅ 已打开 $appName"
        } else {
            "❌ 找不到应用：$appName"
        }
    }
}
