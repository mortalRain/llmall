package com.llmall.search.support;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SearchSyncSchemaGuardTest {
    @Test void onlySufficientPrecisionCanStart() {
        for (Integer precision : new Integer[]{null, 0, 2, 3, 6}) {
            JdbcTemplate jdbc=mock(JdbcTemplate.class);
            when(jdbc.queryForObject(anyString(),eq(Integer.class))).thenReturn(precision);
            SearchSyncSchemaGuard guard=new SearchSyncSchemaGuard(jdbc);
            if(precision!=null&&precision>=3)assertDoesNotThrow(guard::afterSingletonsInstantiated);
            else assertTrue(assertThrows(IllegalStateException.class,guard::afterSingletonsInstantiated).getMessage().contains("66-search"));
        }
    }
    @Test void missingColumnDoesNotSilentlyPass() {
        JdbcTemplate jdbc=mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(),eq(Integer.class))).thenThrow(new EmptyResultDataAccessException(1));
        assertThrows(EmptyResultDataAccessException.class,new SearchSyncSchemaGuard(jdbc)::afterSingletonsInstantiated);
    }
    @Test void failedGuardRunsBeforeMessageListenerLifecycleStarts() {
        JdbcTemplate jdbc=mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(),eq(Integer.class))).thenReturn(0);
        AtomicBoolean started=new AtomicBoolean();
        try(GenericApplicationContext context=new GenericApplicationContext()) {
            context.registerBeanDefinition("guard",BeanDefinitionBuilder.genericBeanDefinition(SearchSyncSchemaGuard.class,
                    ()->new SearchSyncSchemaGuard(jdbc)).getBeanDefinition());
            context.registerBeanDefinition("listener",BeanDefinitionBuilder.genericBeanDefinition(SmartLifecycle.class,()->new SmartLifecycle(){
                public void start(){started.set(true);}public void stop(){}public boolean isRunning(){return started.get();}
            }).getBeanDefinition());
            assertThrows(RuntimeException.class,context::refresh);
            assertFalse(started.get());
        }
    }
}
