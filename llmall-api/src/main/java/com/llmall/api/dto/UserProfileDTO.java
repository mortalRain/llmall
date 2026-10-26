package com.llmall.api.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

import java.io.Serializable;

/**
 * 用户展示资料（仅昵称与头像）。
 *
 * 【安全边界】本 DTO 是跨服务契约，只允许出现展示所需字段：手机号、余额、口令等
 * 一律不得加入。内容域只是为了在评价列表里显示作者昵称/头像，不需要也不应该拿到账户信息。
 *
 * 【空值口径】nickname/icon 都可能为空：昵称为空时由调用方回退成「同城用户{id}」，
 * 头像为空时由前端用昵称首字 + 稳定背景色兜底，不在这里编造数据。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Accessors(chain = true)
public class UserProfileDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;

    /** 昵称；用户未填写时为 null */
    private String nickname;

    /** 头像 URL；用户未上传时为 null */
    private String icon;
}
