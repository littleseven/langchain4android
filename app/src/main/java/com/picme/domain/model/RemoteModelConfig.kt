package com.picme.domain.model

/**
 * 远程 API 协议类型
 */
enum class RemoteProtocol {
    CLAUDE,
    OPENAI
}

/**
 * 远程模型供应商定义
 */
data class RemoteModelProvider(
    val providerId: String,
    val displayName: String,
    val baseUrl: String,
    val protocol: RemoteProtocol,
    val models: List<String>,
    val isVisible: Boolean = true
)

/**
 * 远程模型配置数据类
 *
 * 封装单个远程模型的完整配置信息，包括模型ID、供应商ID、协议类型、API Key和基础URL。
 * 按供应商维度管理：供应商确定 baseUrl 和 protocol，用户只需选择模型和填写 key。
 */
data class RemoteModelConfig(
    val modelId: String,
    val providerId: String = "",
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

    /**
     * 唯一标识键：供应商ID + 模型ID
     * 同一模型在不同供应商下可以共存
     */
    val uniqueKey: String
        get() = if (providerId.isNotBlank()) providerId + ":" + modelId else modelId

    companion object {
        /**
         * 腾讯云 SCF AI Gateway 默认配置
         * 作为用户未配置远程 LLM Key 时的默认远程推理方式
         * 注意：gatewayToken 需要端侧配置，这里只定义基础结构
         */
        val TENCENT_SCF_DEFAULT = RemoteModelConfig(
            modelId = "deepseek-v4-flash",
            protocol = RemoteProtocol.OPENAI,
            baseUrl = "https://1412656811-m5kw2dftdi.ap-beijing.tencentscf.com/"
        )
        
        /**
         * 预定义的远程模型供应商列表
         */
        val PROVIDERS = listOf(
            RemoteModelProvider(
                providerId = "tencent-tokenhub",
                displayName = "腾讯云 TokenHub",
                baseUrl = "https://tokenhub.tencentmaas.com/v1/",
                protocol = RemoteProtocol.OPENAI,
                models = listOf("deepseek-v4-flash", "kimi-k2.6")
            ),
            RemoteModelProvider(
                providerId = "kimi-official",
                displayName = "Kimi 官方",
                baseUrl = "https://api.moonshot.cn/v1/",
                protocol = RemoteProtocol.OPENAI,
                models = listOf("kimi-k2.6", "kimi-k2.5", "moonshot-v1-8k")
            ),
            RemoteModelProvider(
                providerId = "deepseek-official",
                displayName = "DeepSeek 官方",
                baseUrl = "https://api.deepseek.com/",
                protocol = RemoteProtocol.OPENAI,
                models = listOf("deepseek-v4-flash", "deepseek-v4-pro", "deepseek-chat")
            )
        )

        /**
         * 预定义的远程模型列表（设置页展示用）
         * deepseek-v4-flash 排在 kimi-k2.6 之前
         * kimi-for-coding 已隐藏
         */
        val PREDEFINED_MODELS: List<RemoteModelConfig>
            get() = ProviderConfigs.DEFAULT.configs.map { it.toRemoteModelConfig() }

        /**
         * 所有预定义远程模型（包括隐藏的，用于向后兼容和默认配置查找）
         */
        val ALL_PREDEFINED_MODELS = PROVIDERS
            .flatMap { provider ->
                provider.models.map { modelId ->
                    RemoteModelConfig(
                        modelId = modelId,
                        providerId = provider.providerId,
                        protocol = provider.protocol,
                        baseUrl = provider.baseUrl
                    )
                }
            }

        fun getProvider(providerId: String): RemoteModelProvider? =
            PROVIDERS.find { it.providerId == providerId }

        fun getProviderForModel(modelId: String): RemoteModelProvider? =
            PROVIDERS.find { it.models.contains(modelId) }

        /**
         * 获取默认模型配置（包括隐藏的模型）
         */
        fun defaultConfig(modelId: String): RemoteModelConfig {
            return ALL_PREDEFINED_MODELS.find { it.modelId == modelId }
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
    fun getConfig(uniqueKey: String): RemoteModelConfig? {
        return configs.find { it.uniqueKey == uniqueKey }
    }

    fun getConfigByModelId(modelId: String): RemoteModelConfig? {
        val candidates = configs.filter { it.modelId == modelId }
        return candidates.find { it.isConfigured } ?: candidates.firstOrNull()
    }

    /**
     * 更新指定模型的配置（通过 modelId 匹配）
     */
    fun updateConfig(config: RemoteModelConfig): RemoteModelConfigs {
        val updated = configs.map {
            if (it.uniqueKey == config.uniqueKey) config else it
        }
        return copy(configs = updated)
    }

    /**
     * 添加新模型配置
     */
    fun addConfig(config: RemoteModelConfig): RemoteModelConfigs {
        val existing = configs.find { it.uniqueKey == config.uniqueKey }
        return if (existing != null) {
            // 更新已有模型的 API Key 等信息
            val updated = configs.map {
                if (it.uniqueKey == config.uniqueKey) config else it
            }
            copy(configs = updated)
        } else {
            copy(configs = configs + config)
        }
    }

    /**
     * 删除模型配置
     */
    fun removeConfig(uniqueKey: String): RemoteModelConfigs {
        return copy(configs = configs.filter { it.uniqueKey != uniqueKey })
    }

    /**
     * 更新指定模型的配置（通过原始 modelId 匹配，支持修改 modelId）
     */
    fun updateConfig(originalUniqueKey: String, config: RemoteModelConfig): RemoteModelConfigs {
        val updated = configs.map {
            if (it.uniqueKey == originalUniqueKey) config else it
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
                    val providerId = extractField(obj, "providerId") ?: ""
                    configs.add(
                        RemoteModelConfig(
                            modelId = modelId,
                            providerId = providerId,
                            protocol = protocol ?: RemoteProtocol.OPENAI,
                            apiKey = apiKey,
                            baseUrl = baseUrl,
                            gatewayToken = gatewayToken
                        )
                    )
                }
                if (configs.isEmpty()) {
                    RemoteModelConfigs()
                } else {
                    // 合并可见的预定义模型（按 uniqueKey 匹配，支持同一模型在不同供应商下共存）
                    val predefinedMerged = RemoteModelConfig.PREDEFINED_MODELS.map { predefined ->
                        val saved = configs.find { it.uniqueKey == predefined.uniqueKey }
                        if (saved != null) {
                            saved.copy(
                                baseUrl = saved.baseUrl.ifBlank { predefined.baseUrl },
                                protocol = saved.protocol,
                                providerId = saved.providerId.ifBlank { predefined.providerId },
                                apiKey = saved.apiKey,
                                gatewayToken = saved.gatewayToken
                            )
                        } else {
                            predefined.copy()
                        }
                    }
                    // 保留不在可见预定义列表中的已保存配置
                    val customSaved = configs.filter { saved ->
                        RemoteModelConfig.PREDEFINED_MODELS.none { it.uniqueKey == saved.uniqueKey }
                    }
                    RemoteModelConfigs(predefinedMerged + customSaved)
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
                sb.append("{\"modelId\":\"${config.modelId}\",\"providerId\":\"${config.providerId}\",\"protocol\":\"${config.protocol.name}\",\"apiKey\":\"${config.apiKey}\",\"baseUrl\":\"${config.baseUrl}\",\"gatewayToken\":\"${config.gatewayToken}\"}")
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
