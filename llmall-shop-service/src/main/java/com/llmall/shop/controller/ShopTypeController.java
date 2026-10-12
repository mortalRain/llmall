package com.llmall.shop.controller;

import com.llmall.shop.domain.po.ShopType;
import com.llmall.shop.service.IShopTypeService;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "门店类型接口")
@RestController
@RequestMapping("/shop-types")
@RequiredArgsConstructor
public class ShopTypeController {

    private final IShopTypeService shopTypeService;

    @Operation(summary = "查询全部门店类型")
    @GetMapping({ "", "/" })
    public List<ShopType> listAll() {
        return shopTypeService.listAll();
    }
}
