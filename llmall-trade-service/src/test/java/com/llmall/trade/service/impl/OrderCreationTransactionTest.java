package com.llmall.trade.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.llmall.api.client.*;
import com.llmall.api.dto.ItemDTO;
import com.llmall.api.dto.OrderDetailDTO;
import com.llmall.api.dto.UserVoucherDTO;
import com.llmall.common.constant.MqConstants;
import com.llmall.common.exception.BadRequestException;
import com.llmall.common.mq.IMessageLogService;
import com.llmall.common.utils.UserContext;
import com.llmall.trade.domain.dto.OrderFormDTO;
import com.llmall.trade.mapper.OrderDetailMapper;
import com.llmall.trade.mapper.OrderMapper;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real H2/MyBatis transactions; external inventory, coupons and RabbitMQ remain mocks. */
class OrderCreationTransactionTest {
    private JdbcTemplate db;
    private OrderServiceImpl service;
    private ItemClient items;
    private VoucherClient vouchers;

    @BeforeEach
    void setup() throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        db = new JdbcTemplate(ds);
        db.execute("CREATE TABLE `order`(id BIGINT PRIMARY KEY,order_type INT,shop_id BIGINT,voucher_id BIGINT,"
                + "verify_code VARCHAR(32),verify_time TIMESTAMP,client_token VARCHAR(64),request_hash VARCHAR(64),"
                + "total_fee INT,payment_type INT,user_id BIGINT,status INT,create_time TIMESTAMP,pay_time TIMESTAMP,"
                + "consign_time TIMESTAMP,end_time TIMESTAMP,close_time TIMESTAMP,comment_time TIMESTAMP,"
                + "update_time TIMESTAMP,UNIQUE(user_id,client_token))");
        db.execute("CREATE TABLE order_detail(id BIGINT AUTO_INCREMENT PRIMARY KEY,order_id BIGINT,item_id BIGINT,"
                + "num INT,name VARCHAR(100),spec VARCHAR(100),price INT,image VARCHAR(100),"
                + "create_time TIMESTAMP,update_time TIMESTAMP)");
        MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(ds);
        MybatisConfiguration cfg = new MybatisConfiguration();
        cfg.setMapUnderscoreToCamelCase(true);
        cfg.addMapper(OrderMapper.class);
        cfg.addMapper(OrderDetailMapper.class);
        factory.setConfiguration(cfg);
        SqlSessionTemplate sql = new SqlSessionTemplate(factory.getObject());
        OrderDetailServiceImpl details = new OrderDetailServiceImpl();
        ReflectionTestUtils.setField(details, "baseMapper", sql.getMapper(OrderDetailMapper.class));
        ReflectionTestUtils.setField(details, "sqlSessionFactory", factory.getObject());
        items = mock(ItemClient.class);
        vouchers = mock(VoucherClient.class);
        service = new OrderServiceImpl(items, details, mock(RabbitTemplate.class), mock(IMessageLogService.class),
                null, vouchers, mock(UserClient.class), mock(PayClient.class), mock(ShopClient.class),
                new TransactionTemplate(new DataSourceTransactionManager(ds)));
        ReflectionTestUtils.setField(service, "baseMapper", sql.getMapper(OrderMapper.class));
        when(items.queryItemByIds(anyCollection())).thenReturn(Arrays.asList(item(2L, 200), item(1L, 100)));
        UserContext.setUser(1L);
    }

    @AfterEach
    void cleanup() {
        UserContext.removeUser();
        db.execute("DROP ALL OBJECTS");
    }

    @Test
    void authorityPriceDetailsAndStockAgreeWithoutMutatingCaller() {
        OrderFormDTO form = form(2, 3);
        form.getDetails().get(0).setBizId("forged").setBizType("forged").setShopId(999L);
        doAnswer(call -> {
            List<OrderDetailDTO> stock = call.getArgument(0);
            assertEquals(2, stock.get(0).getNum());
            assertEquals(3, stock.get(1).getNum());
            assertEquals(10L, stock.get(0).getShopId());
            assertEquals(MqConstants.BIZ_ORDER_CREATED, stock.get(0).getBizType());
            assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM `order` WHERE id=?",
                    Integer.class, Long.valueOf(stock.get(0).getBizId())));
            return null;
        }).when(items).deductStock(anyList());
        Long id = service.createOrder(form);
        assertEquals(800, total(id));
        assertEquals(800L, db.queryForObject("SELECT SUM(CAST(price AS BIGINT)*num) FROM order_detail WHERE order_id=?", Long.class, id));
        assertEquals(2, count("order_detail"));
        assertEquals("forged", form.getDetails().get(0).getBizId());
        assertEquals("forged", form.getDetails().get(0).getBizType());
        assertEquals(999L, form.getDetails().get(0).getShopId());
        assertNull(form.getDetails().get(1).getBizId());
    }

    @Test
    void replayUsesPersistedOrderEvenAfterPricesChange() {
        OrderFormDTO first = form(1, 1);
        first.setClientToken(" retry-token ");
        Long id = service.createOrder(first);
        when(items.queryItemByIds(anyCollection())).thenThrow(new AssertionError("Replay queried catalog"));
        OrderFormDTO reordered = form(1, 1);
        Collections.reverse(reordered.getDetails());
        reordered.setClientToken("retry-token");
        assertEquals(id, service.createOrder(reordered));
        first.getDetails().get(0).setNum(2);
        assertThrows(BadRequestException.class, () -> service.createOrder(first));
        assertEquals(1, count("`order`"));
        assertEquals(2, count("order_detail"));
        verify(items, times(1)).queryItemByIds(anyCollection());
        verify(items, times(1)).deductStock(anyList());
    }

    @Test
    void maximumIntTotalAndFreeGiftAreSupported() {
        when(items.queryItemByIds(anyCollection())).thenReturn(Arrays.asList(item(1L, Integer.MAX_VALUE), item(2L, 0)));
        assertEquals(Integer.MAX_VALUE, total(service.createOrder(form(1, 1))));
    }

    @Test
    void legacyPhysicalItemsCanHaveNoShopOrType() {
        ItemDTO one = item(1L, 100), two = item(2L, 200);
        one.setShopId(null); one.setType(null);
        two.setShopId(null); two.setType(null);
        when(items.queryItemByIds(anyCollection())).thenReturn(Arrays.asList(one, two));
        Long id = service.createOrder(form(1, 1));
        assertEquals(1, service.getById(id).getOrderType());
        assertNull(service.getById(id).getShopId());
        assertEquals(300, total(id));
    }

    @Test
    void sameShopPackagesAndPositiveDiscountKeepOriginalLinePrices() {
        ItemDTO one = item(1L, 100), two = item(2L, 200);
        one.setType(2); two.setType(2);
        when(items.queryItemByIds(anyCollection())).thenReturn(Arrays.asList(one, two));
        UserVoucherDTO voucher = new UserVoucherDTO();
        voucher.setId(8L); voucher.setStatus(1); voucher.setShopId(10L); voucher.setActualValue(50L);
        when(vouchers.queryMyVoucher(8L)).thenReturn(voucher);
        when(vouchers.useMyVoucher(anyLong(), anyLong())).thenReturn(true);
        OrderFormDTO form = form(1, 1); form.setUserVoucherId(8L);
        Long id = service.createOrder(form);
        assertEquals(250, total(id));
        assertEquals(2, service.getById(id).getOrderType());
        assertEquals(8L, service.getById(id).getVoucherId());
        assertEquals(300L, db.queryForObject("SELECT SUM(CAST(price AS BIGINT)*num) FROM order_detail", Long.class));
        verify(vouchers).useMyVoucher(8L, id);
    }

    @Test
    void stockFailureRollsBackOrderAndActualDetails() {
        doThrow(new RuntimeException("inventory unavailable")).when(items).deductStock(anyList());
        assertThrows(RuntimeException.class, () -> service.createOrder(form(1, 1)));
        assertEquals(0, count("`order`"));
        assertEquals(0, count("order_detail"));
    }

    @Test
    void crossShopFailureWritesNothingAndDoesNotDeductStock() {
        ItemDTO two = item(2L, 200); two.setShopId(20L);
        when(items.queryItemByIds(anyCollection())).thenReturn(Arrays.asList(item(1L, 100), two));
        assertThrows(BadRequestException.class, () -> service.createOrder(form(1, 1)));
        assertEquals(0, count("`order`"));
        assertEquals(0, count("order_detail"));
        verify(items, never()).deductStock(anyList());
    }

    private int count(String table) {
        return db.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private int total(Long id) {
        return db.queryForObject("SELECT total_fee FROM `order` WHERE id=?", Integer.class, id);
    }

    private static OrderFormDTO form(int one, int two) {
        OrderFormDTO form = new OrderFormDTO();
        form.setPaymentType(3);
        form.setDetails(new ArrayList<>(Arrays.asList(new OrderDetailDTO().setItemId(1L).setNum(one),
                new OrderDetailDTO().setItemId(2L).setNum(two))));
        return form;
    }

    private static ItemDTO item(Long id, int price) {
        ItemDTO item = new ItemDTO();
        item.setId(id); item.setPrice(price); item.setType(1); item.setStatus(1); item.setShopId(10L);
        return item;
    }
}
