package com.llmall.common.utils;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.collection.IterUtil;
import cn.hutool.core.util.NumberUtil;

import java.util.*;
import java.util.stream.Collectors;

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

    public static <T> Set<T> singletonSet(T t) {
        return Collections.singleton(t);
    }

    public static <T> List<T> singletonList(T t) {
        return Collections.singletonList(t);
    }

    public static List<Integer> convertToInteger(List<String> originList){
        return CollUtils.isNotEmpty(originList) ? originList.stream().map(NumberUtil::parseInt).collect(Collectors.toList()) : null;
    }

    public static List<Long> convertToLong(List<String> originLIst){
        return CollUtils.isNotEmpty(originLIst) ? originLIst.stream().map(NumberUtil::parseLong).collect(Collectors.toList()) : null;
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

    public static <T> String joinIgnoreNull(Collection<T> collection, CharSequence conjunction) {
        if (null == collection || collection.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (T t : collection) {
            if(t == null) continue;
            sb.append(t).append(",");
        }
        if(sb.length() <= 0){
            return null;
        }
        return sb.deleteCharAt(sb.length() - 1).toString();
    }
}