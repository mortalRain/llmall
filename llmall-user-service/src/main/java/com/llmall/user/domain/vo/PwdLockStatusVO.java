package com.llmall.user.domain.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 支付口令锁定状态视图：供前端预判并展示"还需等待多久"。
 *
 * 【为什么用数据表达状态而不是错误码】这是查询接口，正常返回就是它的成功语义：
 * "锁定中、剩余 8 分钟"是有效信息而非错误；用错误码表达会让前端把一次成功查询当失败处理，
 * 反而要多写一层分支。
 *
 * 【安全边界】只暴露"是否锁定"与"剩余时长"，不暴露已错误次数：
 * 后者会告诉攻击者距离锁定还有几次机会，帮他精确控制尝试节奏。
 */
@Data
@Accessors(chain = true)
@Schema(description = "支付密码锁定状态")
public class PwdLockStatusVO {

    /** 是否处于锁定状态：true 时任何支付口令校验都会被直接拒绝（不做比对） */
    @Schema(description = "是否处于锁定状态")
    private Boolean locked;

    /**
     * 剩余锁定分钟数（向上取整，最小 1）；未锁定时为 0。
     *
     * 【注意】向上取整是有意的：剩余不足 1 分钟若显示 0，用户会立刻重试并再次失败。
     */
    @Schema(description = "剩余锁定分钟数（向上取整）。未锁定时为 0")
    private Long remainMinutes;
}
