package com.llmall.promotion.controller;

import io.swagger.v3.oas.annotations.enums.ParameterIn;

import com.llmall.common.exception.ForbiddenException;
import com.llmall.promotion.domain.dto.VoucherPwDTO;
import com.llmall.promotion.domain.vo.SeckillResultVO;
import com.llmall.promotion.domain.vo.UserVoucherVO;
import com.llmall.promotion.domain.vo.VoucherVO;
import com.llmall.promotion.service.IVoucherService;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 营销域券接口。
 *
 * 返回值风格与本项目其它服务保持一致：单值直接返回（如 user_voucher.id、token、boolean），
 * 列表直接返回 List，不用 R 包一层 —— 统一包装会让"字段是集合还是对象"在网关/前端两侧都变模糊。
 * 身份一律从 {@code UserContext} 取（网关校验 JWT 后写入 user-info 头，llmall-common 的拦截器放进 ThreadLocal），
 * 本服务从不解析 token，也不接收客户端传来的 userId。
 */
@Tag(name = "营销域：优惠券与秒杀券接口")
@RestController
@RequestMapping("/vouchers")
@RequiredArgsConstructor
public class VoucherController {

    private final IVoucherService voucherService;

    @Operation(summary = "可领取的券列表（可按门店/类型过滤；秒杀券带时段与剩余库存，登录用户带是否已领）")
    @GetMapping("/list")
    public List<VoucherVO> listAvailable(@RequestParam(value = "shopId", required = false) Long shopId,
                                         @RequestParam(value = "type", required = false) Integer type) {
        return voucherService.listAvailable(shopId, type);
    }

    @Operation(summary = "我的券列表（status：1 未使用 2 已使用 3 已过期，不传返回全部；过期券按 expire_time 实时判定）")
    @GetMapping("/my")
    public List<UserVoucherVO> listMyVouchers(@RequestParam(value = "status", required = false) Integer status) {
        return voucherService.listMyVouchers(status);
    }

    /**
     * 我的券详情（按券实例 id）。
     *
     * 为什么必须补这个接口：交易域下单要"用券抵钱"，而抵扣的前提是拿到券的**权威状态与面额**
     * （是否本人持有、是否未使用、是否过期、适用哪个门店、抵多少分）。让交易域改成拉
     * /vouchers/my 列表再自己筛，等于把营销域的判定规则复制到交易域，两边早晚不一致；
     * 让调用方直接传面额又等于"前端说了算"。所以由券的归属方（本服务）回答这一个问题，
     * 归属校验也在这里做：非本人持有的券一律 403，调用方拿不到别人的券信息。
     *
     * 映射冲突说明：本方法路径比 /vouchers/{id} 更具体，Spring 会优先匹配字面量段 my，
     * 因此 /vouchers/my/123 不会被当成"券模板 id=my"。
     */
    @Operation(summary = "我的券详情（券实例 id；非本人持有返回 403）")
    @GetMapping("/my/{id}")
    public UserVoucherVO myVoucherDetail(@PathVariable("id") Long id) {
        return voucherService.getMyVoucher(id);
    }

    @Operation(summary = "券详情（含剩余库存与当前是否在秒杀时段）")
    @GetMapping("/{id}")
    public VoucherVO detail(@PathVariable("id") Long id) {
        return voucherService.getVoucherDetail(id);
    }

    @Operation(summary = "领取普通券（一人一张；有库存配置的券会条件扣减库存），返回 user_voucher.id")
    @PostMapping("/{id}/receive")
    public Long receive(@PathVariable("id") Long id,
                        @RequestBody(required = false) VoucherPwDTO body) {
        // Z3：券有售价（pay_value>0，例如"89 元买 100 元代金券"）时必须真的收到这笔钱才发券，
        // 所以这里把支付密码透传给业务层；pay_value=0 的营销券仍然免费领取。
        // 密码走请求体而不是 URL query：query 会进访问日志/浏览器历史/Referer（见 VoucherPwDTO 注释）。
        String pw = body == null ? null : body.getPw();
        return voucherService.receive(id, pw);
    }

    @Operation(summary = "秒杀抢券（Redis+Lua 原子预扣，返回秒杀凭证 token；落库由 MQ 异步完成）")
    @PostMapping("/seckill/{id}")
    public String seckill(@PathVariable("id") Long id,
                          @RequestBody(required = false) VoucherPwDTO body) {
        String pw = body == null ? null : body.getPw();
        return voucherService.seckill(id, pw);
    }

    @Operation(summary = "查询秒杀异步处理结果（PENDING/SUCCESS/FAILED）")
    @Parameter(name = "id", description = "券id", in = ParameterIn.PATH)
    @GetMapping("/seckill/{id}/result")
    public SeckillResultVO seckillResult(@PathVariable("id") Long id,
                                         @RequestParam("token") String token) {
        return voucherService.querySeckillResult(id, token);
    }

    /**
     * 【已关闭】用户自助核销券 —— Z4 漏洞的入口，永久拒绝。
     *
     * <h3>为什么是"永久 403"而不是"加一个内部凭证校验"</h3>
     * 券核销意味着平台为这笔订单让利，它只能由**订单链路**驱动：
     * 必须有一笔真实的、属于本人的、与券同门店且确实锁定了这张券的订单。
     * 让用户自己调用核销接口（orderId 还是前端传的）等于把这个约束全部作废 ——
     * 用户可以把券核销掉、orderId 随便填，下游谁也无法分辨。
     *
     * <p>有人会想"那就要求带上 x-internal-token"：这在**这条路径上无效**。
     * 网关为了下游能信任 user-info，会给**每一个认证通过的外部请求**注入同一个内部凭证头
     * （AuthGlobalFilter#forward），所以任何登录用户都能满足"凭证校验"。
     * 真正有效的边界是结构性的：核销接口只存在于 {@code /internal/vouchers/**}
     * （见 {@link InternalVoucherController}），而那条路径不在网关的路由表里，外部根本到不了。
     *
     * <p>这里保留旧路径并显式 403（而不是直接删掉变成 404），是为了让前端/调用方
     * 拿到一句明确的"内部接口，禁止外部调用"，而不是一个含义模糊的 404。
     */
    @Operation(summary = "【已废弃】核销我的券：核销只能由订单链路发起，外部调用一律 403")
    @PostMapping("/my/{id}/use")
    public boolean useMyVoucher(@PathVariable("id") Long id,
                                @RequestParam(value = "orderId", required = false) Long orderId) {
        throw new ForbiddenException("内部接口，禁止外部调用");
    }

    /**
     * 【已关闭】用户自助退券：同理，退券只能由订单取消/退款链路触发，
     * 否则用户可以自己把券"洗"回未使用状态，重复享受抵扣。
     */
    @Operation(summary = "【已废弃】退回我的券：只能由订单取消/退款链路发起，外部调用一律 403")
    @PutMapping("/my/{id}/refund")
    public boolean refundMyVoucher(@PathVariable("id") Long id,
                                   @RequestParam(value = "orderId", required = false) Long orderId) {
        throw new ForbiddenException("内部接口，禁止外部调用");
    }
}
