package com.rssai.push.ui

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.hilt.navigation.compose.hiltViewModel
import com.rssai.push.ui.common.DigestListSkeleton
import com.rssai.push.ui.common.MessageCard
import com.rssai.push.ui.common.ProgressCard
import com.rssai.push.ui.common.SwipeToDeleteItem
import com.rssai.push.ui.reading.ReadingViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReadingScreen(
    onOpenDetail: (String, String) -> Unit = { _, _ -> },
    viewModel: ReadingViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val readSet by viewModel.readSet.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { viewModel.refresh() }
    LifecycleStartEffect(Unit) {
        viewModel.startPolling()
        onStopOrDispose { viewModel.stopPolling() }
    }

    Column(modifier = Modifier.fillMaxSize()) {
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
            label = "readingContent",
            modifier = Modifier.fillMaxSize()
        ) { contentState ->
            when (contentState) {
                ContentState.LOADING -> DigestListSkeleton()
                ContentState.EMPTY -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "暂无 PDF 总结",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
                ContentState.LIST -> PullToRefreshBox(
                    isRefreshing = state.isRefreshing,
                    onRefresh = { viewModel.pullRefresh() },
                    modifier = Modifier.fillMaxSize()
                ) {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
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
                                    onOpenDetail(d.filename, d.title)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
