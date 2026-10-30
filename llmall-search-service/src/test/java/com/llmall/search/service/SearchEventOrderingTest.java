package com.llmall.search.service;

import com.llmall.api.client.ShopClient;
import com.llmall.api.dto.ShopDTO;
import com.llmall.common.exception.BadRequestException;
import com.llmall.common.metrics.BizMetrics;
import com.llmall.search.domain.po.ShopSearchDoc;
import com.llmall.search.event.ShopDocChangedEvent;
import com.llmall.search.es.*;
import com.llmall.search.mapper.*;
import com.llmall.search.service.impl.SearchServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import java.time.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SearchEventOrderingTest {
    ShopSearchDocMapper docs; ShopClient client; EsShopSearchGateway es; SearchServiceImpl service;
    long newer=1767225600900L,older=1767225600100L;
    @BeforeEach void setup(){
        docs=mock(ShopSearchDocMapper.class);client=mock(ShopClient.class);es=mock(EsShopSearchGateway.class);
        service=new SearchServiceImpl(docs,mock(SearchHotWordMapper.class),mock(SearchHistoryMapper.class),
                mock(ConsumeRecordMapper.class),mock(StringRedisTemplate.class),client,es,new ShopSearchEngineProperties(),mock(BizMetrics.class));
    }
    @Test void missingOrNonpositiveTimeDoesNotInventANewVersion(){
        for(Long time:new Long[]{null,0L,-1L})assertThrows(BadRequestException.class,
                ()->service.applyShopHotChanged(event(time)));
        verifyNoInteractions(docs,client,es);
    }
    @Test void staleSnapshotIsNotUsedForVersionDecision(){
        when(docs.selectById(1L)).thenReturn(row(older));when(docs.selectForUpdate(1L)).thenReturn(row(newer));
        service.applyShopHotChanged(event(older));verify(docs,never()).updateById(any());
    }
    @Test void acceptedUpdateRetainsMillisecondPrecision(){
        when(docs.selectById(1L)).thenReturn(row(older));when(docs.selectForUpdate(1L)).thenReturn(row(older));
        service.applyShopHotChanged(event(newer));
        verify(docs).updateById(argThat(doc->doc.getEventTime().getNano()==900000000));
    }
    @Test void firstInsertUsesNoopUpsertBeforeCurrentRead(){
        when(docs.selectForUpdate(1L)).thenReturn(row(newer));service.applyShopHotChanged(event(newer));
        var order=inOrder(docs);order.verify(docs).selectById(1L);order.verify(docs).insertIfAbsent(any());order.verify(docs).selectForUpdate(1L);
        verify(docs,never()).insert(any());
    }
    @Test void equalTimePreservesCommittedPayload(){
        when(docs.selectById(1L)).thenReturn(row(newer));when(docs.selectForUpdate(1L)).thenReturn(row(newer));
        service.applyShopHotChanged(event(newer));verify(docs,never()).updateById(any());
    }
    @Test void legacyPartialPayloadStillCompletesAFirstRow(){
        ShopDTO shop=new ShopDTO();shop.setId(1L);shop.setName("source");shop.setAddress("source");shop.setTypeId(1L);
        when(client.queryShopById(1L)).thenReturn(shop);when(docs.selectForUpdate(1L)).thenReturn(row(newer));
        service.applyShopHotChanged(new ShopDocChangedEvent().setShopId(1L).setOccurredAt(newer));
        verify(docs).insertIfAbsent(argThat(doc->"source".equals(doc.getName())));
    }
    static ShopDocChangedEvent event(Long at){return new ShopDocChangedEvent().setShopId(1L).setName("fixture").setAddress("fixture").setTypeId(1L).setOccurredAt(at);}
    static ShopSearchDoc row(long at){return new ShopSearchDoc().setShopId(1L).setEventTime(LocalDateTime.ofInstant(Instant.ofEpochMilli(at),ZoneId.systemDefault()));}
}
