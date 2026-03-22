package com.picme.core.common

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.security.MessageDigest

object DuplicateImageDetector {
    
    /**
     * 计算图片的 MD5 哈希值
     */
    fun calculateMD5(file: File): String? {
        return try {
            val md = MessageDigest.getInstance("MD5")
            val bytes = file.readBytes()
            val digest = md.digest(bytes)
            digest.joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * 计算感知哈希 (pHash) - 用于检测相似图片
     */
    fun calculatePerceptualHash(file: File, size: Int = 32): Long? {
        return try {
            val options = BitmapFactory.Options().apply {
                inSampleSize = 1
            }
            val bitmap = BitmapFactory.decodeFile(file.absolutePath, options) ?: return null
            
            // 缩放到固定大小
            val scaled = Bitmap.createScaledBitmap(bitmap, size, size, true)
            
            // 转为灰度图并计算平均值
            val pixels = IntArray(size * size)
            scaled.getPixels(pixels, 0, size, 0, 0, size, size)
            
            var sum = 0L
            for (pixel in pixels) {
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF
                sum += (0.299 * r + 0.587 * g + 0.114 * b).toLong()
            }
            val avg = sum / (size * size)
            
            // 生成哈希：大于平均值的位为 1，否则为 0
            var hash = 0L
            for (i in pixels.indices) {
                val pixel = pixels[i]
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF
                val gray = (0.299 * r + 0.587 * g + 0.114 * b).toLong()
                if (gray >= avg) {
                    hash = hash or (1L shl i)
                }
            }
            
            hash
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * 计算两个哈希值的汉明距离
     */
    fun hammingDistance(hash1: Long, hash2: Long): Int {
        var xor = hash1 xor hash2
        var distance = 0
        while (xor != 0L) {
            distance++
            xor = xor and (xor - 1)
        }
        return distance
    }
    
    /**
     * 判断两张图片是否相似（汉明距离 <= 5）
     */
    fun areImagesSimilar(hash1: Long, hash2: Long, threshold: Int = 5): Boolean {
        return hammingDistance(hash1, hash2) <= threshold
    }
    
    /**
     * 数据类：存储重复图片信息
     */
    data class DuplicateGroup(
        val hash: String,
        val files: List<File>,
        val isExactDuplicate: Boolean = true
    )
    
    /**
     * 查找所有重复的图片组
     */
    suspend fun findDuplicates(files: List<File>): List<DuplicateGroup> {
        val hashMap = mutableMapOf<String, MutableList<File>>()
        val phashMap = mutableMapOf<Long, MutableList<File>>()
        
        // 第一步：按 MD5 分组（精确重复）
        files.forEach { file ->
            if (file.exists()) {
                val md5 = calculateMD5(file)
                if (md5 != null) {
                    hashMap.getOrPut(md5) { mutableListOf() }.add(file)
                }
                
                // 同时计算感知哈希（相似图片）
                val phash = calculatePerceptualHash(file)
                if (phash != null) {
                    phashMap.getOrPut(phash) { mutableListOf() }.add(file)
                }
            }
        }
        
        val duplicates = mutableListOf<DuplicateGroup>()
        
        // 添加精确重复的组
        hashMap.filter { it.value.size > 1 }.forEach { (hash, fileList) ->
            duplicates.add(DuplicateGroup(hash, fileList, isExactDuplicate = true))
        }
        
        // 添加相似但不完全相同的图片
        phashMap.filter { it.value.size > 1 }.forEach { (phash, fileList) ->
            // 排除已经作为精确重复添加的组
            val md5Hashes = fileList.mapNotNull { calculateMD5(it) }.toSet()
            if (md5Hashes.size > 1) { // 只有当 MD5 不完全相同时才添加为相似图片
                duplicates.add(DuplicateGroup(phash.toString(), fileList, isExactDuplicate = false))
            }
        }
        
        return duplicates
    }
}
