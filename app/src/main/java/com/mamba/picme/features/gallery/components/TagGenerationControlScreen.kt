@file:OptIn(ExperimentalLayoutApi::class)

package com.mamba.picme.features.gallery.components

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import com.mamba.picme.PicMeApplication
import com.mamba.picme.data.local.AppDatabase
import com.mamba.picme.data.local.entity.TagScanPass
import com.mamba.picme.domain.tag.TagCategory
import com.mamba.picme.domain.tag.scan.ScanSessionState
import com.mamba.picme.domain.tag.scan.TagScanSessionProgress
import com.mamba.picme.service.tag.TagGenerationService
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * TAG 生成精细控制子页面
 *
 * 显示 3-Pass 混合管道的各阶段进度和数据库统计。
 * MobileCLIP 语义编码已内联合并到 Pass 1，不再作为独立 Pass 4 显示。
 * 所有操作通过 TagGenerationService → TagScanOrchestrator 统一管理。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TagGenerationControlScreen(
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val app = context.applicationContext as PicMeApplication
    val db = remember { AppDatabase.getDatabase(context) }
    val coroutineScope = rememberCoroutineScope()

    // ── 通过 AppContainer 观察 TAG 生成状态（Service 内部分发） ───
    val sessionProgress by app.container.tagGenerationSessionProgress.collectAsState()
    val isScanning by app.container.tagGenerationIsScanning.collectAsState()
    val currentState = sessionProgress?.state
    val isRunning = currentState == ScanSessionState.RUNNING
    val isPausing = currentState == ScanSessionState.PAUSING
    val isPaused = currentState == ScanSessionState.PAUSED
    val isCancelling = currentState == ScanSessionState.CANCELLING
    val isCancelled = currentState == ScanSessionState.CANCELLED
    val snackbarHostState = remember { SnackbarHostState() }

    // 启动前台 Service（Intent 驱动，无外部 Handle）
    LaunchedEffect(Unit) {
        TagGenerationService.startForeground(context)
    }

    // 数据库统计（每次进入时刷新）
    var totalMedia by remember { mutableIntStateOf(0) }
    var withFace by remember { mutableIntStateOf(0) }
    var withLabels by remember { mutableIntStateOf(0) }
    var withSemantic by remember { mutableIntStateOf(0) }
    var personCount by remember { mutableIntStateOf(0) }
    var embeddingCount by remember { mutableIntStateOf(0) }
    var remainingPass1 by remember { mutableIntStateOf(0) }
    var remainingPass3 by remember { mutableIntStateOf(0) }

    // 精细控制：类别 / 时间范围 / 模式
    var selectedCategories by remember { mutableStateOf(setOf<TagCategory>()) }
    var selectedTimeRange by remember { mutableStateOf(TimeRangePreset.ALL) }
    var fullRegenerateMode by remember { mutableStateOf(false) }

    // 刷新统计：统一通过 TagScanOrchestrator.getDbStats(db) 获取，
    // 不依赖 Service/Orchestrator 实例，进入页面即可立即显示。
    fun refreshStats() {
        coroutineScope.launch {
            try {
                android.util.Log.d("TagGenControl", "refreshStats() called")
                val stats = com.mamba.picme.domain.tag.scan.TagScanOrchestrator.getDbStats(db)
                android.util.Log.d("TagGenControl", "stats=$stats")
                totalMedia = stats.totalMedia
                withFace = stats.withFace
                withLabels = stats.withLabels
                withSemantic = stats.withSemantic
                personCount = stats.personCount
                embeddingCount = stats.faceEmbeddingCount
                remainingPass1 = stats.remainingForPass1
                remainingPass3 = stats.remainingForPass3
            } catch (e: Exception) {
                android.util.Log.e("TagGenControl", "refreshStats failed", e)
            }
        }
    }

    // 显示扫描完成通知
    LaunchedEffect(sessionProgress?.messages?.lastOrNull()?.text) {
        val msg = sessionProgress?.messages?.lastOrNull()?.text ?: return@LaunchedEffect
        if (sessionProgress?.state == ScanSessionState.COMPLETED) {
            refreshStats()
            snackbarHostState.showSnackbar(msg)
        }
    }

    // 初始加载统计
    LaunchedEffect(Unit) {
        android.util.Log.d("TagGenControl", "initial refreshStats()")
        refreshStats()
    }

    // 轮询更新数据库累计统计（每秒刷新，不依赖 isScanning 状态，便于诊断）
    LaunchedEffect(Unit) {
        android.util.Log.d("TagGenControl", "poll loop started")
        while (true) {
            android.util.Log.d("TagGenControl", "poll tick, isScanning=${app.container.tagGenerationIsScanning.value}")
            refreshStats()
            delay(1000)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("TAG 生成控制") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // ── 扫描进度卡片 ────────────────────────────
            AnimatedVisibility(visible = sessionProgress != null) {
                sessionProgress?.let { ScanProgressCard(it) }
            }

            // ── 数据库累计统计卡片 ────────────────────────────
            StatsCard(
                totalMedia = totalMedia,
                withFace = withFace,
                withLabels = withLabels,
                withSemantic = withSemantic,
                personCount = personCount,
                embeddingCount = embeddingCount,
                remainingPass1 = remainingPass1,
                remainingPass3 = remainingPass3
            )

            // ── 3-Pass 混合管道概览卡片 ────────────────
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "3-Pass 混合管道",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(8.dp))

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            if (withFace > 0 || !isScanning) Icons.Rounded.CheckCircle else Icons.Rounded.HourglassEmpty,
                            null,
                            modifier = Modifier.size(20.dp),
                            tint = if (withFace > 0 || !isScanning) Color(0xFF4CAF50) else MaterialTheme.colorScheme.outline
                        )
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Pass 1: 人脸检测 + MobileCLIP 语义编码（内联）", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "RetinaFace + MobileCLIP-S2 → $withFace / $totalMedia 张 · 有语义 $withSemantic 张",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                        }
                    }

                    Spacer(Modifier.height(8.dp))

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            if (personCount > 0 || !isScanning) Icons.Rounded.CheckCircle else Icons.Rounded.HourglassEmpty,
                            null,
                            modifier = Modifier.size(20.dp),
                            tint = if (personCount > 0 || !isScanning) Color(0xFF4CAF50) else MaterialTheme.colorScheme.outline
                        )
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Pass 2: DBSCAN 全局聚类", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "$personCount 个人物簇",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                        }
                    }

                    Spacer(Modifier.height(8.dp))

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            if (withLabels > 0 || !isScanning) Icons.Rounded.CheckCircle else Icons.Rounded.HourglassEmpty,
                            null,
                            modifier = Modifier.size(20.dp),
                            tint = if (withLabels > 0 || !isScanning) Color(0xFF4CAF50) else MaterialTheme.colorScheme.outline
                        )
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Pass 3: Qwen 图像理解标签", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "Qwen3.5-2B → $withLabels / $totalMedia 张",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                        }
                    }

                    Spacer(Modifier.height(12.dp))

                    if (isRunning || isPausing || isPaused) {
                        // ── 运行中/暂停中/已暂停：显示生命周期控制按钮 ─────
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            when {
                                isRunning -> {
                                    OutlinedButton(
                                        onClick = {
                                            context.startForegroundService(TagGenerationService.intentPause(context))
                                        },
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Icon(Icons.Rounded.Pause, null, Modifier.size(16.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("暂停")
                                    }
                                }
                                isPausing -> {
                                    OutlinedButton(
                                        onClick = {},
                                        enabled = false,
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Icon(Icons.Rounded.Pause, null, Modifier.size(16.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("暂停中...")
                                    }
                                }
                                isPaused -> {
                                    Button(
                                        onClick = {
                                            context.startForegroundService(TagGenerationService.intentResume(context))
                                        },
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Icon(Icons.Rounded.PlayArrow, null, Modifier.size(16.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("恢复")
                                    }
                                }
                            }

                            OutlinedButton(
                                onClick = {
                                    context.startForegroundService(TagGenerationService.intentCancel(context))
                                },
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = MaterialTheme.colorScheme.error
                                ),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Rounded.Cancel, null, Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("取消")
                            }

                            if ((sessionProgress?.failed ?: 0) > 0) {
                                OutlinedButton(
                                    onClick = {
                                        context.startForegroundService(TagGenerationService.intentRetryFailed(context))
                                    },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.Rounded.Refresh, null, Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("重试")
                                }
                            }
                        }
                    } else {
                        // ── 全量/增量扫描按钮 ─────────────
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = {
                                    refreshStats()
                                    context.startForegroundService(TagGenerationService.intentScanAll(context))
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Rounded.PlayArrow, null, Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("全量扫描")
                            }
                            OutlinedButton(
                                onClick = {
                                    refreshStats()
                                    context.startForegroundService(TagGenerationService.intentScanIncremental(context))
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Rounded.Update, null, Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("增量扫描")
                            }
                        }

                        Spacer(Modifier.height(8.dp))
                        HorizontalDivider()
                        Spacer(Modifier.height(8.dp))

                        // ── 分阶段独立控制 ────────────────
                        Text(
                            "分阶段执行",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                        Spacer(Modifier.height(8.dp))

                        PassControlCard(
                            title = "Pass 1：人脸检测 + MobileCLIP 语义编码",
                            subtitle = "RetinaFace + MobileCLIP-S2：检测人脸并提取语义向量",
                            onIncremental = {
                                refreshStats()
                                context.startForegroundService(TagGenerationService.intentScanPass1(context))
                            },
                            onFull = {
                                refreshStats()
                                context.startForegroundService(TagGenerationService.intentScanPass1Full(context))
                            }
                        )

                        Spacer(Modifier.height(8.dp))

                        PassControlCard(
                            title = "Pass 2：DBSCAN 人脸聚类",
                            subtitle = "基于全量人脸 Embedding 进行全局聚类",
                            onIncremental = {
                                refreshStats()
                                context.startForegroundService(TagGenerationService.intentScanPass2(context))
                            },
                            onFull = {
                                refreshStats()
                                context.startForegroundService(TagGenerationService.intentScanPass2Full(context))
                            }
                        )

                        Spacer(Modifier.height(8.dp))

                        PassControlCard(
                            title = "Pass 3：Qwen 图像理解标签",
                            subtitle = "Qwen3.5-2B：生成场景、物体、活动、摘要等标签",
                            onIncremental = {
                                refreshStats()
                                context.startForegroundService(TagGenerationService.intentScanPass3(context))
                            },
                            onFull = {
                                refreshStats()
                                context.startForegroundService(TagGenerationService.intentScanPass3Full(context))
                            }
                        )

                        Spacer(Modifier.height(8.dp))
                        HorizontalDivider()
                        Spacer(Modifier.height(8.dp))

                        // ── MobileCLIP 语义编码单独重编码（兼容/高级） ──────────
                        Text(
                            "MobileCLIP 语义编码单独重编码",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "仅重新生成语义向量，不影响人脸检测与标签结果。常规扫描已内联合并到 Pass 1。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                        Spacer(Modifier.height(8.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = {
                                    refreshStats()
                                    context.startForegroundService(TagGenerationService.intentScanPass4(context))
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("增量生成")
                            }
                            Button(
                                onClick = {
                                    refreshStats()
                                    context.startForegroundService(TagGenerationService.intentScanPass4Full(context))
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.tertiary
                                ),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("全部重新生成")
                            }
                        }

                        Spacer(Modifier.height(8.dp))
                        HorizontalDivider()
                        Spacer(Modifier.height(8.dp))

                        // ── 精细控制：按类别 / 时间范围重新生成 ──────────
                        Text(
                            "精细控制",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                        Spacer(Modifier.height(8.dp))

                        Text(
                            "选择 TAG 类别",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                        )
                        Spacer(Modifier.height(4.dp))
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            CategoryChip(
                                label = "人脸",
                                selected = TagCategory.FACE in selectedCategories,
                                onClick = {
                                    selectedCategories = selectedCategories.toggle(TagCategory.FACE)
                                }
                            )
                            CategoryChip(
                                label = "场景",
                                selected = TagCategory.SCENE in selectedCategories,
                                onClick = {
                                    selectedCategories = selectedCategories.toggle(TagCategory.SCENE)
                                }
                            )
                            CategoryChip(
                                label = "活动",
                                selected = TagCategory.ACTIVITY in selectedCategories,
                                onClick = {
                                    selectedCategories = selectedCategories.toggle(TagCategory.ACTIVITY)
                                }
                            )
                            CategoryChip(
                                label = "物体",
                                selected = TagCategory.OBJECTS in selectedCategories,
                                onClick = {
                                    selectedCategories = selectedCategories.toggle(TagCategory.OBJECTS)
                                }
                            )
                            CategoryChip(
                                label = "标签",
                                selected = TagCategory.TAGS in selectedCategories,
                                onClick = {
                                    selectedCategories = selectedCategories.toggle(TagCategory.TAGS)
                                }
                            )
                            CategoryChip(
                                label = "摘要",
                                selected = TagCategory.SUMMARY in selectedCategories,
                                onClick = {
                                    selectedCategories = selectedCategories.toggle(TagCategory.SUMMARY)
                                }
                            )
                        }

                        Spacer(Modifier.height(8.dp))

                        Text(
                            "时间范围",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                        )
                        Spacer(Modifier.height(4.dp))
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            TimeRangePreset.entries.forEach { preset ->
                                CategoryChip(
                                    label = preset.label,
                                    selected = selectedTimeRange == preset,
                                    onClick = { selectedTimeRange = preset }
                                )
                            }
                        }

                        Spacer(Modifier.height(8.dp))

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                "模式: ${if (fullRegenerateMode) "全量重标" else "仅补充缺失"}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                            )
                            Switch(
                                checked = fullRegenerateMode,
                                onCheckedChange = { fullRegenerateMode = it }
                            )
                        }

                        Spacer(Modifier.height(8.dp))

                        Button(
                            onClick = {
                                refreshStats()
                                val categories = selectedCategories.ifEmpty { TagCategory.ALL }
                                val startTimeMs = selectedTimeRange.startTimeMs
                                context.startForegroundService(
                                    TagGenerationService.intentRegenerateCategories(
                                        context = context,
                                        categories = categories.map { it.name },
                                        startTimeMs = startTimeMs,
                                        fullMode = fullRegenerateMode
                                    )
                                )
                            },
                            enabled = selectedCategories.isNotEmpty() || selectedTimeRange != TimeRangePreset.ALL,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Rounded.Tune, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("按选择重新生成")
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun ScanProgressCard(progress: TagScanSessionProgress) {
    val isScanning = progress.state in setOf(
        ScanSessionState.RUNNING,
        ScanSessionState.PAUSING,
        ScanSessionState.CANCELLING
    )
    val stateText = when (progress.state) {
        ScanSessionState.RUNNING -> "扫描中"
        ScanSessionState.PAUSING -> "暂停中"
        ScanSessionState.PAUSED -> "已暂停"
        ScanSessionState.CANCELLING -> "取消中"
        ScanSessionState.CANCELLED -> "已取消"
        ScanSessionState.COMPLETED -> "完成"
        ScanSessionState.IDLE -> "空闲"
    }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = when (progress.state) {
                ScanSessionState.PAUSED -> MaterialTheme.colorScheme.secondaryContainer
                ScanSessionState.CANCELLED -> MaterialTheme.colorScheme.errorContainer
                else -> MaterialTheme.colorScheme.primaryContainer
            }
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isScanning) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                } else {
                    Icon(
                        Icons.Rounded.Info,
                        null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
                Spacer(Modifier.width(12.dp))
                val subtitle = when {
                    progress.state == ScanSessionState.CANCELLING -> "等待当前任务结束"
                    progress.state == ScanSessionState.CANCELLED -> ""
                    progress.state == ScanSessionState.COMPLETED -> ""
                    progress.currentPass == null -> "准备中"
                    else -> passDisplayName(progress.currentPass)
                }
                Text(
                    text = if (subtitle.isNotEmpty()) "$stateText · $subtitle" else stateText,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = {
                    if (progress.total > 0) progress.processed.toFloat() / progress.total else 0f
                },
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                trackColor = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.2f)
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "任务 ${progress.processed}/${progress.total} 完成 · 待处理 ${progress.pending} · 失败 ${progress.failed}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            if (progress.estimatedRemainingMs != null && isScanning) {
                Text(
                    "预计剩余: ${formatDuration(progress.estimatedRemainingMs)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                )
            }
            if (progress.messages.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    progress.messages.last().text,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                )
            }
        }
    }
}

private fun passDisplayName(pass: TagScanPass?): String = when (pass) {
    TagScanPass.FACE_DETECTION -> "Pass 1: 人脸检测 + MobileCLIP"
    TagScanPass.DBSCAN -> "Pass 2: DBSCAN 聚类"
    TagScanPass.QWEN_TAGGING -> "Pass 3: Qwen 标签"
    TagScanPass.MOBILE_CLIP_ENCODING -> "MobileCLIP 语义编码（单独）"
    null -> "准备中"
}

@Composable
private fun PassControlCard(
    title: String,
    subtitle: String,
    onIncremental: () -> Unit,
    onFull: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onIncremental,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("增量生成")
                }
                Button(
                    onClick = onFull,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.secondary
                    ),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("全部重新生成")
                }
            }
        }
    }
}

