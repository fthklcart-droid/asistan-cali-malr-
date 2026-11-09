package com.fk.jarvis.data

import android.content.Context
import com.fk.jarvis.core.HistoryStore
import com.fk.jarvis.llm.ChatTurn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

data class ConversationEntry(val fromUser: Boolean, val text: String, val time: Long)

/**
 * Konuşma geçmişi: yalnızca METİN, cihazda yerel olarak saklanır. Ses kaydı saklanmaz.
 * "Geçmişi sakla" kapalıysa geçmiş yalnızca bellekte tutulur.
 */
class ConversationRepository(context: Context, private val persist: () -> Boolean) : HistoryStore {
    private val prefs = context.getSharedPreferences("jarvis_history", Context.MODE_PRIVATE)
    private val _entries = MutableStateFlow(if (persist()) load() else emptyList())
    val entries: StateFlow<List<ConversationEntry>> = _entries.asStateFlow()

    override fun add(fromUser: Boolean, text: String) {
        if (text.isBlank()) return
        val list = (_entries.value + ConversationEntry(fromUser, text, System.currentTimeMillis())).takeLast(MAX)
        _entries.value = list
        if (persist()) save(list) else prefs.edit().remove(KEY).apply()
    }

    override fun lastUserText(): String? = _entries.value.lastOrNull { it.fromUser }?.text
    override fun lastAssistantText(): String? = _entries.value.lastOrNull { !it.fromUser }?.text

    override fun recentTurns(n: Int): List<ChatTurn> = _entries.value.takeLast(n).map { ChatTurn(it.fromUser, it.text) }

    fun clear() {
        _entries.value = emptyList()
        prefs.edit().remove(KEY).apply()
    }

    private fun load(): List<ConversationEntry> = try {
        val arr = JSONArray(prefs.getString(KEY, "[]"))
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            ConversationEntry(o.getBoolean("u"), o.getString("t"), o.optLong("ts"))
        }
    } catch (e: Exception) {
        emptyList()
    }

    private fun save(list: List<ConversationEntry>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("u", it.fromUser).put("t", it.text).put("ts", it.time)) }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    companion object {
        private const val KEY = "entries"
        private const val MAX = 60
    }
}
