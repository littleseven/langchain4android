package com.picme.domain.agent.remote

import android.util.LruCache
import com.picme.domain.agent.model.AgentCommand

/**
 * L1 本地意图缓存
 *
 * 消除高频指令的 LLM 调用开销，支持：
 * 1. 预置高频意图精确匹配
 * 2. LRU 缓存动态学习
 * 3. 模糊匹配（编辑距离）
 *
 * 本地/远程模式下都先查 L1，命中则直接返回，零延迟。
 */
class IntentCache(maxSize: Int = 100) {

    private val cache = LruCache<String, AgentCommand>(maxSize)

    /**
     * 预置高频意图映射（启动时预热）
     */
    private val presetIntents: Map<String, AgentCommand> = buildPresetIntents()

    /**
     * 尝试匹配用户输入到预定义命令
     *
     * @return 匹配的命令，未命中返回 null
     */
    fun match(input: String): AgentCommand? {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return null

        // 1. 精确匹配预置意图
        presetIntents[trimmed]?.let { return it }

        // 2. LRU 缓存匹配
        cache.get(trimmed)?.let { return it }

        // 3. 模糊匹配（编辑距离 <= 1）
        fuzzyMatch(trimmed)?.let { return it }

        return null
    }

    /**
     * 将解析结果加入缓存（用于学习）
     */
    fun put(input: String, command: AgentCommand) {
        cache.put(input.trim(), command)
    }

    /**
     * 清空缓存（保留预置意图）
     */
    fun clear() {
        cache.evictAll()
    }

    /**
     * 获取当前缓存统计
     */
    fun stats(): CacheStats = CacheStats(
        hitCount = cache.hitCount(),
        missCount = cache.missCount(),
        size = cache.size(),
        maxSize = cache.maxSize()
    )

    private fun fuzzyMatch(input: String): AgentCommand? {
        // 先查预置意图的模糊匹配
        for ((preset, command) in presetIntents) {
            if (levenshteinDistance(input, preset) <= 1) {
                return command
            }
        }
        // 再查缓存的模糊匹配
        for (i in 0 until cache.size()) {
            val key = cache.get(cache.snapshot().keys.elementAtOrNull(i) ?: continue) ?: continue
            // 不遍历缓存做模糊匹配，避免性能问题
        }
        return null
    }

    private fun levenshteinDistance(s1: String, s2: String): Int {
        if (s1 == s2) return 0
        if (s1.isEmpty()) return s2.length
        if (s2.isEmpty()) return s1.length

        val prev = IntArray(s2.length + 1)
        val curr = IntArray(s2.length + 1)

        for (j in 0..s2.length) prev[j] = j

        for (i in 1..s1.length) {
            curr[0] = i
            for (j in 1..s2.length) {
                val cost = if (s1[i - 1] == s2[j - 1]) 0 else 1
                curr[j] = minOf(
                    curr[j - 1] + 1,      // 插入
                    prev[j] + 1,          // 删除
                    prev[j - 1] + cost    // 替换
                )
            }
            prev.indices.forEach { prev[it] = curr[it] }
        }
        return curr[s2.length]
    }

    private fun buildPresetIntents(): Map<String, AgentCommand> {
        return buildMap {
            // 拍照
            put("拍照", AgentCommand.CapturePhoto)
            put("拍一张", AgentCommand.CapturePhoto)
            put("拍照片", AgentCommand.CapturePhoto)
            put("拍个照", AgentCommand.CapturePhoto)
            put("咔嚓", AgentCommand.CapturePhoto)
            put("take a photo", AgentCommand.CapturePhoto)
            put("capture", AgentCommand.CapturePhoto)

            // 录像
            put("录像", AgentCommand.ToggleRecording)
            put("开始录像", AgentCommand.ToggleRecording)
            put("拍视频", AgentCommand.ToggleRecording)
            put("录视频", AgentCommand.ToggleRecording)
            put("停止录像", AgentCommand.ToggleRecording)
            put("结束录像", AgentCommand.ToggleRecording)

            // 翻转摄像头
            put("翻转", AgentCommand.FlipCamera)
            put("切前置", AgentCommand.FlipCamera)
            put("切后置", AgentCommand.FlipCamera)
            put("换摄像头", AgentCommand.FlipCamera)
            put("前后切换", AgentCommand.FlipCamera)
            put("flip camera", AgentCommand.FlipCamera)
            put("switch camera", AgentCommand.FlipCamera)

            // 美颜开关
            put("开美颜", AgentCommand.AdjustBeauty(com.picme.beauty.api.BeautySettings(enabled = true)))
            put("关美颜", AgentCommand.AdjustBeauty(com.picme.beauty.api.BeautySettings(enabled = false)))
            put("打开美颜", AgentCommand.AdjustBeauty(com.picme.beauty.api.BeautySettings(enabled = true)))
            put("关闭美颜", AgentCommand.AdjustBeauty(com.picme.beauty.api.BeautySettings(enabled = false)))

            // 滤镜重置
            put("原图", AgentCommand.SwitchFilter(com.picme.beauty.api.FilterType.NONE))
            put("无滤镜", AgentCommand.SwitchFilter(com.picme.beauty.api.FilterType.NONE))
            put("重置滤镜", AgentCommand.SwitchFilter(com.picme.beauty.api.FilterType.NONE))
        }
    }

    data class CacheStats(
        val hitCount: Int,
        val missCount: Int,
        val size: Int,
        val maxSize: Int
    ) {
        val hitRate: Float
            get() = if (hitCount + missCount > 0) {
                hitCount.toFloat() / (hitCount + missCount)
            } else 0f
    }
}
