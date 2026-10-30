package com.llmall.search.service;

import com.llmall.api.client.ShopClient;
import com.llmall.api.dto.ShopDTO;
import com.llmall.common.domain.PageDTO;
import com.llmall.common.metrics.BizMetrics;
import com.llmall.search.es.*;
import com.llmall.search.mapper.*;
import com.llmall.search.service.impl.SearchServiceImpl;
import com.llmall.search.domain.po.ShopSearchDoc;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.junit.jupiter.api.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class EmptySearchProbeTest {
    EsShopSearchGateway es;
    ShopSearchDocMapper docs;
    SearchServiceImpl service;
    PageDTO<ShopDTO> hit;
    ShopSearchEngineProperties properties;
    @BeforeEach void setup() {
        es=mock(EsShopSearchGateway.class);docs=mock(ShopSearchDocMapper.class);
        ShopDTO shop=new ShopDTO();shop.setId(1L);hit=new PageDTO<>(1L,1L,List.of(shop));
        when(es.enabled()).thenReturn(true);when(es.count()).thenReturn(10000L);
        when(es.search(any(),any(),anyInt(),anyInt(),any(),any())).thenAnswer(c->
                "found".equals(c.getArgument(0))?hit:new PageDTO<>(0L,0L,List.of()));
        properties=new ShopSearchEngineProperties();
        service=new SearchServiceImpl(docs,mock(SearchHotWordMapper.class),mock(SearchHistoryMapper.class),
                mock(ConsumeRecordMapper.class),mock(StringRedisTemplate.class),mock(ShopClient.class),es,
                properties,mock(BizMetrics.class));
    }
    @Test void actualHitsProveIndexNotEmptyWithoutExtraCountForFollowingMisses() {
        for(int i=0;i<10;i++) {
            assertSame(hit,service.search("found",null,1,10,null));
            assertEquals(0L,service.search("missing",null,1,10,null).getTotal());
        }
        verify(es,never()).count();verifyNoInteractions(docs);
    }
    @Test void concurrentColdMissesShareOneGlobalCount() throws Exception {
        CyclicBarrier queries=new CyclicBarrier(4);
        when(es.search(any(),any(),anyInt(),anyInt(),any(),any())).thenAnswer(c->{queries.await(3,TimeUnit.SECONDS);return new PageDTO<>(0L,0L,List.of());});
        when(es.count()).thenAnswer(c->{Thread.sleep(100);return 10000L;});
        ExecutorService workers=Executors.newFixedThreadPool(4);
        try {
            List<Future<PageDTO<ShopDTO>>> calls=new ArrayList<>();
            for(int i=0;i<4;i++)calls.add(workers.submit(()->service.search("missing",null,1,10,null)));
            for(Future<PageDTO<ShopDTO>> call:calls)assertEquals(0L,call.get(5,TimeUnit.SECONDS).getTotal());
        } finally {workers.shutdownNow();assertTrue(workers.awaitTermination(2,TimeUnit.SECONDS));}
        verify(es,times(1)).count();verifyNoInteractions(docs);
    }
    @Test void emptyGlobalIndexAndPopulatedReadModelFallBackWithoutRepeatedCounts() {
        when(es.count()).thenReturn(0L);when(docs.selectCount(isNull())).thenReturn(10L);stubMysql();
        for(int i=0;i<4;i++)assertEquals(1L,service.search("missing",null,1,10,null).getList().get(0).getId());
        verify(es,times(1)).count();verify(docs,times(1)).selectCount(isNull());
        verify(docs,times(4)).selectPage(any(Page.class),any());
    }
    @Test void recoveryHitImmediatelyStopsCachedFallback() {
        when(es.count()).thenReturn(0L);when(docs.selectCount(isNull())).thenReturn(10L);stubMysql();
        service.search("missing",null,1,10,null);assertSame(hit,service.search("found",null,1,10,null));
        assertEquals(0L,service.search("missing",null,1,10,null).getTotal());
        verify(docs,times(1)).selectPage(any(Page.class),any());verify(es,times(1)).count();
    }
    @Test void inconclusiveProbeIsShortCachedWithoutMysqlFallback() {
        when(es.count()).thenThrow(new IllegalStateException("fixture unavailable"));
        for(int i=0;i<3;i++)assertEquals(0L,service.search("missing",null,1,10,null).getTotal());
        verify(es,times(1)).count();verifyNoInteractions(docs);
    }
    @Test void pureEsDoesNotProbeAndMysqlModeDoesNotQueryEs() {
        properties.setEngine("es");assertEquals(0L,service.search("missing",null,1,10,null).getTotal());
        verify(es,never()).count();verifyNoInteractions(docs);
        when(es.enabled()).thenReturn(false);stubMysql();clearInvocations(es);
        assertEquals(1L,service.search("found",null,1,10,null).getList().get(0).getId());
        verify(es,never()).search(any(),any(),anyInt(),anyInt(),any(),any());verify(es,never()).count();
    }
    private void stubMysql() {
        when(docs.selectPage(any(Page.class),any())).thenAnswer(c->{Page<ShopSearchDoc> p=c.getArgument(0);
            p.setTotal(1);p.setRecords(List.of(new ShopSearchDoc().setShopId(1L).setName("fallback")));return p;});
    }
}
