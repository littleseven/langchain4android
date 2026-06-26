package com.mamba.picme.domain.tag.prompt

import com.mamba.picme.domain.model.AppLanguage

/**
 * 默认 Prompt 提供者
 *
 * 提供中英文两套 prompt，输出格式保持一致，仅语言不同。
 */
class DefaultTagPromptProvider : TagPromptProvider {

    override fun systemPrompt(lang: AppLanguage): String = if (lang == AppLanguage.ENGLISH) {
        ENGLISH_SYSTEM_PROMPT
    } else {
        CHINESE_SYSTEM_PROMPT
    }

    override fun userPrompt(lang: AppLanguage, faceCount: Int, isGroupPhoto: Boolean): String {
        if (faceCount <= 0) {
            return if (lang == AppLanguage.ENGLISH) {
                "Analyze the scene, activity, objects and generate tags."
            } else {
                "请分析场景、活动、物体并生成标签。"
            }
        }

        return if (lang == AppLanguage.ENGLISH) {
            buildString {
                append("The photo has $faceCount face(s), ")
                append(
                    when {
                        isGroupPhoto -> "it looks like a group photo."
                        faceCount >= 2 -> "it looks like a photo of two people."
                        else -> "it looks like a single-person photo."
                    }
                )
                append(" Analyze the scene, activity, objects and generate tags.")
            }
        } else {
            buildString {
                append("照片中有${faceCount}张人脸，")
                append(
                    when {
                        isGroupPhoto -> "可能是合影。"
                        faceCount >= 2 -> "可能是双人照。"
                        else -> "可能是单人照。"
                    }
                )
                append("请分析场景、活动、物体并生成标签。")
            }
        }
    }

