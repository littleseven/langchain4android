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

            // 磨皮调节
            put("磨皮", AgentCommand.AdjustBeauty(com.picme.beauty.api.BeautySettings(enabled = true, smoothing = 50f)))
            put("磨皮50", AgentCommand.AdjustBeauty(com.picme.beauty.api.BeautySettings(enabled = true, smoothing = 50f)))
            put("磨皮高一点", AgentCommand.AdjustBeauty(com.picme.beauty.api.BeautySettings(enabled = true, smoothing = 60f)))
            put("磨皮低一点", AgentCommand.AdjustBeauty(com.picme.beauty.api.BeautySettings(enabled = true, smoothing = 30f)))
            put("关磨皮", AgentCommand.AdjustBeauty(com.picme.beauty.api.BeautySettings(smoothing = 0f)))

            // 美白调节
            put("美白", AgentCommand.AdjustBeauty(com.picme.beauty.api.BeautySettings(enabled = true, whitening = 40f)))
            put("美白50", AgentCommand.AdjustBeauty(com.picme.beauty.api.BeautySettings(enabled = true, whitening = 50f)))
            put("美白高一点", AgentCommand.AdjustBeauty(com.picme.beauty.api.BeautySettings(enabled = true, whitening = 55f)))
            put("美白低一点", AgentCommand.AdjustBeauty(com.picme.beauty.api.BeautySettings(enabled = true, whitening = 20f)))
            put("关美白", AgentCommand.AdjustBeauty(com.picme.beauty.api.BeautySettings(whitening = 0f)))

            // 瘦脸调节
            put("瘦脸", AgentCommand.AdjustBeauty(com.picme.beauty.api.BeautySettings(enabled = true, slimFace = 20f)))
            put("瘦脸30", AgentCommand.AdjustBeauty(com.picme.beauty.api.BeautySettings(enabled = true, slimFace = 30f)))
            put("瘦脸高一点", AgentCommand.AdjustBeauty(com.picme.beauty.api.BeautySettings(enabled = true, slimFace = 30f)))
            put("瘦脸低一点", AgentCommand.AdjustBeauty(com.picme.beauty.api.BeautySettings(enabled = true, slimFace = 10f)))
            put("关瘦脸", AgentCommand.AdjustBeauty(com.picme.beauty.api.BeautySettings(slimFace = 0f)))

            // 大眼调节
            put("大眼", AgentCommand.AdjustBeauty(com.picme.beauty.api.BeautySettings(enabled = true, bigEyes = 30f)))
            put("大眼50", AgentCommand.AdjustBeauty(com.picme.beauty.api.BeautySettings(enabled = true, bigEyes = 50f)))
            put("大眼高一点", AgentCommand.AdjustBeauty(com.picme.beauty.api.BeautySettings(enabled = true, bigEyes = 40f)))
            put("大眼低一点", AgentCommand.AdjustBeauty(com.picme.beauty.api.BeautySettings(enabled = true, bigEyes = 15f)))
            put("关大眼", AgentCommand.AdjustBeauty(com.picme.beauty.api.BeautySettings(bigEyes = 0f)))

            // 唇色调节
            put("唇色", AgentCommand.AdjustBeauty(com.picme.beauty.api.BeautySettings(enabled = true, lipColor = 40f)))
            put("唇色40", AgentCommand.AdjustBeauty(com.picme.beauty.api.BeautySettings(enabled = true, lipColor = 40f)))
            put("唇色高一点", AgentCommand.AdjustBeauty(com.picme.beauty.api.BeautySettings(enabled = true, lipColor = 55f)))
            put("唇色低一点", AgentCommand.AdjustBeauty(com.picme.beauty.api.BeautySettings(enabled = true, lipColor = 25f)))
            put("关唇色", AgentCommand.AdjustBeauty(com.picme.beauty.api.BeautySettings(lipColor = 0f)))

            // 腮红调节
            put("腮红", AgentCommand.AdjustBeauty(com.picme.beauty.api.BeautySettings(enabled = true, blush = 30f)))
            put("腮红20", AgentCommand.AdjustBeauty(com.picme.beauty.api.BeautySettings(enabled = true, blush = 20f)))
            put("腮红高一点", AgentCommand.AdjustBeauty(com.picme.beauty.api.BeautySettings(enabled = true, blush = 40f)))
            put("腮红低一点", AgentCommand.AdjustBeauty(com.picme.beauty.api.BeautySettings(enabled = true, blush = 15f)))
            put("关腮红", AgentCommand.AdjustBeauty(com.picme.beauty.api.BeautySettings(blush = 0f)))

            // 常用滤镜
            put("徕卡经典", AgentCommand.SwitchFilter(com.picme.beauty.api.FilterType.LEICA_CLASSIC))
            put("徕卡鲜艳", AgentCommand.SwitchFilter(com.picme.beauty.api.FilterType.LEICA_VIBRANT))
            put("徕卡黑白", AgentCommand.SwitchFilter(com.picme.beauty.api.FilterType.LEICA_BW))
            put("胶片金", AgentCommand.SwitchFilter(com.picme.beauty.api.FilterType.FILM_GOLD))
            put("胶片富士", AgentCommand.SwitchFilter(com.picme.beauty.api.FilterType.FILM_FUJI))
            put("复古", AgentCommand.SwitchFilter(com.picme.beauty.api.FilterType.VINTAGE))
            put("冷调", AgentCommand.SwitchFilter(com.picme.beauty.api.FilterType.COOL))
            put("暖调", AgentCommand.SwitchFilter(com.picme.beauty.api.FilterType.WARM))

            // 常用风格特效
            put("卡通", AgentCommand.SwitchStyle(com.picme.beauty.api.StyleFilter.TOON))
            put("素描", AgentCommand.SwitchStyle(com.picme.beauty.api.StyleFilter.SKETCH))
            put("海报", AgentCommand.SwitchStyle(com.picme.beauty.api.StyleFilter.POSTERIZE))
            put("浮雕", AgentCommand.SwitchStyle(com.picme.beauty.api.StyleFilter.EMBOSS))
            put("交叉线", AgentCommand.SwitchStyle(com.picme.beauty.api.StyleFilter.CROSSHATCH))
            put("关风格", AgentCommand.SwitchStyle(com.picme.beauty.api.StyleFilter.NONE))

            // 恢复默认
            put("恢复默认", AgentCommand.AdjustBeauty(com.picme.beauty.api.BeautySettings()))
            put("重置美颜", AgentCommand.AdjustBeauty(com.picme.beauty.api.BeautySettings()))
            put("默认参数", AgentCommand.AdjustBeauty(com.picme.beauty.api.BeautySettings()))

            // 导航
            put("去相册", AgentCommand.NavigateTo("gallery"))
            put("去设置", AgentCommand.NavigateTo("settings"))
            put("回相机", AgentCommand.NavigateTo("camera"))
            put("返回", AgentCommand.GoBack)
            put("上一页", AgentCommand.GoBack)

            // 变焦
            put("放大", AgentCommand.AdjustZoom(2.0f))
            put("缩小", AgentCommand.AdjustZoom(1.0f))
            put("拉近", AgentCommand.AdjustZoom(2.0f))
            put("拉远", AgentCommand.AdjustZoom(1.0f))

            // 曝光
            put("调亮", AgentCommand.AdjustExposure(1))
            put("调暗", AgentCommand.AdjustExposure(-1))
            put("亮一点", AgentCommand.AdjustExposure(1))
            put("暗一点", AgentCommand.AdjustExposure(-1))
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
