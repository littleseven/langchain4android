package com.mamba.picme.domain.tag.i18n

import com.mamba.picme.domain.model.AppLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TagTranslatorTest {

    @Test
    fun `中文 ML Kit 标签查询扩展为英文标签`() {
        val vocab = BilingualVocab(
            zhToEn = mapOf(
                "团队" to "Team",
                "猫" to "cat",
                "日落" to "Sunset"
            ),
            enToZh = mapOf(
                "Team" to "团队",
                "cat" to "猫",
                "Sunset" to "日落"
            ),
            enSynonyms = emptyMap()
        )
        val translator = TagTranslator(vocab)

        val candidates = translator.expandForSearch("团队", AppLanguage.CHINESE)

        assertEquals(setOf("团队", "team"), candidates)
    }

    @Test
    fun `英文 ML Kit 标签查询扩展为中文标签`() {
        val vocab = BilingualVocab(
            zhToEn = mapOf("团队" to "Team"),
            enToZh = mapOf("team" to "团队"),
            enSynonyms = emptyMap()
        )
        val translator = TagTranslator(vocab)

        val candidates = translator.expandForSearch("Team", AppLanguage.ENGLISH)

        assertEquals(setOf("team", "团队"), candidates)
    }

    @Test
    fun `无词表命中时返回原查询`() {
        val translator = TagTranslator(BilingualVocab.empty())

        val candidates = translator.expandForSearch("未知标签", AppLanguage.CHINESE)

        assertEquals(setOf("未知标签"), candidates)
    }
}
