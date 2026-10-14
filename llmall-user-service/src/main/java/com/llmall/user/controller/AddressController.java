package com.llmall.user.controller;


import com.llmall.common.exception.BadRequestException;
import com.llmall.common.utils.BeanUtils;
import com.llmall.common.utils.CollUtils;
import com.llmall.common.utils.UserContext;

import com.llmall.user.domain.dto.AddressDTO;
import com.llmall.user.domain.po.Address;
import com.llmall.user.service.IAddressService;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.util.List;

/**
 * 收货地址接口：当前登录用户地址的增删改查与默认地址维护。
 *
 * @author 同城生活
 */
@RestController
@RequestMapping("/addresses")
@RequiredArgsConstructor
@Tag(name = "收货地址管理接口")
public class AddressController {

    private final IAddressService addressService;

    @Operation(summary = "根据id查询地址")
    @GetMapping("{addressId}")
    public AddressDTO findAddressById(@Parameter(name = "addressId", description = "地址id") @PathVariable("addressId") Long id) {
        Address address = addressService.getById(id);
        Long userId = UserContext.getUser();
        if (address == null) {
            throw new BadRequestException("地址不存在");
        }
        if (!address.getUserId().equals(userId)) {
            throw new BadRequestException("地址不属于当前登录用户");
        }
        return BeanUtils.copyBean(address, AddressDTO.class);
    }

    @Operation(summary = "查询当前用户地址列表")
    @GetMapping
    public List<AddressDTO> findMyAddresses() {
        List<Address> list = addressService.query().eq("user_id", UserContext.getUser()).list();
        if (CollUtils.isEmpty(list)) {
            return CollUtils.emptyList();
        }
        return BeanUtils.copyList(list, AddressDTO.class);
    }

    @Operation(summary = "新增收货地址")
    @PostMapping
    // 【注意】@Valid 是 AddressDTO 上 @NotBlank/@Pattern 生效的前提，去掉后手机号等约束形同虚设
    public Long saveAddress(@Valid @RequestBody AddressDTO addressDTO) {
        Long userId = requireLogin();
        Address address = BeanUtils.copyBean(addressDTO, Address.class);
        // userId 只能由网关透传的身份决定，绝不能采信前端传上来的 userId（否则可给他人写地址）
        address.setUserId(userId);
        address.setId(null);
        if (address.getIsDefault() == null) {
            address.setIsDefault(0);
        }
        if (address.getIsDefault() == 1) {
            // 默认地址只允许一个：先把其它地址的默认标记清掉
            clearDefault(userId);
        }
        addressService.save(address);
        return address.getId();
    }

    @Operation(summary = "修改收货地址")
    @PutMapping
    public void updateAddress(@Valid @RequestBody AddressDTO addressDTO) {
        Long userId = requireLogin();
        Address exist = addressService.getById(addressDTO.getId());
        if (exist == null || !exist.getUserId().equals(userId)) {
            throw new BadRequestException("地址不存在或不属于当前登录用户");
        }
        Address address = BeanUtils.copyBean(addressDTO, Address.class);
        address.setUserId(userId);
        if (address.getIsDefault() != null && address.getIsDefault() == 1) {
            clearDefault(userId);
        }
        addressService.updateById(address);
    }

    @Operation(summary = "删除收货地址")
    @DeleteMapping("{addressId}")
    public void deleteAddress(@PathVariable("addressId") Long id) {
        Long userId = requireLogin();
        Address exist = addressService.getById(id);
        if (exist == null || !exist.getUserId().equals(userId)) {
            throw new BadRequestException("地址不存在或不属于当前登录用户");
        }
        addressService.removeById(id);
    }

    private Long requireLogin() {
        Long userId = UserContext.getUser();
        if (userId == null) {
            throw new BadRequestException("未登录");
        }
        return userId;
    }

    private void clearDefault(Long userId) {
        addressService.update()
                .set("is_default", 0)
                .eq("user_id", userId)
                .update();
    }
}