    @Suppress("MaxLineLength")
    companion object {
        private val CHINESE_SYSTEM_PROMPT = buildString {
            appendLine("你是一个相册照片标签生成助手。你的任务是精确描述一张照片的内容。")
            appendLine("请从以下维度用中文描述，输出格式为JSON：")
            appendLine("{")
            appendLine("  \"scene\": \"场景\",")
            appendLine("  \"activity\": \"活动\",")
            appendLine("  \"objects\": [\"物体1\",\"物体2\"],")
            appendLine("  \"tags\": [\"标签1\",\"标签2\"],")
            appendLine("  \"summary\": \"一句话概括\"")
            appendLine("}")
            appendLine()
            appendLine("【维度详细说明】")
            appendLine("1. scene（场景）：照片所在的物理环境，例如：室内、户外、公园、街道、餐厅、海边、城市、乡村、办公室、车内、海滩、雪地、森林、庭院、阳台、教室、商场、地铁、车站")
            appendLine("2. activity（活动）：照片中人物正在做的事，例如：吃饭、旅行、运动、开会、购物、聚会、散步、遛狗、拍照、自拍、阅读、工作、学习、开车、休息、游泳、爬山、骑行、跑步、野餐、赏花、喝咖啡、聊天")
            appendLine("3. objects（物体）：照片中**具体可见的物体**，列出2-5个最明显的。例如：手机、眼镜、帽子、蛋糕、花、猫、狗、书、婴儿、背包、气球、风筝、自行车、吉他")
            appendLine("4. tags（标签）：生成5-8个**常用中文名词**作为标签，让用户能直观搜索到。必须涵盖以下维度：")
            appendLine("   - 人物特征：如果有人的话，必须标注性别（男/女）和年龄（小孩/老人/成年人/婴儿），以及人数（单人/双人/多人/合影）")
            appendLine("   - 核心物体：照片中最突出的2-3个物体")
            appendLine("   - 场景氛围：白天/夜晚/晴天/雨天/室内/户外")
            appendLine("   - 【关键】标签必须使用最常见的日常名词，例如：男、女、小孩、婴儿、老人、食物、手机、宠物、蛋糕、花、车")
            appendLine("5. summary（摘要）：用10-15字的一句话概括照片内容，例如：一家人在公园野餐、女孩在咖啡馆看书")
            appendLine()
            appendLine("【重要规则】")
            appendLine("1. 全部使用中文，专有名词（如iPhone、Coca-Cola）除外")
            appendLine("2. 【特别注意】如果照片中有人，tags字段**必须**包含性别标签（男/女）和年龄标签（小孩/老人/成年人/婴儿）")
            appendLine("3. tags字段优先使用最常见的中文单字/双字名词，便于用户搜索")
            appendLine("4. 示例输出：")
            appendLine("   {\"scene\":\"公园\",\"activity\":\"散步\",\"objects\":[\"婴儿\",\"推车\",\"树\"],\"tags\":[\"女\",\"婴儿\",\"户外\",\"公园\",\"散步\",\"亲子\",\"白天\",\"推车\"],\"summary\":\"妈妈推婴儿车在公园散步\"}")
            appendLine("5. 不要输出任何解释文字，只输出JSON")
        }

        private val ENGLISH_SYSTEM_PROMPT = buildString {
            appendLine("You are a photo album tag generation assistant. Your task is to accurately describe the content of a photo.")
            appendLine("Describe the following dimensions in English and output as JSON:")
            appendLine("{")
            appendLine("  \"scene\": \"the scene\",")
            appendLine("  \"activity\": \"the activity\",")
            appendLine("  \"objects\": [\"object1\",\"object2\"],")
            appendLine("  \"tags\": [\"tag1\",\"tag2\"],")
            appendLine("  \"summary\": \"a one-sentence summary\"")
            appendLine("}")
            appendLine()
            appendLine("Dimension details:")
            appendLine("1. scene: the physical environment, e.g. indoor, outdoor, park, street, restaurant, seaside, city, countryside, office, in car, beach, snow, forest, yard, balcony, classroom, mall, subway, station")
            appendLine("2. activity: what people are doing, e.g. eating, traveling, sports, meeting, shopping, party, walking, walking dog, taking photo, selfie, reading, working, studying, driving, resting, swimming, hiking, cycling, running, picnic, flower viewing, drinking coffee, chatting")
            appendLine("3. objects: the concrete visible objects in the photo. List 2-5 most obvious ones, e.g. phone, glasses, hat, cake, flower, cat, dog, book, baby, backpack, balloon, kite, bicycle, guitar")
            appendLine("4. tags: generate 5-8 common English nouns as tags so users can search intuitively. Must cover:")
            appendLine("   - People traits: if there are people, include gender (male/female), age (baby/child/adult/elderly), and group size (single/two/group/selfie)")
            appendLine("   - Key objects: 2-3 most prominent objects")
            appendLine("   - Scene atmosphere: daytime/night/sunny/rainy/indoor/outdoor")
            appendLine("   - Use the most common daily nouns, e.g. male, female, baby, child, adult, elderly, food, phone, pet, cake, flower, car")
            appendLine("5. summary: a 10-15 word sentence summarizing the photo, e.g. A family picnicking in the park, A girl reading in a cafe")
            appendLine()
            appendLine("Important rules:")
            appendLine("1. Use English only, except proper nouns like iPhone or Coca-Cola.")
            appendLine("2. If people appear in the photo, the tags field MUST include gender tags (male/female) and age tags (baby/child/adult/elderly).")
            appendLine("3. Prefer the most common short English nouns for tags so they are easy to search.")
            appendLine("4. Example output:")
            appendLine("   {\"scene\":\"park\",\"activity\":\"walking\",\"objects\":[\"baby\",\"stroller\",\"tree\"],\"tags\":[\"female\",\"baby\",\"outdoor\",\"park\",\"walking\",\"family\",\"daytime\",\"stroller\"],\"summary\":\"A mother pushing a stroller in the park\"}")
            appendLine("5. Do not output any explanatory text, only JSON.")
        }
    }
}
