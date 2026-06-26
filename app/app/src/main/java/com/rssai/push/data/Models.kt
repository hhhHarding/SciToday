package com.rssai.push.data

import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class Status(
    val enabled: Boolean = false,
    val feeds_count: Int = 0,
    val inbox_summaries: Int = 0,
    val last_run: String = "",
    val pdf_interval: Int = 5,
    val pending_papers: Int = 0,
    val rss_interval: Int = 30,
    val total_articles: Int = 0,
    val pdf_count: Int = 0,
    val api_balance: String = "N/A"
)

@JsonClass(generateAdapter = true)
data class Feed(
    val title: String = "",
    val url: String = ""
)

@JsonClass(generateAdapter = true)
data class Digest(
    val filename: String = "",
    val timestamp: String = "",
    val title: String = "",
    val cn_title: String = "",
    val keywords: String = "",
    val journal: String = "",
    val source: String = "rss",
    val preview: String = ""
)

@JsonClass(generateAdapter = true)
data class RunResponse(
    val ok: Boolean = false,
    val message: String = ""
)

@JsonClass(generateAdapter = true)
data class AppHeartbeatRequest(
    val appVersion: String = "",
    val backendMode: String = "",
    val baseUrl: String = "",
    val reason: String = "",
    val downloadTreeGranted: Boolean = false,
    val lastEvent: String = "",
    val lastStatus: String = "",
    val lastError: String = "",
    val timestampMs: Long = 0L
)

@JsonClass(generateAdapter = true)
data class ClearResponse(
    val ok: Boolean = false,
    val count: Int = 0
)

@JsonClass(generateAdapter = true)
data class PdfUploadResponse(
    val ok: Boolean = false,
    val uploaded: Int = 0,
    val paths: List<String> = emptyList(),
    val errors: List<Map<String, String>> = emptyList()
)

@JsonClass(generateAdapter = true)
data class FeedRequest(
    val title: String,
    val url: String
)

@JsonClass(generateAdapter = true)
data class AiConfig(
    val api_key: String = "",
    val base_url: String = "",
    val model: String = "",
    val system_prompt: String = "",
    val rss_prompt: String = "",
    val pdf_prompt: String = ""
)

@JsonClass(generateAdapter = true)
data class ScheduleConfig(
    val rss_interval_minutes: Int = 30,
    val pdf_interval_minutes: Int = 5,
    val enabled: Boolean = true
)

@JsonClass(generateAdapter = true)
data class ConfigResponse(
    val ai: AiConfig? = null,
    val rss: RssConfig? = null,
    val schedule: ScheduleConfig? = null
)

@JsonClass(generateAdapter = true)
data class RssConfig(
    val opml_path: String = "",
    val per_feed_limit: Int = 3,
    val max_push_items: Int = 20
)

@JsonClass(generateAdapter = true)
data class AddFeedResponse(
    val ok: Boolean = false,
    val error: String = "",
    val count: Int = 0
)

@JsonClass(generateAdapter = true)
data class TaskProgress(
    val active: Boolean = false,
    val current: Int = 0,
    val total: Int = 0,
    val message: String = ""
)

@JsonClass(generateAdapter = true)
data class ProgressResponse(
    val rss: TaskProgress = TaskProgress(),
    val pdf: TaskProgress = TaskProgress()
)

@JsonClass(generateAdapter = true)
data class ChatMessage(
    val role: String = "user",
    val content: String = ""
)

@JsonClass(generateAdapter = true)
data class ChatRequest(
    val filename: String,
    val message: String,
    val history: List<ChatMessage>
)

@JsonClass(generateAdapter = true)
data class ChatResponse(
    val reply: String = "",
    val error: String = ""
)
