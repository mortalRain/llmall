package com.llmall.shop.cache;

import com.llmall.api.dto.ShopDTO;
import com.llmall.common.exception.CommonException;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class ShopCacheFallbackTest {
    @Test void overlappingSameKeyLoadsShareOneSource() throws Exception {
        ShopCacheFallback fallback=new ShopCacheFallback(1,1000);AtomicInteger reads=new AtomicInteger();
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        ExecutorService workers=Executors.newFixedThreadPool(4);ShopDTO dto=new ShopDTO();dto.setId(1L);
        try {
            Future<ShopDTO> owner=workers.submit(()->fallback.load(1L,()->{reads.incrementAndGet();entered.countDown();await(release);return dto;}));
            assertTrue(entered.await(2,TimeUnit.SECONDS));List<Future<ShopDTO>> joined=new ArrayList<>();
            Queue<Thread> followers=new ConcurrentLinkedQueue<>();
            for(int i=0;i<3;i++)joined.add(workers.submit(()->{followers.add(Thread.currentThread());
                return fallback.load(1L,()->{fail("duplicate source");return null;});}));
            waitUntil(()->followers.size()==3&&followers.stream().allMatch(t->t.getState()==Thread.State.TIMED_WAITING));
            release.countDown();assertSame(dto,owner.get(2,TimeUnit.SECONDS));
            for(Future<ShopDTO> f:joined)assertSame(dto,f.get(2,TimeUnit.SECONDS));assertEquals(1,reads.get());
        } finally {release.countDown();workers.shutdownNow();assertTrue(workers.awaitTermination(2,TimeUnit.SECONDS));}
    }
    @Test void distinctKeyCapacityIsImmediate503AndRecoversAfterOwnerFinishes() throws Exception {
        ShopCacheFallback fallback=new ShopCacheFallback(1,100);CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        ExecutorService worker=Executors.newSingleThreadExecutor();
        try {
            Future<ShopDTO> busy=worker.submit(()->fallback.load(1L,()->{entered.countDown();await(release);return null;}));
            assertTrue(entered.await(2,TimeUnit.SECONDS));AtomicBoolean called=new AtomicBoolean();
            CommonException e=assertThrows(CommonException.class,()->fallback.load(2L,()->{called.set(true);return null;}));
            assertEquals(503,e.getCode());assertFalse(called.get());release.countDown();busy.get(2,TimeUnit.SECONDS);
            assertNull(fallback.load(2L,()->null));
        } finally {release.countDown();worker.shutdownNow();assertTrue(worker.awaitTermination(2,TimeUnit.SECONDS));}
    }
    @Test void waitingTimeoutDoesNotCancelOrDuplicateOwnerRead() throws Exception {
        ShopCacheFallback fallback=new ShopCacheFallback(1,20);CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        ExecutorService worker=Executors.newSingleThreadExecutor();
        try {
            Future<ShopDTO> owner=worker.submit(()->fallback.load(1L,()->{entered.countDown();await(release);return null;}));
            assertTrue(entered.await(2,TimeUnit.SECONDS));
            assertEquals(503,assertThrows(CommonException.class,()->fallback.load(1L,()->{fail("second read");return null;})).getCode());
            assertFalse(owner.isDone());release.countDown();owner.get(2,TimeUnit.SECONDS);
        } finally {release.countDown();worker.shutdownNow();assertTrue(worker.awaitTermination(2,TimeUnit.SECONDS));}
    }
    @Test void sourceFailureAndNullResultReleaseAdmissionWithoutRetainingData() {
        ShopCacheFallback fallback=new ShopCacheFallback(1,100);IllegalStateException failure=new IllegalStateException("database fixture");
        assertSame(failure,assertThrows(IllegalStateException.class,()->fallback.load(1L,()->{throw failure;})));
        AtomicInteger count=new AtomicInteger();
        assertNull(fallback.load(1L,()->{count.incrementAndGet();return null;}));
        assertNull(fallback.load(1L,()->{count.incrementAndGet();return null;}));assertEquals(2,count.get());
    }
    @Test void interruptedCallerDoesNotStartSourceAndPreservesFlag() {
        ShopCacheFallback fallback=new ShopCacheFallback(1,100);
        try {
            Thread.currentThread().interrupt();assertEquals(503,assertThrows(CommonException.class,()->fallback.load(1L,()->{fail("interrupted read");return null;})).getCode());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {Thread.interrupted();}
    }
    @Test void invalidCapacityOrWaitFailsFast() {
        assertThrows(IllegalArgumentException.class,()->new ShopCacheFallback(0,100));
        assertThrows(IllegalArgumentException.class,()->new ShopCacheFallback(4,0));
        assertThrows(IllegalArgumentException.class,()->new ShopCacheFallback(65,100));
    }
    @Test void joinedWaiterReceivesOriginalDatabaseFailure() throws Exception {
        ShopCacheFallback fallback=new ShopCacheFallback(1,1000);CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        ExecutorService workers=Executors.newFixedThreadPool(2);AtomicReference<Thread> waiterThread=new AtomicReference<>();
        IllegalStateException failure=new IllegalStateException("database fixture");
        try {
            Future<ShopDTO> owner=workers.submit(()->fallback.load(1L,()->{entered.countDown();await(release);throw failure;}));
            assertTrue(entered.await(2,TimeUnit.SECONDS));
            Future<ShopDTO> waiter=workers.submit(()->{waiterThread.set(Thread.currentThread());return fallback.load(1L,()->{fail("duplicate failure read");return null;});});
            waitUntil(()->waiterThread.get()!=null&&waiterThread.get().getState()==Thread.State.TIMED_WAITING);release.countDown();
            assertSame(failure,assertThrows(ExecutionException.class,()->owner.get(2,TimeUnit.SECONDS)).getCause());
            assertSame(failure,assertThrows(ExecutionException.class,()->waiter.get(2,TimeUnit.SECONDS)).getCause());
            assertNull(fallback.load(2L,()->null));
        } finally {release.countDown();workers.shutdownNow();assertTrue(workers.awaitTermination(2,TimeUnit.SECONDS));}
    }
    @Test void interruptedWaiterPreservesInterruptWithoutCancellingOwner() throws Exception {
        ShopCacheFallback fallback=new ShopCacheFallback(1,1000);CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        ExecutorService workers=Executors.newFixedThreadPool(2);AtomicReference<Thread> waiterThread=new AtomicReference<>();
        try {
            Future<ShopDTO> owner=workers.submit(()->fallback.load(1L,()->{entered.countDown();await(release);return null;}));
            assertTrue(entered.await(2,TimeUnit.SECONDS));
            Future<Boolean> waiter=workers.submit(()->{waiterThread.set(Thread.currentThread());
                assertEquals(503,assertThrows(CommonException.class,()->fallback.load(1L,()->null)).getCode());
                return Thread.currentThread().isInterrupted();});
            waitUntil(()->waiterThread.get()!=null&&waiterThread.get().getState()==Thread.State.TIMED_WAITING);
            waiterThread.get().interrupt();assertTrue(waiter.get(2,TimeUnit.SECONDS));assertFalse(owner.isDone());release.countDown();owner.get(2,TimeUnit.SECONDS);
        } finally {release.countDown();workers.shutdownNow();assertTrue(workers.awaitTermination(2,TimeUnit.SECONDS));}
    }
    static void await(CountDownLatch latch) {
        try {assertTrue(latch.await(3,TimeUnit.SECONDS));}catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}
    }
    static void waitUntil(java.util.function.BooleanSupplier ready) throws Exception {
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
        while(!ready.getAsBoolean()&&System.nanoTime()<until)Thread.sleep(2);
        assertTrue(ready.getAsBoolean());
    }
}
