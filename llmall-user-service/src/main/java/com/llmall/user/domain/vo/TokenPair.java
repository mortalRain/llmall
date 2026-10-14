package com.llmall.user.domain.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 双令牌对：access 短效（30 分钟），refresh 长效（7 天）且服务端可主动作废。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TokenPair {
    private String accessToken;
    private String refreshToken;
}
