package com.rssai.push.ui.messages

import com.rssai.push.data.ProgressResponse
import com.rssai.push.data.TaskProgress
import com.rssai.push.data.local.ReadStore
import com.rssai.push.data.repository.DigestRepository
import com.rssai.push.ui.feed.DigestFeedViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class MessagesViewModel @Inject constructor(
    private val repo: DigestRepository,
    readStore: ReadStore,
) : DigestFeedViewModel(repo, readStore, source = "rss") {

    override suspend fun triggerTask() = repo.runRss()

    // 消息页：RSS 优先展示，其次 PDF（两类任务都可能在跑）。
    override fun relevantProgress(p: ProgressResponse): Pair<TaskProgress?, String> = when {
        p.rss.active -> p.rss to "RSS 抓取中"
        p.pdf.active -> p.pdf to "PDF 监控中"
        else -> null to ""
    }
}
