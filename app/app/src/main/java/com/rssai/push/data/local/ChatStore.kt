package com.rssai.push.data.local

import android.content.Context
import com.rssai.push.data.ChatMessage
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 按 filename 持久化每篇文章的 AI 追问对话。
 *
 * 后端 ai_chat 无状态，模型能否看到上文取决于请求携带的 history。把对话缓存在内存并持久化到
 * SharedPreferences，重进详情页时恢复，发送时仍作为 history 传给后端，上下文得以完整保留。
 */
@Singleton
class ChatStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cache = mutableMapOf<String, List<ChatMessage>>()

    /** 加载某篇文章的历史对话；已加载过直接返回内存副本。 */
    suspend fun load(filename: String): List<ChatMessage> {
        cache[filename]?.let { return it }
        val msgs = withContext(Dispatchers.IO) {
            val prefs = context.getSharedPreferences(CHAT_PREFS, Context.MODE_PRIVATE)
            val raw = prefs.getString(filename, null) ?: return@withContext emptyList<ChatMessage>()
            parse(raw)
        }
        cache[filename] = msgs
        return msgs
    }

    /** 覆盖保存某篇文章的对话：更新内存缓存并异步写盘。 */
    fun save(filename: String, messages: List<ChatMessage>) {
        cache[filename] = messages
        scope.launch {
            val prefs = context.getSharedPreferences(CHAT_PREFS, Context.MODE_PRIVATE)
            prefs.edit().putString(filename, serialize(messages)).apply()
        }
    }

    /** 清空某篇文章的对话（内存 + 磁盘）。 */
    fun clear(filename: String) {
        cache.remove(filename)
        scope.launch {
            val prefs = context.getSharedPreferences(CHAT_PREFS, Context.MODE_PRIVATE)
            prefs.edit().remove(filename).apply()
        }
    }

    private fun serialize(messages: List<ChatMessage>): String {
        val arr = JSONArray()
        for (m in messages) {
            arr.put(JSONObject().put("role", m.role).put("content", m.content))
        }
        return arr.toString()
    }

    private fun parse(raw: String): List<ChatMessage> {
        return try {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    add(ChatMessage(o.optString("role", "user"), o.optString("content", "")))
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private companion object {
        const val CHAT_PREFS = "chat_history"
    }
}
