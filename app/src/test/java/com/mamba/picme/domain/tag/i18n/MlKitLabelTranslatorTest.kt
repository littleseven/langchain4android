package com.mamba.picme.domain.tag.i18n

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MlKitLabelTranslatorTest {

    @Test
    fun `英文标签翻译为中文`() {
        val translator = MlKitLabelTranslator(
            enToZh = mapOf("Cat" to "猫", "Dog" to "狗", "Outdoor" to "户外"),
            zhToEn = emptyMap()
        )

        val result = translator.translateToZh(listOf("Cat", "Outdoor", "Unknown"))

        assertEquals(listOf("猫", "户外"), result)
    }

    @Test
    fun `英文标签翻译为 JSON 数组`() {
        val translator = MlKitLabelTranslator(
            enToZh = mapOf("Cat" to "猫", "Dog" to "狗"),
            zhToEn = emptyMap()
        )

        val json = translator.translateToZhJson(listOf("Cat", "Dog"))

        assertTrue(json.contains("猫"))
        assertTrue(json.contains("狗"))
        assertTrue(json.startsWith("["))
        assertTrue(json.endsWith("]"))
    }

    @Test
    fun `空标签返回空 JSON 数组`() {
        val translator = MlKitLabelTranslator(
            enToZh = mapOf("Cat" to "猫"),
            zhToEn = emptyMap()
        )

        val json = translator.translateToZhJson(emptyList())

        assertEquals("[]", json)
    }

    @Test
    fun `中文反向查找英文标签`() {
        val translator = MlKitLabelTranslator(
            enToZh = mapOf("Cat" to "猫", "Kitten" to "猫", "Dog" to "狗"),
            zhToEn = mapOf("猫" to listOf("Cat", "Kitten"), "狗" to listOf("Dog"))
        )

        val result = translator.reverseLookup("猫")

        assertEquals(listOf("Cat", "Kitten"), result)
    }

    @Test
    fun `未命中反向查找返回空列表`() {
        val translator = MlKitLabelTranslator(
            enToZh = mapOf("Cat" to "猫"),
            zhToEn = mapOf("猫" to listOf("Cat"))
        )

        val result = translator.reverseLookup("未知词")

        assertTrue(result.isEmpty())
    }

    @Test
    fun `空翻译器正常降级`() {
        val translator = MlKitLabelTranslator.empty()

        val zhResult = translator.translateToZh(listOf("Cat"))
        val reverseResult = translator.reverseLookup("猫")

        assertTrue(zhResult.isEmpty())
        assertTrue(reverseResult.isEmpty())
        assertEquals("[]", translator.translateToZhJson(listOf("Cat")))
    }
}
