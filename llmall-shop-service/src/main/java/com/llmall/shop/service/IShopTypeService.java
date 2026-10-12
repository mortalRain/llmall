package com.llmall.shop.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.llmall.shop.domain.po.ShopType;

import java.util.List;

public interface IShopTypeService extends IService<ShopType> {

    /** 全部门店类型，按 sort 升序 */
    List<ShopType> listAll();
}
