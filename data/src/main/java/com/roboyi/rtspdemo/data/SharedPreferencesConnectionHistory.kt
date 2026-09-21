package com.roboyi.rtspdemo.data

import com.roboyi.rtspdemo.domain.ConnectionEntry
import com.roboyi.rtspdemo.domain.ConnectionHistoryRepository
import org.json.JSONArray
import org.json.JSONObject

/** Retains the existing JSON format inside encrypted local storage. */
class SharedPreferencesConnectionHistory(private val store: SecureConnectionStore) : ConnectionHistoryRepository {
    override fun entries(): List<ConnectionEntry> {
        val array = runCatching { JSONArray(store.get(SecureConnectionStore.HISTORY) ?: "[]") }
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
        store.put(SecureConnectionStore.HISTORY, array.toString())
    }

    override fun clear() = store.remove(SecureConnectionStore.HISTORY)

    private companion object {
        const val MAX_ENTRIES = 10
    }
}
