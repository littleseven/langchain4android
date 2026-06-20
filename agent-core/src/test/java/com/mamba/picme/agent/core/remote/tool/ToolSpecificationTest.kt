package com.mamba.picme.agent.core.remote.tool

import android.view.WindowManager
import org.junit.Test

/**
 * 验证 ToolSpecifications 从 @Tool 注解提取的参数名是否正确
 *
 * 问题背景：Kotlin 编译器默认会剥离参数名（变为 arg0, arg1...），
 * 导致 LLM 收到的参数名与实际解析时使用的 key 不一致。
 */
class ToolSpecificationTest {

    @Test
    fun `verify switch_filter parameter names`() {
        // 使用 ToolSpecificationExtractor 从 PicMeToolService 提取 ToolSpecification
        val toolService = PicMeToolService(null as WindowManager)
        val specs = ToolSpecificationExtractor.extract(toolService)
        val switchFilterSpec = specs.find { it.name() == "switch_filter" }
            ?: throw AssertionError("switch_filter spec not found")

        val props = switchFilterSpec.parameters()?.properties()
        println("switch_filter parameters: ${props?.keys}")
        println("switch_filter required: ${switchFilterSpec.parameters()?.required()}")

        // 检查参数名是否为 "filter" 而不是 "arg0"
        assert(props?.containsKey("filter") == true) {
            "Expected parameter 'filter' but found: ${props?.keys}"
        }

        // 检查参数描述
        val filterSchema = props?.get("filter")
        println("filter schema: $filterSchema")
    }

    @Test
    fun `verify all tool parameter names`() {
        val toolService = PicMeToolService(null as WindowManager)
        val specs = ToolSpecificationExtractor.extract(toolService)
        for (spec in specs) {
            val props = spec.parameters()?.properties()
            println("Tool '${spec.name()}' parameters: ${props?.keys}")
        }
    }
}
