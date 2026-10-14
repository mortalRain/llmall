package com.llmall.common.utils;

/** 对 origin 计算后设置到 target */
public interface Convert<R,T>{
    void convert(R origin, T target);
}
