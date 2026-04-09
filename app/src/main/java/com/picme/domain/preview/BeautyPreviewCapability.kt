package com.picme.domain.preview

/**
 * GL 美颜预览专属能力扩展接口（typealias 桥接）
 *
 * Phase 2 后，此接口的权威定义已迁移至 beauty-engine-rplan 模块的
 * [com.picme.beauty.api.BeautyPreviewCapability]。
 * 此 typealias 保持 app 层调用方无感知兼容。
 *
 * @see com.picme.beauty.api.BeautyPreviewCapability
 */
typealias BeautyPreviewCapability = com.picme.beauty.api.BeautyPreviewCapability

