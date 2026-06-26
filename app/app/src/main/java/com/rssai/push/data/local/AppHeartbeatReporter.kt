package com.rssai.push.data.local

import android.content.Context
import com.rssai.push.data.ApiClient
import com.rssai.push.data.AppHeartbeatRequest
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppHeartbeatReporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val phonePdfUploader: PhonePdfUploader,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var started = false
    @Volatile private var lastEvent = "startup"
    @Volatile private var lastStatus = "ok"
    @Volatile private var lastError = ""

    fun start() {
        if (started) return
        started = true
        scope.launch {
            sendNow("startup")
            while (true) {
                delay(60_000)
                sendNow("periodic")
            }
        }
    }

    fun sendNow(reason: String) {
        scope.launch { post(reason) }
    }

    fun record(event: String, success: Boolean, error: String? = null) {
        lastEvent = event
        lastStatus = if (success) "ok" else "error"
        lastError = if (success) "" else error.orEmpty()
        sendNow(event)
    }

    private suspend fun post(reason: String) {
        runCatching {
            val profile = ApiClient.currentProfile()
            ApiClient.api.postHeartbeat(
                AppHeartbeatRequest(
                    appVersion = appVersion(),
                    backendMode = profile.mode.raw,
                    baseUrl = profile.activeBaseUrl,
                    reason = reason,
                    downloadTreeGranted = phonePdfUploader.hasDownloadTreeAccess(),
                    lastEvent = lastEvent,
                    lastStatus = lastStatus,
                    lastError = lastError,
                    timestampMs = System.currentTimeMillis()
                )
            )
        }
    }

    private fun appVersion(): String {
        return runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            info.versionName ?: ""
        }.getOrDefault("")
    }
}
