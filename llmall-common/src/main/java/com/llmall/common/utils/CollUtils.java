package com.llmall.common.utils;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.collection.IterUtil;

import java.util.*;

/**
 * 集合工具类，在 hutool CollectionUtil 基础上补充不可变集合与元素拼接。
 */
public class CollUtils extends CollectionUtil {

    public static <T> List<T> emptyList() {
        return Collections.emptyList();
    }

    public static <T> Set<T> emptySet() {
        return Collections.emptySet();
    }

    public static <K,V> Map<K, V> emptyMap() {
        return Collections.emptyMap();
    }

    public static <T> List<T> singletonList(T t) {
        return Collections.singletonList(t);
    }

    /**
     * 以 conjunction 为分隔符将集合转换为字符串。
     */
    public static <T> String join(Collection<T> collection, CharSequence conjunction) {
        if (null == collection || collection.isEmpty()) {
            return null;
        }
        return IterUtil.join(collection.iterator(), conjunction);
    }
}
