package com.llmall.user.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;

import com.llmall.user.domain.po.Address;
import com.llmall.user.mapper.AddressMapper;
import com.llmall.user.service.IAddressService;
import org.springframework.stereotype.Service;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 同城生活
 * @since 2023-05-05
 */
@Service
public class AddressServiceImpl extends ServiceImpl<AddressMapper, Address> implements IAddressService {

}
