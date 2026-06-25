package com.rssai.push.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rssai.push.data.ApiClient
import com.rssai.push.data.ChatMessage
import com.rssai.push.ui.reading.ReadingDetailViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReadingDetailScreen(filename: String, title: String, onBack: () -> Unit) {
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    val tabs = listOf("文章内容", "PDF原文", "提问")

    BackHandler { onBack() }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text(title, maxLines = 1, fontSize = 14.sp) },
                    windowInsets = WindowInsets(0, 0, 0, 0),
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                        }
                    }
                )
                TabRow(selectedTabIndex = selectedTab) {
                    tabs.forEachIndexed { index, name ->
                        Tab(
                            selected = selectedTab == index,
                            onClick = { selectedTab = index },
                            text = { Text(name) }
                        )
                    }
                }
            }
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (selectedTab) {
                0 -> DigestWebView(
                    url = ApiClient.inboxUrl(filename),
                    modifier = Modifier.fillMaxSize()
                )
                1 -> PdfViewer(filename = filename, modifier = Modifier.fillMaxSize())
                2 -> ChatTab(filename = filename)
            }
        }
    }
}

@Composable
private fun ChatTab(
    filename: String,
    viewModel: ReadingDetailViewModel = hiltViewModel(),
) {
    val chat by viewModel.chat.collectAsStateWithLifecycle()
    var input by rememberSaveable { mutableStateOf("") }
    val keyboard = LocalSoftwareKeyboardController.current
    val listState = rememberLazyListState()

    LaunchedEffect(filename) { viewModel.loadChat(filename) }

    LaunchedEffect(chat.messages.size) {
        if (chat.messages.isNotEmpty()) {
            listState.animateScrollToItem(chat.messages.size - 1)
        }
    }

    fun send() {
        val text = input.trim()
        if (text.isEmpty() || chat.loading) return
        input = ""
        keyboard?.hide()
        viewModel.send(filename, text)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        if (chat.messages.isEmpty()) {
            Box(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "向 AI 追问这篇文章的相关内容\n例如：研究方法、主要结论、创新点……",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                state = listState,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(chat.messages) { m ->
                    ChatBubble(m)
                }
                if (chat.loading) {
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(4.dp),
                            horizontalArrangement = Arrangement.Start
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        }
                    }
                }
            }
        }

        // 输入栏
        Surface(tonalElevation = 2.dp) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("输入追问…") },
                    maxLines = 4,
                    enabled = !chat.loading
                )
                Spacer(modifier = Modifier.width(8.dp))
                IconButton(onClick = { send() }, enabled = !chat.loading && input.isNotBlank()) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "发送")
                }
            }
        }
    }
}

@Composable
private fun ChatBubble(message: ChatMessage) {
    val isUser = message.role == "user"
    val bg = if (isUser) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (isUser) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    val align = if (isUser) Arrangement.End else Arrangement.Start

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = align
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = bg,
            modifier = Modifier.widthIn(max = 300.dp)
        ) {
            Text(
                message.content,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                color = fg,
                fontSize = 14.sp,
                fontWeight = FontWeight.Normal
            )
        }
    }
}
