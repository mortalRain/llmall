package com.llmall.search.service;

import com.llmall.api.client.ShopClient;
import com.llmall.api.dto.ShopDTO;
import com.llmall.common.domain.PageDTO;
import com.llmall.common.metrics.BizMetrics;
import com.llmall.search.domain.po.ShopSearchDoc;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.llmall.search.es.*;
import com.llmall.search.mapper.*;
import com.llmall.search.service.impl.SearchServiceImpl;
import org.junit.jupiter.api.*;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SearchHistoryResilienceTest {
    SearchHistoryMapper histories;
    ShopSearchDocMapper docs;
    EsShopSearchGateway es;
    SearchServiceImpl service;
    PageDTO<ShopDTO> result;
    ShopSearchEngineProperties properties;
    BizMetrics metrics;
    @BeforeEach void setup() {
        histories=mock(SearchHistoryMapper.class); docs=mock(ShopSearchDocMapper.class); es=mock(EsShopSearchGateway.class);
        ShopDTO shop=new ShopDTO(); shop.setId(11L);
        result=new PageDTO<>(1L,1L,List.of(shop));
        when(es.enabled()).thenReturn(true);
        when(es.search(any(),any(),anyInt(),anyInt(),any(),any())).thenReturn(result);
        properties=new ShopSearchEngineProperties();metrics=mock(BizMetrics.class);
        service=new SearchServiceImpl(docs,mock(SearchHotWordMapper.class),histories,mock(ConsumeRecordMapper.class),
                mock(StringRedisTemplate.class),mock(ShopClient.class),es,properties,metrics);
    }
    @Test void historyOutageDoesNotLoseSuccessfulEsResultOrTriggerFallback() {
        doThrow(new DataAccessResourceFailureException("fixture outage")).when(histories).insertIfAbsent(anyLong(),anyString());
        assertSame(result,service.search("火锅",null,1,10,1L));
        verify(es,times(1)).search(any(),any(),anyInt(),anyInt(),any(),any());
        verifyNoInteractions(docs);
        verify(metrics).increment("llmall.search.history.failure","reason","database");
    }
    @Test void overlongHistoryIsSkippedWithoutTruncatingSearchKeyword() {
        String keyword="店".repeat(65);
        assertSame(result,service.search(keyword,null,1,10,1L));
        verifyNoInteractions(histories);
        verify(es).search(eq(keyword),any(),anyInt(),anyInt(),any(),any());
    }
    @Test void repeatedSearchUsesIdempotentSqlAndDoesNotRetryEs() {
        service.search("  火锅  ",null,1,10,1L);service.search("火锅",null,1,10,1L);
        verify(histories,times(2)).insertIfAbsent(1L,"火锅");
        verify(es,times(2)).search(eq("火锅"),any(),anyInt(),anyInt(),any(),any());
        verifyNoInteractions(docs);
    }
    @Test void anonymousAndBlankSearchDoNotWritePersonalHistory() {
        service.search("火锅",null,1,10,null);service.search("  ",null,1,10,1L);
        verifyNoInteractions(histories);
    }
    @Test void sixtyFourUnicodeCharactersAreAllowedEvenWithSurrogatePairs() {
        String keyword="\uD83C\uDFEC".repeat(64);
        service.search(keyword,null,1,10,1L);
        verify(histories).insertIfAbsent(1L,keyword);
        service.search(keyword+"店",null,1,10,1L);
        verifyNoMoreInteractions(histories);
    }
    @Test void historyOutageDoesNotBreakPureEsMode() {
        properties.setEngine("es");
        doThrow(new DataAccessResourceFailureException("fixture outage")).when(histories).insertIfAbsent(anyLong(),anyString());
        assertSame(result,service.search("火锅",null,1,10,1L));verifyNoInteractions(docs);
    }
    @Test void mysqlResultSurvivesHistoryOutage() {
        when(es.enabled()).thenReturn(false);
        when(docs.selectPage(any(Page.class),any())).thenAnswer(call->{
            Page<ShopSearchDoc> page=call.getArgument(0);page.setTotal(1);
            page.setRecords(List.of(new ShopSearchDoc().setShopId(11L).setName("fixture")));return page;
        });
        doThrow(new DataAccessResourceFailureException("fixture outage")).when(histories).insertIfAbsent(anyLong(),anyString());
        assertEquals(11L,service.search("火锅",null,1,10,1L).getList().get(0).getId());
    }
}
