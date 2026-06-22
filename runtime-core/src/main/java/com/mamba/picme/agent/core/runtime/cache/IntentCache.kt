package com.mamba.picme.agent.core.runtime.cache

import com.mamba.picme.agent.core.model.command.AgentCommand
import com.mamba.picme.beauty.api.BeautySettings
import com.mamba.picme.beauty.api.FilterType
import com.mamba.picme.beauty.api.StyleFilter

/**
 * L1 本地意图缓存
 *
 * 消除高频指令的 LLM 调用开销，支持：
 * 1. 预置高频意图精确匹配
 * 2. LRU 缓存动态学习
 * 3. 模糊匹配（编辑距离）
 *
 * 本地/远程模式下都先查 L1，命中则直接返回，零延迟。
 *
 * 使用 LinkedHashMap 实现 LRU，兼容 JVM 单元测试（无需 Android 框架）。
 */
class IntentCache(maxSize: Int = 100) {

    private val maxCacheSize = maxSize

    private val cache = object : LinkedHashMap<String, AgentCommand>(maxSize, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, AgentCommand>?): Boolean {
            return size > maxCacheSize
        }
    }

    /**
     * 预置高频意图映射（启动时预热）
     */
    private val presetIntents: Map<String, AgentCommand> = buildPresetIntents()

    private var hitCount = 0
    private var missCount = 0

    /**
     * 尝试匹配用户输入到预定义命令
     *
     * @return 匹配的命令，未命中返回 null
     */
    fun match(input: String): AgentCommand? {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return null

        // 调试开关：关闭 L1 缓存时直接未命中
        if (!L1CacheSettings.isEnabled()) {
            return null
        }

        // 1. 精确匹配预置意图
        presetIntents[trimmed]?.let {
            hitCount++
            return it
        }

        // 2. LRU 缓存匹配
        cache[trimmed]?.let {
            hitCount++
            return it
        }

        // 3. 模糊匹配（编辑距离 <= 1）
        fuzzyMatch(trimmed)?.let {
            hitCount++
            return it
        }

        missCount++
        return null
    }

    /**
     * 将解析结果加入缓存（用于学习）
     */
    fun put(input: String, command: AgentCommand) {
        cache[input.trim()] = command
    }

    /**
     * 清空缓存（保留预置意图）
     */
    fun clear() {
        cache.clear()
    }

    /**
     * 获取当前缓存统计
     */
    fun stats(): CacheStats = CacheStats(
        hitCount = hitCount,
        missCount = missCount,
        size = cache.size,
        maxSize = maxCacheSize
    )

    private fun fuzzyMatch(input: String): AgentCommand? {
        for ((preset, command) in presetIntents) {
            if (levenshteinDistance(input, preset) <= 1) {
                return command
            }
        }

        var bestDistance = Int.MAX_VALUE
        var bestCommand: AgentCommand? = null
        for ((cachedInput, command) in cache) {
            val dist = levenshteinDistance(input, cachedInput)
            if (dist <= 1 && dist < bestDistance) {
                bestDistance = dist
                bestCommand = command
            }
        }
        return bestCommand
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
                    curr[j - 1] + 1,
                    prev[j] + 1,
                    prev[j - 1] + cost
                )
            }
            prev.indices.forEach { prev[it] = curr[it] }
        }
        return curr[s2.length]
    }

    private fun buildPresetIntents(): Map<String, AgentCommand> {
        return buildMap {
            put("拍照", AgentCommand.CapturePhoto())
            put("拍一张", AgentCommand.CapturePhoto())
            put("拍张照", AgentCommand.CapturePhoto())
            put("拍照片", AgentCommand.CapturePhoto())
            put("拍个照", AgentCommand.CapturePhoto())
            put("咔嚓", AgentCommand.CapturePhoto())
            put("take a photo", AgentCommand.CapturePhoto())
            put("capture", AgentCommand.CapturePhoto())

            put("3秒后拍照", AgentCommand.BatchExecute(commands = listOf(AgentCommand.Delay(delayMs = 3000), AgentCommand.CapturePhoto())))
            put("倒计时拍照", AgentCommand.BatchExecute(commands = listOf(AgentCommand.Delay(delayMs = 3000), AgentCommand.CapturePhoto())))
            put("延时拍照", AgentCommand.BatchExecute(commands = listOf(AgentCommand.Delay(delayMs = 3000), AgentCommand.CapturePhoto())))
            put("延迟拍摄", AgentCommand.BatchExecute(commands = listOf(AgentCommand.Delay(delayMs = 3000), AgentCommand.CapturePhoto())))
            put("5秒后拍照", AgentCommand.BatchExecute(commands = listOf(AgentCommand.Delay(delayMs = 5000), AgentCommand.CapturePhoto())))
            put("10秒后拍照", AgentCommand.BatchExecute(commands = listOf(AgentCommand.Delay(delayMs = 10000), AgentCommand.CapturePhoto())))

            put("录像", AgentCommand.ToggleRecording())
            put("开始录像", AgentCommand.ToggleRecording())
            put("拍视频", AgentCommand.ToggleRecording())
            put("录视频", AgentCommand.ToggleRecording())
            put("停止录像", AgentCommand.ToggleRecording())
            put("结束录像", AgentCommand.ToggleRecording())

            put("翻转", AgentCommand.FlipCamera())
            put("切前置", AgentCommand.FlipCamera())
            put("切后置", AgentCommand.FlipCamera())
            put("换摄像头", AgentCommand.FlipCamera())
            put("前后切换", AgentCommand.FlipCamera())
            put("打开前置", AgentCommand.FlipCamera())
            put("打开后置", AgentCommand.FlipCamera())
            put("flip camera", AgentCommand.FlipCamera())
            put("switch camera", AgentCommand.FlipCamera())

            put("开美颜", AgentCommand.AdjustBeauty(settings = BeautySettings(enabled = true)))
            put("关美颜", AgentCommand.AdjustBeauty(settings = BeautySettings(enabled = false)))
            put("打开美颜", AgentCommand.AdjustBeauty(settings = BeautySettings(enabled = true)))
            put("关闭美颜", AgentCommand.AdjustBeauty(settings = BeautySettings(enabled = false)))
            put("调高美颜", AgentCommand.AdjustBeauty(settings = BeautySettings(enabled = true, smoothing = 65f, whitening = 65f)))
            put("增强美颜", AgentCommand.AdjustBeauty(settings = BeautySettings(enabled = true, smoothing = 65f, whitening = 65f)))

            put("原图", AgentCommand.SwitchFilter(filterType = FilterType.NONE))
            put("无滤镜", AgentCommand.SwitchFilter(filterType = FilterType.NONE))
            put("重置滤镜", AgentCommand.SwitchFilter(filterType = FilterType.NONE))

            put("磨皮", AgentCommand.AdjustBeauty(settings = BeautySettings(enabled = true, smoothing = 50f)))
            put("磨皮50", AgentCommand.AdjustBeauty(settings = BeautySettings(enabled = true, smoothing = 50f)))
            put("磨皮高一点", AgentCommand.AdjustBeauty(settings = BeautySettings(enabled = true, smoothing = 60f)))
            put("磨皮低一点", AgentCommand.AdjustBeauty(settings = BeautySettings(enabled = true, smoothing = 30f)))
            put("关磨皮", AgentCommand.AdjustBeauty(settings = BeautySettings(smoothing = 0f)))

            put("美白", AgentCommand.AdjustBeauty(settings = BeautySettings(enabled = true, whitening = 40f)))
            put("美白50", AgentCommand.AdjustBeauty(settings = BeautySettings(enabled = true, whitening = 50f)))
            put("美白高一点", AgentCommand.AdjustBeauty(settings = BeautySettings(enabled = true, whitening = 55f)))
            put("美白低一点", AgentCommand.AdjustBeauty(settings = BeautySettings(enabled = true, whitening = 20f)))
            put("关美白", AgentCommand.AdjustBeauty(settings = BeautySettings(whitening = 0f)))

            put("瘦脸", AgentCommand.AdjustBeauty(settings = BeautySettings(enabled = true, slimFace = 20f)))
            put("瘦脸30", AgentCommand.AdjustBeauty(settings = BeautySettings(enabled = true, slimFace = 30f)))
            put("瘦脸高一点", AgentCommand.AdjustBeauty(settings = BeautySettings(enabled = true, slimFace = 30f)))
            put("瘦脸低一点", AgentCommand.AdjustBeauty(settings = BeautySettings(enabled = true, slimFace = 10f)))
            put("关瘦脸", AgentCommand.AdjustBeauty(settings = BeautySettings(slimFace = 0f)))

            put("大眼", AgentCommand.AdjustBeauty(settings = BeautySettings(enabled = true, bigEyes = 30f)))
            put("大眼50", AgentCommand.AdjustBeauty(settings = BeautySettings(enabled = true, bigEyes = 50f)))
            put("大眼高一点", AgentCommand.AdjustBeauty(settings = BeautySettings(enabled = true, bigEyes = 40f)))
            put("大眼低一点", AgentCommand.AdjustBeauty(settings = BeautySettings(enabled = true, bigEyes = 15f)))
            put("关大眼", AgentCommand.AdjustBeauty(settings = BeautySettings(bigEyes = 0f)))

            put("唇色", AgentCommand.AdjustBeauty(settings = BeautySettings(enabled = true, lipColor = 40f)))
            put("唇色40", AgentCommand.AdjustBeauty(settings = BeautySettings(enabled = true, lipColor = 40f)))
            put("唇色高一点", AgentCommand.AdjustBeauty(settings = BeautySettings(enabled = true, lipColor = 55f)))
            put("唇色低一点", AgentCommand.AdjustBeauty(settings = BeautySettings(enabled = true, lipColor = 25f)))
            put("关唇色", AgentCommand.AdjustBeauty(settings = BeautySettings(lipColor = 0f)))

            put("腮红", AgentCommand.AdjustBeauty(settings = BeautySettings(enabled = true, blush = 30f)))
            put("腮红20", AgentCommand.AdjustBeauty(settings = BeautySettings(enabled = true, blush = 20f)))
            put("腮红高一点", AgentCommand.AdjustBeauty(settings = BeautySettings(enabled = true, blush = 40f)))
            put("腮红低一点", AgentCommand.AdjustBeauty(settings = BeautySettings(enabled = true, blush = 15f)))
            put("关腮红", AgentCommand.AdjustBeauty(settings = BeautySettings(blush = 0f)))

            put("徕卡经典", AgentCommand.SwitchFilter(filterType = FilterType.LEICA_CLASSIC))
            put("徕卡鲜艳", AgentCommand.SwitchFilter(filterType = FilterType.LEICA_VIBRANT))
            put("徕卡黑白", AgentCommand.SwitchFilter(filterType = FilterType.LEICA_BW))
            put("胶片金", AgentCommand.SwitchFilter(filterType = FilterType.FILM_GOLD))
            put("胶片富士", AgentCommand.SwitchFilter(filterType = FilterType.FILM_FUJI))
            put("复古", AgentCommand.SwitchFilter(filterType = FilterType.VINTAGE))
            put("冷调", AgentCommand.SwitchFilter(filterType = FilterType.COOL))
            put("暖调", AgentCommand.SwitchFilter(filterType = FilterType.WARM))
            put("冷滤镜", AgentCommand.SwitchFilter(filterType = FilterType.COOL))
            put("换个冷调滤镜", AgentCommand.SwitchFilter(filterType = FilterType.COOL))
            put("换冷调", AgentCommand.SwitchFilter(filterType = FilterType.COOL))

            put("卡通", AgentCommand.SwitchStyle(styleFilter = StyleFilter.TOON))
            put("素描", AgentCommand.SwitchStyle(styleFilter = StyleFilter.SKETCH))
            put("海报", AgentCommand.SwitchStyle(styleFilter = StyleFilter.POSTERIZE))
            put("浮雕", AgentCommand.SwitchStyle(styleFilter = StyleFilter.EMBOSS))
            put("交叉线", AgentCommand.SwitchStyle(styleFilter = StyleFilter.CROSSHATCH))
            put("关风格", AgentCommand.SwitchStyle(styleFilter = StyleFilter.NONE))

            put("恢复默认", AgentCommand.AdjustBeauty(settings = BeautySettings()))
            put("重置美颜", AgentCommand.AdjustBeauty(settings = BeautySettings()))
            put("默认参数", AgentCommand.AdjustBeauty(settings = BeautySettings()))

            put("去相册", AgentCommand.NavigateTo(destination = "gallery"))
            put("打开相册", AgentCommand.NavigateTo(destination = "gallery"))
            put("去设置", AgentCommand.NavigateTo(destination = "settings"))
            put("打开设置", AgentCommand.NavigateTo(destination = "settings"))
            put("去相机", AgentCommand.NavigateTo(destination = "camera"))
            put("回相机", AgentCommand.NavigateTo(destination = "camera"))
            put("打开相机", AgentCommand.NavigateTo(destination = "camera"))
            put("去拍照", AgentCommand.NavigateTo(destination = "camera"))
            put("回拍照", AgentCommand.NavigateTo(destination = "camera"))
            put("返回", AgentCommand.GoBack())
            put("上一页", AgentCommand.GoBack())

            put("放大", AgentCommand.AdjustZoom(zoomRatio = 2.0f))
            put("缩小", AgentCommand.AdjustZoom(zoomRatio = 1.0f))
            put("拉近", AgentCommand.AdjustZoom(zoomRatio = 2.0f))
            put("拉远", AgentCommand.AdjustZoom(zoomRatio = 1.0f))

            put("调亮", AgentCommand.AdjustExposure(exposure = 1))
            put("调暗", AgentCommand.AdjustExposure(exposure = -1))
            put("亮一点", AgentCommand.AdjustExposure(exposure = 1))
            put("暗一点", AgentCommand.AdjustExposure(exposure = -1))
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
