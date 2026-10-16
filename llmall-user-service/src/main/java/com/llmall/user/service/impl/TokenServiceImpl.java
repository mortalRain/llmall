package com.llmall.user.service.impl;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.llmall.common.constant.AuthConstants;
import com.llmall.common.domain.TokenPayload;
import com.llmall.common.exception.UnauthorizedException;
import com.llmall.common.utils.RedisScripts;
import com.llmall.common.security.JwtProperties;
import com.llmall.user.domain.vo.TokenPair;
import com.llmall.user.service.ITokenService;
import com.llmall.common.security.JwtTool;
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

    /**
     * 全量失效水位的 TTL（小时）。
     *
     * 【作用】改密、冻结或踢人时写入一个用户维度的时间戳水位：签发时间早于该水位的 access 一律失效，
     * 即"一次改密让全部旧 access 立即失效"，无需逐条拉黑（access 不落白名单，逐条拉黑不可行）。
     *
     * 【与 access 令牌寿命的倍数关系与计算过程】水位按令牌签发时间判定，只需覆盖 access 的最大寿命：
     * 水位 TTL = access 最大寿命 × 2。按 access 寿命 30 分钟计即 30 × 2 = 60 分钟 = 1 小时；
     * 取 2 倍是给服务间时钟偏差留余量，避免边界上的旧令牌被漏判。
     * access 寿命由 {@code llmall.jwt.tokenTTL} 决定，该配置改动后本值须按同一算法重算。
     *
     * 【关联配置】本常量直接引用 IdempotentKeys.REDIS_GLOBAL_LOGOUT_TTL_HOURS，两处是同一个值，
     * 修改必须成对；水位键前缀由 AuthConstants 定义，网关侧读同一个 key，容器内不得出现第二个字面量。
     *
     * 【不同步的后果】把 tokenTTL 调大而本值不动，会出现"水位已过期、旧 access 仍未过期"的窗口：
     * 该窗口内改密或踢人后的旧令牌仍能正常调用业务接口，会话失效形同虚设。
     */
    private static final long GLOBAL_LOGOUT_TTL_HOURS =
            com.llmall.common.constant.IdempotentKeys.REDIS_GLOBAL_LOGOUT_TTL_HOURS;

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
        // 【签发时间水位校验】口令变更（改密/冻结/踢人）后，此前签发的 refresh 一并失效。
        //
        // 为什么在"白名单已被删除"之外还要判一次：
        //   正常路径下 invalidateAllSessions → revokeAllRefresh 已经把白名单删掉，刷新自会失败；
        //   但那条失效链路依赖"删除成功"。若删除因 Redis 抖动部分失败，
        //   refresh 的寿命远长于 access（分别由 llmall.jwt.refreshTTL 与 llmall.jwt.tokenTTL 决定），
        //   仅靠"键没了"这一层兜底不够稳。
        //   水位是写一次、判一次的成本极低的第二道闸，用来给长寿命令牌上一道确定性保险。
        //
        // 【失败取向】水位读取失败时放行后续流程（不因读不到水位就拒绝刷新）：
        //   Redis 抖动不该让全部用户无法续期；此时白名单校验仍然生效，安全基线未降低。
        if (isIssuedBeforeLogoutWatermark(payload)) {
            log.warn("【安全】refresh token 签发早于会话失效水位，拒绝刷新: userId={}, iat={}",
                    payload.getUserId(), payload.getIssuedAt());
            throw new UnauthorizedException("登录状态已失效，请重新登录");
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
            // 【注意】令牌已过期也必须把该用户的 refresh 全部作废，不能直接返回"成功"了事：
            // 用户点注销的正常时机恰恰是 access 刚过期，此时若跳过 revokeAllRefresh，
            // refresh 白名单还在，被"注销"的 refresh 仍能在其整个寿命内不断换取新 access —— 会话实际还活着。
            // 过期令牌无法再被使用，故黑名单写不写都无影响；要作废的是寿命更长的 refresh。
            revokeAllRefreshByExpiredToken(accessToken, e);
        }
    }

    /**
     * 令牌不可用（过期/非法）时的注销兜底：仅当能验签证明它确实是我们签发且已过期的令牌时，
     * 才按其中的 userId 作废 refresh。
     *
     * 【注意】用 {@code parseAllowExpired} 而不是放宽 {@code parse} 的校验：后者是鉴权入口，
     * 一旦允许过期令牌通过，等于取消所有令牌的过期校验。
     */
    private void revokeAllRefreshByExpiredToken(String accessToken, Exception cause) {
        TokenPayload expired = jwtTool.parseAllowExpired(accessToken);
        if (expired == null || expired.getUserId() == null) {
            log.debug("注销时令牌不可用且无法识别身份，按已注销处理: {}", cause.getMessage());
            return;
        }
        log.info("注销时 access 已过期，改以 refresh 作废为准（避免会话残留）: userId={}", expired.getUserId());
        revokeAllRefresh(expired.getUserId());
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

    /**
     * 口令变更后的全量会话失效：作废全部 refresh + 标记"此前签发的 access 立即失效"。
     *
     * 【两件事都要做，缺一不可】
     * 1. 作废 refresh：杜绝"用旧 refresh 换出新 access"绕过改密；
     * 2. 写失效水位：让仍在有效期内的旧 access 立即失效；少了它，改密后旧令牌在其剩余寿命内
     *    仍可继续操作账户。
     *
     * 【水位 TTL 为什么取 access 寿命的倍数】水位按签发时间判定，只要水位存续期间所有旧 access
     * 都已过期即可回收：TTL = access 最大寿命 × 2（按默认寿命计为 30 × 2 = 60 分钟），
     * 倍数用于吸收服务间时钟偏差造成的边界漏判。取值依据与关联配置见本类 GLOBAL_LOGOUT_TTL_HOURS。
     *
     * 【失败处理】任一步失败都只记 ERROR 不抛异常：口令已经改成功，
     * 不能因为会话清理失败而让用户以为改密失败并重试。
     */
    @Override
    public void invalidateAllSessions(Long userId) {
        if (userId == null) {
            return;
        }
        try {
            // ① 作废该用户全部 refresh（含索引集合）
            revokeAllRefresh(userId);
        } catch (Exception e) {
            log.error("【安全】口令变更后作废 refresh token 失败，需人工确认: userId={}", userId, e);
        }
        try {
            // ② 写失效水位：网关据此拒绝"签发时间早于水位"的 access token
            redisTemplate.opsForValue().set(
                    AuthConstants.REDIS_GLOBAL_LOGOUT_AT + userId,
                    String.valueOf(System.currentTimeMillis()),
                    Duration.ofHours(GLOBAL_LOGOUT_TTL_HOURS));
            log.info("【安全】口令已变更，该用户全部会话已失效（需重新登录）: userId={}", userId);
        } catch (Exception e) {
            // 水位写失败时旧 access 仍能用满剩余寿命，需人工关注
            log.error("【安全】口令变更后写入全量失效水位失败，旧 access token 将在剩余寿命内继续有效: userId={}",
                    userId, e);
        }
    }

    private String newJti() {
        return IdUtil.fastSimpleUUID();
    }

    /**
     * 判断令牌的签发时间是否早于"全量会话失效水位"。
     *
     * @return true 表示该令牌已被水位覆盖（应拒绝）；无法判定或读取失败时返回 false（不阻断）
     */
    private boolean isIssuedBeforeLogoutWatermark(TokenPayload payload) {
        if (payload.getIssuedAt() == null || payload.getUserId() == null) {
            // 旧版本签发的令牌没有 iat：无法判定，按"未失效"处理（与网关侧口径一致，
            // 避免灰度期间把在用令牌集体拒掉）
            return false;
        }
        try {
            String at = redisTemplate.opsForValue()
                    .get(AuthConstants.REDIS_GLOBAL_LOGOUT_AT + payload.getUserId());
            if (at == null) {
                return false;
            }
            return Long.parseLong(at) > payload.getIssuedAt().getTime();
        } catch (Exception e) {
            // 读取失败不阻断刷新：白名单校验仍会生效，安全基线未降低
            log.warn("会话失效水位读取失败（放行刷新，白名单校验仍生效）: userId={}", payload.getUserId(), e);
            return false;
        }
    }
}
