package com.llmall.shop;

import com.llmall.shop.config.ShopCacheConfig;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

class ShopCacheExecutorTest {
    @Test void saturationMustNotRunRebuildOnTheRequestThread() {
        ThreadPoolTaskExecutor executor=(ThreadPoolTaskExecutor)new ShopCacheConfig().cacheRebuildExecutor();
        CountDownLatch release=new CountDownLatch(1);Thread requestThread=Thread.currentThread();AtomicBoolean callerRan=new AtomicBoolean();
        Runnable blocked=()->{if(Thread.currentThread()==requestThread){callerRan.set(true);return;}
            try {release.await(3,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}};
        try {
            for(int i=0;i<108;i++)executor.execute(blocked);
            assertThrows(RejectedExecutionException.class,()->executor.execute(blocked));
            assertFalse(callerRan.get());
        } finally {release.countDown();executor.shutdown();}
    }
}
