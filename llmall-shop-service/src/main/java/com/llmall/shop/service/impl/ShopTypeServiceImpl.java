package com.llmall.shop.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.llmall.shop.domain.po.ShopType;
import com.llmall.shop.mapper.ShopTypeMapper;
import com.llmall.shop.service.IShopTypeService;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Override
    public List<ShopType> listAll() {
        return lambdaQuery().orderByAsc(ShopType::getSort).list();
    }
}
