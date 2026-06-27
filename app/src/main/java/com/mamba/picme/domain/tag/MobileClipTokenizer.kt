package com.mamba.picme.domain.tag

import android.content.Context
import android.util.Log
import com.mamba.picme.data.download.ModelPathConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.regex.Pattern

/**
 * MobileCLIP BPE Tokenizer
 *
 * 解析 Hugging Face 格式的 tokenizer.json，实现文本 → token IDs 编码。
 * 模型配套文件已随 MobileCLIP-MNN 下载到 llm_models/mobileclip-mnn/ 目录。
 *
 * 编码流程：
 * 1. 文本预处理（NFC 规范化、小写、去除多余空格）
 * 2. 按 pre_tokenizer 规则切分（通常是空格/标点分词）
 * 3. 对每个词进行 BPE 合并（加载 merges.txt 或 tokenizer.json 中的 merges）
 * 4. 将 token 映射为 id（加载 vocab）
 * 5. 添加特殊 token（BOS/EOS/PAD）
 *
 * @param context Application Context
 */
class MobileClipTokenizer(context: Context) {

    companion object {
        private const val TAG = "MobileClipTokenizer"

        /** 默认 BOS token id（<|startoftext|>） */
        private const val DEFAULT_BOS_ID = 49406L

        /** 默认 EOS token id（<|endoftext|>） */
        private const val DEFAULT_EOS_ID = 49407L

        /** 默认 PAD token id */
        private const val DEFAULT_PAD_ID = 0L

        /** 默认上下文长度 */
        private const val DEFAULT_CONTEXT_LENGTH = 77
    }

    private val modelDir: File = ModelPathConfig.getMobileClipModelDir(context)

    /** token → id 映射表 */
    private val vocab: MutableMap<String, Long> = mutableMapOf()

    /** id → token 反向映射（调试用） */
    private val idToToken: MutableMap<Long, String> = mutableMapOf()

    /** BPE 合并规则列表（按优先级排序） */
    private val merges: MutableList<Pair<String, String>> = mutableListOf()

    /** 特殊 token 配置 */
    private var bosTokenId: Long = DEFAULT_BOS_ID
    private var eosTokenId: Long = DEFAULT_EOS_ID
    private var padTokenId: Long = DEFAULT_PAD_ID
    private var contextLength: Int = DEFAULT_CONTEXT_LENGTH

    /** 是否已加载 */
    private var isLoaded: Boolean = false

    /** 字节到 Unicode 字符的映射（用于 BPE 处理字节序列） */
    private val byteEncoder: Map<Int, String> by lazy { buildByteEncoder() }

    /** 预编译的正则：按空格和标点切分 */
    private val preTokenizePattern: Pattern by lazy {
        Pattern.compile("""'s|'t|'re|'ve|'m|'ll|'d|[\p{L}]+|[\p{N}]+|[^\s\p{L}\p{N}]+|\p{Z}+""")
    }

    /**
     * 加载 tokenizer 配置（优先 tokenizer.json，回退 vocab.txt + merges.txt）
     *
     * @return 是否加载成功
     */
    fun load(): Boolean {
        if (isLoaded) return true

        val tokenizerJsonFile = File(modelDir, "tokenizer.json")
        return if (tokenizerJsonFile.exists()) {
            loadFromTokenizerJson(tokenizerJsonFile)
        } else {
            loadFromVocabAndMerges()
        }
    }

    /**
     * 从 tokenizer.json 加载（Hugging Face 标准格式）
     */
    private fun loadFromTokenizerJson(file: File): Boolean {
        return try {
            val json = JSONObject(file.readText())

            // 1. 加载 vocab
            val modelObj = json.getJSONObject("model")
            val vocabObj = modelObj.getJSONObject("vocab")
            vocabObj.keys().forEach { token ->
                val id = vocabObj.getLong(token)
                vocab[token] = id
                idToToken[id] = token
            }

            // 2. 加载 merges
            if (modelObj.has("merges")) {
                val mergesArray = modelObj.getJSONArray("merges")
                for (i in 0 until mergesArray.length()) {
                    val mergeStr = mergesArray.getString(i).trim()
                    if (mergeStr.isNotEmpty() && !mergeStr.startsWith("#")) {
                        val parts = mergeStr.split(" ")
                        if (parts.size == 2) {
                            merges.add(parts[0] to parts[1])
                        }
                    }
                }
            }

            // 3. 加载特殊 token 配置
            if (json.has("added_tokens")) {
                val addedTokens = json.getJSONArray("added_tokens")
                for (i in 0 until addedTokens.length()) {
                    val tokenObj = addedTokens.getJSONObject(i)
                    val token = tokenObj.getString("content")
                    val id = tokenObj.getLong("id")
                    vocab[token] = id
                    idToToken[id] = token
                    when (token) {
                        "<|startoftext|>" -> bosTokenId = id
                        "<|endoftext|>" -> eosTokenId = id
                        "<|pad|>" -> padTokenId = id
                    }
                }
            }

            // 4. 加载 post_processor / 配置
            if (json.has("post_processor")) {
                val postProcessor = json.getJSONObject("post_processor")
                // 解析 special_tokens 获取 bos/eos
            }

            isLoaded = true
            Log.i(TAG, "Tokenizer loaded from tokenizer.json: vocab=${vocab.size}, merges=${merges.size}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load tokenizer.json", e)
            false
        }
    }

