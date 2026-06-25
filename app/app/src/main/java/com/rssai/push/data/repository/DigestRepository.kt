package com.rssai.push.data.repository

import com.rssai.push.data.ApiClient
import com.rssai.push.data.BackendMode
import com.rssai.push.data.ChatMessage
import com.rssai.push.data.ChatRequest
import com.rssai.push.data.ClearResponse
import com.rssai.push.data.ConfigResponse
import com.rssai.push.data.Digest
import com.rssai.push.data.Feed
import com.rssai.push.data.FeedRequest
import com.rssai.push.data.ProgressResponse
import com.rssai.push.data.Status
import com.rssai.push.data.local.PhonePdfUploader
import okhttp3.RequestBody
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 所有后端访问的唯一入口。包裹 ApiClient.api（保留其 Termux/PC 运行时切换与 token 鉴权能力），
 * 把可能抛出的网络异常统一收敛为 Result，ViewModel 据此区分成功/失败而不必各自 try/catch。
 */
@Singleton
class DigestRepository @Inject constructor(
    private val phonePdfUploader: PhonePdfUploader,
) {

    private val api get() = ApiClient.api

    suspend fun getDigests(limit: Int, source: String?): Result<List<Digest>> =
        runCatching { api.getDigests(limit, source) }

    suspend fun deleteDigest(filename: String): Result<Unit> =
        runCatching { api.deleteDigest(filename); Unit }

    suspend fun clearDigests(source: String?): Result<ClearResponse> =
        runCatching { api.clearDigests(source) }

    suspend fun resetDigests(): Result<ClearResponse> =
        runCatching { api.resetDigests() }

    suspend fun runRss(): Result<Unit> = runCatching { api.runRss(); Unit }

    suspend fun runPdf(): Result<Unit> = runCatching {
        if (ApiClient.currentProfile().mode == BackendMode.PC) {
            phonePdfUploader.uploadRecentDownloads()
        }
        api.runPdf()
        Unit
    }

    suspend fun getProgress(): Result<ProgressResponse> =
        runCatching { api.getProgress() }

    suspend fun getStatus(): Result<Status> = runCatching { api.getStatus() }

    suspend fun getConfig(): Result<ConfigResponse> = runCatching { api.getConfig() }

    suspend fun saveConfig(body: RequestBody): Result<Unit> =
        runCatching { api.saveConfig(body); Unit }

    suspend fun getFeeds(): Result<List<Feed>> = runCatching { api.getFeeds() }

    suspend fun addFeed(request: FeedRequest): Result<Unit> =
        runCatching { api.addFeed(request); Unit }

    suspend fun deleteFeed(encodedUrl: String): Result<Unit> =
        runCatching { api.deleteFeed(encodedUrl); Unit }

    suspend fun chat(filename: String, message: String, history: List<ChatMessage>): Result<String> =
        runCatching { api.chat(ChatRequest(filename, message, history)).reply }

    suspend fun getLogs(lines: Int): Result<List<String>> =
        runCatching { api.getLogs(lines) }
}
