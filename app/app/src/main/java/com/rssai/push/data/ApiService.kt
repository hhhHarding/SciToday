package com.rssai.push.data

import android.content.Context
import android.net.Uri
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import retrofit2.http.*
import okhttp3.OkHttpClient
import okhttp3.MultipartBody
import okhttp3.RequestBody
import java.net.URLEncoder

interface ApiService {
    @GET("/api/status")
    suspend fun getStatus(): Status

    @GET("/api/config")
    suspend fun getConfig(): ConfigResponse

    @POST("/api/config")
    suspend fun saveConfig(@Body body: RequestBody): RunResponse

    @GET("/api/progress")
    suspend fun getProgress(): ProgressResponse

    @GET("/api/digests")
    suspend fun getDigests(
        @Query("limit") limit: Int = 20,
        @Query("source") source: String? = null
    ): List<Digest>

    @DELETE("/api/digests/{filename}")
    suspend fun deleteDigest(@Path("filename") filename: String): RunResponse

    @DELETE("/api/digests")
    suspend fun clearDigests(@Query("source") source: String? = null): ClearResponse

    @POST("/api/reset")
    suspend fun resetDigests(): ClearResponse

    @POST("/api/chat")
    suspend fun chat(@Body request: ChatRequest): ChatResponse

    @GET("/api/feeds")
    suspend fun getFeeds(): List<Feed>

    @POST("/api/feeds")
    suspend fun addFeed(@Body request: FeedRequest): AddFeedResponse

    @DELETE("/api/feeds/{url}")
    suspend fun deleteFeed(@Path("url") encodedUrl: String): RunResponse

    @POST("/api/run/rss")
    suspend fun runRss(): RunResponse

    @POST("/api/run/pdf")
    suspend fun runPdf(): RunResponse

    @Multipart
    @POST("/api/pdf/upload")
    suspend fun uploadPdf(@Part files: List<MultipartBody.Part>): PdfUploadResponse

    @GET("/api/logs")
    suspend fun getLogs(@Query("lines") lines: Int = 200): List<String>
}

object ApiClient {
    @Volatile
    private var profile: BackendProfile = BackendProfile()

    @Volatile
    private var service: ApiService? = null

    @Volatile
    private var serviceKey: String = ""

    /**
     * 摘要 HTML 在后端 inbox 目录下的访问地址。
     * filename 常含中文，按 path 段规则编码（保留 . 等合法字符，空格转 %20 而非 +），
     * 避免依赖 WebView 各自不一致的自动编码行为。
     */
    fun inboxUrl(filename: String): String {
        val encoded = encodePathSegment(filename)
        return withToken("${baseUrl()}/inbox/$encoded")
    }

    fun pdfUrl(filename: String): String {
        val encoded = URLEncoder.encode(filename, "UTF-8")
        return withToken("${baseUrl()}/api/pdf?filename=$encoded")
    }

    fun configure(context: Context) {
        updateProfile(BackendSettings.load(context))
    }

    fun updateProfile(next: BackendProfile) {
        val normalized = next.copy(pcBaseUrl = BackendSettings.normalizeBaseUrl(next.pcBaseUrl))
        profile = normalized
        service = null
        serviceKey = ""
    }

    fun currentProfile(): BackendProfile = profile

    fun baseUrl(): String = profile.activeBaseUrl

    fun authToken(): String? = profile.activeAuthToken.ifBlank { null }

    fun isBackendUrl(url: String): Boolean {
        return try {
            val target = Uri.parse(url)
            val backend = Uri.parse(baseUrl())
            target.scheme.equals(backend.scheme, ignoreCase = true) &&
                target.host.equals(backend.host, ignoreCase = true) &&
                target.port == backend.port
        } catch (_: Exception) {
            false
        }
    }

    private val moshi = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()

    val api: ApiService
        get() {
            val key = "${baseUrl()}|${authToken().orEmpty()}"
            service?.let {
                if (serviceKey == key) return it
            }
            return synchronized(this) {
                service?.let {
                    if (serviceKey == key) return@synchronized it
                }
                createService(baseUrl(), authToken()).also {
                    service = it
                    serviceKey = key
                }
            }
        }

    private fun createService(baseUrl: String, token: String?): ApiService {
        val client = OkHttpClient.Builder()
            // AI 总结 / chat 等接口耗时长，默认 10s 超时会误判失败，统一放宽到 120s。
            .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val request = if (token.isNullOrBlank()) {
                    chain.request()
                } else {
                    chain.request().newBuilder()
                        .header("Authorization", "Bearer $token")
                        .build()
                }
                chain.proceed(request)
            }
            .build()
        return Retrofit.Builder()
            .baseUrl(retrofitBaseUrl(baseUrl))
            .client(client)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(ApiService::class.java)
    }

    private fun retrofitBaseUrl(value: String): String {
        return if (value.endsWith("/")) value else "$value/"
    }

    private fun encodePathSegment(value: String): String {
        return URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    }

    private fun withToken(url: String): String {
        val token = authToken() ?: return url
        val sep = if (url.contains("?")) "&" else "?"
        return "$url${sep}token=${URLEncoder.encode(token, "UTF-8")}"
    }
}
