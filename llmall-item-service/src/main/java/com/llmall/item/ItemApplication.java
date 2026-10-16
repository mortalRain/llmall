package com.llmall.item;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 商品/库存域微服务的启动类（Spring Boot 3 段式启动：扫描 Mapper → 装配容器 → 注册到 Nacos）。
 *
 * 【核心职责】
 * 1. 启动 llmall-item-service，把商品查询、库存扣减/回滚、库存流水这条链路拉起来
 * 2. 声明 Mapper 扫描包：{@code com.llmall.item.mapper} 下只有 ItemMapper 与 StockFlowMapper 两个接口，
 *    没有 XML，全部 SQL 以 {@code @Update} 注解内联在接口上
 * 3. 通过 bootstrap.yaml 从 Nacos 拉取 shared-jdbc / shared-mybatis / shared-log / shared-swagger 四份公共配置
 *
 * 【使用场景】
 * 仅由运维/本机以进程方式启动（{@code java -jar llmall-item-service.jar} 或 IDE 直接运行），
 * 业务代码不引用本类；调用方一律走网关（匿名读接口）或 Feign（{@code ItemClient}，服务间写接口）。
 *
 * 【关键设计】
 * 本服务是「库存的唯一权威方」：库存数字只在 item 表上变化，变动的证据只在 stock_flow 表上留痕。
 * 因此启动类本身不承载逻辑，但它的两个开关直接决定库存语义是否成立：
 * 1. {@code @MapperScan} 漏了或包名写错 → 启动即失败（NoSuchBeanDefinition），不会静默降级
 * 2. Seata 在 application.yaml 中默认 {@code enabled: false}，本服务是普通本地事务参与者
 *
 * 【注意】不要在本类上追加 {@code @EnableTransactionManagement} 之类的重复注解，
 * 也不要给 {@code @MapperScan} 加 {@code sqlSessionFactoryRef} —— 本服务只有一个数据源，
 * 多写一层反而会让 StockFlowMapper 绑到别的 SessionFactory 上，导致"流水插不进唯一键、幂等闸门静默失效"。
 *
 * @author 同城生活
 * @since 2023-05-05
 */
@MapperScan("com.llmall.item.mapper")
@SpringBootApplication
public class ItemApplication {
    /**
     * 进程入口：启动 Spring 容器并阻塞主线程。
     *
     * 【注意】不要在这里写业务初始化（预热缓存、补库存等）：启动失败会让整个商品域不可用，
     * 而任何写库动作都可能与 Nacos 配置下发时序竞争，把"配置没生效"变成"数据被写坏"。
     */
    public static void main(String[] args) {
        SpringApplication.run(ItemApplication.class, args);
    }
}