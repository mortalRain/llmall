package com.llmall.trade.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.llmall.api.client.*;
import com.llmall.api.dto.*;
import com.llmall.common.exception.BadRequestException;
import com.llmall.common.mq.IMessageLogService;
import com.llmall.common.utils.UserContext;
import com.llmall.trade.domain.dto.OrderFormDTO;
import com.llmall.trade.domain.po.Order;
import com.llmall.trade.mapper.OrderMapper;
import com.llmall.trade.mq.OrderEventFactory;
import com.llmall.trade.service.IOrderDetailService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import java.util.stream.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OrderInputBoundaryTest {
    OrderServiceImpl service; ItemClient items; VoucherClient vouchers; IOrderDetailService details;
    IMessageLogService<?> messages; RabbitTemplate rabbit;
    @BeforeAll static void metadata(){TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(),""),Order.class);}
    @BeforeEach void setup(){
        items=mock(ItemClient.class);vouchers=mock(VoucherClient.class);details=mock(IOrderDetailService.class);
        messages=mock(IMessageLogService.class);rabbit=mock(RabbitTemplate.class);
        TransactionTemplate tx=mock(TransactionTemplate.class);
        when(tx.execute(any())).thenAnswer(c->((TransactionCallback<?>)c.getArgument(0)).doInTransaction(new SimpleTransactionStatus()));
        service=spy(new OrderServiceImpl(items,details,rabbit,(IMessageLogService)messages,mock(OrderEventFactory.class),vouchers,
                mock(UserClient.class),mock(PayClient.class),mock(ShopClient.class),tx));
        ReflectionTestUtils.setField(service,"baseMapper",mock(OrderMapper.class));
        doThrow(new AssertionError("Invalid request crossed persistence boundary")).when(service).save(any(Order.class));
        when(items.queryItemByIds(anyCollection())).thenReturn(List.of(item(1,10,1,100),item(2,10,1,200)));
        UserContext.setUser(1L);
    }
    @AfterEach void clear(){UserContext.removeUser();}
    @ParameterizedTest(name="{0}") @MethodSource("invalidForms")
    void malformedFormStopsBeforeAnyRemoteCall(String name,OrderFormDTO form){
        assertThrows(BadRequestException.class,()->service.createOrder(form));
        verifyNoInteractions(items,vouchers,details,messages,rabbit);
        verify(service,never()).save(any());
    }
    static Stream<Arguments> invalidForms(){
        OrderFormDTO duplicate=form(line(1,1),line(1,2));
        OrderFormDTO tooMany=form(IntStream.rangeClosed(1,51).mapToObj(i->line(i,1)).toArray(OrderDetailDTO[]::new));
        OrderFormDTO payment=form();payment.setPaymentType(9);
        OrderFormDTO token=form();token.setClientToken("x".repeat(65));
        OrderFormDTO voucher=form();voucher.setUserVoucherId(-1L);
        OrderFormDTO address=form();address.setAddressId(-1L);
        return Stream.of(Arguments.of("null form",null),Arguments.of("empty details",form(new OrderDetailDTO[0])),
                Arguments.of("null row",form((OrderDetailDTO)null)),Arguments.of("null id",form(new OrderDetailDTO().setNum(1))),
                Arguments.of("nonpositive id",form(line(0,1))),Arguments.of("bad quantity",form(line(1,100))),
                Arguments.of("duplicate sku",duplicate),Arguments.of("over 50 rows",tooMany),Arguments.of("unknown payment",payment),
                Arguments.of("token over 64",token),Arguments.of("bad voucher id",voucher),Arguments.of("bad address id",address));
    }
    @ParameterizedTest(name="{0}") @MethodSource("invalidItems")
    void badAuthorityDataCannotReachOrderOrStock(String name,List<ItemDTO> response){
        when(items.queryItemByIds(anyCollection())).thenReturn(response);
        assertThrows(BadRequestException.class,()->service.createOrder(form()));
        verify(items,never()).deductStock(anyList());verifyNoInteractions(details,messages,rabbit,vouchers);
        verify(service,never()).save(any());
    }
    static Stream<Arguments> invalidItems(){
        ItemDTO missingPrice=item(1,10,1,100);missingPrice.setPrice(null);
        ItemDTO off=item(1,10,1,100);off.setStatus(2);
        ItemDTO missingShop=item(1,10,2,100);missingShop.setShopId(null);
        ItemDTO missingStatus=item(1,10,1,100);missingStatus.setStatus(null);
        return Stream.of(Arguments.of("different shops",List.of(item(1,10,1,100),item(2,20,1,200))),
                Arguments.of("different fulfillment",List.of(item(1,10,1,100),item(2,10,2,200))),
                Arguments.of("sum overflow",List.of(item(1,10,1,1500000000),item(2,10,1,1500000000))),
                Arguments.of("duplicate response id",List.of(item(1,10,1,100),item(1,10,1,100))),
                Arguments.of("extra response id",List.of(item(1,10,1,100),item(2,10,1,200),item(3,10,1,300))),
                Arguments.of("null response row",Arrays.asList(null,item(2,10,1,200))),
                Arguments.of("missing price",List.of(missingPrice,item(2,10,1,200))),
                Arguments.of("negative price",List.of(item(1,10,1,-1),item(2,10,1,200))),
                Arguments.of("off shelf",List.of(off,item(2,10,1,200))),
                Arguments.of("unknown shelf status",List.of(missingStatus,item(2,10,1,200))),
                Arguments.of("unknown fulfillment",List.of(item(1,10,7,100),item(2,10,7,200))),
                Arguments.of("package without shop",List.of(missingShop,item(2,10,2,200))),
                Arguments.of("all zero",List.of(item(1,10,1,0),item(2,10,1,0))));
    }
    @ParameterizedTest @ValueSource(longs={300,301,2147483648L,Long.MAX_VALUE})
    void zeroOrOversizedDiscountCannotReachPersistence(long face){
        UserVoucherDTO voucher=new UserVoucherDTO();voucher.setId(8L);voucher.setStatus(1);voucher.setActualValue(face);
        when(vouchers.queryMyVoucher(8L)).thenReturn(voucher);
        OrderFormDTO form=form();form.setUserVoucherId(8L);
        assertThrows(BadRequestException.class,()->service.createOrder(form));
        verify(service,never()).save(any());verify(items,never()).deductStock(anyList());
        verifyNoInteractions(details,messages,rabbit);
    }
    @Test void lineMultiplicationCannotOverflow(){
        when(items.queryItemByIds(anyCollection())).thenReturn(List.of(item(1,10,1,Integer.MAX_VALUE)));
        assertThrows(BadRequestException.class,()->service.createOrder(form(line(1,2))));
        verify(service,never()).save(any());
    }
    static OrderFormDTO form(OrderDetailDTO... rows){
        OrderFormDTO form=new OrderFormDTO();form.setPaymentType(3);form.setDetails(Arrays.asList(rows));return form;
    }
    static OrderFormDTO form(){return form(line(1,1),line(2,1));}
    static OrderDetailDTO line(long id,int count){return new OrderDetailDTO().setItemId(id).setNum(count);}
    static ItemDTO item(long id,long shop,int type,int price){
        ItemDTO item=new ItemDTO();item.setId(id);item.setShopId(shop);item.setType(type);item.setPrice(price);item.setStatus(1);return item;
    }
}
