package com.rssai.push.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import com.rssai.push.data.ApiClient

// 注入到摘要页的脚本：移除"返回列表"按钮（由后端 tasks.py 的 inbox 模板生成），
// App 自身有 TopAppBar 返回箭头和系统返回手势，不需要页面内的返回列表按钮。
private const val HIDE_BACK_TO_LIST_JS = """
(function(){
  var as = document.querySelectorAll('a.btn');
  as.forEach(function(a){
    var href = a.getAttribute('href') || '';
    var txt = (a.textContent || '').trim();
    if (href === 'index.html' || txt.indexOf('返回列表') >= 0) {
      a.style.display = 'none';
    }
  });
})();
"""

// 让摘要页配色/字体跟随 App 主题（而非系统 prefers-color-scheme）：注入一段高优先级 CSS
// 覆盖模板的 :root 变量与字体，与 App 的靖蓝亮/暗主题、卡片字号行距保持一致观感。
private fun themeInjectJs(dark: Boolean): String {
    val bg = if (dark) "#0F172A" else "#F6F7F9"
    val card = if (dark) "#1E293B" else "#FFFFFF"
    val text = if (dark) "#F1F5F9" else "#15171A"
    val muted = if (dark) "#94A3B8" else "#5F6B7A"
    val border = if (dark) "#334155" else "#E2E8F0"
    val accent = if (dark) "#60A5FA" else "#2563EB"
    val scheme = if (dark) "dark" else "light"
    val css = """
:root{color-scheme:$scheme !important;--bg:$bg !important;--card:$card !important;--text:$text !important;--muted:$muted !important;--border:$border !important;--accent:$accent !important;}
html,body{background:$bg !important;color:$text !important;
  font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,'Noto Sans CJK SC','PingFang SC','Microsoft YaHei',sans-serif !important;
  font-size:16px !important;line-height:1.7 !important;}
.card{background:$card !important;border-color:$border !important;}
h1{font-size:21px !important;line-height:1.4 !important;font-weight:700 !important;color:$text !important;}
h2,h3{color:$text !important;}
.meta{color:$muted !important;}
.content{font-size:15.5px !important;line-height:1.78 !important;color:$text !important;}
a,.btn{color:$accent !important;}
.btn{background:$accent !important;color:#fff !important;}
.btn.secondary{background:transparent !important;color:$accent !important;border-color:$accent !important;}
""".trim()
    val escaped = css.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "")
    return """
(function(){
  var id='app-theme-override';
  var old=document.getElementById(id);
  if(old) old.remove();
  var s=document.createElement('style');
  s.id=id; s.innerHTML='$escaped';
  document.head.appendChild(s);
})();
"""
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun DigestWebView(
    url: String,
    modifier: Modifier = Modifier,
    darkTheme: Boolean = androidx.compose.foundation.isSystemInDarkTheme(),
    onWebViewReady: (WebView) -> Unit = {}
) {
    val context = LocalContext.current
    var loading by remember(url) { mutableStateOf(true) }
    // WebView 底色与遮罩用 App 主题背景，消除渲染前的黑屏。
    val themeBg = if (darkTheme) androidx.compose.ui.graphics.Color(0xFF0F172A)
        else androidx.compose.ui.graphics.Color(0xFFF6F7F9)
    val themeBgArgb = themeBg.toArgb()

    // 打开外链到系统浏览器；失败时回退到 WebView 内加载
    fun openExternal(target: String): Boolean {
        return try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(target))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            true
        } catch (_: Exception) {
            false
        }
    }

    Box(modifier = modifier) {
        AndroidView(
            factory = { ctx ->
            WebView(ctx).apply {
                // 外链（期刊站点）交给系统浏览器打开：
                // 期刊站的 cookie 同意弹窗在 WebView 内难以交互，会导致无法下载 PDF。
                // 系统 Chrome 能正常处理 cookie 同意和 PDF 下载。
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: WebView, request: WebResourceRequest
                    ): Boolean {
                        val u = request.url ?: return false
                        // 当前后端摘要页保持在 WebView 内，期刊外链交给系统浏览器。
                        if (ApiClient.isBackendUrl(u.toString())) {
                            return false
                        }
                        return openExternal(u.toString())
                    }

                    override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                        super.onPageStarted(view, url, favicon)
                        loading = true
                    }

                    override fun onPageFinished(view: WebView, url: String?) {
                        super.onPageFinished(view, url)
                        view.evaluateJavascript(HIDE_BACK_TO_LIST_JS, null)
                        view.evaluateJavascript(themeInjectJs(darkTheme)) { _ ->
                            // 主题 CSS 注入完成后再撤遮罩，避免先白/黑底再变色的闪烁。
                            loading = false
                        }
                    }
                }

                // 直接的 PDF / 下载链接也交给系统浏览器/下载器
                setDownloadListener { target, _, _, _, _ ->
                    openExternal(target)
                }

                // target=_blank 等新窗口链接 → 转交系统浏览器
                webChromeClient = object : WebChromeClient() {
                    override fun onCreateWindow(
                        view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message
                    ): Boolean {
                        val hrefView = WebView(view.context)
                        val transport = resultMsg.obj as? android.webkit.WebView.WebViewTransport
                        transport?.webView = hrefView
                        resultMsg.sendToTarget()
                        hrefView.webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(
                                v: WebView, request: WebResourceRequest
                            ): Boolean {
                                val u = request.url ?: return false
                                if (ApiClient.isBackendUrl(u.toString())) return false
                                openExternal(u.toString())
                                return true
                            }
                        }
                        return true
                    }
                }

                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.loadWithOverviewMode = true
                settings.useWideViewPort = true
                settings.setSupportMultipleWindows(true)
                settings.javaScriptCanOpenWindowsAutomatically = true
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                settings.cacheMode = WebSettings.LOAD_DEFAULT
                settings.allowContentAccess = true
                settings.allowFileAccess = false

                // 摘要页配色由 App 显式注入 CSS 控制（themeInjectJs，跟随 App 亮/暗主题），
                // 因此关闭 WebView 的算法暗化，避免二次反色导致颜色错乱。
                if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
                    WebSettingsCompat.setAlgorithmicDarkeningAllowed(settings, false)
                }

                settings.userAgentString = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

                val cookieManager = CookieManager.getInstance()
                cookieManager.setAcceptCookie(true)
                cookieManager.setAcceptThirdPartyCookies(this, true)

                // WebView 底色设为 App 主题背景，消除渲染前的黑屏。
                setBackgroundColor(themeBgArgb)

                loadUrl(url)
                onWebViewReady(this)
            }
        },
        modifier = Modifier.fillMaxSize()
        )
        androidx.compose.animation.AnimatedVisibility(
            visible = loading,
            exit = androidx.compose.animation.fadeOut()
        ) {
            Box(
                Modifier.fillMaxSize().background(themeBg),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebViewScreen(url: String, title: String, onBack: () -> Unit) {
    var webView by remember { mutableStateOf<WebView?>(null) }

    fun handleBack() {
        val wv = webView
        if (wv != null && wv.canGoBack()) {
            wv.goBack()
        } else {
            onBack()
        }
    }

    BackHandler { handleBack() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1, fontSize = 14.sp) },
                windowInsets = WindowInsets(0, 0, 0, 0),
                navigationIcon = {
                    IconButton(onClick = { handleBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        DigestWebView(
            url = url,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            onWebViewReady = { webView = it }
        )
    }
}
