package com.rssai.push

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.rssai.push.data.ApiClient
import com.rssai.push.navigation.AppNavigation
import com.rssai.push.navigation.DeepLink
import com.rssai.push.ui.theme.RssAiPushTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ApiClient.configure(this)
        enableEdgeToEdge()
        setContent {
            RssAiPushTheme {
                AppNavigation(deepLink = parseDeepLink(intent))
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    // 推送通知通过 rssaipush:// deep link 打开 App：
    //   rssaipush://reading/<filename> → PDF 总结详情页（含 PDF 原文/提问）
    //   rssaipush://digest/<filename>  → RSS 摘要 WebView 页
    private fun parseDeepLink(intent: Intent?): DeepLink? {
        val uri = intent?.data ?: return null
        if (uri.scheme != "rssaipush") return null
        val filename = uri.lastPathSegment ?: return null
        return when (uri.host) {
            "reading" -> DeepLink.Reading(filename)
            "digest" -> DeepLink.Digest(filename)
            else -> null
        }
    }
}
