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

    override suspend fun triggerTask(): Result<String> = repo.runRss().map { "" }

    // 订阅页只展示 RSS 任务；PDF 监控状态只允许出现在阅读页顶部。
    override fun relevantProgress(p: ProgressResponse): Pair<TaskProgress?, String> = when {
        p.rss.active -> p.rss to "RSS 抓取中"
        else -> null to ""
    }
}
