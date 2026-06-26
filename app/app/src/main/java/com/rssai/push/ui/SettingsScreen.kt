package com.rssai.push.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rssai.push.data.BackendMode
import com.rssai.push.ui.common.SettingsSection
import com.rssai.push.ui.common.StatCard
import com.rssai.push.ui.settings.SettingsViewModel
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onOpenLogs: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val downloadTreeLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) viewModel.saveDownloadTree(uri)
        else viewModel.refreshDownloadAccess()
    }
    val allFilesLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        viewModel.refreshDownloadAccess()
    }

    if (state.isLoading) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    // 各分组展开态，跨进程保存。默认仅"后端连接"展开，缩短首屏动线。
    var backendExpanded by rememberSaveable { mutableStateOf(true) }
    var scheduleExpanded by rememberSaveable { mutableStateOf(false) }
    var aiExpanded by rememberSaveable { mutableStateOf(false) }
    var promptExpanded by rememberSaveable { mutableStateOf(false) }
    var feedExpanded by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(state.message) {
        if (state.message != null) {
            delay(3_000)
            viewModel.clearMessage()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
        item {
            Text("设置", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onBackground)
        }

        // 仪表盘统计（常显）
        state.status?.let { s ->
            item {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatCard("RSS源", "${s.feeds_count}", Modifier.weight(1f))
                    StatCard("总结数", "${s.inbox_summaries}", Modifier.weight(1f))
                }
            }
            item {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatCard("PDF原文", "${s.pdf_count}", Modifier.weight(1f))
                    StatCard("API余额", s.api_balance, Modifier.weight(1f))
                }
            }
            item {
                Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("定时任务状态", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            if (s.enabled) "已开启 · RSS ${s.rss_interval}分钟 · PDF ${s.pdf_interval}分钟" else "已关闭",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text("上次运行: ${s.last_run}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        // 后端连接
        item {
            SettingsSection("后端连接", backendExpanded, { backendExpanded = !backendExpanded }) {
                val options = listOf(BackendMode.TERMUX to "Termux 默认", BackendMode.PC to "PC 后端")
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    options.forEachIndexed { index, option ->
                        SegmentedButton(
                            selected = state.backendMode == option.first,
                            onClick = { viewModel.setBackendMode(option.first) },
                            shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                            label = { Text(option.second) }
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = state.pcBaseUrl, onValueChange = { viewModel.setPcBaseUrl(it) },
                    label = { Text("PC 后端 HTTPS 地址") },
                    placeholder = { Text("https://your-tunnel.example.com") },
                    enabled = state.backendMode == BackendMode.PC,
                    modifier = Modifier.fillMaxWidth(), singleLine = true
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = state.authToken, onValueChange = { viewModel.setAuthToken(it) },
                    label = { Text("访问 Token") },
                    enabled = state.backendMode == BackendMode.PC,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(), singleLine = true
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { viewModel.saveBackendAndConnect(test = false) }, modifier = Modifier.weight(1f)) { Text("保存") }
                    OutlinedButton(onClick = { viewModel.saveBackendAndConnect(test = true) }, modifier = Modifier.weight(1f)) { Text("测试") }
                }
                Spacer(modifier = Modifier.height(8.dp))
                BackendInfoBox(
                    title = "下载列表读取",
                    value = if (state.allFilesAccessGranted) "已授权" else "未授权",
                    description = "允许后可读取 Download 根目录和子目录中的 PDF 列表。",
                    positive = state.allFilesAccessGranted,
                    action = {
                        OutlinedButton(onClick = {
                            val appIntent = Intent(
                                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                Uri.parse("package:${context.packageName}")
                            )
                            runCatching { allFilesLauncher.launch(appIntent) }
                                .onFailure {
                                    allFilesLauncher.launch(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                                }
                        }) {
                            Text(if (state.allFilesAccessGranted) "重新授权" else "授予权限")
                        }
                    }
                )
                Spacer(modifier = Modifier.height(8.dp))
                BackendInfoBox(
                    title = "下载目录访问",
                    value = if (state.downloadTreeGranted) "已授权" else "未授权",
                    description = "通过系统目录授权读取下载目录，用于发现新下载的 PDF。",
                    positive = state.downloadTreeGranted,
                    action = {
                        OutlinedButton(onClick = { downloadTreeLauncher.launch(null) }) {
                            Text(if (state.downloadTreeGranted) "重选目录" else "授权目录")
                        }
                    }
                )
                Spacer(modifier = Modifier.height(8.dp))
                BackendInfoBox(
                    title = "Download 根目录",
                    value = "/storage/emulated/0/Download",
                    description = if (state.downloadTreeGranted) {
                        "已保存目录授权，上传 PDF 到 PC 时会扫描该目录及子目录。"
                    } else {
                        "授权目录时请选择 Download 根目录，否则新下载的全文 PDF 可能无法被扫描。"
                    },
                    action = {
                        OutlinedButton(onClick = { downloadTreeLauncher.launch(null) }) {
                            Text("选择目录")
                        }
                    }
                )
            }
        }

        // 定时任务设置
        item {
            SettingsSection("定时任务设置", scheduleExpanded, { scheduleExpanded = !scheduleExpanded }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = state.scheduleEnabled, onCheckedChange = { viewModel.setScheduleEnabled(it) })
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("启用定时任务")
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = state.rssInterval, onValueChange = { viewModel.setRssInterval(it) },
                        label = { Text("RSS间隔(分)") }, modifier = Modifier.weight(1f), singleLine = true
                    )
                    OutlinedTextField(
                        value = state.pdfInterval, onValueChange = { viewModel.setPdfInterval(it) },
                        label = { Text("PDF间隔(分)") }, modifier = Modifier.weight(1f), singleLine = true
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Button(onClick = { viewModel.saveSchedule() }) { Text("保存定时配置") }
            }
        }

        // AI 配置
        item {
            SettingsSection("AI 配置", aiExpanded, { aiExpanded = !aiExpanded }) {
                OutlinedTextField(
                    value = state.apiKey, onValueChange = { viewModel.setApiKey(it) },
                    label = { Text("API Key") }, visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(), singleLine = true
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = state.baseUrl, onValueChange = { viewModel.setBaseUrl(it) },
                    label = { Text("Base URL") }, modifier = Modifier.fillMaxWidth(), singleLine = true
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = state.model, onValueChange = { viewModel.setModel(it) },
                    label = { Text("Model") }, modifier = Modifier.fillMaxWidth(), singleLine = true
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = state.systemPrompt, onValueChange = { viewModel.setSystemPrompt(it) },
                    label = { Text("System Prompt") }, modifier = Modifier.fillMaxWidth(), minLines = 2
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(onClick = { viewModel.saveAiConfig() }) { Text("保存 AI 配置") }
            }
        }

        // 提示词
        item {
            SettingsSection("提示词管理", promptExpanded, { promptExpanded = !promptExpanded }) {
                OutlinedTextField(
                    value = state.rssPrompt, onValueChange = { viewModel.setRssPrompt(it) },
                    label = { Text("RSS 总结提示词") }, modifier = Modifier.fillMaxWidth(), minLines = 4
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = state.pdfPrompt, onValueChange = { viewModel.setPdfPrompt(it) },
                    label = { Text("PDF 总结提示词") }, modifier = Modifier.fillMaxWidth(), minLines = 4
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(onClick = { viewModel.savePrompts() }) { Text("保存提示词") }
            }
        }

        // RSS 源管理
        item {
            var showAddFeed by remember { mutableStateOf(false) }
            var newFeedTitle by remember { mutableStateOf("") }
            var newFeedUrl by remember { mutableStateOf("") }

            SettingsSection(
                "RSS 源 (${state.feeds.size})",
                feedExpanded,
                { feedExpanded = !feedExpanded },
                trailing = {
                    IconButton(onClick = { feedExpanded = true; showAddFeed = !showAddFeed }) {
                        Icon(Icons.Default.Add, contentDescription = "添加")
                    }
                }
            ) {
                if (showAddFeed) {
                    OutlinedTextField(
                        value = newFeedTitle, onValueChange = { newFeedTitle = it },
                        label = { Text("名称") }, modifier = Modifier.fillMaxWidth(), singleLine = true
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    OutlinedTextField(
                        value = newFeedUrl, onValueChange = { newFeedUrl = it },
                        label = { Text("RSS URL") }, modifier = Modifier.fillMaxWidth(), singleLine = true
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(onClick = {
                        viewModel.addFeed(newFeedTitle, newFeedUrl)
                        newFeedTitle = ""; newFeedUrl = ""; showAddFeed = false
                    }) { Text("添加") }
                    Spacer(modifier = Modifier.height(8.dp))
                }

                state.feeds.forEach { feed ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(feed.title, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                            Text(feed.url, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = { viewModel.deleteFeed(feed.url) }) {
                            Icon(Icons.Default.Delete, contentDescription = "删除", tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }

        item {
            OutlinedButton(onClick = onOpenLogs, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.AutoMirrored.Filled.List, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("查看日志")
            }
        }

        // 重置（常显，不折叠）
        item {
            var showResetDialog by remember { mutableStateOf(false) }

            OutlinedButton(
                onClick = { showResetDialog = true },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
            ) {
                Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                Spacer(modifier = Modifier.width(4.dp))
                Text("删除全部消息并重置收录")
            }

            if (showResetDialog) {
                AlertDialog(
                    onDismissRequest = { showResetDialog = false },
                    title = { Text("确认重置") },
                    text = { Text("确定要删除所有消息和阅读，并重置收录到最近一周吗？此操作不可撤销。") },
                    confirmButton = {
                        TextButton(onClick = {
                            viewModel.resetDigests()
                            showResetDialog = false
                        }) { Text("确定", color = MaterialTheme.colorScheme.error) }
                    },
                    dismissButton = {
                        TextButton(onClick = { showResetDialog = false }) { Text("取消") }
                    }
                )
            }
        }

        item { Spacer(modifier = Modifier.height(80.dp)) }
        }

        state.message?.let { msg ->
            SettingsMessagePopup(
                message = msg,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            )
        }
    }
}

@Composable
private fun SettingsMessagePopup(
    message: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.inverseSurface,
        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
        tonalElevation = 6.dp,
        shadowElevation = 8.dp
    ) {
        Text(
            text = message,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
private fun BackendInfoBox(
    title: String,
    value: String,
    description: String,
    modifier: Modifier = Modifier,
    positive: Boolean? = null,
    action: (@Composable RowScope.() -> Unit)? = null,
) {
    val valueColor = when (positive) {
        true -> MaterialTheme.colorScheme.primary
        false -> MaterialTheme.colorScheme.error
        null -> MaterialTheme.colorScheme.onSurface
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        title,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        value,
                        style = MaterialTheme.typography.bodyMedium,
                        color = valueColor
                    )
                }
                action?.let {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        content = it
                    )
                }
            }
            Text(
                description,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
