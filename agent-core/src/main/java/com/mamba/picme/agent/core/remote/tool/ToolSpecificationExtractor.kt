package com.mamba.picme.agent.core.remote.tool

import com.mamba.model.chat.request.json.JsonBooleanSchema
import com.mamba.model.chat.request.json.JsonEnumSchema
import com.mamba.model.chat.request.json.JsonIntegerSchema
import com.mamba.model.chat.request.json.JsonNumberSchema
import com.mamba.model.chat.request.json.JsonObjectSchema
import com.mamba.model.chat.request.json.JsonSchemaElement
import com.mamba.model.chat.request.json.JsonStringSchema
import com.mamba.tool.P
import com.mamba.tool.Tool
import com.mamba.tool.ToolSpecification
import kotlin.reflect.KClass
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.memberFunctions

/**
 * 从 @Tool 注解方法提取 ToolSpecification 列表。
 *
 * 通过 Kotlin 反射从 @Tool/@P 注解生成 ToolSpecification。
 * 注意：Java 注解的方法在 Kotlin 中作为属性访问，不需要 () 调用。
 */
object ToolSpecificationExtractor {

    /**
     * 从服务实例中提取所有 @Tool 注解方法的 ToolSpecification。
     */
    fun extract(service: Any): List<ToolSpecification> {
        return service::class.memberFunctions
            .filter { it.findAnnotation<Tool>() != null }
            .map { func ->
                val toolAnnotation = func.findAnnotation<Tool>()!!
                val name = toolAnnotation.name.ifBlank { func.name }
                val descriptions = toolAnnotation.value
                val description = descriptions.joinToString(" ").ifBlank { name }

                val builder = ToolSpecification.builder()
                    .name(name)
                    .description(description)

                // 提取参数（drop receiver parameter）
                val parameters = func.parameters.drop(1)
                if (parameters.isNotEmpty()) {
                    val schemaBuilder = JsonObjectSchema.builder()
                    val required = mutableListOf<String>()

                    for ((index, param) in parameters.withIndex()) {
                        val pAnnotation = param.findAnnotation<P>()
                        val paramName = pAnnotation?.name?.ifBlank { param.name } ?: param.name ?: "arg$index"
                        val paramDesc = pAnnotation?.description?.ifBlank { pAnnotation.value } ?: ""
                        val isRequired = pAnnotation?.required ?: true

                        val schemaElement = createSchemaElement(param, paramDesc)
                        schemaBuilder.addProperty(paramName, schemaElement)

                        if (isRequired) {
                            required.add(paramName)
                        }
                    }

                    if (required.isNotEmpty()) {
                        schemaBuilder.required(required)
                    }
                    builder.parameters(schemaBuilder.build())
                }

                builder.build()
            }
    }

    private fun createSchemaElement(param: kotlin.reflect.KParameter, description: String): JsonSchemaElement {
        val kClass = param.type.classifier as? KClass<*>

        return when {
            kClass == String::class -> JsonStringSchema.builder().description(description).build()
            kClass == Int::class || kClass == java.lang.Integer::class ->
                JsonIntegerSchema.builder().description(description).build()
            kClass == Double::class || kClass == java.lang.Double::class ||
                kClass == Float::class || kClass == java.lang.Float::class ->
                JsonNumberSchema.builder().description(description).build()
            kClass == Boolean::class || kClass == java.lang.Boolean::class ->
                JsonBooleanSchema.builder().description(description).build()
            kClass?.java?.isEnum == true -> {
                val enumValues = kClass.java.enumConstants.map { it.toString() }
                JsonEnumSchema.builder().description(description).enumValues(enumValues).build()
            }
            // 可空类型：尝试获取非空原始类型
            param.type.isMarkedNullable -> {
                val innerClassifier = param.type.classifier as? KClass<*>
                when {
                    innerClassifier == String::class -> JsonStringSchema.builder().description(description).build()
                    innerClassifier == Int::class -> JsonIntegerSchema.builder().description(description).build()
                    innerClassifier == Double::class || innerClassifier == Float::class ->
                        JsonNumberSchema.builder().description(description).build()
                    innerClassifier == Boolean::class -> JsonBooleanSchema.builder().description(description).build()
                    else -> JsonStringSchema.builder().description(description).build()
                }
            }
            else -> JsonStringSchema.builder().description(description).build()
        }
    }
}
