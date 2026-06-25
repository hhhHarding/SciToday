package com.rssai.push.ui.reading

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rssai.push.data.ChatMessage
import com.rssai.push.data.local.ChatStore
import com.rssai.push.data.repository.DigestRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val loading: Boolean = false,
)

@HiltViewModel
class ReadingDetailViewModel @Inject constructor(
    private val repo: DigestRepository,
    private val chatStore: ChatStore,
) : ViewModel() {

    private val _chat = MutableStateFlow(ChatUiState())
    val chat: StateFlow<ChatUiState> = _chat.asStateFlow()

    private var loadedFor: String? = null

    /** 进入某篇文章时恢复对话历史（按 filename）。 */
    fun loadChat(filename: String) {
        if (loadedFor == filename) return
        loadedFor = filename
        viewModelScope.launch {
            _chat.update { it.copy(messages = chatStore.load(filename)) }
        }
    }

    fun send(filename: String, text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || _chat.value.loading) return
        val history = _chat.value.messages
        val withUser = history + ChatMessage("user", trimmed)
        _chat.update { it.copy(messages = withUser, loading = true) }
        chatStore.save(filename, withUser)
        viewModelScope.launch {
            val reply = repo.chat(filename, trimmed, history)
                .getOrElse { e -> "请求失败：${e.message}" }
            val withReply = withUser + ChatMessage("assistant", reply)
            _chat.update { it.copy(messages = withReply, loading = false) }
            chatStore.save(filename, withReply)
        }
    }
}

