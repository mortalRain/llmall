package com.llmall.shop;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.llmall.api.dto.ShopDTO;
import com.llmall.shop.domain.bo.RedisData;
import com.llmall.shop.domain.po.Shop;
import com.llmall.shop.mapper.*;
import com.llmall.shop.mq.ShopEventPublisher;
import com.llmall.shop.service.impl.ShopServiceImpl;
import com.llmall.shop.cache.ShopDetailCache;
import com.llmall.shop.cache.ShopCacheFallback;
import org.junit.jupiter.api.*;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.*;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ShopDetailResilienceTest {
    StringRedisTemplate redis;
    ValueOperations<String,String> values;
    ShopMapper mapper;
    Executor executor;
    ShopServiceImpl service;
    ObjectMapper json;
    @BeforeEach void setup() {
        redis=mock(StringRedisTemplate.class);values=mock(ValueOperations.class);when(redis.opsForValue()).thenReturn(values);
        mapper=mock(ShopMapper.class);executor=mock(Executor.class);json=new ObjectMapper().findAndRegisterModules();
        ShopDetailCache cache=new ShopDetailCache(redis,json,executor,new ShopCacheFallback(4,500));
        service=new ShopServiceImpl(redis,cache,mock(ConsumeRecordMapper.class),mock(ShopTypeMapper.class),mock(ShopEventPublisher.class));
        ReflectionTestUtils.setField(service,"baseMapper",mapper);
        when(mapper.selectById(1L)).thenReturn(new Shop().setId(1L).setName("database"));
    }
    String stale() throws Exception {
        ShopDTO dto=new ShopDTO();dto.setId(1L);dto.setName("stale");
        return json.writeValueAsString(RedisData.of(dto,LocalDateTime.now().minusMinutes(1)));
    }
    @Test void redisGetFailureFallsBackInsteadOfFailingTheDetailRequest() {
        when(values.get(anyString())).thenThrow(new RedisConnectionFailureException("fixture"));
        ShopDTO result=assertDoesNotThrow(()->service.queryById(1L));assertEquals("database",result.getName());
        verify(mapper,times(1)).selectById(1L);
    }
    @Test void staleValueSurvivesLockBackendFailureWithoutDatabaseRead() throws Exception {
        when(values.get("cache:shop:1")).thenReturn(stale());
        when(values.setIfAbsent(anyString(),anyString(),any(Duration.class))).thenThrow(new RedisConnectionFailureException("fixture"));
        assertEquals("stale",assertDoesNotThrow(()->service.queryById(1L)).getName());verifyNoInteractions(mapper);
    }
    @Test void rejectedBackgroundTaskStillReturnsStaleValueAndReleasesItsLock() throws Exception {
        when(values.get("cache:shop:1")).thenReturn(stale());
        when(values.setIfAbsent(anyString(),anyString(),any(Duration.class))).thenReturn(true);
        doThrow(new RejectedExecutionException("fixture full")).when(executor).execute(any());
        assertEquals("stale",assertDoesNotThrow(()->service.queryById(1L)).getName());verifyNoInteractions(mapper);
        verify(redis).execute(any(org.springframework.data.redis.core.script.RedisScript.class),eq(java.util.List.of("lock:shop:1")),anyString());
    }
}
