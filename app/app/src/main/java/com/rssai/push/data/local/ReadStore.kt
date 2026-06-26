package com.rssai.push.data.local

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 已读状态（消息页 / 阅读页共用）。内存 StateFlow 作为单一数据源：构造时异步加载一次，
 * 判断已读纯内存（零主线程 I/O）；标记已读立即更新 flow（驱动 UI）并异步写盘持久化。
 */
@Singleton
class ReadStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _readSet = MutableStateFlow<Set<String>>(emptySet())
    val readSet: StateFlow<Set<String>> = _readSet

    init {
        scope.launch {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            _readSet.value = prefs.getStringSet(KEY_READ_SET, emptySet())?.toSet() ?: emptySet()
        }
    }

    /** 标记为已读：立即更新内存状态（驱动 UI），并异步持久化。 */
    fun markRead(filename: String) {
        if (filename in _readSet.value) return
        _readSet.value = _readSet.value + filename
        scope.launch {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putStringSet(KEY_READ_SET, _readSet.value).apply()
        }
    }

    /** 批量标记全部已读。 */
    fun markAllRead(filenames: List<String>) {
        val newSet = _readSet.value + filenames
        _readSet.value = newSet
        scope.launch {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putStringSet(KEY_READ_SET, newSet).apply()
        }
    }

    private companion object {
        const val PREFS_NAME = "read_messages"
        const val KEY_READ_SET = "read_filenames"
    }
}
