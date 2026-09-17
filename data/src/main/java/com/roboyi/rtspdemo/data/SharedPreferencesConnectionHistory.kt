package com.roboyi.rtspdemo.data

import android.content.SharedPreferences
import com.roboyi.rtspdemo.domain.ConnectionEntry
import com.roboyi.rtspdemo.domain.ConnectionHistoryRepository
import org.json.JSONArray
import org.json.JSONObject

/** 沿用现有 JSON 结构，不迁移或清空用户已有的连接记录。 */
class SharedPreferencesConnectionHistory(private val prefs: SharedPreferences) : ConnectionHistoryRepository {
    override fun entries(): List<ConnectionEntry> {
        val array = runCatching { JSONArray(prefs.getString(KEY, "[]")) }
            .getOrDefault(JSONArray())
        return (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val url = item.optString("url")
            if (url.isBlank()) null else ConnectionEntry(url, item.optLong("at"))
        }
    }

    override fun record(url: String) {
        val recent = listOf(ConnectionEntry(url, System.currentTimeMillis())) +
            entries().filterNot { it.url == url }.take(MAX_ENTRIES - 1)
        val array = JSONArray()
        recent.forEach { array.put(JSONObject().put("url", it.url).put("at", it.connectedAtMs)) }
        prefs.edit().putString(KEY, array.toString()).apply()
    }

    override fun clear() = prefs.edit().remove(KEY).apply()

    private companion object {
        const val KEY = "history"
        const val MAX_ENTRIES = 10
    }
}
