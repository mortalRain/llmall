package com.llmall.user.service.impl;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.llmall.common.constant.AuthConstants;
import com.llmall.common.domain.TokenPayload;
import com.llmall.common.exception.UnauthorizedException;
import com.llmall.common.utils.RedisScripts;
import com.llmall.user.config.JwtProperties;
import com.llmall.user.domain.vo.TokenPair;
import com.llmall.user.service.ITokenService;
import com.llmall.user.util.JwtTool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.Set;

/**
 * 令牌的签发、轮换与注销。JWT 天生"签发后无法撤回"，要支持登出/踢人就必须引入服务端状态，
 * 且只对需要失效的令牌引入状态、而不是退回 Session：access token 用短 TTL + 黑名单
 * （只存被注销的那部分 jti，TTL 设为令牌剩余寿命，自动清理）；refresh token 用白名单
 * （Redis 里存在才有效），以支持轮换与批量作废。
 *
 * 【注意】refresh 一次一换：每次刷新都作废旧 refresh 并下发新的，令牌泄露时攻击者与真实用户
 * 会互相把对方的令牌刷失效，从而暴露异常。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TokenServiceImpl implements ITokenService {

    /**
     * 签发 refresh token 的原子脚本：白名单 + 用户索引集合 + 集合 TTL 一次完成。
     *
     * 【注意】三条命令分开发时，索引写入失败会留下一个"没有进索引的白名单令牌"：用户自己用着
     * 一切正常，但"登出全部设备 / 改密强制下线"永远扫不到它，且没有任何报错。脚本只在白名单写入
     * 成功后才动索引集合，Lua 里 SET 抛错则整个脚本不执行，不会出现反向脏数据。
     *
     * KEYS[1]=白名单 key，KEYS[2]=用户索引集合 key；ARGV[1]=userId，ARGV[2]=TTL（秒）。
     */
    private static final DefaultRedisScript<Long> ISSUE_REFRESH_SCRIPT = new DefaultRedisScript<>(
            "redis.call('set', KEYS[1], ARGV[1], 'EX', tonumber(ARGV[2])) "
                    + "redis.call('sadd', KEYS[2], string.sub(KEYS[1], #'auth:refresh:' + 1)) "
                    + "redis.call('expire', KEYS[2], tonumber(ARGV[2])) "
                    + "return 1",
            Long.class);

    private final JwtTool jwtTool;
    private final JwtProperties jwtProperties;
    private final StringRedisTemplate redisTemplate;

    @Override
    public TokenPair issue(Long userId) {
        String accessJti = newJti();
        String refreshJti = newJti();

        String accessToken = jwtTool.createToken(userId, accessJti, AuthConstants.TOKEN_TYPE_ACCESS, jwtProperties.getTokenTTL());
        String refreshToken = jwtTool.createToken(userId, refreshJti, AuthConstants.TOKEN_TYPE_REFRESH, jwtProperties.getRefreshTTL());

        // refresh token 白名单与用户索引集合必须原子写入，理由见 ISSUE_REFRESH_SCRIPT
        long refreshTtlSeconds = jwtProperties.getRefreshTTL().getSeconds();
        try {
            redisTemplate.execute(ISSUE_REFRESH_SCRIPT,
                    Arrays.asList(AuthConstants.REDIS_REFRESH + refreshJti,
                            AuthConstants.REDIS_REFRESH_USER + userId),
                    userId.toString(),
                    String.valueOf(refreshTtlSeconds));
        } catch (Exception e) {
            // 白名单是 refresh 生效的唯一依据（网关与用户服务都只认它），写失败就必须让签发失败：
            // 返回一对"刷新时必然报失效"的令牌，比返回一对"看起来能用、其实无法轮换也无法作废"的令牌更好排查
            log.error("签发 refresh token 白名单失败, userId={}", userId, e);
            throw new UnauthorizedException("登录状态签发失败，请重试");
        }

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
        String expectedUserId = String.valueOf(payload.getUserId());

        // 【注意】轮换必须是原子的 check-and-delete：两步实现（GET 校验、再 DEL 作废）之间隔着一次
        // 网络往返，两个并发刷新请求都会在对方删除之前读到值并通过校验，一次刷新换出两套有效令牌。
        // 这里用 Lua 把"比对 userId + 删除"合成一次原子操作：只有拿到删除结果 1 的请求算轮换成功，
        // 其余一律拿 0 被拒绝；删除即轮换，成功者才继续签发新令牌。
        Long consumed;
        try {
            consumed = redisTemplate.execute(RedisScripts.COMPARE_AND_DELETE,
                    Collections.singletonList(key), expectedUserId);
        } catch (Exception e) {
            // Redis 不可用时拒绝而不是放行：这是认证链路，fail-open 等于让"已作废的 refresh 继续可用"
            log.error("校验并作废 refresh token 失败（按失效处理）: jti={}", payload.getJti(), e);
            throw new UnauthorizedException("刷新令牌校验失败，请重新登录");
        }
        if (consumed == null || consumed <= 0) {
            // 0 或 null 有两种可能：key 不存在（已过期/已轮换/已登出），或值不是这个用户（令牌与用户被拼接伪造）
            throw new UnauthorizedException("刷新令牌已失效，请重新登录");
        }
        // 索引集合里的这条 jti 也要收掉，否则"登出全部设备"会拿着已作废的 jti 去删不存在的 key
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

    /**
     * 作废某用户全部 refresh token（登出所有设备 / 改密强制下线）。
     *
     * 【注意】判据是索引集合里的 jti，集合本身不能比它索引的令牌先过期：单 key 的 TTL 无法表达
     * "涵盖所有成员的最大剩余寿命"，这里用可行的近似——每次签发（含每次轮换）都重新 {@code EXPIRE}
     * 一次，用户只要还在用集合就一直是满 TTL，而集合成员最多活 refreshTTL，两者长度一致。
     *
     * 残余风险：某个 jti 在集合过期后才签发、且之后用户再没登录过，那条 jti 就不在集合里了。
     */
    private void revokeAllRefresh(Long userId) {
        if (userId == null) {
            return;
        }
        String userSetKey = AuthConstants.REDIS_REFRESH_USER + userId;
        Set<String> jtis = redisTemplate.opsForSet().members(userSetKey);
        if (jtis != null && !jtis.isEmpty()) {
            // 一次 DEL 传多个 key，避免 N 个设备 N 次往返
            redisTemplate.delete(jtis.stream()
                    .map(jti -> AuthConstants.REDIS_REFRESH + jti)
                    .collect(java.util.stream.Collectors.toList()));
        }
        redisTemplate.delete(userSetKey);
    }

    private String newJti() {
        return IdUtil.fastSimpleUUID();
    }
}
