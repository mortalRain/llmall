package com.llmall.shop.domain.bo;

/**
 * 门店缓存重建的原因。
 *
 * 仅用于诊断；是否可回填由Redis原子比较锁token决定，不根据原因推断权限。
 */
public enum RebuildReason {

    /** 冷 key 未命中：正常回填 */
    COLD_MISS,

    /** 命中但逻辑过期：必须回填新值，否则缓存永远不会被刷新 */
    LOGICAL_EXPIRE
}
