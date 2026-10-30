package com.llmall.promotion.controller;

import io.swagger.v3.oas.annotations.enums.ParameterIn;

import com.llmall.common.exception.ForbiddenException;
import com.llmall.promotion.domain.dto.VoucherPwDTO;
import com.llmall.promotion.domain.vo.SeckillResultVO;
import com.llmall.promotion.domain.vo.UserVoucherVO;
import com.llmall.promotion.domain.vo.VoucherVO;
import com.llmall.promotion.domain.vo.VoucherPurchaseVO;
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
 * 营销域优惠券与秒杀券接口。
 *
 * 身份一律从 {@code UserContext} 取，本服务不解析 token，也不接收客户端传来的 userId。
 * 返回值与本项目其它服务保持一致：单值直接返回、列表直接返回 List，不用 R 包一层。
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
     * 我的券详情（按券实例 id）：由券的归属方回答券的权威状态与面额，归属校验也在这里做，
     * 非本人持有的券一律 403，调用方拿不到别人的券信息。
     *
     * 【注意】路径段 my 是字面量，Spring 优先于 /vouchers/{id} 匹配，不会把 my 当成券模板 id。
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

    @Operation(summary = "领取普通券（一人一张；付费需requestId；秒杀券走独立入口），返回 user_voucher.id")
    @PostMapping("/{id}/receive")
    public Long receive(@PathVariable("id") Long id,
                        @RequestBody(required = false) VoucherPwDTO body) {
        // 券有售价（pay_value>0，例如"89 元买 100 元代金券"）时必须真的收到这笔钱才发券，
        // 所以这里把支付密码透传给业务层；pay_value=0 的营销券仍然免费领取。
        // 【注意】密码走请求体而不是 URL query：query 会进访问日志/浏览器历史/Referer。
        String pw = body == null ? null : body.getPw();
        return voucherService.receive(id, pw, body == null ? null : body.getRequestId());
    }

    @Operation(summary = "查询自己的普通券购买请求；未找到时返回NOT_FOUND")
    @GetMapping("/purchases/{requestId}")
    public VoucherPurchaseVO purchase(@PathVariable("requestId") String requestId) {
        return voucherService.purchase(requestId);
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
     * 已关闭：用户自助核销券，外部调用一律 403。
     *
     * 【注意】核销只能由订单链路驱动，必须有一笔真实的、属于本人的、与券同门店且确实锁定了该券的订单。
     * 在这条路径上要求携带 x-internal-token 无效：网关会给每一个认证通过的外部请求注入同一个
     * 内部凭证头，任何登录用户都能满足"凭证校验"；真正有效的边界是路径本身 —— 核销接口只存在于
     * {@code /internal/vouchers/**}（见 {@link InternalVoucherController}），不在网关路由表里。
     *
     * 保留旧路径并显式 403（而不是直接删掉变成 404），是为了让调用方拿到明确的"内部接口，禁止外部调用"。
     */
    @Operation(summary = "【已废弃】核销我的券：核销只能由订单链路发起，外部调用一律 403")
    @PostMapping("/my/{id}/use")
    public boolean useMyVoucher(@PathVariable("id") Long id,
                                @RequestParam(value = "orderId", required = false) Long orderId) {
        throw new ForbiddenException("内部接口，禁止外部调用");
    }

    /**
     * 已关闭：用户自助退券，外部调用一律 403。
     *
     * 【注意】退券只能由订单取消/退款链路触发，否则用户可以把券"洗"回未使用状态重复抵扣。
     */
    @Operation(summary = "【已废弃】退回我的券：只能由订单取消/退款链路发起，外部调用一律 403")
    @PutMapping("/my/{id}/refund")
    public boolean refundMyVoucher(@PathVariable("id") Long id,
                                   @RequestParam(value = "orderId", required = false) Long orderId) {
        throw new ForbiddenException("内部接口，禁止外部调用");
    }
}