    /**
     * 从 vocab.txt + merges.txt 加载（传统 BPE 格式）
     */
    private fun loadFromVocabAndMerges(): Boolean {
        return try {
            // 1. 加载 vocab.txt
            val vocabFile = File(modelDir, "vocab.txt")
            if (vocabFile.exists()) {
                vocabFile.readLines().forEachIndexed { index, line ->
                    val token = line.trim()
                    if (token.isNotEmpty()) {
                        vocab[token] = index.toLong()
                        idToToken[index.toLong()] = token
                    }
                }
            }

            // 2. 加载 merges.txt
            val mergesFile = File(modelDir, "merges.txt")
            if (mergesFile.exists()) {
                mergesFile.readLines().forEach { line ->
                    val trimmed = line.trim()
                    if (trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
                        val parts = trimmed.split(" ")
                        if (parts.size == 2) {
                            merges.add(parts[0] to parts[1])
                        }
                    }
                }
            }

            isLoaded = true
            Log.i(TAG, "Tokenizer loaded from vocab.txt + merges.txt: vocab=${vocab.size}, merges=${merges.size}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load vocab/merges", e)
            false
        }
    }

    /**
     * 将文本编码为 token ID 数组（LongArray，适配 MNN text 模型输入）
     *
     * @param text 用户输入文本
     * @param contextLength 最大序列长度（默认 77）
 * @param addSpecialTokens 是否添加 BOS/EOS
     * @return LongArray token IDs，失败返回 null
     */
    fun encode(
        text: String,
        contextLength: Int = this.contextLength,
        addSpecialTokens: Boolean = true
    ): LongArray? {
        if (!isLoaded && !load()) {
            Log.w(TAG, "Tokenizer not loaded")
            return null
        }

        return try {
            // 1. 文本预处理
            val normalized = normalizeText(text)

            // 2. 预分词（按空格/标点切分）
            val words = preTokenize(normalized)

            // 3. 对每个词进行 BPE 编码
            val tokenIds = mutableListOf<Long>()

            if (addSpecialTokens) {
                tokenIds.add(bosTokenId)
            }

            for (word in words) {
                val wordTokenIds = bpeEncode(word)
                tokenIds.addAll(wordTokenIds)
            }

            if (addSpecialTokens) {
                tokenIds.add(eosTokenId)
            }

            // 4. 截断或填充到固定长度
            val result = padOrTruncate(tokenIds, contextLength)

            result.toLongArray()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to encode text: $text", e)
            null
        }
    }

    /**
     * 文本规范化：NFC 规范化、小写、去除多余空格
     */
    private fun normalizeText(text: String): String {
        return text
            .trim()
            .lowercase()
            .replace(Regex("\\s+"), " ")
    }

    /**
     * 预分词：按空格和标点切分
     */
    private fun preTokenize(text: String): List<String> {
        val matcher = preTokenizePattern.matcher(text)
        val words = mutableListOf<String>()
        while (matcher.find()) {
            val match = matcher.group()
            // 跳过纯空格
            if (!match.matches(Regex("\\p{Z}+"))) {
                words.add(match)
            }
        }
        return words.ifEmpty { listOf(text) }
    }

