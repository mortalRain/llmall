package com.llmall.shop;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.llmall.shop.cache.ShopCacheFallback;
import com.llmall.shop.cache.ShopDetailCache;
import com.llmall.shop.domain.po.Shop;
import com.llmall.shop.mapper.ConsumeRecordMapper;
import com.llmall.shop.mapper.ShopMapper;
import com.llmall.shop.mapper.ShopTypeMapper;
import com.llmall.shop.mq.ShopEventPublisher;
import com.llmall.shop.service.impl.ShopServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class ShopCacheWiringTest {
    @Test
    void springWiresCacheWithTwoExecutorCandidatesAndTheInheritedMapper() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenThrow(new RedisConnectionFailureException("fixture"));
        ShopMapper mapper = mock(ShopMapper.class);
        when(mapper.selectById(1L)).thenReturn(new Shop().setId(1L).setName("database"));
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(StringRedisTemplate.class, () -> redis);
            context.registerBean(ObjectMapper.class, () -> new ObjectMapper().findAndRegisterModules());
            context.registerBean("cacheRebuildExecutor", Executor.class, () -> mock(Executor.class));
            context.registerBean("applicationTaskExecutor", Executor.class, () -> mock(Executor.class));
            context.registerBean(ShopMapper.class, () -> mapper);
            context.registerBean(org.apache.ibatis.session.SqlSessionFactory.class,
                    () -> mock(org.apache.ibatis.session.SqlSessionFactory.class));
            context.registerBean(ConsumeRecordMapper.class, () -> mock(ConsumeRecordMapper.class));
            context.registerBean(ShopTypeMapper.class, () -> mock(ShopTypeMapper.class));
            context.registerBean(ShopEventPublisher.class, () -> mock(ShopEventPublisher.class));
            context.register(ShopCacheFallback.class, ShopDetailCache.class, ShopServiceImpl.class);
            context.refresh();
            assertEquals("database", context.getBean(ShopServiceImpl.class).queryById(1L).getName());
            verify(mapper).selectById(1L);
        }
    }

    @Test
    void offlineInvalidationInsideTransactionWaitsForCommit() {
        ShopDetailCache cache = mock(ShopDetailCache.class);
        ShopServiceImpl service = service(cache);
        TransactionSynchronizationManager.initSynchronization();
        try {
            service.markShopOffline(1L);
            verifyNoInteractions(cache);
            for (TransactionSynchronization callback : TransactionSynchronizationManager.getSynchronizations()) {
                callback.afterCommit();
            }
            verify(cache).invalidate(1L);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void rollbackDoesNotInvalidateButManualEvictionOutsideTransactionIsImmediate() {
        ShopDetailCache cache = mock(ShopDetailCache.class);
        ShopServiceImpl service = service(cache);
        TransactionSynchronizationManager.initSynchronization();
        try {
            service.markShopOffline(1L);
            for (TransactionSynchronization callback : TransactionSynchronizationManager.getSynchronizations()) {
                callback.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
            }
            verifyNoInteractions(cache);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
        service.evictCache(1L);
        verify(cache).invalidate(1L);
    }

    private ShopServiceImpl service(ShopDetailCache cache) {
        return new ShopServiceImpl(mock(StringRedisTemplate.class), cache, mock(ConsumeRecordMapper.class),
                mock(ShopTypeMapper.class), mock(ShopEventPublisher.class));
    }
}
