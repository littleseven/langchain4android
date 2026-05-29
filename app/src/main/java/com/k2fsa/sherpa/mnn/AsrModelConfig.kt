package com.k2fsa.sherpa.mnn

import android.util.Log
import org.json.JSONObject
import java.io.File

data class AsrModelConfig(
    val modelType: String,
    val transducer: TransducerConfig,
    val tokens: String,
    val language: List<String>? = null,
    val description: String = "",
    val lm: LmConfig? = null
)

data class TransducerConfig(
    val encoder: String,
    val decoder: String,
    val joiner: String
)

data class LmConfig(
    val model: String,
    val scale: Float = 0.5f
)

object AsrConfigManager {
    private const val TAG = "AsrConfigManager"
    private const val CONFIG_FILE_NAME = "config.json"

    fun getModelConfigFromDirectory(modelDir: String): OnlineModelConfig? {
        return try {
            val configFile = File(modelDir, CONFIG_FILE_NAME)
            Log.d(TAG, "Looking for config file at: ${configFile.absolutePath}")

            if (!configFile.exists()) {
                Log.w(TAG, "Config file not found at ${configFile.absolutePath}, using fallback")
                return getFallbackConfig(modelDir)
            }

            val configContent = configFile.readText()
            Log.d(TAG, "Read config file content: ${configContent.take(200)}...")
            val configJson = JSONObject(configContent)

            val transducerJson = configJson.getJSONObject("transducer")
            val transducerConfig = TransducerConfig(
                encoder = transducerJson.getString("encoder"),
                decoder = transducerJson.getString("decoder"),
                joiner = transducerJson.getString("joiner")
            )

            val lmConfig = if (configJson.has("lm")) {
                val lmJson = configJson.getJSONObject("lm")
                LmConfig(
                    model = lmJson.getString("model"),
                    scale = lmJson.optDouble("scale", 0.5).toFloat()
                )
            } else null

            val asrConfig = AsrModelConfig(
                modelType = configJson.getString("modelType"),
                transducer = transducerConfig,
                tokens = configJson.getString("tokens"),
                language = null,
                description = configJson.optString("description", ""),
                lm = lmConfig
            )

            convertToOnlineModelConfig(modelDir, asrConfig)
        } catch (e: Exception) {
            Log.e(TAG, "Error reading config from $modelDir", e)
            getFallbackConfig(modelDir)
        }
    }

    private fun convertToOnlineModelConfig(modelDir: String, config: AsrModelConfig): OnlineModelConfig {
        return OnlineModelConfig(
            transducer = OnlineTransducerModelConfig(
                encoder = File(modelDir, config.transducer.encoder).absolutePath,
                decoder = File(modelDir, config.transducer.decoder).absolutePath,
                joiner = File(modelDir, config.transducer.joiner).absolutePath,
            ),
            tokens = File(modelDir, config.tokens).absolutePath,
            modelType = config.modelType,
        )
    }

    private fun getFallbackConfig(modelDir: String): OnlineModelConfig? {
        Log.w(TAG, "Using fallback configuration for modelDir: $modelDir")

        val dirName = File(modelDir).name.lowercase()

        return when {
            dirName.contains("bilingual") || dirName.contains("zh") -> {
                Log.d(TAG, "Using bilingual/Chinese fallback config")
                OnlineModelConfig(
                    transducer = OnlineTransducerModelConfig(
                        encoder = "$modelDir/encoder-epoch-99-avg-1.int8.mnn",
                        decoder = "$modelDir/decoder-epoch-99-avg-1.int8.mnn",
                        joiner = "$modelDir/joiner-epoch-99-avg-1.int8.mnn",
                    ),
                    tokens = "$modelDir/tokens.txt",
                    modelType = "zipformer",
                )
            }
            dirName.contains("en") -> {
                Log.d(TAG, "Using English fallback config")
                OnlineModelConfig(
                    transducer = OnlineTransducerModelConfig(
                        encoder = "$modelDir/encoder-epoch-99-avg-1.mnn",
                        decoder = "$modelDir/decoder-epoch-99-avg-1.mnn",
                        joiner = "$modelDir/joiner-epoch-99-avg-1.mnn",
                    ),
                    tokens = "$modelDir/tokens.txt",
                    modelType = "zipformer",
                )
            }
            else -> {
                Log.d(TAG, "Using default fallback config")
                OnlineModelConfig(
                    transducer = OnlineTransducerModelConfig(
                        encoder = "$modelDir/encoder-epoch-99-avg-1.int8.mnn",
                        decoder = "$modelDir/decoder-epoch-99-avg-1.int8.mnn",
                        joiner = "$modelDir/joiner-epoch-99-avg-1.int8.mnn",
                    ),
                    tokens = "$modelDir/tokens.txt",
                    modelType = "zipformer",
                )
            }
        }
    }

    fun getLmConfigFromDirectory(modelDir: String): OnlineLMConfig {
        return try {
            val configFile = File(modelDir, CONFIG_FILE_NAME)

            if (!configFile.exists()) {
                Log.w(TAG, "Config file not found, using default LM config")
                return getDefaultLmConfig(modelDir)
            }

            val configContent = configFile.readText()
            val configJson = JSONObject(configContent)

            if (configJson.has("lm")) {
                val lmJson = configJson.getJSONObject("lm")
                val fullModelPath = File(modelDir, lmJson.getString("model")).absolutePath
                Log.i(TAG, "Using LM config from JSON: ${lmJson.getString("model")} with scale ${lmJson.getDouble("scale")}")
                OnlineLMConfig(
                    model = fullModelPath,
                    scale = lmJson.getDouble("scale").toFloat()
                )
            } else {
                Log.d(TAG, "No LM config found in configuration, using default")
                getDefaultLmConfig(modelDir)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading LM config from $modelDir", e)
            getDefaultLmConfig(modelDir)
        }
    }

    private fun getDefaultLmConfig(modelDir: String): OnlineLMConfig {
        val dirName = File(modelDir).name.lowercase()
        val shouldUseLm = dirName.contains("zh") || dirName.contains("bilingual") || dirName.contains("chinese")

        return if (shouldUseLm) {
            val lmPath = "$modelDir/with-state-epoch-99-avg-1.int8.onnx"
            if (File(lmPath).exists()) {
                Log.d(TAG, "Using default LM config with model: $lmPath")
                OnlineLMConfig(
                    model = lmPath,
                    scale = 0.5f
                )
            } else {
                Log.d(TAG, "LM file not found at $lmPath, using empty LM config")
                OnlineLMConfig()
            }
        } else {
            Log.d(TAG, "No LM needed for model: $dirName")
            OnlineLMConfig()
        }
    }
}
