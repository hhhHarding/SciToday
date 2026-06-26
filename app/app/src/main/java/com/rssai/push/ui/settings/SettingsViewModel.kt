package com.rssai.push.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rssai.push.data.ApiClient
import com.rssai.push.data.BackendMode
import com.rssai.push.data.BackendProfile
import com.rssai.push.data.BackendSettings
import com.rssai.push.data.ConfigResponse
import com.rssai.push.data.Feed
import com.rssai.push.data.FeedRequest
import com.rssai.push.data.Status
import com.rssai.push.data.local.PhonePdfUploader
import com.rssai.push.data.repository.DigestRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import javax.inject.Inject

data class SettingsUiState(
    // 后端连接
    val backendMode: BackendMode = BackendMode.TERMUX,
    val pcBaseUrl: String = "",
    val authToken: String = "",
    // AI 配置
    val apiKey: String = "",
    val baseUrl: String = "",
    val model: String = "",
    val systemPrompt: String = "",
    val rssPrompt: String = "",
    val pdfPrompt: String = "",
    // 定时
    val rssInterval: String = "30",
    val pdfInterval: String = "5",
    val scheduleEnabled: Boolean = true,
    // 远端只读状态
    val feeds: List<Feed> = emptyList(),
    val status: Status? = null,
    val downloadTreeGranted: Boolean = false,
    val allFilesAccessGranted: Boolean = false,
    val isLoading: Boolean = true,
    val message: String? = null,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repo: DigestRepository,
    private val phonePdfUploader: PhonePdfUploader,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val _ui = MutableStateFlow(SettingsUiState())
    val ui: StateFlow<SettingsUiState> = _ui.asStateFlow()

    init {
        val backend = BackendSettings.load(context)
        _ui.update {
            it.copy(
                backendMode = backend.mode,
                pcBaseUrl = backend.pcBaseUrl,
                authToken = backend.authToken,
                downloadTreeGranted = phonePdfUploader.hasDownloadTreeAccess(),
                allFilesAccessGranted = phonePdfUploader.hasAllFilesAccess()
            )
        }
        load()
        startStatusPolling()
    }

    // ── 表单字段更新 ──
    fun setBackendMode(v: BackendMode) = _ui.update { it.copy(backendMode = v) }
    fun setPcBaseUrl(v: String) = _ui.update { it.copy(pcBaseUrl = v) }
    fun setAuthToken(v: String) = _ui.update { it.copy(authToken = v) }
    fun setApiKey(v: String) = _ui.update { it.copy(apiKey = v) }
    fun setBaseUrl(v: String) = _ui.update { it.copy(baseUrl = v) }
    fun setModel(v: String) = _ui.update { it.copy(model = v) }
    fun setSystemPrompt(v: String) = _ui.update { it.copy(systemPrompt = v) }
    fun setRssPrompt(v: String) = _ui.update { it.copy(rssPrompt = v) }
    fun setPdfPrompt(v: String) = _ui.update { it.copy(pdfPrompt = v) }
    fun setRssInterval(v: String) = _ui.update { it.copy(rssInterval = v) }
    fun setPdfInterval(v: String) = _ui.update { it.copy(pdfInterval = v) }
    fun setScheduleEnabled(v: Boolean) = _ui.update { it.copy(scheduleEnabled = v) }
    fun clearMessage() = _ui.update { it.copy(message = null) }
    fun refreshDownloadAccess() =
        _ui.update {
            it.copy(
                downloadTreeGranted = phonePdfUploader.hasDownloadTreeAccess(),
                allFilesAccessGranted = phonePdfUploader.hasAllFilesAccess()
            )
        }

    fun saveDownloadTree(uri: android.net.Uri) {
        runCatching { phonePdfUploader.persistDownloadTree(uri) }
            .onSuccess {
                _ui.update {
                    it.copy(
                        downloadTreeGranted = phonePdfUploader.hasDownloadTreeAccess(),
                        allFilesAccessGranted = phonePdfUploader.hasAllFilesAccess(),
                        message = "下载目录授权已保存"
                    )
                }
            }
            .onFailure { e ->
                _ui.update { it.copy(message = "下载目录授权失败: ${e.message}") }
            }
    }

    fun selectedBackendProfile() = BackendProfile(
        mode = _ui.value.backendMode,
        pcBaseUrl = _ui.value.pcBaseUrl,
        authToken = _ui.value.authToken,
    )

    private fun applyRemoteConfig(cfg: ConfigResponse) {
        _ui.update { s ->
            val ai = cfg.ai
            val sch = cfg.schedule
            s.copy(
                apiKey = ai?.api_key ?: s.apiKey,
                baseUrl = ai?.base_url ?: s.baseUrl,
                model = ai?.model ?: s.model,
                systemPrompt = ai?.system_prompt ?: s.systemPrompt,
                rssPrompt = ai?.rss_prompt ?: s.rssPrompt,
                pdfPrompt = ai?.pdf_prompt ?: s.pdfPrompt,
                rssInterval = sch?.rss_interval_minutes?.toString() ?: s.rssInterval,
                pdfInterval = sch?.pdf_interval_minutes?.toString() ?: s.pdfInterval,
                scheduleEnabled = sch?.enabled ?: s.scheduleEnabled,
            )
        }
    }

    private fun load() {
        viewModelScope.launch {
            _ui.update { it.copy(isLoading = true) }
            repo.getConfig().onSuccess { applyRemoteConfig(it) }
                .onFailure { e -> _ui.update { it.copy(message = "加载配置失败: ${e.message}") } }
            repo.getStatus().onSuccess { s -> _ui.update { it.copy(status = s) } }
            loadFeeds()
            _ui.update { it.copy(isLoading = false) }
        }
    }

    private fun startStatusPolling() {
        viewModelScope.launch {
            while (true) {
                delay(10000)
                repo.getStatus().onSuccess { s -> _ui.update { it.copy(status = s) } }
            }
        }
    }

    private fun loadFeeds() {
        viewModelScope.launch {
            repo.getFeeds().onSuccess { f -> _ui.update { it.copy(feeds = f) } }
        }
    }

    private fun refreshStatus() {
        viewModelScope.launch {
            repo.getStatus().onSuccess { s -> _ui.update { it.copy(status = s) } }
        }
    }

    /** 应用后端连接配置（保存到本地 + 切换 ApiClient）。返回生效的 profile，校验失败返回 null。 */
    fun applyBackend(onResult: (BackendProfile?) -> Unit) {
        val s = _ui.value
        if (s.backendMode == BackendMode.PC && s.pcBaseUrl.isBlank()) {
            _ui.update { it.copy(message = "PC 后端地址不能为空") }
            onResult(null)
            return
        }
        val profile = selectedBackendProfile()
        BackendSettings.save(context, profile)
        ApiClient.updateProfile(profile)
        onResult(ApiClient.currentProfile())
    }

    fun saveBackendAndConnect(test: Boolean) {
        applyBackend { profile ->
            if (profile == null) return@applyBackend
            viewModelScope.launch {
                val cfg = repo.getConfig()
                cfg.onSuccess { applyRemoteConfig(it) }
                repo.getStatus().onSuccess { st -> _ui.update { it.copy(status = st) } }
                loadFeeds()
                val ok = cfg.isSuccess
                _ui.update {
                    it.copy(message = when {
                        ok && test -> "测试成功"
                        ok -> "保存成功"
                        test -> "连接失败"
                        else -> "保存成功，但连接失败"
                    })
                }
            }
        }
    }

    fun saveAiConfig() {
        val s = _ui.value
        saveConfig(buildBody {
            put("ai", JSONObject().apply {
                put("api_key", s.apiKey)
                put("base_url", s.baseUrl)
                put("model", s.model)
                put("system_prompt", s.systemPrompt)
                put("rss_prompt", s.rssPrompt)
                put("pdf_prompt", s.pdfPrompt)
            })
        }, "AI 配置已保存")
    }

    fun savePrompts() = saveAiConfig()

    fun saveSchedule() {
        val s = _ui.value
        saveConfig(buildBody {
            put("schedule", JSONObject().apply {
                put("rss_interval_minutes", s.rssInterval.toIntOrNull() ?: 30)
                put("pdf_interval_minutes", s.pdfInterval.toIntOrNull() ?: 5)
                put("enabled", s.scheduleEnabled)
            })
        }, "定时配置已保存", refreshAfter = true)
    }

    private fun saveConfig(body: RequestBody, successMsg: String, refreshAfter: Boolean = false) {
        viewModelScope.launch {
            repo.saveConfig(body)
                .onSuccess {
                    _ui.update { it.copy(message = successMsg) }
                    if (refreshAfter) refreshStatus()
                }
                .onFailure { e -> _ui.update { it.copy(message = "保存失败: ${e.message}") } }
        }
    }

    fun addFeed(title: String, url: String) {
        viewModelScope.launch {
            repo.addFeed(FeedRequest(title, url))
                .onSuccess { _ui.update { it.copy(message = "RSS 源已添加") }; loadFeeds(); refreshStatus() }
                .onFailure { e -> _ui.update { it.copy(message = "添加失败: ${e.message}") } }
        }
    }

    fun deleteFeed(url: String) {
        viewModelScope.launch {
            repo.deleteFeed(url)
                .onSuccess { _ui.update { it.copy(message = "已删除") }; loadFeeds(); refreshStatus() }
                .onFailure { e -> _ui.update { it.copy(message = "删除失败: ${e.message}") } }
        }
    }

    fun resetDigests() {
        viewModelScope.launch {
            repo.resetDigests()
                .onSuccess { r ->
                    _ui.update { it.copy(message = "已重置，删除 ${r.count} 条消息") }
                    refreshStatus()
                }
                .onFailure { e -> _ui.update { it.copy(message = "重置失败: ${e.message}") } }
        }
    }

    private fun buildBody(block: JSONObject.() -> Unit): RequestBody {
        val json = JSONObject().apply(block)
        return json.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
    }
}
