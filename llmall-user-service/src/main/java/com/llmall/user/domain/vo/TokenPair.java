package com.llmall.user.domain.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 双令牌对。
 * access  ：短有效期（30 分钟），随请求携带，泄露影响面小；
 * refresh ：长有效期（7 天），只用于换取新的 access，且服务端可主动作废。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TokenPair {
    private String accessToken;
    private String refreshToken;
}
