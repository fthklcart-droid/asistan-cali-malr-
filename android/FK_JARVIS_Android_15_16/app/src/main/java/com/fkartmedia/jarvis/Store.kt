package com.fkartmedia.jarvis

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate

data class ChatLine(val role: String, val text: String)
data class Todo(val id: Long, val text: String, val done: Boolean = false, val remindAt: Long? = null)

/** Uygulama verisi: yalnız bu telefonda (uygulamanın özel klasöründe) JSON dosyaları. */
object Store {
    private fun file(ctx: Context, name: String) = File(ctx.filesDir, name)

    private fun writeAtomic(f: File, text: String) {
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }

    // ---------------- sohbet ----------------
    fun loadChat(ctx: Context): Pair<List<ChatLine>, JSONArray> {
        val f = file(ctx, "sohbet.json")
        if (!f.exists()) return emptyList<ChatLine>() to JSONArray()
        return try {
            val o = JSONObject(f.readText())
            val d = o.optJSONArray("display") ?: JSONArray()
            val lines = (0 until d.length()).map { d.getJSONObject(it).let { x -> ChatLine(x.getString("role"), x.getString("text")) } }
            lines to (o.optJSONArray("api") ?: JSONArray())
        } catch (e: Exception) {
            emptyList<ChatLine>() to JSONArray()
        }
    }

    fun saveChat(ctx: Context, lines: List<ChatLine>, api: JSONArray) {
        val d = JSONArray()
        lines.forEach { d.put(JSONObject().put("role", it.role).put("text", it.text)) }
        writeAtomic(file(ctx, "sohbet.json"), JSONObject().put("display", d).put("api", api).toString())
    }

    // ---------------- görevler ----------------
    fun loadTodos(ctx: Context): List<Todo> {
        val f = file(ctx, "gorevler.json")
        if (!f.exists()) return emptyList()
        return try {
            val a = JSONArray(f.readText())
            (0 until a.length()).map {
                val o = a.getJSONObject(it)
                Todo(o.getLong("id"), o.getString("text"), o.optBoolean("done"),
                    if (o.has("remindAt") && !o.isNull("remindAt")) o.getLong("remindAt") else null)
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun saveTodos(ctx: Context, todos: List<Todo>) {
        val a = JSONArray()
        todos.forEach { t ->
            a.put(JSONObject().put("id", t.id).put("text", t.text).put("done", t.done)
                .put("remindAt", t.remindAt ?: JSONObject.NULL))
        }
        writeAtomic(file(ctx, "gorevler.json"), a.toString())
    }

    // ---------------- ayarlar ve kullanım ----------------
    fun prefs(ctx: Context) = ctx.getSharedPreferences("ayarlar", Context.MODE_PRIVATE)

    fun addUsage(ctx: Context, cost: Double?) {
        val day = LocalDate.now().toString()
        val p = prefs(ctx)
        p.edit()
            .putInt("calls_$day", p.getInt("calls_$day", 0) + 1)
            .putFloat("cost_$day", p.getFloat("cost_$day", 0f) + (cost ?: 0.0).toFloat())
            .apply()
    }

    fun usageToday(ctx: Context): Pair<Int, Double> {
        val day = LocalDate.now().toString()
        val p = prefs(ctx)
        return p.getInt("calls_$day", 0) to p.getFloat("cost_$day", 0f).toDouble()
    }
}
