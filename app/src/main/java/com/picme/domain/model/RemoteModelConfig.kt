package com.picme.domain.model

/**
 * 远程 API 协议类型
 */
enum class RemoteProtocol {
    CLAUDE,
    OPENAI
}

/**
 * 远程模型配置数据类
 *
 * 封装单个远程模型的完整配置信息，包括模型ID、协议类型、API Key和基础URL。
 * 用于在设置页以模型为纬度管理远程推理配置。
 */
data class RemoteModelConfig(
    val modelId: String,
    val protocol: RemoteProtocol = RemoteProtocol.OPENAI,
    val apiKey: String = "",
    val baseUrl: String = "",
    val gatewayToken: String = ""
) {
    /**
     * 是否已配置（用于用户自定义模型）
     * 腾讯云 SCF Gateway 默认模型无需 apiKey，有 baseUrl + gatewayToken 即视为可用
     */
    val isConfigured: Boolean
        get() = baseUrl.isNotBlank() && (apiKey.isNotBlank() || gatewayToken.isNotBlank())

    companion object {
        /**
         * 腾讯云 SCF AI Gateway 默认配置
         * 作为用户未配置远程 LLM Key 时的默认远程推理方式
         * 注意：gatewayToken 需要端侧配置，这里只定义基础结构
         */
        val TENCENT_SCF_DEFAULT = RemoteModelConfig(
            modelId = "deepseek-chat",
            protocol = RemoteProtocol.OPENAI,
            baseUrl = "https://1412656811-m5kw2dftdi.ap-beijing.tencentscf.com/"
        )
        
        /**
         * 预定义的远程模型列表
         */
        val PREDEFINED_MODELS = listOf(
            RemoteModelConfig(
                modelId = "kimi-for-coding",
                protocol = RemoteProtocol.CLAUDE,
                baseUrl = "https://api.kimi.com/coding/v1/"
            ),
            RemoteModelConfig(
                modelId = "kimi-k2.6",
                protocol = RemoteProtocol.OPENAI,
                baseUrl = "https://tokenhub.tencentmaas.com/v1/"
            ),
            RemoteModelConfig(
                modelId = "deepseek-v4-flash",
                protocol = RemoteProtocol.OPENAI,
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
     * 更新指定模型的配置（通过 modelId 匹配）
     */
    fun updateConfig(config: RemoteModelConfig): RemoteModelConfigs {
        val updated = configs.map {
            if (it.modelId == config.modelId) config else it
        }
        return copy(configs = updated)
    }

    /**
     * 添加新模型配置
     */
    fun addConfig(config: RemoteModelConfig): RemoteModelConfigs {
        if (configs.any { it.modelId == config.modelId }) {
            return this
        }
        return copy(configs = configs + config)
    }

    /**
     * 删除模型配置
     */
    fun removeConfig(modelId: String): RemoteModelConfigs {
        return copy(configs = configs.filter { it.modelId != modelId })
    }

    /**
     * 更新指定模型的配置（通过原始 modelId 匹配，支持修改 modelId）
     */
    fun updateConfig(originalModelId: String, config: RemoteModelConfig): RemoteModelConfigs {
        val updated = configs.map {
            if (it.modelId == originalModelId) config else it
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
                    val protocolStr = extractField(obj, "protocol")
                    val protocol = protocolStr?.let {
                        runCatching { RemoteProtocol.valueOf(it) }.getOrNull()
                    }
                    val apiKey = extractField(obj, "apiKey") ?: ""
                    val baseUrl = extractField(obj, "baseUrl") ?: ""
                    val gatewayToken = extractField(obj, "gatewayToken") ?: ""
                    configs.add(RemoteModelConfig(modelId, protocol ?: RemoteProtocol.OPENAI, apiKey, baseUrl, gatewayToken))
                }
                if (configs.isEmpty()) {
                    RemoteModelConfigs()
                } else {
                    val merged = RemoteModelConfig.PREDEFINED_MODELS.map { predefined ->
                        val saved = configs.find { it.modelId == predefined.modelId }
                        if (saved != null) {
                            saved.copy(
                                baseUrl = saved.baseUrl.ifBlank { predefined.baseUrl },
                                protocol = saved.protocol
                            )
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
                sb.append("{\"modelId\":\"${config.modelId}\",\"protocol\":\"${config.protocol.name}\",\"apiKey\":\"${config.apiKey}\",\"baseUrl\":\"${config.baseUrl}\",\"gatewayToken\":\"${config.gatewayToken}\"}")
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
