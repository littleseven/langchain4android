package com.mamba.picme.domain.search

enum class SegmentType {
    /** 时间：去年3月、今年夏天、上周一等 */
    TIME,
    /** 地点：北京、室内、海边等 */
    LOCATION,
    /** 人物：小孩、我、宝宝、某个人名等 */
    PERSON,
    /** 物体：猫、车、食物等 */
    OBJECT,
    /** 场景：室内、户外、海滩、餐厅等 */
    SCENE,
    /** 活动：聚餐、运动会、婚礼等 */
    ACTIVITY,
    /** OCR 文字：发票、车牌、菜单等 */
    OCR,
    /** 未知/停用词：照片、的、了等 */
    UNKNOWN;

    /** 是否属于显式约束段 */
    fun isExplicit(): Boolean = this in setOf(TIME, LOCATION, PERSON)

    /** 是否属于内容检索段 */
    fun isContent(): Boolean = this in setOf(OBJECT, SCENE, ACTIVITY, OCR)
}
