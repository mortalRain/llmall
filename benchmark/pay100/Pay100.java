import ch.qos.logback.classic.Level;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.fasterxml.jackson.databind.*;
import com.llmall.api.client.*;
import com.llmall.api.dto.*;
import com.llmall.common.advice.CommonExceptionAdvice;
import com.llmall.common.constant.MqConstants;
import com.llmall.common.mq.IMessageLogService;
import com.llmall.common.utils.UserContext;
import com.llmall.pay.controller.PayController;
import com.llmall.pay.mapper.*;
import com.llmall.pay.service.impl.*;
import com.llmall.trade.controller.OrderController;
import com.llmall.trade.domain.po.Order;
import com.llmall.trade.mapper.*;
import com.llmall.trade.mq.OrderEventFactory;
import com.llmall.trade.service.impl.*;
import com.llmall.user.mapper.*;
import com.llmall.user.service.impl.BalancePaymentService;
import com.llmall.user.support.*;
import com.zaxxer.hikari.HikariDataSource;
import org.apache.catalina.Context;
import org.apache.catalina.startup.Tomcat;
import org.apache.coyote.AbstractProtocol;
import org.apache.ibatis.session.LocalCacheScope;
import org.mybatis.spring.SqlSessionTemplate;
import org.slf4j.LoggerFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.*;
import org.springframework.web.servlet.config.annotation.*;
import javax.servlet.http.*;
import java.lang.management.ManagementFactory;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Current-source HTTP/MySQL component fixture, NOT independently deployed services or real MQ/Redis. */
public class Pay100 {
    static final ObjectMapper JSON = new ObjectMapper();
    static final List<Map<String,Object>> samples = Collections.synchronizedList(new ArrayList<>());
    static final AtomicLong sequence = new AtomicLong();
    static final AtomicInteger active = new AtomicInteger(), peak = new AtomicInteger();
    static URI base;
    static JdbcTemplate db;
    static HttpClient client;
    static AnnotationConfigWebApplicationContext webContext;
    static Map<String,Object> report = new LinkedHashMap<>();

