package com.lightagent.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

class WeatherTool : Tool {

    private val client = OkHttpClient()

    override val name = "get_weather"
    override val description = "Query weather for a city using wttr.in. Parameters: city (string)"

    override suspend fun execute(params: JSONObject): String =
        withContext(Dispatchers.IO) {

            val city = params.optString("city", "").ifBlank {
                return@withContext "❌ 缺少 city 参数"
            }

            val url = "https://wttr.in/$city?format=j1&lang=zh"

            try {
                val request = Request.Builder().url(url).build()
                val response = client.newCall(request).execute()
                val body = response.body?.string()
                    ?: return@withContext "❌ 获取天气失败"

                val json    = JSONObject(body)
                val current = json.getJSONArray("current_condition")
                    .getJSONObject(0)

                val temp    = current.getString("temp_C")
                val desc    = current.getJSONArray("weatherDesc")
                    .getJSONObject(0)
                    .getString("value")

                "✅ $city 当前天气：$desc，${temp}°C"

            } catch (e: Exception) {
                "❌ 天气查询失败：${e.message}"
            }
        }
}
