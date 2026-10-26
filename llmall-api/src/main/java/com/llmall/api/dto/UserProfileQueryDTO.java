package com.llmall.api.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

/**
 * 批量查询用户展示资料的请求体。
 *
 * 【为什么单独定义而不是传 List】Feign/OpenAPI 对裸数组作为请求体的支持不一致，
 * 统一包一层对象后，调用方与用户服务对 `{ids:[...]}` 这一契约没有歧义。
 *
 * 【注意】ids 只用于查询昵称/头像，不接受手机号或余额等敏感字段的查询条件。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserProfileQueryDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 用户 id 列表；调用方按当前页/当前条目的作者去重后传入 */
    private List<Long> ids;
}
