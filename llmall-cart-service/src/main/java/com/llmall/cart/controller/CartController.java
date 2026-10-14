package com.llmall.cart.controller;
import com.llmall.cart.domain.dto.CartFormDTO;
import com.llmall.cart.domain.po.Cart;
import com.llmall.cart.domain.vo.CartVO;
import com.llmall.cart.service.ICartService;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.apache.ibatis.annotations.Param;
import org.springframework.web.bind.annotation.*;
import javax.validation.Valid;
import java.util.List;

@Tag(name = "购物车相关接口")
@RestController
@RequestMapping("/carts")
@RequiredArgsConstructor
public class CartController {
    private final ICartService cartService;

    @Operation(summary = "添加商品到购物车")
    @PostMapping
    public void addItem2Cart(@Valid @RequestBody CartFormDTO cartFormDTO){
        cartService.addItem2Cart(cartFormDTO);
    }

    @Operation(summary = "更新购物车数据")
    @PutMapping
    public void updateCart(@RequestBody Cart cart){
        // 请求体里的 id/userId/itemId/price 都不参与 UPDATE：归属校验与"只改 num"在 service 内完成
        cartService.updateCartNum(cart);
    }

    @Operation(summary = "删除购物车中商品")
    @DeleteMapping("{id}")
    public void deleteCartItem(@Param ("购物车条目id")@PathVariable("id") Long id){
        // 删除前必须确认条目属于当前用户，否则传任意 id 就能清空别人的购物车
        cartService.removeMyCartItem(id);
    }

    @Operation(summary = "查询购物车列表")
    @GetMapping
    public List<CartVO> queryMyCarts(){
        return cartService.queryMyCarts();
    }
    @Operation(summary = "批量删除购物车中商品")
    @Parameter(name = "ids", description = "购物车条目id集合")
    @DeleteMapping
    public void deleteCartItemByIds(@RequestParam("ids") List<Long> ids){
        cartService.removeByItemIds(ids);
    }
}
