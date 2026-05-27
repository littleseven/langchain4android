package com.picme.testing.agent.cases

import com.picme.testing.agent.core.AgentTestCase
import com.picme.testing.agent.core.TestCategory
import com.picme.testing.agent.core.TestPriority
import com.picme.testing.agent.core.agentTestCase
import com.picme.testing.agent.device.DeviceTestController
import kotlin.time.Duration.Companion.seconds

/**
 * 美颜模块 Agent 测试用例
 */
object BeautyAgentTestCases {

    /**
     * TC-BEAUTY-01: 美颜参数设置与效果验证
     */
    fun tcBeauty01Slider(controller: DeviceTestController): AgentTestCase<Map<String, Float>> =
        agentTestCase("TC-BEAUTY-01", "美颜参数设置与效果验证") {
            category(TestCategory.BEAUTY)
            priority(TestPriority.P0)

            step("打开美颜面板") {
                action { ctx ->
                    controller.toggleBeautyPanel()
                    kotlinx.coroutines.delay(500)
                }
            }

            step("设置最大美颜参数") {
                action { ctx ->
                    controller.setBeauty(smooth = 100, whiten = 100, slimFace = 80, bigEye = 60)
                    val state = controller.getCameraState()
                    ctx.addStateSnapshot(
                        mapOf(
                            "beautySmooth" to (state?.beautySmooth ?: 0f),
                            "beautyWhiten" to (state?.beautyWhiten ?: 0f),
                            "beautyEnabled" to (state?.beautyEnabled ?: false)
                        )
                    )
                }
                assertState("美颜已启用") { state ->
                    state["beautyEnabled"] == true
                }
            }

            step("截屏记录最大美颜效果") {
                action { ctx ->
                    controller.takeScreenshot("beauty_max", ctx)
                }
            }

            step("重置美颜参数为 0") {
                action { ctx ->
                    controller.setBeauty(smooth = 0, whiten = 0, slimFace = 0, bigEye = 0)
                    val state = controller.getCameraState()
                    ctx.addStateSnapshot(
                        mapOf(
                            "beautySmooth" to (state?.beautySmooth ?: 0f),
                            "beautyWhiten" to (state?.beautyWhiten ?: 0f)
                        )
                    )
                }
                assertState("美颜参数已归零") { state ->
                    (state["beautySmooth"] as? Float)?.let { it == 0f } == true
                }
            }

            step("截屏记录无美颜效果") {
                action { ctx ->
                    controller.takeScreenshot("beauty_none", ctx)
                }
            }

            step("关闭美颜面板") {
                action { ctx ->
                    controller.toggleBeautyPanel()
                }
            }

            output { ctx ->
                mapOf(
                    "maxSmooth" to 100f,
                    "maxWhiten" to 100f,
                    "screenshotCount" to ctx.screenshots.size.toFloat()
                )
            }
        }

    /**
     * TC-BEAUTY-02: 连续滤镜切换验证
     */
    fun tcBeauty02FilterSwitch(controller: DeviceTestController): AgentTestCase<Int> =
        agentTestCase("TC-BEAUTY-02", "连续滤镜切换验证") {
            category(TestCategory.BEAUTY)
            priority(TestPriority.P1)

            val filters = listOf(
                "none", "leica_classic", "leica_vibrant", "leica_bw",
                "film_gold", "film_fuji", "vintage", "cool", "warm"
            )

            step("打开滤镜面板") {
                action { ctx ->
                    controller.toggleFilterPanel()
                    kotlinx.coroutines.delay(500)
                }
            }

            filters.forEach { filter ->
                step("切换滤镜: $filter") {
                    action { ctx ->
                        controller.setFilter(filter)
                        val state = controller.getCameraState()
                        ctx.addStateSnapshot(mapOf("currentFilter" to (state?.currentFilter ?: "unknown")))
                    }
                    timeout(2.seconds)
                }
            }

            step("截屏记录最终滤镜效果") {
                action { ctx ->
                    controller.takeScreenshot("filter_final", ctx)
                }
            }

            step("关闭滤镜面板") {
                action { ctx ->
                    controller.toggleFilterPanel()
                }
            }

            output { filters.size }
        }

    /**
     * TC-BEAUTY-03: 相册编辑美颜验证
     */
    fun tcBeauty03GalleryEdit(controller: DeviceTestController): AgentTestCase<Boolean> =
        agentTestCase("TC-BEAUTY-03", "相册编辑美颜验证") {
            category(TestCategory.BEAUTY)
            priority(TestPriority.P0)

            step("进入相册") {
                action { ctx ->
                    controller.enterGallery()
                }
            }

            step("打开第一张照片") {
                action { ctx ->
                    controller.openPhoto(0)
                }
            }

            step("进入编辑模式") {
                action { ctx ->
                    controller.startEdit()
                }
            }

            step("设置编辑磨皮 50") {
                action { ctx ->
                    controller.setEditSmooth(50)
                    kotlinx.coroutines.delay(500)
                }
            }

            step("设置编辑美白 30") {
                action { ctx ->
                    controller.setEditWhiten(30)
                    kotlinx.coroutines.delay(500)
                }
            }

            step("截屏记录编辑效果") {
                action { ctx ->
                    controller.takeScreenshot("edit_beauty", ctx)
                }
            }

            step("保存编辑") {
                action { ctx ->
                    controller.saveEdit()
                }
            }

            output { true }
        }

    /**
     * 获取所有美颜测试用例
     */
    fun allCases(controller: DeviceTestController): List<AgentTestCase<*>> = listOf(
        tcBeauty01Slider(controller),
        tcBeauty02FilterSwitch(controller),
        tcBeauty03GalleryEdit(controller)
    )
}
