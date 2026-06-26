package com.rssai.push.ui.reading

import com.rssai.push.data.ProgressResponse
import com.rssai.push.data.TaskProgress
import com.rssai.push.data.local.ReadStore
import com.rssai.push.data.repository.DigestRepository
import com.rssai.push.ui.feed.DigestFeedViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class ReadingViewModel @Inject constructor(
    private val repo: DigestRepository,
    readStore: ReadStore,
) : DigestFeedViewModel(repo, readStore, source = "pdf") {

    override val triggerOnPullRefresh: Boolean = true

    override suspend fun triggerTask(): Result<String> = repo.runPdf()

    // 阅读页只关注 PDF 任务。
    override fun relevantProgress(p: ProgressResponse): Pair<TaskProgress?, String> =
        if (p.pdf.active) p.pdf to "PDF 监控中" else null to ""
}
