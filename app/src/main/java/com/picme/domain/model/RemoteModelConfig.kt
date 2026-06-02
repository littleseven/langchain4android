package com.picme.domain.model

/**
 * 远程模型配置数据类
 *
 * 封装单个远程模型的完整配置信息，包括模型ID、API Key和基础URL。
 * 用于在设置页以模型为纬度管理远程推理配置。
 */
data class RemoteModelConfig(
    val modelId: String,
    val apiKey: String = "",
    val baseUrl: String = ""
) {
    val isConfigured: Boolean
        get() = apiKey.isNotBlank() && baseUrl.isNotBlank()

    companion object {
        /**
         * 预定义的远程模型列表
         */
        val PREDEFINED_MODELS = listOf(
            RemoteModelConfig(
                modelId = "kimi-for-coding",
                baseUrl = "https://api.kimi.com/coding/v1/"
            ),
            RemoteModelConfig(
                modelId = "kimi-k2.6",
                baseUrl = "https://tokenhub.tencentmaas.com/v1/"
            ),
            RemoteModelConfig(
                modelId = "deepseek-v4-flash",
                baseUrl = "https://tokenhub.tencentmaas.com/v1/"
            )
        )

        /**
         * 获取默认模型配置
         */
        fun defaultConfig(modelId: String): RemoteModelConfig {
            return PREDEFINED_MODELS.find { it.modelId == modelId }
                ?: RemoteModelConfig(modelId = modelId)
        }
    }
}

/**
 * 远程模型配置集合
 *
 * 管理多个远程模型的配置，支持序列化存储。
 */
data class RemoteModelConfigs(
    val configs: List<RemoteModelConfig> = RemoteModelConfig.PREDEFINED_MODELS.map { it.copy() }
) {
    /**
     * 获取指定模型的配置
     */
    fun getConfig(modelId: String): RemoteModelConfig? {
        return configs.find { it.modelId == modelId }
    }

    /**
     * 更新指定模型的配置
     */
    fun updateConfig(config: RemoteModelConfig): RemoteModelConfigs {
        val updated = configs.map {
            if (it.modelId == config.modelId) config else it
        }
        return copy(configs = updated)
    }

    /**
     * 获取已配置的模型列表
     */
    val configuredModels: List<RemoteModelConfig>
        get() = configs.filter { it.isConfigured }

    companion object {
        /**
         * 从JSON字符串反序列化
         */
        fun fromJson(json: String): RemoteModelConfigs {
            return try {
                val configs = mutableListOf<RemoteModelConfig>()
                val regex = "\\{([^}]*)\\}".toRegex()
                regex.findAll(json).forEach { match ->
                    val obj = match.groupValues[1]
                    val modelId = extractField(obj, "modelId") ?: return@forEach
                    val apiKey = extractField(obj, "apiKey") ?: ""
                    val baseUrl = extractField(obj, "baseUrl") ?: ""
                    configs.add(RemoteModelConfig(modelId, apiKey, baseUrl))
                }
                if (configs.isEmpty()) {
                    RemoteModelConfigs()
                } else {
                    val merged = RemoteModelConfig.PREDEFINED_MODELS.map { predefined ->
                        val saved = configs.find { it.modelId == predefined.modelId }
                        if (saved != null) {
                            saved.copy(baseUrl = saved.baseUrl.ifBlank { predefined.baseUrl })
                        } else {
                            predefined.copy()
                        }
                    }
                    RemoteModelConfigs(merged)
                }
            } catch (_: Exception) {
                RemoteModelConfigs()
            }
        }

        /**
         * 序列化为JSON字符串
         */
        fun toJson(configs: RemoteModelConfigs): String {
            val sb = StringBuilder("[")
            configs.configs.forEachIndexed { index, config ->
                if (index > 0) sb.append(",")
                sb.append("{\"modelId\":\"${config.modelId}\",\"apiKey\":\"${config.apiKey}\",\"baseUrl\":\"${config.baseUrl}\"}")
            }
            sb.append("]")
            return sb.toString()
        }

        private fun extractField(obj: String, field: String): String? {
            val regex = "\"$field\"\\s*:\\s*\"([^\"]*)\"".toRegex()
            return regex.find(obj)?.groupValues?.get(1)
        }
    }
}
