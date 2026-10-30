package com.llmall.trade.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.llmall.api.client.*;
import com.llmall.api.dto.PayOrderStatusDTO;
import com.llmall.common.utils.UserContext;
import com.llmall.trade.domain.po.OrderDetail;
import com.llmall.trade.mapper.OrderMapper;
import com.llmall.trade.service.IOrderDetailService;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OrderCompensationTransactionTest {
    private JdbcTemplate db;
    private TransactionTemplate tx;
    private OrderServiceImpl service;
    private UserClient users; private ItemClient items; private PayClient pay; private VoucherClient vouchers;
    @BeforeEach void setup() throws Exception {
        JdbcDataSource ds=new JdbcDataSource();ds.setURL("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1");
        db=new JdbcTemplate(ds);tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
        db.execute("CREATE TABLE `order`(id BIGINT PRIMARY KEY,order_type INT,shop_id BIGINT,voucher_id BIGINT,verify_code VARCHAR(32),verify_time TIMESTAMP,client_token VARCHAR(64),request_hash VARCHAR(64),total_fee INT,payment_type INT,user_id BIGINT,status INT,create_time TIMESTAMP,pay_time TIMESTAMP,consign_time TIMESTAMP,end_time TIMESTAMP,close_time TIMESTAMP,comment_time TIMESTAMP,update_time TIMESTAMP)");
        db.update("INSERT INTO `order`(id,user_id,total_fee,status) VALUES(100,1,300,2)");
        MybatisSqlSessionFactoryBean factory=new MybatisSqlSessionFactoryBean();factory.setDataSource(ds);
        MybatisConfiguration cfg=new MybatisConfiguration();cfg.setMapUnderscoreToCamelCase(true);cfg.addMapper(OrderMapper.class);factory.setConfiguration(cfg);
        users=mock(UserClient.class);items=mock(ItemClient.class);pay=mock(PayClient.class);vouchers=mock(VoucherClient.class);
        IOrderDetailService details=mock(IOrderDetailService.class);
        when(details.listByOrderId(100L)).thenReturn(Collections.singletonList(new OrderDetail().setItemId(20L).setNum(1)));
        service=new OrderServiceImpl(items,details,null,null,null,vouchers,users,pay,null,tx);
        ReflectionTestUtils.setField(service,"baseMapper",new SqlSessionTemplate(factory.getObject()).getMapper(OrderMapper.class));
        UserContext.setUser(1L);
    }
    @AfterEach void cleanup(){UserContext.removeUser();db.execute("DROP ALL OBJECTS");}
    private int status(){return db.queryForObject("SELECT status FROM `order` WHERE id=100",Integer.class);}
    @Test void failedRefundRemainsDurableAndCanRecoverAfterRestart(){
        doThrow(new RuntimeException("user unavailable")).when(users).refundMoney(1L,300,"ORDER_REFUND:100");
        service.refundMyOrder(100L);assertEquals(9,status());verifyNoInteractions(items,pay);
        doAnswer(call->{assertFalse(TransactionSynchronizationManager.isActualTransactionActive());assertEquals(9,status());return null;})
                .when(users).refundMoney(1L,300,"ORDER_REFUND:100");
        UserContext.removeUser();service.recoverCompensation(100L);assertEquals(5,status());assertNull(UserContext.getUser());
        service.recoverCompensation(100L);verify(users,times(2)).refundMoney(1L,300,"ORDER_REFUND:100");
    }
    @Test void paymentCloseFailureDoesNotMarkRefundCompleted(){
        doThrow(new RuntimeException("pay unavailable")).when(pay).closePayOrder(100L);
        service.refundMyOrder(100L);assertEquals(9,status());
        doNothing().when(pay).closePayOrder(100L);service.recoverCompensation(100L);assertEquals(5,status());
    }
    @Test void sentinelWrappedFailureKeepsRefundAcceptedAndRecoverable(){
        doThrow(new AssertionError("Sentinel fallback threw")).when(pay).closePayOrder(100L);
        assertDoesNotThrow(()->service.refundMyOrder(100L));assertEquals(9,status());
    }
    @Test void rejectedPaymentFenceDoesNotReleaseStock(){
        db.update("UPDATE `order` SET status=1 WHERE id=100");
        when(pay.closeUnpaid(any())).thenReturn(PayOrderStatusDTO.unknown("processing"));
        assertThrows(RuntimeException.class,()->service.cancelMyOrder(100L));assertEquals(1,status());verifyNoInteractions(items,users);
    }
    @Test void verifiedOrderCannotEnterRefund(){
        db.update("UPDATE `order` SET status=7,verify_time=CURRENT_TIMESTAMP WHERE id=100");
        assertThrows(RuntimeException.class,()->service.refundMyOrder(100L));assertEquals(7,status());verifyNoInteractions(users,items,pay);
    }
    @Test void afterCommitCancellationReadsFreshCompensationState(){
        db.update("UPDATE `order` SET status=1 WHERE id=100");
        when(pay.closeUnpaid(any())).thenReturn(PayOrderStatusDTO.of(2));
        tx.executeWithoutResult(s->{
            service.getById(100L); // Populate the outer MyBatis session's first-level cache.
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){
                @Override public void afterCommit(){service.cancelOrder(100L);}
            });
        });
        assertEquals(5,status());verify(items).restoreStock(anyList());
    }
}
