package com.llmall.user.service.impl;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.llmall.common.constant.AuthConstants;
import com.llmall.common.domain.TokenPayload;
import com.llmall.common.exception.UnauthorizedException;
import com.llmall.user.config.JwtProperties;
import com.llmall.user.domain.vo.TokenPair;
import com.llmall.user.service.ITokenService;
import com.llmall.user.util.JwtTool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Set;

/**
 * 令牌服务实现。
 *
 * 设计说明（面试重点）：
 * - JWT 是无状态的，天生"签发后无法撤回"。要能登出/踢人，就必须引入**服务端状态**，
 *   但只对"需要失效的令牌"引入状态，而不是退回 Session：
 *     · access token  —— 短 TTL + 黑名单（只存被注销的那部分 jti），TTL 设为令牌剩余寿命，自动清理；
 *     · refresh token —— 白名单（Redis 里存在才有效），支持轮换与批量作废。
 * - refresh 轮换：每次刷新都作废旧 refresh 并下发新的，即使 refresh 泄露，攻击者与真实用户
 *   会互相把对方的令牌刷失效，从而暴露异常（这是 OAuth2 的推荐实践）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TokenServiceImpl implements ITokenService {

    private final JwtTool jwtTool;
    private final JwtProperties jwtProperties;
    private final StringRedisTemplate redisTemplate;

    @Override
    public TokenPair issue(Long userId) {
        String accessJti = newJti();
        String refreshJti = newJti();

        String accessToken = jwtTool.createToken(userId, accessJti, AuthConstants.TOKEN_TYPE_ACCESS, jwtProperties.getTokenTTL());
        String refreshToken = jwtTool.createToken(userId, refreshJti, AuthConstants.TOKEN_TYPE_REFRESH, jwtProperties.getRefreshTTL());

        // refresh token 白名单：Redis 里有才算有效
        redisTemplate.opsForValue().set(
                AuthConstants.REDIS_REFRESH + refreshJti, userId.toString(), jwtProperties.getRefreshTTL());
        // 记录用户已签发的 refresh，便于"登出全部设备"
        String userSetKey = AuthConstants.REDIS_REFRESH_USER + userId;
        redisTemplate.opsForSet().add(userSetKey, refreshJti);
        redisTemplate.expire(userSetKey, jwtProperties.getRefreshTTL());

        return new TokenPair(accessToken, refreshToken);
    }

    @Override
    public TokenPair refresh(String refreshToken) {
        if (StrUtil.isBlank(refreshToken)) {
            throw new UnauthorizedException("缺少刷新令牌");
        }
        TokenPayload payload = jwtTool.parse(refreshToken);
        if (!AuthConstants.TOKEN_TYPE_REFRESH.equals(payload.getType())) {
            // 防止拿 access token 当 refresh token 用
            throw new UnauthorizedException("非法的刷新令牌");
        }
        String key = AuthConstants.REDIS_REFRESH + payload.getJti();
        String cachedUserId = redisTemplate.opsForValue().get(key);
        if (cachedUserId == null || !cachedUserId.equals(String.valueOf(payload.getUserId()))) {
            throw new UnauthorizedException("刷新令牌已失效，请重新登录");
        }
        // 轮换：旧 refresh 立刻作废
        redisTemplate.delete(key);
        redisTemplate.opsForSet().remove(AuthConstants.REDIS_REFRESH_USER + payload.getUserId(), payload.getJti());

        log.debug("refresh token 轮换成功, userId={}", payload.getUserId());
        return issue(payload.getUserId());
    }

    @Override
    public void logout(String accessToken) {
        if (StrUtil.isBlank(accessToken)) {
            return;
        }
        try {
            TokenPayload payload = jwtTool.parse(accessToken);
            long remain = payload.remainSeconds();
            if (remain > 0 && payload.getJti() != null) {
                // 黑名单只保留到令牌自然过期的那一刻，过期即自动清理，不会无限堆积
                redisTemplate.opsForValue().set(
                        AuthConstants.REDIS_BLACKLIST + payload.getJti(), "1", Duration.ofSeconds(remain));
            }
            revokeAllRefresh(payload.getUserId());
        } catch (Exception e) {
            // 令牌已过期/非法时，注销同样视为成功（幂等）
            log.debug("注销时令牌不可用，按已注销处理: {}", e.getMessage());
        }
    }

    /** 作废某用户全部 refresh token（登出所有设备 / 改密强制下线） */
    private void revokeAllRefresh(Long userId) {
        if (userId == null) {
            return;
        }
        String userSetKey = AuthConstants.REDIS_REFRESH_USER + userId;
        Set<String> jtis = redisTemplate.opsForSet().members(userSetKey);
        if (jtis != null && !jtis.isEmpty()) {
            jtis.forEach(jti -> redisTemplate.delete(AuthConstants.REDIS_REFRESH + jti));
        }
        redisTemplate.delete(userSetKey);
    }

    private String newJti() {
        return IdUtil.fastSimpleUUID();
    }
}
