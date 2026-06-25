package com.rssai.push.ui

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.rssai.push.data.ApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 直接加载本地 PDF 源文件（经后端 /api/pdf 取字节，PdfRenderer 原生渲染分页）。
 */
@Composable
fun PdfViewer(filename: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var file by remember { mutableStateOf<File?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(filename) {
        loading = true
        error = null
        file = null
        withContext(Dispatchers.IO) {
            try {
                val url = ApiClient.pdfUrl(filename)
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15000
                    readTimeout = 120000
                    setRequestProperty("Connection", "close")
                    ApiClient.authToken()?.let {
                        setRequestProperty("Authorization", "Bearer $it")
                    }
                }
                try {
                    if (conn.responseCode == 404) {
                        error = "未找到对应的 PDF 源文件"
                        return@withContext
                    }
                    if (conn.responseCode != 200) {
                        error = "加载失败: HTTP ${conn.responseCode}"
                        return@withContext
                    }
                    val dest = File(context.cacheDir, "srcpdf_${filename.hashCode()}.pdf")
                    conn.inputStream.use { input ->
                        dest.outputStream().use { input.copyTo(it) }
                    }
                    file = dest
                } finally {
                    conn.disconnect()
                }
            } catch (e: Exception) {
                error = "加载失败: ${e.message}"
            }
        }
        loading = false
    }

    when {
        loading -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        error != null -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(error!!, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        file != null -> PdfPages(file!!, modifier)
    }
}

/**
 * PdfRenderer 不是线程安全的，且同一时刻只能打开一页（openPage 未关闭前再次调用会抛
 * IllegalStateException）。LazyColumn 会为每个可见页起独立协程渲染，快速滚动时多页并发
 * 访问同一个 renderer 必然崩溃。用 Mutex 串行化所有渲染调用，把请求排队而非并发。
 */
private class PdfDoc(val renderer: PdfRenderer) {
    val mutex = Mutex()
    val pageCount: Int get() = renderer.pageCount

    /** 串行渲染指定页为 Bitmap。所有调用通过 mutex 排队，保证同一时刻只打开一页。 */
    suspend fun renderPage(index: Int, targetWidth: Int = 1080): Bitmap =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val page = renderer.openPage(index)
                try {
                    val scale = targetWidth.toFloat() / page.width
                    val targetHeight = (page.height * scale).toInt()
                    val b = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
                    b.eraseColor(Color.WHITE)
                    page.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    b
                } finally {
                    page.close()
                }
            }
        }
}

@Composable
private fun PdfPages(file: File, modifier: Modifier = Modifier) {
    var doc by remember { mutableStateOf<PdfDoc?>(null) }
    DisposableEffect(file) {
        val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        val renderer = PdfRenderer(pfd)
        val d = PdfDoc(renderer)
        doc = d
        onDispose {
            // 关闭前先抢锁，确保没有渲染调用正在使用 renderer（否则 close 会与 openPage 竞争）。
            // 这里只是尽力等待——若锁被长期占用，tryLock 失败也仍然关闭以避免泄漏。
            if (d.mutex.tryLock()) {
                try {
                    try { renderer.close() } catch (_: Exception) {}
                    try { pfd.close() } catch (_: Exception) {}
                } finally {
                    d.mutex.unlock()
                }
            } else {
                try { renderer.close() } catch (_: Exception) {}
                try { pfd.close() } catch (_: Exception) {}
            }
        }
    }
    val d = doc
    if (d != null) {
        LazyColumn(modifier = modifier.fillMaxSize()) {
            items(d.pageCount) { index ->
                PdfPage(d, index)
            }
        }
    }
}

@Composable
private fun PdfPage(doc: PdfDoc, index: Int) {
    var bitmap by remember(index) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(doc, index) {
        bitmap = try {
            doc.renderPage(index)
        } catch (_: Exception) {
            // renderer 已关闭或该页渲染失败：保持占位，不让异常冒泡导致崩溃。
            null
        }
    }
    if (bitmap != null) {
        Image(
            bitmap = bitmap!!.asImageBitmap(),
            contentDescription = "第 ${index + 1} 页",
            modifier = Modifier.fillMaxWidth()
        )
    } else {
        Box(Modifier.fillMaxWidth().height(240.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
    }
    Spacer(Modifier.height(4.dp))
}
