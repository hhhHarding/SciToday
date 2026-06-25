package com.rssai.push.ui.logs

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rssai.push.data.repository.DigestRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class LogsUiState(
    val logs: List<String> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class LogsViewModel @Inject constructor(
    private val repo: DigestRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(LogsUiState())
    val uiState: StateFlow<LogsUiState> = _uiState.asStateFlow()

    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            repo.getLogs(300)
                .onSuccess { lines -> _uiState.update { it.copy(logs = lines, error = null) } }
                .onFailure { e -> _uiState.update { it.copy(error = "加载失败: ${e.message}") } }
            _uiState.update { it.copy(isLoading = false) }
        }
    }
}