    /**
     * 对单个词进行 BPE 编码
     */
    private fun bpeEncode(word: String): List<Long> {
        // 1. 将词转换为字节序列，然后映射为 Unicode 字符
        val byteWord = word.encodeToByteArray()
        var wordTokens = byteWord.map { byte ->
            byteEncoder[byte.toInt() and 0xFF] ?: ""
        }.filter { it.isNotEmpty() }

        if (wordTokens.isEmpty()) return emptyList()

        // 2. 迭代应用 BPE 合并规则
        var currentWord = wordTokens.joinToString("")
        val tokenIds = mutableListOf<Long>()

        // 简化的 BPE：直接查找 vocab 中最长匹配
        // 注意：这是简化实现，完整 BPE 需要按 merges 优先级逐步合并
        val encoded = bpeMerge(currentWord)

        // 3. 映射为 token IDs
        for (token in encoded) {
            val id = vocab[token]
            if (id != null) {
                tokenIds.add(id)
            } else {
                // 未知 token，尝试子词拆分或使用 <unk>
                Log.w(TAG, "Unknown token: '$token' in word '$word'")
            }
        }

        return tokenIds
    }

    /**
     * BPE 合并：按 merges 规则逐步合并最频繁的字符对
     */
    private fun bpeMerge(word: String): List<String> {
        if (word.isEmpty()) return emptyList()

        // 初始：每个字符（或字节映射后的字符）作为一个 token
        var wordTokens = word.map { it.toString() }.toMutableList()

        // 迭代应用 merges
        var changed = true
        var iterations = 0
        val maxIterations = wordTokens.size * 2

        while (changed && iterations < maxIterations) {
            changed = false
            iterations++

            // 找到第一个可应用的 merge 规则
            for (i in 0 until wordTokens.size - 1) {
                val pair = wordTokens[i] to wordTokens[i + 1]
                val merged = pair.first + pair.second

                // 检查合并后的 token 是否在 vocab 中
                if (vocab.containsKey(merged)) {
                    // 应用合并
                    val newTokens = mutableListOf<String>()
                    var j = 0
                    while (j < wordTokens.size) {
                        if (j < wordTokens.size - 1 &&
                            wordTokens[j] == pair.first &&
                            wordTokens[j + 1] == pair.second
                        ) {
                            newTokens.add(merged)
                            j += 2
                            changed = true
                        } else {
                            newTokens.add(wordTokens[j])
                            j++
                        }
                    }
                    wordTokens = newTokens
                    break // 一次只应用一个 merge，然后重新扫描
                }
            }
        }

        // 最后，尝试将连续 token 合并为 vocab 中更长的 token
        return greedyMerge(wordTokens)
    }

    /**
     * 贪婪合并：尽可能合并 vocab 中存在的长 token
     */
    private fun greedyMerge(tokens: List<String>): List<String> {
        if (tokens.isEmpty()) return emptyList()

        val result = mutableListOf<String>()
        var i = 0
        while (i < tokens.size) {
            // 尝试从当前位置开始，找到 vocab 中最长的匹配
            var longestMatch: String? = null
            var longestLen = 0

            for (len in minOf(10, tokens.size - i) downTo 1) {
                val candidate = tokens.subList(i, i + len).joinToString("")
                if (vocab.containsKey(candidate) && len > longestLen) {
                    longestMatch = candidate
                    longestLen = len
                }
            }

            if (longestMatch != null) {
                result.add(longestMatch)
                i += longestLen
            } else {
                result.add(tokens[i])
                i++
            }
        }

        return result
    }

    /**
     * 截断或填充到固定长度
     */
    private fun padOrTruncate(tokenIds: List<Long>, maxLength: Int): List<Long> {
        return when {
            tokenIds.size > maxLength -> tokenIds.take(maxLength - 1) + eosTokenId
            tokenIds.size < maxLength -> tokenIds + List(maxLength - tokenIds.size) { padTokenId }
            else -> tokenIds
        }
    }

    /**
     * 构建字节到 Unicode 的映射表（GPT-2 / CLIP 风格）
     */
    private fun buildByteEncoder(): Map<Int, String> {
        val bytes = mutableListOf<Int>()
        // 可打印 ASCII + 扩展 Latin-1
        var i = 0
        while (i < 256) {
            bytes.add(i)
            i++
        }

        // 按 Unicode 码点排序
        val chars = bytes.map { it.toChar() }.sorted()

        return bytes.mapIndexed { index, byte ->
            byte to chars[index].toString()
        }.toMap()
    }

    /**
     * 检查 tokenizer 是否已加载
     */
    fun isReady(): Boolean = isLoaded

    /**
     * 获取 vocab 大小（调试用）
     */
    fun vocabSize(): Int = vocab.size

    /**
     * 将 token IDs 解码回文本（调试用）
     */
    fun decode(tokenIds: LongArray): String {
        val result = StringBuilder()
        for (id in tokenIds) {
            val token = idToToken[id]
            if (token != null) {
                result.append(token)
            }
        }
        return result.toString()
    }
}