    @Configuration @EnableWebMvc
    public static class Mvc implements WebMvcConfigurer {
        public void extendMessageConverters(List<org.springframework.http.converter.HttpMessageConverter<?>> converters) {
            for(org.springframework.http.converter.HttpMessageConverter<?> converter:converters)
                if(converter instanceof org.springframework.http.converter.json.AbstractJackson2HttpMessageConverter)
                    ((org.springframework.http.converter.json.AbstractJackson2HttpMessageConverter)converter).getObjectMapper()
                        .registerModule(new com.fasterxml.jackson.module.paramnames.ParameterNamesModule(com.fasterxml.jackson.annotation.JsonCreator.Mode.PROPERTIES));
        }
        public void addInterceptors(InterceptorRegistry registry) {
            registry.addInterceptor(new HandlerInterceptor() {
                public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) {
                    // Test identity adapter on loopback only. No JWT/security claim.
                    UserContext.setUser(Long.valueOf(req.getHeader("x-fixture-user")));
                    peak.accumulateAndGet(active.incrementAndGet(), Math::max);
                    return true;
                }
                public void afterCompletion(HttpServletRequest req,HttpServletResponse res,Object handler,Exception ex) {
                    UserContext.removeUser(); active.decrementAndGet();
                }
            });
        }
    }

    public static void main(String[] args) throws Exception {
        Path output=Path.of(args[0]);
        if(Files.exists(output))throw new IllegalArgumentException("Fresh output required");
        Files.createDirectories(output);
        ((ch.qos.logback.classic.Logger)LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)).setLevel(Level.ERROR);
        report.put("completed",false);report.put("cleanupPassed",false);
        report.put("scope","100-worker current-source HTTP/MySQL component; real order/pay/balance services and Spring transactions; fixture inventory, identity, in-process RPC, Redis password guard and MQ adapters; no full-stack claim");
        report.put("schema","llmall_pay100_"+UUID.randomUUID().toString().replace("-","").substring(0,12));
        report.put("clientWorkers",100);report.put("serverMaxThreads",100);report.put("dbPoolMax",8);
        report.put("bcryptCost",4);report.put("passwordGuard","fixture no-op; no Redis or gateway");report.put("warmupFlows",3);report.put("javaVersion",System.getProperty("java.version"));
        ExecutorService clients=Executors.newFixedThreadPool(100);
        Tomcat server=null;
        boolean success=false;
        HikariDataSource pool=new HikariDataSource();
        try(pool) {
            memory("start");
            String schema=(String)report.get("schema");
            String password=Objects.requireNonNull(System.getenv("LLMALL_TEST_DB_PASSWORD"));
            try(Connection admin=DriverManager.getConnection(url(""),"root",password);Statement st=admin.createStatement()) {
                if(!schema.matches("llmall_pay100_[0-9a-f]{12}"))throw new AssertionError("schema ownership");
                st.execute("CREATE DATABASE `"+schema+"`");
            }
            pool.setJdbcUrl(url(schema));pool.setUsername("root");pool.setPassword(password);
            pool.setMaximumPoolSize(8);pool.setMinimumIdle(1);pool.setConnectionTimeout(30000);
            db=new JdbcTemplate(pool);seed();
            MybatisConfiguration cfg=new MybatisConfiguration();cfg.setMapUnderscoreToCamelCase(true);
            cfg.setCacheEnabled(false);cfg.setLocalCacheScope(LocalCacheScope.STATEMENT);
            for(Class<?> mapper:List.of(OrderMapper.class,OrderDetailMapper.class,PayOrderMapper.class,
                    PaymentAttemptMapper.class,UserMapper.class,BalancePaymentMapper.class,BalanceFlowMapper.class))cfg.addMapper(mapper);
            MybatisSqlSessionFactoryBean factory=new MybatisSqlSessionFactoryBean();factory.setDataSource(pool);factory.setConfiguration(cfg);
            SqlSessionTemplate sql=new SqlSessionTemplate(factory.getObject());
            DataSourceTransactionManager manager=new DataSourceTransactionManager(pool);
            OrderDetailServiceImpl details=new OrderDetailServiceImpl();
            ReflectionTestUtils.setField(details,"baseMapper",sql.getMapper(OrderDetailMapper.class));
            ReflectionTestUtils.setField(details,"sqlSessionFactory",factory.getObject());
            ItemClient inventory=mock(ItemClient.class);
            when(inventory.queryItemByIds(anyCollection())).thenAnswer(c->{
                ItemDTO item=new ItemDTO();item.setId(1L);item.setShopId(10L);item.setType(1);
                item.setPrice(300);item.setStatus(1);item.setName("synthetic");return List.of(item);
            });
            // Synthetic inventory adapter. Monetary and order tables use actual production mappers.
            doAnswer(c->{for(OrderDetailDTO line:(List<OrderDetailDTO>)c.getArgument(0)){
                check(db.update("UPDATE fixture_stock SET stock=stock-? WHERE id=1 AND stock>=?",line.getNum(),line.getNum())==1,"stock");
            }return null;}).when(inventory).deductStock(anyList());
            OrderServiceImpl orders=new OrderServiceImpl(inventory,details,mock(RabbitTemplate.class),mock(IMessageLogService.class),
                    new OrderEventFactory(details),mock(VoucherClient.class),mock(UserClient.class),mock(PayClient.class),
                    mock(ShopClient.class),new TransactionTemplate(manager));
            final OrderServiceImpl transactionalOrders=tx(orders,manager);
            ReflectionTestUtils.setField(orders,"baseMapper",sql.getMapper(OrderMapper.class));
            TradeClient trade=mock(TradeClient.class);
            when(trade.queryOrderById(anyLong())).thenAnswer(c->{
                Order row=orders.getById((Long)c.getArgument(0));if(row==null)return null;
                TradeOrderDTO dto=new TradeOrderDTO();dto.setId(row.getId());dto.setUserId(row.getUserId());
                dto.setStatus(row.getStatus());dto.setTotalFee(row.getTotalFee());dto.setShopId(row.getShopId());dto.setVoucherId(row.getVoucherId());return dto;
            });
            BalancePaymentService money=tx(new BalancePaymentService(sql.getMapper(BalancePaymentMapper.class),sql.getMapper(BalanceFlowMapper.class),
                    sql.getMapper(UserMapper.class),new PayPasswordVerifier(mock(PasswordAttemptGuard.class),sql.getMapper(UserMapper.class),new BCryptPasswordEncoder(4))),manager);
            BalancePaymentClient balance=mock(BalancePaymentClient.class);
            doAnswer(c->{money.debit(c.getArgument(0));return null;}).when(balance).debit(any());
            doAnswer(c->{money.cancel(c.getArgument(0));return null;}).when(balance).cancel(any());
            PaymentAttemptStore store=tx(new PaymentAttemptStore(sql.getMapper(PayOrderMapper.class),sql.getMapper(PaymentAttemptMapper.class)),manager);
            RabbitTemplate broker=mock(RabbitTemplate.class);
            doAnswer(c->{
                Long id=c.getArgument(2);CorrelationData data=c.getArgument(3);
                // Explicit synchronous delivery adapter instead of a RabbitMQ server.
                transactionalOrders.markOrderPaySuccess(id);data.getFuture().set(new CorrelationData.Confirm(true,null));return null;
            }).when(broker).convertAndSend(anyString(),anyString(),any(Object.class),any(CorrelationData.class));
            BalancePaymentCoordinator coordinator=new BalancePaymentCoordinator(store,sql.getMapper(PaymentAttemptMapper.class),balance,trade,
                    mock(VoucherClient.class),new PaymentSuccessPublisher(broker,sql.getMapper(PaymentAttemptMapper.class)));
            PayOrderServiceImpl payments=new PayOrderServiceImpl(trade,mock(VoucherClient.class),coordinator);
            ReflectionTestUtils.setField(payments,"baseMapper",sql.getMapper(PayOrderMapper.class));
            server=start(new OrderController(orders),new PayController(payments));
            base=URI.create("http://127.0.0.1:"+server.getConnector().getLocalPort());
            client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).version(HttpClient.Version.HTTP_1_1).build();
            for(int i=0;i<3;i++)flow("warmup",1001,i);
            peak.set(0);
            Map<String,Object> loop=parallel(clients,worker->{for(int round=0;round<15;round++)flow("loop",worker+1,round);});
            loop.put("flows",1500);loop.put("httpRequests",4500);loop.put("serverPeakInFlight",peak.get());
            check(number("SELECT COUNT(*) FROM `order` WHERE user_id BETWEEN 1 AND 100 AND status=2")==1500,"paid orders");
            check(number("SELECT COUNT(*) FROM pay_order WHERE biz_user_id BETWEEN 1 AND 100 AND status=3")==1500,"paid payorders");
            check(number("SELECT COUNT(*) FROM payment_attempt WHERE user_id BETWEEN 1 AND 100 AND state='SUCCEEDED'")==1500,"succeeded attempts");
            check(number("SELECT COUNT(*) FROM balance_payment WHERE user_id BETWEEN 1 AND 100 AND state='DEBITED'")==1500,"debits");
            check(number("SELECT COUNT(*) FROM balance_flow WHERE user_id BETWEEN 1 AND 100")==1500,"flows");
            long balanceAfter=number("SELECT SUM(balance) FROM user WHERE id BETWEEN 1 AND 100");
            check(balanceAfter==10000000L-450000,"balance reconciliation");
            loop.put("balanceBeforeCents",10000000);loop.put("balanceAfterCents",balanceAfter);loop.put("debitCents",450000);
            loop.put("paidOrdersVerified",1500);loop.put("paidPayOrdersVerified",1500);loop.put("successfulAttemptsVerified",1500);loop.put("debitRecordsVerified",1500);loop.put("ledgerRowsVerified",1500);
            report.put("loop",loop);memory("afterLoop");
            List<Map<String,Object>> races=new ArrayList<>();
            for(int round=0;round<3;round++) {
                final long user=201+round;
                long order=create("setup",user,0),pay=apply("setup",user,order);
                peak.set(0);AtomicInteger accepted=new AtomicInteger(),rejected=new AtomicInteger();
                String phase="race"+round;
                Map<String,Object> race=parallel(clients,worker->{int status=post(phase,user,"debit","/pay-orders/"+pay,"{\"pw\":\"654321\"}").statusCode();
                    if(status==200)accepted.incrementAndGet();else if(status==400)rejected.incrementAndGet();else throw new AssertionError("Unexpected race status "+status);});
                check(accepted.get()==1&&rejected.get()==99,"one accepted + 99 rejected");
                check(number("SELECT balance FROM user WHERE id="+user)==99700,"race balance");
                check(number("SELECT COUNT(*) FROM balance_flow WHERE user_id="+user)==1,"race flow count");
                check(number("SELECT COUNT(*) FROM payment_attempt WHERE user_id="+user+" AND state='SUCCEEDED'")==1,"race attempt count");
                check(number("SELECT status FROM `order` WHERE id="+order)==2,"race order callback");
                check(number("SELECT status FROM pay_order WHERE id="+pay)==3,"race pay status");
                race.put("callbackPaidVerified",true);race.put("accepted",accepted.get());race.put("rejected",rejected.get());race.put("debits",1);
                race.put("balanceBeforeCents",100000);race.put("balanceAfterCents",99700);race.put("serverPeakInFlight",peak.get());races.add(race);
                memory(phase);
            }
            report.put("races",races);
            check(number("SELECT stock FROM fixture_stock WHERE id=1")==100000-1506,"fixture stock reconciliation");
            report.put("totalRecordedHttpRequests",samples.size());
            report.put("fixtureStockConsumed",1506);report.put("completed",true);success=true;
            memory("end");
        } finally {
            clients.shutdownNow();boolean stopped=clients.awaitTermination(10,TimeUnit.SECONDS);
            if(server!=null){server.stop();server.destroy();}
            if(webContext!=null)webContext.close();
            boolean httpStopped=true;
            if(base!=null){try(java.net.Socket socket=new java.net.Socket()){socket.connect(new InetSocketAddress("127.0.0.1",base.getPort()),500);httpStopped=false;}catch(java.io.IOException expected){}}
            report.put("clientPoolStopped",stopped);report.put("httpPortClosed",httpStopped);report.put("dbPoolClosed",pool.isClosed());
            report.put("schemaRetained",true);report.put("temporaryTomcatDirectoryRetained",true);
            report.put("cleanupPassed",stopped&&httpStopped&&pool.isClosed());report.put("completed",success);
            StringBuilder csv=new StringBuilder("sequence,phase,user,operation,status,startNs,endNs,latencyNs\n");
            for(Map<String,Object>s:samples)csv.append(s.get("sequence")).append(',').append(s.get("phase")).append(',').append(s.get("user")).append(',').append(s.get("operation")).append(',').append(s.get("status")).append(',').append(s.get("startNs")).append(',').append(s.get("endNs")).append(',').append(s.get("latencyNs")).append('\n');
            Files.writeString(output.resolve("requests.csv"),csv);
            JSON.writerWithDefaultPrettyPrinter().writeValue(output.resolve("results.json").toFile(),report);
        }
        System.out.println("COMPLETE "+samples.size()+" HTTP responses; 1500 flows; 3 races each 1 accepted/99 rejected");
    }

    static String url(String schema){return "jdbc:mysql://127.0.0.1:3307/"+schema+"?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai&characterEncoding=utf8&connectTimeout=3000&socketTimeout=30000";}
    static <T>T tx(T target,DataSourceTransactionManager manager){ProxyFactory proxy=new ProxyFactory(target);proxy.setProxyTargetClass(true);proxy.addAdvice(new TransactionInterceptor(manager,new AnnotationTransactionAttributeSource()));return (T)proxy.getProxy();}
    static void check(boolean ok,String what){if(!ok)throw new AssertionError(what);}
    static long number(String query){return db.queryForObject(query,Long.class);}
    static void memory(String phase){long free=((com.sun.management.OperatingSystemMXBean)ManagementFactory.getOperatingSystemMXBean()).getFreePhysicalMemorySize()/1048576;
        report.put("freeMiB_"+phase,free);check(free>=768,"Host memory guard: "+free);}
    interface Worker{void run(int worker)throws Exception;}
    static Map<String,Object> parallel(ExecutorService pool,Worker work)throws Exception{
        CountDownLatch ready=new CountDownLatch(100),start=new CountDownLatch(1);List<Future<?>> futures=new ArrayList<>();
        for(int i=0;i<100;i++){final int worker=i;futures.add(pool.submit(()->{ready.countDown();check(start.await(10,TimeUnit.SECONDS),"barrier");work.run(worker);return null;}));}
        check(ready.await(10,TimeUnit.SECONDS),"all 100 client workers ready");long begin=System.nanoTime();start.countDown();
        for(Future<?> f:futures)f.get(120,TimeUnit.SECONDS);
        Map<String,Object> result=new LinkedHashMap<>();result.put("readyWorkers",100);result.put("elapsedMs",(System.nanoTime()-begin)/1000000.0);return result;
    }
    static HttpResponse<String> post(String phase,long user,String operation,String path,String body)throws Exception{
        long seq=sequence.incrementAndGet();long begin=System.nanoTime();HttpResponse<String> response=client.send(HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(30))
                .header("Content-Type","application/json").header("x-fixture-user",String.valueOf(user)).POST(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
        long end=System.nanoTime();Map<String,Object>s=new LinkedHashMap<>();s.put("sequence",seq);s.put("phase",phase);s.put("user",user);s.put("operation",operation);s.put("status",response.statusCode());s.put("startNs",begin);s.put("endNs",end);s.put("latencyNs",end-begin);samples.add(s);return response;
    }
    static long id(HttpResponse<String> response)throws Exception{check(response.statusCode()==200,"HTTP "+response.statusCode()+": "+response.body());JsonNode data=JSON.readTree(response.body());if(data.isObject())data=data.get("data");return Long.parseLong(data.asText());}
    static long create(String phase,long user,int round)throws Exception{return id(post(phase,user,"order","/orders","{\"paymentType\":3,\"clientToken\":\""+phase+"-"+user+"-"+round+"\",\"details\":[{\"itemId\":1,\"num\":1}]}"));}
    static long apply(String phase,long user,long order)throws Exception{return id(post(phase,user,"apply","/pay-orders","{\"bizOrderNo\":"+order+",\"amount\":300,\"payChannelCode\":\"balance\",\"payType\":5,\"orderInfo\":\"synthetic\"}"));}
    static void flow(String phase,long user,int round)throws Exception{long pay=apply(phase,user,create(phase,user,round));HttpResponse<String> response=post(phase,user,"debit","/pay-orders/"+pay,"{\"pw\":\"654321\"}");check(response.statusCode()==200,"payment "+response.statusCode()+": "+response.body());}
    static Tomcat start(OrderController orders,PayController pay)throws Exception{
        Tomcat server=new Tomcat();Path dir=Files.createTempDirectory("llmall-pay100-");server.setBaseDir(dir.toString());server.setHostname("127.0.0.1");server.setPort(0);
        server.getConnector().setProperty("address","127.0.0.1");AbstractProtocol<?> protocol=(AbstractProtocol<?>)server.getConnector().getProtocolHandler();
        protocol.setMaxThreads(100);protocol.setMinSpareThreads(1);protocol.setMaxConnections(200);
        Context context=server.addContext("",dir.toString());context.setParentClassLoader(Pay100.class.getClassLoader());
        AnnotationConfigWebApplicationContext web=new AnnotationConfigWebApplicationContext();webContext=web;web.register(Mvc.class);
        web.addBeanFactoryPostProcessor(f->{f.registerSingleton("orders",orders);f.registerSingleton("pay",pay);f.registerSingleton("exceptions",new CommonExceptionAdvice());});
        Tomcat.addServlet(context,"fixture",new DispatcherServlet(web)).setLoadOnStartup(1);context.addServletMappingDecoded("/","fixture");server.start();return server;
    }
    static void seed(){
        db.execute("CREATE TABLE `order`(id BIGINT PRIMARY KEY,order_type INT,shop_id BIGINT,voucher_id BIGINT,verify_code VARCHAR(32),verify_time TIMESTAMP,client_token VARCHAR(64),request_hash VARCHAR(64),total_fee INT,payment_type INT,user_id BIGINT,status INT,create_time TIMESTAMP,pay_time TIMESTAMP,consign_time TIMESTAMP,end_time TIMESTAMP,close_time TIMESTAMP,comment_time TIMESTAMP,update_time TIMESTAMP,UNIQUE(user_id,client_token))");
        db.execute("CREATE TABLE order_detail(id BIGINT AUTO_INCREMENT PRIMARY KEY,order_id BIGINT,item_id BIGINT,num INT,name VARCHAR(100),spec VARCHAR(100),price INT,image VARCHAR(100),create_time TIMESTAMP,update_time TIMESTAMP)");
        db.execute("CREATE TABLE pay_order(id BIGINT PRIMARY KEY,biz_order_no BIGINT UNIQUE,pay_order_no BIGINT UNIQUE,biz_user_id BIGINT,pay_channel_code VARCHAR(32),amount INT,pay_type INT,status INT,expand_json VARCHAR(255),result_code VARCHAR(64),result_msg VARCHAR(255),pay_success_time TIMESTAMP,pay_over_time TIMESTAMP,qr_code_url VARCHAR(255),create_time TIMESTAMP,update_time TIMESTAMP,creater BIGINT,updater BIGINT,is_delete BOOLEAN DEFAULT FALSE)");
        db.execute("CREATE TABLE payment_attempt(id BIGINT PRIMARY KEY,pay_order_id BIGINT,biz_order_no BIGINT,user_id BIGINT,amount INT,state VARCHAR(16),notified BOOLEAN,create_time TIMESTAMP,next_check_time TIMESTAMP)");
        db.execute("CREATE TABLE user(id BIGINT PRIMARY KEY,username VARCHAR(64),nick_name VARCHAR(64),icon VARCHAR(255),password VARCHAR(64),pay_password VARCHAR(100),phone VARCHAR(32),status INT,balance INT,create_time TIMESTAMP,update_time TIMESTAMP)");
        db.execute("CREATE TABLE balance_payment(payment_key VARCHAR(64) PRIMARY KEY,user_id BIGINT,amount INT,state VARCHAR(16),update_time TIMESTAMP)");
        db.execute("CREATE TABLE balance_flow(id BIGINT AUTO_INCREMENT PRIMARY KEY,user_id BIGINT,change_amount INT,balance_after INT,biz_type VARCHAR(32),biz_no VARCHAR(64),remark VARCHAR(255),create_time TIMESTAMP,UNIQUE(biz_type,biz_no))");
        db.execute("CREATE TABLE fixture_stock(id BIGINT PRIMARY KEY,stock INT)");db.update("INSERT INTO fixture_stock VALUES(1,100000)");
        String hash=new BCryptPasswordEncoder(4).encode("654321");
        for(long user=1;user<=100;user++)db.update("INSERT INTO user(id,pay_password,status,balance) VALUES(?,?,1,100000)",user,hash);
        for(long user:List.of(201L,202L,203L,1001L))db.update("INSERT INTO user(id,pay_password,status,balance) VALUES(?,?,1,100000)",user,hash);
    }
}
