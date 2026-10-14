package com.llmall.user.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;

import com.llmall.user.domain.po.Address;
import com.llmall.user.mapper.AddressMapper;
import com.llmall.user.service.IAddressService;
import org.springframework.stereotype.Service;

/**
 * 收货地址服务实现，CRUD 全部由 MyBatis-Plus 的 ServiceImpl 提供。
 */
@Service
public class AddressServiceImpl extends ServiceImpl<AddressMapper, Address> implements IAddressService {

}