private fun formatDuration(ms: Long): String {
    val seconds = ms / 1000
    val minutes = seconds / 60
    val hours = minutes / 60
    return when {
        hours > 0 -> "${hours}h ${minutes % 60}m"
        minutes > 0 -> "${minutes}m ${seconds % 60}s"
        else -> "${seconds}s"
    }
}

private enum class TimeRangePreset(val label: String, private val startOffsetMs: Long) {
    ALL("全部", 0),
    DAYS_7("最近7天", 7 * 24 * 60 * 60 * 1000L),
    DAYS_30("最近30天", 30 * 24 * 60 * 60 * 1000L),
    DAYS_90("最近90天", 90 * 24 * 60 * 60 * 1000L);

    val startTimeMs: Long
        get() = if (startOffsetMs > 0) System.currentTimeMillis() - startOffsetMs else 0L
}

private fun Set<TagCategory>.toggle(category: TagCategory): Set<TagCategory> {
    return if (category in this) this - category else this + category
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CategoryChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) }
    )
}

@Composable
private fun StatsCard(
    totalMedia: Int,
    withFace: Int,
    withLabels: Int,
    withSemantic: Int,
    personCount: Int,
    embeddingCount: Int,
    remainingPass1: Int,
    remainingPass3: Int
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "数据库累计统计",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                StatItem("总照片", totalMedia.toString())
                StatItem("含人脸", withFace.toString())
                StatItem("有标签", withLabels.toString())
                StatItem("有语义", withSemantic.toString())
                StatItem("人物簇", personCount.toString())
            }
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                StatItem("Embedding", embeddingCount.toString())
                StatItem("Pass1剩余", remainingPass1.toString())
                StatItem("Pass3剩余", remainingPass3.toString())
            }
        }
    }
}

@Composable
private fun RowScope.StatItem(
    label: String,
    value: String,
    valueColor: Color = MaterialTheme.colorScheme.onSurface
) {
    Column(
        modifier = Modifier.weight(1f),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = valueColor
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
    }
}
