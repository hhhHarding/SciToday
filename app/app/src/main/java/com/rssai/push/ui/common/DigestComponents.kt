package com.rssai.push.ui.common

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rssai.push.data.Digest
import com.rssai.push.data.TaskProgress
import com.rssai.push.ui.formatDigestTimestamp
import com.rssai.push.ui.journalAbbr
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

// 消息页 / 阅读页共用的列表与卡片组件。两屏 90% 重复，统一抽到 common 复用。

@Composable
fun SwipeToDeleteItem(
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val density = LocalDensity.current
    val revealPx = with(density) { 72.dp.toPx() }
    val offsetX = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()

    val bgAlpha = if (offsetX.value < 0f) {
        (-offsetX.value / revealPx).coerceIn(0f, 1f)
    } else 0f

    Box(modifier = modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(MaterialTheme.colorScheme.error.copy(alpha = bgAlpha), RoundedCornerShape(10.dp))
                .padding(horizontal = 16.dp),
            contentAlignment = Alignment.CenterEnd
        ) {
            if (bgAlpha > 0.3f) {
                IconButton(onClick = {
                    scope.launch {
                        offsetX.animateTo(0f, tween(200))
                        onDelete()
                    }
                }) {
                    Icon(Icons.Default.Delete, contentDescription = "删除", tint = Color.White.copy(alpha = bgAlpha))
                }
            }
        }

        Box(
            modifier = Modifier
                .offset { IntOffset(offsetX.value.roundToInt(), 0) }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            scope.launch {
                                if (offsetX.value < -revealPx / 2) {
                                    offsetX.animateTo(-revealPx, tween(200))
                                } else {
                                    offsetX.animateTo(0f, tween(200))
                                }
                            }
                        },
                        onHorizontalDrag = { _, dragAmount ->
                            scope.launch {
                                val newOffset = (offsetX.value + dragAmount).coerceIn(-revealPx, 0f)
                                offsetX.snapTo(newOffset)
                            }
                        }
                    )
                }
        ) {
            content()
        }
    }
}

@Composable
fun MessageCard(digest: Digest, read: Boolean, onClick: () -> Unit) {
    val titleColor = if (read) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
    // 题目加粗：未读最重，已读略轻但仍醒目。
    val titleWeight = if (read) FontWeight.SemiBold else FontWeight.Bold
    // 未读用主色淡染背景 + 主色描边，已读纯净 surface，对比更分明。
    val bgColor = if (read) MaterialTheme.colorScheme.surface
        else MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = bgColor),
        shape = RoundedCornerShape(10.dp),
        border = if (read) null
            else androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f))
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            if (!read) {
                Box(
                    modifier = Modifier
                        .width(5.dp)
                        .height(IntrinsicSize.Max)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(topStart = 10.dp, bottomStart = 10.dp))
                        .background(MaterialTheme.colorScheme.primary)
                )
            }
            Column(modifier = Modifier.padding(12.dp).weight(1f)) {
                Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (!read) {
                        // 未读圆点指示
                        Box(
                            modifier = Modifier
                                .padding(top = 5.dp)
                                .size(8.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(MaterialTheme.colorScheme.primary)
                        )
                    }
                    Text(
                        digest.title,
                        modifier = Modifier.weight(1f),
                        fontWeight = titleWeight,
                        color = titleColor,
                        fontSize = 14.sp,
                        maxLines = 3
                    )
                }
                if (digest.cn_title.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        digest.cn_title,
                        fontWeight = if (read) FontWeight.SemiBold else FontWeight.Bold,
                        color = if (read) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
                        fontSize = 13.sp,
                        maxLines = 3
                    )
                }
                if (digest.keywords.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        "关键词：${digest.keywords}",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2
                    )
                }
                if (digest.preview.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(
                        digest.preview,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                // 底部行：时间（左） + 期刊缩写（右）
                val abbr = journalAbbr(digest.journal)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        formatDigestTimestamp(digest.timestamp),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (abbr.isNotEmpty()) {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                        ) {
                            Text(
                                abbr,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ProgressCard(title: String, progress: TaskProgress) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    title,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            if (progress.total > 0) {
                LinearProgressIndicator(
                    progress = { progress.current.toFloat() / progress.total.toFloat() },
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "${progress.current} / ${progress.total}",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
            if (progress.message.isNotEmpty()) {
                Text(
                    progress.message,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                    maxLines = 2
                )
            }
        }
    }
}

@Composable
fun StatCard(label: String, value: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(value, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
