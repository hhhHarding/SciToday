package com.rssai.push.ui.feed

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rssai.push.data.Digest
import com.rssai.push.data.ProgressResponse
import com.rssai.push.data.TaskProgress
import com.rssai.push.data.local.ReadStore
import com.rssai.push.data.repository.DigestRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DigestFeedUiState(
    val digests: List<Digest> = emptyList(),
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val isRunning: Boolean = false,
    val progress: TaskProgress? = null,
    val progressLabel: String = "",
    val notice: String? = null,
    val error: String? = null,
)

/**
 * 消息页 / 阅读页共享的列表逻辑。两屏差异仅在 source（rss/pdf）、手动触发端点、轮询关注的任务，
 * 由子类提供；refresh / 删除 / 轮询 / 已读状态全部在此统一。
 *
 * 轮询用 viewModelScope 内的单循环；UI 用 collectAsStateWithLifecycle 收集，进后台自动停止收集，
 * 但循环本身需在 onStart/onStop 控制——这里改为由 UI 调用 startPolling/stopPolling。
 */
abstract class DigestFeedViewModel(
    private val repo: DigestRepository,
    private val readStore: ReadStore,
    private val source: String,
) : ViewModel() {

    private val _uiState = MutableStateFlow(DigestFeedUiState())
    val uiState: StateFlow<DigestFeedUiState> = _uiState.asStateFlow()

    val readSet: StateFlow<Set<String>> = readStore.readSet

    // 点击触发后到轮询首次观测到 active 之间的空窗，用 pendingTrigger 维持运行态，避免按钮闪回。
    private var pendingTrigger = false
    private var pendingPolls = 0
    private var pollingJob: kotlinx.coroutines.Job? = null

    // 连续两次下拉刷新（间隔 < 3 秒）触发后端强制抓取/扫描。
    private var lastPullElapsed = 0L

    /** 子类指定手动触发的端点（runRss / runPdf）。 */
    protected abstract suspend fun triggerTask(): Result<Unit>

    /** 子类从整体进度里挑出本屏关注的任务（消息页含 rss+pdf，阅读页仅 pdf）。 */
    protected abstract fun relevantProgress(p: ProgressResponse): Pair<TaskProgress?, String>

    fun refresh(showLoading: Boolean = true) {
        viewModelScope.launch {
            if (showLoading) _uiState.update { it.copy(isLoading = true) }
            repo.getDigests(100, source)
                .onSuccess { list -> _uiState.update { it.copy(digests = list, error = null) } }
                .onFailure { e -> _uiState.update { it.copy(error = "连接失败: ${e.message}") } }
            if (showLoading) _uiState.update { it.copy(isLoading = false) }
        }
    }

    /** 下拉刷新：静默重新拉取列表。连续两次下拉（间隔 < 3 秒）额外触发后端强制抓取/扫描。 */
    fun pullRefresh() {
        val now = android.os.SystemClock.elapsedRealtime()
        val isSecondPull = now - lastPullElapsed < 3000
        if (isSecondPull) {
            lastPullElapsed = 0L
            trigger()  // 第二次下拉：强制抓取
            flashNotice("已启动强制抓取，请稍候…")
        } else {
            lastPullElapsed = now
            flashNotice("再次下拉可强制抓取最新")
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isRefreshing = true) }
            repo.getDigests(100, source)
                .onSuccess { list -> _uiState.update { it.copy(digests = list, error = null) } }
                .onFailure { e -> _uiState.update { it.copy(error = "连接失败: ${e.message}") } }
            _uiState.update { it.copy(isRefreshing = false) }
        }
    }

    private var noticeJob: kotlinx.coroutines.Job? = null

    /** 顶部短暂提示，几秒后自动消失。 */
    private fun flashNotice(text: String) {
        _uiState.update { it.copy(notice = text) }
        noticeJob?.cancel()
        noticeJob = viewModelScope.launch {
            delay(2500)
            _uiState.update { it.copy(notice = null) }
        }
    }

    fun trigger() {
        viewModelScope.launch {
            pendingTrigger = true
            pendingPolls = 0
            _uiState.update { it.copy(isRunning = true) }
            triggerTask().onFailure {
                pendingTrigger = false
                pendingPolls = 0
                _uiState.update { state -> state.copy(isRunning = false) }
            }
        }
    }

    fun delete(filename: String) {
        viewModelScope.launch {
            repo.deleteDigest(filename)
                .onSuccess {
                    _uiState.update { s -> s.copy(digests = s.digests.filter { it.filename != filename }) }
                }
                .onFailure { e -> _uiState.update { it.copy(error = "删除失败: ${e.message}") } }
        }
    }

    fun markRead(filename: String) = readStore.markRead(filename)

    /** UI STARTED 时调用：启动轮询循环（已在跑则忽略）。 */
    fun startPolling() {
        if (pollingJob?.isActive == true) return
        pollingJob = viewModelScope.launch {
            var wasRunning = false
            var idleTicks = 0
            while (true) {
                repo.getProgress().onSuccess { p ->
                    val (prog, label) = relevantProgress(p)
                    val nowRunning = prog != null
                    if (nowRunning) {
                        pendingTrigger = false
                        pendingPolls = 0
                    } else if (pendingTrigger) {
                        pendingPolls++
                        if (pendingPolls >= 3) {
                            pendingTrigger = false
                            pendingPolls = 0
                            refresh(showLoading = false)
                        }
                    }
                    _uiState.update {
                        it.copy(progress = prog, progressLabel = label, isRunning = nowRunning || pendingTrigger)
                    }
                    if (wasRunning && !nowRunning) refresh(showLoading = false)
                    if (!nowRunning) {
                        idleTicks++
                        if (idleTicks >= 15) { idleTicks = 0; refresh(showLoading = false) }
                    } else idleTicks = 0
                    wasRunning = nowRunning
                }
                delay(2000)
            }
        }
    }

    /** UI STOPPED 时调用：停止轮询，避免后台持续打网络。 */
    fun stopPolling() {
        pollingJob?.cancel()
        pollingJob = null
    }

    fun clearError() = _uiState.update { it.copy(error = null) }
}
