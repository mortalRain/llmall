package com.llmall.pay.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.llmall.pay.domain.po.PayOrder;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;

/**
 * 支付单表的 MyBatis-Plus 基础 Mapper：常规查询与条件更新全部由 ServiceImpl 的
 * lambdaQuery / lambdaUpdate 条件构造器生成（状态推进依赖 where 子句承担不变量，见 PayOrderServiceImpl）。
 *
 * 唯一的自定义 SQL 是 {@link #saveOrGetExisting}：把"按交易订单号查重 + 建单"压成一条语句，
 * 使申请支付单只取一次数据库连接（原先是 SELECT 一次、INSERT 一次，各借还一条连接）。
 *
 * @author 同城生活
 * @since 2023-05-16
 */
public interface PayOrderMapper extends BaseMapper<PayOrder> {

    /**
     * 建单或取回已存在的支付单：一条 {@code INSERT ... ON DUPLICATE KEY UPDATE} 完成"查重 + 插入"。
     *
     * 【为什么能替代先查后插】唯一性本来就由 {@code pay_order.uk_biz_order_no} 裁决，
     * 先查后插只是把裁决结果提前读了一次；并发下先查后插仍要靠唯一键兜底（第二个请求会拿到
     * DuplicateKeyException 再回查）。改成 upsert 后，由 InnoDB 在同一条语句里原子完成
     * "插入或命中已存在行"，并发重复申请不会抛异常，也不会各写一条。
     *
     * 【重复时为什么不改已有行】{@code ON DUPLICATE KEY UPDATE id = LAST_INSERT_ID(id)} 把 id 赋成它自己，
     * MySQL 认为"行没有变化"，因此不会改写金额/状态/渠道，也不会触发 update_time 的 ON UPDATE ——
     * "已存在则复用/重置"的判定仍然留在 Java 侧（终端态报错、渠道不同才重置），SQL 只负责不重复插入。
     *
     * 【主键怎么回填】入参对象的 id 由调用方用 IdWorker 预生成：
     *   - 新插入：驱动回填的就是这个预生成 id；
     *   - 命中已存在行：{@code LAST_INSERT_ID(id)} 让驱动回填**已有行的 id**。
     * 调用方据此比较"回填 id 是否等于预生成 id"，无需额外一次查询即可区分新建与复用。
     * 这也是 MySQL 官方推荐的"upsert 后取原主键"写法，不能改成 {@code id = id}（那样 LAST_INSERT_ID() 不会被设置）。
     *
     * 【失效条件】{@code uk_biz_order_no} 被删除或降级为普通索引后，本语句会真的插入第二条支付单。
     *
     * @param payOrder 待插入的支付单，id 必须已预生成；命中已存在行时其 id 会被改写成已有行 id
     * @return 受影响行数（Connector/J 默认 CLIENT_FOUND_ROWS 下新插入与命中都返回 1，**不能**用它区分两种情况）
     */
    @Insert("INSERT INTO pay_order (id, biz_order_no, pay_order_no, biz_user_id, pay_channel_code, amount, pay_type, status, pay_over_time) "
            + "VALUES (#{id}, #{bizOrderNo}, #{payOrderNo}, #{bizUserId}, #{payChannelCode}, #{amount}, #{payType}, #{status}, #{payOverTime}) "
            + "ON DUPLICATE KEY UPDATE id = LAST_INSERT_ID(id)")
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    int saveOrGetExisting(PayOrder payOrder);
}
