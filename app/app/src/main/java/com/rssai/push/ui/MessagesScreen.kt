package com.rssai.push.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.hilt.navigation.compose.hiltViewModel
import com.rssai.push.data.Digest
import com.rssai.push.ui.common.DigestListSkeleton
import com.rssai.push.ui.common.MessageCard
import com.rssai.push.ui.common.ProgressCard
import com.rssai.push.ui.common.SwipeToDeleteItem
import com.rssai.push.ui.messages.MessagesViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessagesScreen(
    onOpenDigest: (String, String) -> Unit = { _, _ -> },
    viewModel: MessagesViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val readSet by viewModel.readSet.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { viewModel.refresh() }
    LifecycleStartEffect(Unit) {
        viewModel.startPolling()
        onStopOrDispose { viewModel.stopPolling() }
    }

    var menuExpanded by remember { mutableStateOf(false) }
    var collapsedJournalKeys by rememberSaveable { mutableStateOf(emptyList<String>()) }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            Surface(
                color = MaterialTheme.colorScheme.background,
                tonalElevation = 0.dp,
                shadowElevation = 0.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(64.dp)
                        .padding(start = 16.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "订阅",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            if (state.groupByJournal) "按期刊分组 · ${state.digests.size} 篇"
                            else "最近推送 · ${state.digests.size} 篇",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Box {
                        IconButton(onClick = { menuExpanded = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "菜单")
                        }
                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = {
                                    Text(if (state.groupByJournal) "取消期刊分组" else "按期刊分组")
                                },
                                onClick = {
                                    menuExpanded = false
                                    viewModel.toggleGrouping()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("全部已读") },
                                onClick = {
                                    menuExpanded = false
                                    viewModel.markAllRead()
                                }
                            )
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            state.error?.let { err ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(err, modifier = Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onErrorContainer)
                }
            }

            androidx.compose.animation.AnimatedVisibility(visible = state.notice != null) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(
                        state.notice ?: "",
                        modifier = Modifier.padding(12.dp),
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        fontSize = 13.sp
                    )
                }
            }

            state.progress?.let { prog ->
                Box(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                    ProgressCard(state.progressLabel, prog)
                }
            }

            Crossfade(
                targetState = when {
                    state.isLoading && state.digests.isEmpty() -> ContentState.LOADING
                    state.digests.isEmpty() -> ContentState.EMPTY
                    else -> ContentState.LIST
                },
                label = "messagesContent",
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surface)
            ) { contentState ->
                when (contentState) {
                    ContentState.LOADING -> DigestListSkeleton()
                    ContentState.EMPTY -> PullToRefreshBox(
                        isRefreshing = state.isRefreshing,
                        onRefresh = { viewModel.pullRefresh() },
                        modifier = Modifier.fillMaxSize()
                    ) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("暂无消息", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    ContentState.LIST -> PullToRefreshBox(
                        isRefreshing = state.isRefreshing,
                        onRefresh = { viewModel.pullRefresh() },
                        modifier = Modifier.fillMaxSize()
                    ) {
                        if (state.groupByJournal) {
                            val groups = remember(state.digests) { groupDigestsByJournal(state.digests) }
                            val activeGroupKeys = remember(groups) { groups.map { it.key }.toSet() }
                            LaunchedEffect(activeGroupKeys) {
                                collapsedJournalKeys = collapsedJournalKeys.filter { it in activeGroupKeys }
                            }
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(MaterialTheme.colorScheme.surface),
                                contentPadding = PaddingValues(start = 12.dp, top = 10.dp, end = 12.dp, bottom = 0.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                groups.forEach { group ->
                                    val unreadCount = group.digests.count { it.filename !in readSet }
                                    val collapsed = group.key in collapsedJournalKeys
                                    item(key = "header_${group.key}") {
                                        JournalGroupHeader(
                                            title = group.title,
                                            total = group.digests.size,
                                            unread = unreadCount,
                                            collapsed = collapsed,
                                            onToggle = {
                                                collapsedJournalKeys = if (collapsed) {
                                                    collapsedJournalKeys - group.key
                                                } else {
                                                    collapsedJournalKeys + group.key
                                                }
                                            }
                                        )
                                    }
                                    if (!collapsed) {
                                        items(group.digests, key = { it.filename }) { d ->
                                            val read = d.filename in readSet
                                            SwipeToDeleteItem(
                                                onDelete = { viewModel.delete(d.filename) },
                                                modifier = Modifier.animateItem()
                                            ) {
                                                MessageCard(d, read) {
                                                    viewModel.markRead(d.filename)
                                                    onOpenDigest(d.filename, d.title)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        } else {
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(MaterialTheme.colorScheme.surface),
                                contentPadding = PaddingValues(start = 12.dp, top = 10.dp, end = 12.dp, bottom = 0.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                items(state.digests, key = { it.filename }) { d ->
                                    val read = d.filename in readSet
                                    SwipeToDeleteItem(
                                        onDelete = { viewModel.delete(d.filename) },
                                        modifier = Modifier.animateItem()
                                    ) {
                                        MessageCard(d, read) {
                                            viewModel.markRead(d.filename)
                                            onOpenDigest(d.filename, d.title)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

internal enum class ContentState { LOADING, EMPTY, LIST }

private data class JournalDigestGroup(
    val key: String,
    val title: String,
    val digests: List<Digest>,
)

private fun groupDigestsByJournal(digests: List<Digest>): List<JournalDigestGroup> =
    digests
        .groupBy { journalGroupKey(it) }
        .map { (key, items) ->
            JournalDigestGroup(
                key = key,
                title = journalGroupName(items.first()),
                digests = items
            )
        }
        .sortedWith(
            compareBy<JournalDigestGroup> { it.title == "未标注期刊" }
                .thenBy { it.title.lowercase() }
        )

@Composable
private fun JournalGroupHeader(
    title: String,
    total: Int,
    unread: Int,
    collapsed: Boolean,
    onToggle: () -> Unit,
) {
    val arrowRotation by animateFloatAsState(
        targetValue = if (collapsed) -90f else 0f,
        label = "journalGroupArrow"
    )

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 5.dp, bottom = 1.dp)
            .clickable(onClick = onToggle),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f),
        border = BorderStroke(0.6.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.32f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    if (collapsed) "期刊分组 · 已折叠" else "期刊分组",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                imageVector = Icons.Default.KeyboardArrowDown,
                contentDescription = if (collapsed) "展开期刊分组" else "折叠期刊分组",
                modifier = Modifier.size(20.dp).rotate(arrowRotation),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Surface(
                shape = RoundedCornerShape(50),
                color = if (unread > 0) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.surface,
                border = if (unread > 0) null
                    else BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f))
            ) {
                Text(
                    if (unread > 0) "$unread 未读 · $total 篇" else "$total 篇",
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = if (unread > 0) MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
        }
    }
}
