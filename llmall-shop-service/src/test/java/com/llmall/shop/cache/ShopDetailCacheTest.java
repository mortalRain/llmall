package com.llmall.shop.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.llmall.api.dto.ShopDTO;
import com.llmall.shop.domain.bo.RedisData;
import org.junit.jupiter.api.*;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.*;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.*;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ShopDetailCacheTest {
    StringRedisTemplate redis;ValueOperations<String,String> values;ObjectMapper json;Executor executor;ShopDetailCache cache;
    @BeforeEach void setup() {
        redis=mock(StringRedisTemplate.class);values=mock(ValueOperations.class);when(redis.opsForValue()).thenReturn(values);
        json=new ObjectMapper().findAndRegisterModules();executor=mock(Executor.class);
        cache=new ShopDetailCache(redis,json,executor,new ShopCacheFallback(4,100));
    }
    ShopDTO dto(long id,String name){ShopDTO d=new ShopDTO();d.setId(id);d.setName(name);return d;}
    String payload(ShopDTO dto,boolean fresh) throws Exception {return json.writeValueAsString(RedisData.of(dto,LocalDateTime.now().plusMinutes(fresh?10:-1)));}
    @Test void freshHotHitUsesOnlyOneGetAndNoDatabaseOrLock() throws Exception {
        when(values.get("cache:shop:1")).thenReturn(payload(dto(1,"fresh"),true));
        assertEquals("fresh",cache.get(1L,()->{fail("hot DB read");return null;}).getName());
        verify(values,times(1)).get("cache:shop:1");verifyNoMoreInteractions(values);verifyNoInteractions(executor);
        verify(redis,times(1)).opsForValue();verifyNoMoreInteractions(redis);
    }
    @Test void freshNegativeSentinelDoesNotQueryDatabase() throws Exception {
        when(values.get("cache:shop:1")).thenReturn(payload(null,true));
        assertNull(cache.get(1L,()->{fail("negative-cache read");return null;}));verifyNoInteractions(executor);
    }
    @Test void cacheFailureDoesNotCatchAndRetryDatabaseException() {
        when(values.get(anyString())).thenThrow(new RedisConnectionFailureException("fixture"));
        Supplier<ShopDTO> source=mock(Supplier.class);DataAccessResourceFailureException failure=new DataAccessResourceFailureException("database fixture");
        when(source.get()).thenThrow(failure);assertSame(failure,assertThrows(DataAccessResourceFailureException.class,()->cache.get(1L,source)));
        verify(source,times(1)).get();
    }
    @Test void coldLockContentionFallbackNeverPublishes() {
        ReflectionTestUtils.setField(cache,"waitMillis",0L);when(values.setIfAbsent(anyString(),anyString(),any(Duration.class))).thenReturn(false);
        ShopDTO expected=dto(1,"source");assertSame(expected,cache.get(1L,()->expected));
        verify(redis,never()).execute(any(RedisScript.class),anyList(),anyString(),anyString(),anyString());
    }
    @Test void queuedRebuildWithLostLeaseDoesNotQueryDatabaseOrPublish() throws Exception {
        when(values.get("cache:shop:1")).thenReturn(payload(dto(1,"stale"),false));
        when(values.setIfAbsent(anyString(),anyString(),any(Duration.class))).thenReturn(true);
        List<Runnable> queue=new ArrayList<>();doAnswer(c->{queue.add(c.getArgument(0));return null;}).when(executor).execute(any());
        assertEquals("stale",cache.get(1L,()->{fail("dead queued task queried DB");return null;}).getName());
        when(values.get("lock:shop:1")).thenReturn("new-owner");queue.get(0).run();
        verify(redis,never()).execute(any(RedisScript.class),anyList(),anyString(),anyString(),anyString());
    }
    @Test void malformedEnvelopeAndMismatchedIdsAreTreatedAsCold() throws Exception {
        for(String raw:List.of("{}",payload(dto(2,"wrong id"),true),"not-json")) {
            reset(values);when(values.get("cache:shop:1")).thenReturn(raw,null);
            when(values.setIfAbsent(anyString(),anyString(),any(Duration.class))).thenReturn(true);
            assertEquals("correct",cache.get(1L,()->dto(1,"correct")).getName());
            verify(redis,atLeastOnce()).execute(any(RedisScript.class),eq(List.of("cache:shop:1")),eq(raw));
        }
    }
    @Test void invalidationBackendFailureDoesNotEscapeCommittedOperation() {
        when(redis.execute(any(RedisScript.class),eq(List.of("lock:shop:1","cache:shop:1")))).thenThrow(new RedisConnectionFailureException("fixture"));
        assertDoesNotThrow(()->cache.invalidate(1L));
    }
    @Test void invalidIdsNeverTouchRedisOrDatabase() {
        for(Long id:Arrays.asList(null,0L,-1L))assertThrows(com.llmall.common.exception.BadRequestException.class,()->cache.get(id,()->{fail("invalid source");return null;}));
        verifyNoInteractions(redis);
    }
}
