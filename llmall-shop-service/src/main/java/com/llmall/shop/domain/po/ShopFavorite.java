package com.llmall.shop.domain.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 门店收藏关系（表 shop_favorite）。
 *
 * 【数据形态】一行 = "某用户收藏了某门店"，只存关系与收藏时间，不复制门店字段：
 * 门店名称/评分/图片会变，收藏列表展示时按 shop_id 回门店表取最新值。
 * 唯一键 uk_user_shop(user_id, shop_id) 是收藏幂等的落库兜底，重复收藏不会产生第二行。
 *
 * 【为什么不做成 shop 表的 favorite 计数字段】收藏是用户维度的明细关系，删除收藏、查询
 * "我的收藏"都要求按用户过滤；计数字段只能回答"被收藏多少次"，无法支撑这两个主路径。
 * 若后续需要展示热度计数，应由关系表聚合或另建统计表，而不是改这张关系表。
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("shop_favorite")
public class ShopFavorite implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 收藏人（llmall-user.user.id，跨库逻辑外键，无物理外键） */
    private Long userId;

    /** 被收藏门店（llmall-shop.shop.id） */
    private Long shopId;

    private LocalDateTime createTime;
}
